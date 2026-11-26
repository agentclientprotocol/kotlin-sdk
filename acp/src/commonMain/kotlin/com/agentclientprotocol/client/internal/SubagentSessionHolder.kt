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
 * The holder of a subagent session.
 *
 * @property parent the holder of the session this subagent was announced in
 */
internal class SubagentSessionHolder(
    sessionId: SessionId,
    val parent: ClientSessionHolder,
) : ClientSessionHolder(sessionId, parent.protocol, parent.openingScope, parent.failSessionTree) {
    /**
     * The state of this subagent's opening, which consumes its queue. The state that the first stop replaces decides
     * who settles the holder.
     */
    sealed class OpeningState {
        /**
         * No opening was started. A stop that finds it leaves the holder for the stop's caller to fail.
         */
        object NotStarted : OpeningState()

        /**
         * The opening runs in [job]. A stop that finds it means the opening never completes the holder, so the stop's
         * caller cancels [job] and fails the holder.
         */
        class Running(val job: Job) : OpeningState()

        /**
         * The opening has delivered its queue and completes the holder next. A stop that finds it leaves the holder to
         * the opening.
         */
        object Finished : OpeningState()

        /**
         * A stop has run, so no opening starts any more. A later stop that finds it settles nothing.
         */
        object Stopped : OpeningState()
    }

    private val opening = atomic<OpeningState>(OpeningState.NotStarted)

    /*
     * The session that the setup built, stored by [startOpening]. Patches, nested subagents' working directories and
     * the close reach it here, before the holder completes.
     */
    private val _subagent = atomic<ClientSubagentSession?>(null)

    /**
     * The subagent's pending permission requests. They belong to the holder, so a reopened root's new holders start
     * with none pending.
     */
    val permissions: SubagentPendingPermissions = SubagentPendingPermissions()

    init {
        // After the root's answer, the opened marker is the first item, queued before the holder is reachable.
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
     * Puts the opened marker into the queue, and resolves the held-back permission state.
     *
     * @param promote whether the root's opening call replayed no history, so held-back values take effect
     */
    fun markOpened(promote: Boolean) {
        if (queue.trySend(QueuedItem.Opened(null)).isSuccess) permissions.onOpened(promote)
    }

    /**
     * Applies a later `subagent_update` to the subagent's session, if it is set up.
     */
    fun applyPatch(update: SessionUpdate.SubagentUpdate, parentOpened: Boolean) {
        _subagent.value?.applyPatch(update, parentOpened)
    }

    /**
     * Closes the subagent's session, if it is set up.
     */
    fun markClosed() {
        _subagent.value?.markClosed()
    }

    /**
     * Stores the set-up [session] and starts the opening in [openingScope], unless the opening was stopped: then it
     * closes [session] instead.
     */
    fun startOpening(session: ClientSubagentSession) {
        // Stored first, so a stop after this point closes the session itself.
        check(_subagent.compareAndSet(null, session)) { "Subagent session $sessionId is already set up" }

        val job = openingScope.launch(start = CoroutineStart.LAZY) { completeOpening() }
        if (opening.compareAndSet(OpeningState.NotStarted, OpeningState.Running(job))) {
            job.start()
        } else {
            job.cancel()
            // A stop before the store couldn't close the session. It stays in the parent's `subagents`, closed.
            session.markClosed()
        }
    }

    /**
     * Marks the opening stopped, without cancelling a running one, and returns the state it replaced.
     */
    fun markOpeningStopped(): OpeningState = opening.getAndSet(OpeningState.Stopped)

    /**
     * The subagent's opening: delivers the queue up to the opened marker and the marker, waits for the parent's holder,
     * then delivers the rest and completes this holder.
     *
     * A failure before the marker fails this holder, and a failure after it is logged. A failure after the opening was
     * stopped or cancelled fails nothing, because whoever stopped it fails the holder.
     */
    private suspend fun completeOpening() {
        try {
            val session = requireSubagent()
            val opened = session.executeWithSession {
                while (true) {
                    /*
                     * `receiveCatching` and `notify` can return without suspending, and the cascade and the close
                     * cancel this opening without joining it. Without this check a cancelled opening would keep taking
                     * the drain's items.
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

            // The root's opening can still fail after its answer, failing every holder below, so wait for the parent.
            parent.session.join()
            if (parent.isFailed) return

            queue.close()
            /*
             * Check as above: `tryReceive` never suspends, and [deliverLive] swallows failures. The loop runs in one
             * context, because a context switch between taking an item and delivering it could drop the item.
             */
            session.executeWithSession {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = queue.tryReceive().getOrNull() ?: break
                    deliverLive(item, session)
                }
            }
            // Claims the completion unless a stop came first.
            val running = opening.value as? OpeningState.Running ?: return
            if (!opening.compareAndSet(running, OpeningState.Finished)) return
            _session.complete(session)
        } catch (ce: CancellationException) {
            // Rethrow the opening's own cancellation: its stopper fails the holder, or the connection has ended.
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
     * Fails this holder with [failure] for what the opening [caught], unless the opening was stopped or cancelled: then
     * [caught] comes from that, even if wrapped, and is only logged.
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
            // Rethrow only the opening's own cancellation, not one from the app's `notify`, such as its `withTimeout`.
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of session $sessionId was cancelled" }
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of session $sessionId failed" }
        }
    }
}
