@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.internal.RootSessionHolder.Stage
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The holder of a subagent session, registered under the holder of the session it was announced in.
 *
 * A holder created after the root's answer takes its [QueuedItem.Opened] marker at once, as its first item, before
 * it is added to [parent] or the session map. A holder created before the answer gets it from
 * [RootSessionHolder.markOpened]. Both run in read-loop order, so each subagent gets its opened marker exactly once.
 *
 * It takes the connection's dependencies from [parent].
 *
 * @property parent the holder of the session this subagent was announced in
 */
internal class SubagentSessionHolder(
    sessionId: SessionId,
    val parent: ClientSessionHolder,
) : ClientSessionHolder(sessionId, parent.protocol, parent.openingScope, parent.failSessionTree) {
    /**
     * The state of this subagent's opening, which consumes its queue. A root's opening is its opening call.
     */
    sealed class OpeningState {
        /**
         * No opening was started: the holder isn't set up yet, or its setup failed.
         */
        object NotStarted : OpeningState()

        /**
         * The opening runs in [job] and hasn't completed the holder.
         */
        class Running(val job: Job) : OpeningState()

        /**
         * The opening has delivered its whole queue and claimed the right to complete the holder, which it does next.
         */
        object Finished : OpeningState()

        /**
         * [markOpeningStopped] has run, so no opening starts any more, and only one that had claimed its completion
         * ([Finished]) still completes the holder.
         */
        object Stopped : OpeningState()
    }

    private val opening = atomic<OpeningState>(OpeningState.NotStarted)

    /*
     * The subagent session that the setup built for this holder, stored by [startOpening]. It stays `null` while the
     * holder isn't set up, when its setup failed, and when the parent took no more subagents by the end of the setup.
     * It exists before the holder completes, so patches, the working directories of nested subagents and the close
     * after a failure reach it through the holder.
     */
    private val _subagent = atomic<ClientSubagentSession?>(null)

    /**
     * The pending permission requests of this subagent, answered `cancelled` on a cancel, an `idle(cancelled)` state
     * or a close. The registry feeds them when the requests and states arrive.
     *
     * They belong to this holder, so a load or resume of the root, which builds new holders, starts with none
     * pending.
     */
    val permissions: SubagentPendingPermissions = SubagentPendingPermissions()

    init {
        val phase = root.phase
        if (phase.isAtLeast(Stage.ANSWERED)) markOpened(promote = phase.opening?.replayRequested != true)
    }

    override val root: RootSessionHolder get() = parent.root

    override val blocksId: Boolean get() = !isFailed || !parent.isFailed

    override fun sessionCwd(): String = requireSubagent().cwd

    override fun sessionAdditionalDirectories(): List<String> = requireSubagent().additionalDirectories

    private fun requireSubagent(): ClientSubagentSession =
        checkNotNull(_subagent.value) { "Subagent session $sessionId is not set up" }

    /**
     * Puts the [QueuedItem.Opened] marker of a subagent into its queue, and resolves its held-back permission state.
     *
     * @param promote whether the root's opening call replayed no history, so held-back values take effect
     */
    fun markOpened(promote: Boolean) {
        if (queue.trySend(QueuedItem.Opened(null)).isSuccess) permissions.onOpened(promote)
    }

    /**
     * Applies a later `subagent_update` of this subagent to its session, see [ClientSubagentSession.applyPatch]. A
     * subagent that isn't set up has no session object, and then its patches are plain updates.
     *
     * @param parentOpened whether the opened marker of the parent's stream has been delivered
     */
    fun applyPatch(update: SessionUpdate.SubagentUpdate, parentOpened: Boolean) {
        _subagent.value?.applyPatch(update, parentOpened)
    }

    /**
     * Closes this subagent's session, if it is set up, see [ClientSubagentSession.markClosed].
     */
    fun markClosed() {
        _subagent.value?.markClosed()
    }

    /**
     * Stores [session], the subagent session that the setup built for this holder, and then starts the opening of
     * this subagent in [openingScope], see [completeOpening], unless [markOpeningStopped] has run: a holder that
     * failed or was removed with its root's tree gets no opening, and this closes [session] instead.
     *
     * The session is stored first, so a failure that stops the opening after the store closes the session itself, and
     * a failure that stopped it before leaves the close to this call.
     */
    fun startOpening(session: ClientSubagentSession) {
        check(_subagent.compareAndSet(null, session)) { "Subagent session $sessionId is already set up" }

        val job = openingScope.launch(start = CoroutineStart.LAZY) { completeOpening() }
        if (opening.compareAndSet(OpeningState.NotStarted, OpeningState.Running(job))) {
            job.start()
        } else {
            job.cancel()
            /*
             * This holder failed, or was removed with its root's tree after a `session/close`, possibly before it
             * stored [session], so that failure or removal may not have closed it. The session stays in the parent's
             * `subagents`, closed, like any subagent whose holder failed.
             */
            session.markClosed()
        }
    }

    /**
     * Marks the opening stopped, so from now on [startOpening] starts none and a running one can't complete the
     * holder, and returns the state it replaced:
     * - [OpeningState.Running]: the opening will never complete the holder. It isn't cancelled or joined here: the
     *   caller cancels it and fails the holder;
     * - [OpeningState.Finished]: the opening has completed the holder, or is about to, so the subagent counts as
     *   opened;
     * - [OpeningState.NotStarted]: no opening was started, and the caller fails the holder;
     * - [OpeningState.Stopped]: an earlier call stopped it, and that call's caller fails the holder, unless it found
     *   [OpeningState.Finished].
     *
     * So the first stop decides who settles the holder: the opening, if the stop found it finished, and otherwise the
     * stop's caller. An opening that fails after a stop leaves the holder to that caller.
     */
    fun markOpeningStopped(): OpeningState = opening.getAndSet(OpeningState.Stopped)

    /**
     * Delivers this subagent's queue to its session's operations, with the session in the context of the calls:
     * - the updates queued up to the opened marker, setting up the nested subagents announced in them;
     * - the opened marker, as `SessionEvent.Opened(null)`;
     * - once the parent's holder has completed: closes the queue, delivers the rest and completes this holder.
     *
     * It is the body of the opening that [startOpening] starts, after the session is stored.
     *
     * It completes this holder only if it moves the opening state from its own [OpeningState.Running] to
     * [OpeningState.Finished] first. The stop in [markOpeningStopped] and that claim both swap the state atomically,
     * so exactly one of them wins: a stopped opening that passed its last cancellation check before the stop, and so
     * found the queue empty, returns without completing a holder that the stop's caller fails.
     *
     * ### Failure
     *
     * - before the opened marker is delivered, a failure is logged and fails this holder, with [failSessionTree]. A
     *   [CancellationException] that the operations throw while the opening is active counts as such a failure;
     * - after it, a failure is logged, as for any live update;
     * - if the parent's holder failed, this returns, and the failure cascade fails this holder;
     * - the opening's own cancellation is rethrown and fails nothing;
     * - a failure after the opening was cancelled or stopped, such as an operations call that turns the cancellation
     *   into another exception, fails nothing either. Whoever stopped the opening, the failure cascade or the close,
     *   fails this holder with its own error, so two failures never drain the queue at once.
     */
    private suspend fun completeOpening() {
        try {
            val session = requireSubagent()
            val opened = session.executeWithSession {
                while (true) {
                    /*
                     * `receiveCatching` doesn't check for cancellation when it finds an item without suspending, and
                     * the client application's `notify` may return without suspending too. The failure cascade and the
                     * close cancel this opening without joining it, so without this check a cancelled opening would
                     * keep taking items meant for the drain of [failAndDrainQueue].
                     */
                    currentCoroutineContext().ensureActive()
                    when (val item = queue.receiveCatching().getOrNull() ?: return@executeWithSession false) {
                        is QueuedItem.Opened -> break
                        else -> deliverItem(item, session, opened = false)
                    }
                }
                resolveHeldSubagentState()
                session.operations.notify(SessionEvent.Opened(null))
                true
            }
            // The queue was closed because this holder failed.
            if (!opened) return

            /*
             * The root's opening can still fail after its answer, while it delivers its opened marker and the updates
             * queued behind it, and then every holder below fails with it. So the later updates stay queued, and this
             * holder stays incomplete, until the parent's holder has completed.
             */
            parent.session.join()
            if (parent.isFailed) return

            queue.close()
            /*
             * `tryReceive` never suspends, so it never checks for cancellation, the client application's `notify` may
             * return without suspending, and [deliverLive] swallows its failures. The failure cascade and the close
             * cancel this opening without joining it, so without this check a cancelled opening would keep taking
             * items meant for the drain of [failAndDrainQueue].
             *
             * Check before taking each item, and nowhere between taking it and handing it to the operations, so one
             * loop runs in one context: an item that a stopped opening took would reach neither the session nor the
             * drain.
             */
            session.executeWithSession {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = queue.tryReceive().getOrNull() ?: break
                    deliverLive(item, session)
                }
            }
            // Claims the completion unless a stop came first. Only this opening sets the state to Running.
            val running = opening.value as? OpeningState.Running ?: return
            if (!opening.compareAndSet(running, OpeningState.Finished)) return
            _session.complete(session)
        } catch (ce: CancellationException) {
            // A stopped opening's holder is already failing, and the end of the connection fails nothing.
            currentCoroutineContext().ensureActive()
            failOpening(
                ce,
                IllegalStateException("ClientSessionOperations.notify was cancelled", ce),
                "ClientSessionOperations.notify of subagent session $sessionId was cancelled",
            )
        } catch (t: Throwable) {
            failOpening(
                t,
                IllegalStateException("Failed to deliver the history of subagent session $sessionId", t),
                "ClientSessionOperations.notify of subagent session $sessionId failed",
            )
        }
    }

    /**
     * Fails this holder with [failure], for what the opening [caught], and logs [message], unless the opening was
     * cancelled or stopped: then the stopper fails the holder, or the connection has ended, and [caught] is only
     * logged at debug level.
     */
    private suspend fun failOpening(caught: Throwable, failure: Throwable, message: String) {
        if (currentCoroutineContext().isActive && failSessionTree(this, failure)) {
            logger.error(caught) { message }
        } else {
            logger.debug(caught) { "$message after its opening was stopped" }
        }
    }

    /**
     * Delivers a live [item] of a subagent's queue, logging a failure as for any live update.
     */
    private suspend fun deliverLive(item: QueuedItem, session: ClientSubagentSession) {
        try {
            deliverItem(item, session, opened = true)
        } catch (ce: CancellationException) {
            /*
             * Rethrow only the opening's own cancellation; one that the client application's `notify` throws itself,
             * such as its own `withTimeout`, is logged.
             */
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of session $sessionId was cancelled" }
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of session $sessionId failed" }
        }
    }
}
