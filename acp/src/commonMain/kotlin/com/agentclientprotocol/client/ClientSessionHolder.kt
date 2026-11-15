@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * An item of a [ClientSessionHolder]'s queue.
 */
internal sealed class QueuedItem {
    /**
     * A `session/update` of the holder's session.
     */
    class Update(val update: SessionUpdate, val meta: JsonElement?) : QueuedItem()

    /**
     * The point where the answer of the opening call fell among the updates, see [SessionEvent.Opened]. Only the
     * response hook of the opening call puts it into the queue, through [ClientSessionHolder.markOpened].
     */
    class Opened(val response: AcpCreatedSessionResponse) : QueuedItem()
}

/**
 * The client's slot for one session id: buffers its updates until the session is created, then delivers them to it.
 *
 * [Client] creates a holder when it starts initializing a session, and speculatively for an unknown session id that
 * receives an update while some initialization is in progress. One opening call at most claims a holder, see
 * [claimForOpening].
 *
 * Every update that arrives before the session exists goes into one queue, and so does the [QueuedItem.Opened]
 * marker that the response hook of the opening call adds. The queue has one consumer at a time: [deliverEarly] for a
 * load with ready operations, or [drainHistory] for a call with a factory, until the marker, then [completeOpening].
 * [completeOpening] closes the queue to switch to the session. An update that misses the closed queue waits until
 * the consumer finished and until the update that missed the queue before it is delivered, and then goes to the
 * session. So the updates keep the order in which their handlers reach the holder, and the marker keeps its place
 * among them.
 */
internal class ClientSessionHolder {
    private class Opening(val replayRequested: Boolean, val parameters: SessionCreationParameters)

    private val sessionDeferred: CompletableDeferred<BaseClientSession> = CompletableDeferred()
    // Don't make the channel limited, because it leads to a deadlock also:
    // when client side makes loadSession/newSession and an agent sends updates more than channel.capacity
    // the message with call response suspends because protocol thread is suspended in handleNotification
    // if to address it we have to somehow reorder events, that's not obvious on the protocol level, so we pay with memory right now to handle it
    private val queue = Channel<QueuedItem>(capacity = Channel.UNLIMITED)
    // `null` until an opening call claims the holder, then that call's replay request and parameters.
    private val opening = atomic<Opening?>(null)
    private val answered = atomic(false)
    // The delivery of the last update that missed the closed queue, see [handleOrQueue].
    private val lateDeliveries = atomic<Job>(Job().apply { complete() })

    /*
     * The last session state that the updates of an early delivery carried. Only the consumer in [deliverEarly]
     * writes them, and [applyEarlyState] reads them after that consumer has finished. Unlike the other fields,
     * they need no atomic: the join of the consumer orders its writes before the read, and nothing reads them
     * concurrently.
     */
    // TODO remove together with the deprecated ClientSession.currentMode.
    private var earlyMode: SessionUpdate.CurrentModeUpdate? = null
    // TODO remove together with the deprecated ClientSession.configOptions.
    @OptIn(UnstableApi::class)
    private var earlyConfigOptions: SessionUpdate.ConfigOptionUpdate? = null

    val session: Deferred<BaseClientSession> get() = sessionDeferred

    /**
     * The [BaseClientSession.subagents] of this holder's session. It lives here, because children are added while the
     * history of a loading root is delivered, before the root's [ClientSession] exists.
     */
    val subagents = SubagentList()

    /**
     * Whether the opening of this holder failed, so a new opening of the session id needs a new holder.
     */
    val isFailed: Boolean
        @OptIn(ExperimentalCoroutinesApi::class)
        get() = sessionDeferred.isCompleted && sessionDeferred.getCompletionExceptionOrNull() != null

    /**
     * Whether the call that claimed this holder asked the agent to replay the history: `true` for `session/load`,
     * `false` for the other opening calls, and `null` while no call has claimed the holder.
     */
    val replayRequested: Boolean? get() = opening.value?.replayRequested

    /**
     * The parameters of the call that claimed this holder, which the effective working directories of the root's
     * subagents come from. They are known from the claim on, so subagents can read them before the root's
     * [ClientSession] exists. `null` while no call has claimed the holder.
     */
    val rootParameters: SessionCreationParameters? get() = opening.value?.parameters

    /**
     * Whether [markOpened] has put the marker into the queue.
     */
    val isAnswered: Boolean get() = answered.value

    /**
     * Makes the opening call that asked for [replayRequested] with [parameters] the owner of this holder.
     *
     * Only a holder that has no owner and hasn't completed can be claimed: the response hook can put the marker
     * only into an open queue, and two openings of one id would race for it.
     *
     * @return `false` if the holder already has an owner or has completed. The holder is then unchanged.
     */
    fun claimForOpening(replayRequested: Boolean, parameters: SessionCreationParameters): Boolean =
        !sessionDeferred.isCompleted && opening.compareAndSet(null, Opening(replayRequested, parameters))

    /**
     * Puts the [QueuedItem.Opened] marker with [response] into the queue, after every update that has reached the
     * holder, and records that the opening call was answered.
     *
     * The response hook of the opening call calls this in the read loop, so it must not suspend or block.
     *
     * @return `false` if the queue is closed because the holder failed. The marker is then dropped.
     */
    fun markOpened(response: AcpCreatedSessionResponse): Boolean {
        if (queue.trySend(QueuedItem.Opened(response)).isFailure) return false
        answered.value = true
        return true
    }

    /**
     * Hands the updates queued before the marker to [session], in queue order, and returns the marker without
     * delivering it. Pass the marker to [completeOpening].
     *
     * Must run after the opening call was answered, so the marker is in the queue. A failure of the session's
     * operations fails this call, and the remaining items stay in the queue for [failAndDrainQueue].
     */
    suspend fun drainHistory(session: ClientSessionImpl): QueuedItem.Opened {
        while (true) {
            when (val item = queue.receive()) {
                is QueuedItem.Update -> session.executeWithSession {
                    session.deliverUpdate(item.update, item.meta)
                }
                is QueuedItem.Opened -> return item
            }
        }
    }

    /**
     * Fails the holder with [cause] and hands whatever was queued to [onQueuedUpdate]. The queued updates belong
     * to no live session: either no initialization claimed the holder, or its opening failed. A queued marker is
     * dropped, because a failed opening delivers none.
     *
     * The channel is closed before draining, so an update can't be queued after the drain and get lost.
     * The session is failed only after the drain, so [handleOrQueue] callers that missed the channel see
     * their updates delivered after the queued ones.
     */
    suspend fun failAndDrainQueue(cause: Throwable, onQueuedUpdate: suspend (SessionUpdate, JsonElement?) -> Unit) {
        queue.close(cause)
        try {
            while (true) {
                when (val item = queue.tryReceive().getOrNull() ?: break) {
                    is QueuedItem.Update -> onQueuedUpdate(item.update, item.meta)
                    is QueuedItem.Opened -> {}
                }
            }
        } finally {
            sessionDeferred.completeExceptionally(cause)
        }
    }

    /**
     * Hands each queued update and each arriving update to [operations], one at a time and in arrival order, until
     * it takes the marker, and returns the marker without delivering it: only the opening call knows whether it
     * succeeded. Pass the marker to [completeOpening] once it has.
     *
     * The holder must be claimed. If [operations] throws, the remaining items stay in the queue for
     * [failAndDrainQueue].
     *
     * @return `null` if the queue was closed before the marker
     */
    suspend fun deliverEarly(operations: ClientSessionOperations): QueuedItem.Opened? {
        while (true) {
            when (val item = queue.receiveCatching().getOrNull() ?: return null) {
                is QueuedItem.Update -> {
                    @OptIn(UnstableApi::class)
                    when (val update = item.update) {
                        // TODO remove together with the deprecated ClientSession.currentMode.
                        is SessionUpdate.CurrentModeUpdate -> earlyMode = update
                        // TODO remove together with the deprecated ClientSession.configOptions.
                        is SessionUpdate.ConfigOptionUpdate -> earlyConfigOptions = update
                        else -> {}
                    }
                    operations.notify(item.update, item.meta)
                }
                is QueuedItem.Opened -> return item
            }
        }
    }

    // TODO remove together with the deprecated ClientSession.currentMode and configOptions.
    /**
     * Applies the session state that the updates of [deliverEarly] carried to [session]. Must run after
     * [deliverEarly] has returned.
     */
    fun applyEarlyState(session: ClientSessionImpl) {
        // TODO remove together with the deprecated ClientSession.currentMode.
        earlyMode?.let(session::trackState)
        // TODO remove together with the deprecated ClientSession.configOptions.
        earlyConfigOptions?.let(session::trackState)
    }

    /**
     * Delivers [opened] and the updates queued after it to [session], then makes [session] the receiver of all
     * later updates.
     *
     * Must run after the opening call succeeded and its consumer, [deliverEarly] or [drainHistory], returned
     * [opened]. The operations then know that the session is open, so from here on this can't fail the opening:
     * it runs to the end even if the caller is cancelled, and a failure of the operations for one event is logged,
     * as in the synchronous delivery after the switch.
     */
    suspend fun completeOpening(session: ClientSessionImpl, opened: QueuedItem.Opened) {
        withContext(NonCancellable) {
            deliverLive(session) { session.deliverOpened(opened.response) }
            queue.close()
            for (item in queue) {
                when (item) {
                    is QueuedItem.Update -> deliverLive(session) { session.deliverUpdate(item.update, item.meta) }
                    is QueuedItem.Opened -> logger.error { "Session ${session.sessionId} got a second opening marker" }
                }
            }
        }
        check(sessionDeferred.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    private suspend fun deliverLive(session: BaseClientSession, block: suspend () -> Unit) {
        try {
            session.executeWithSession(block)
        } catch (t: Throwable) {
            // Runs in NonCancellable, so a cancellation here comes from the operations themselves, like a timeout.
            logger.error(t) { "Delivery of a session event to session ${session.sessionId} failed" }
        }
    }

    /**
     * Delivers [item] to the session, or queues it while the session is initializing.
     *
     * @return `false` if the holder ended without a session, so the update belongs to no connected session
     */
    suspend fun handleOrQueue(item: QueuedItem): Boolean {
        if (queue.trySend(item).isSuccess) return true

        // The common case: the session is open and no update before this one still waits for it.
        if (sessionDeferred.isCompleted && lateDeliveries.value.isCompleted) return deliverToSession(item)

        /*
         * The channel is closed by a drain. Wait for it to finish instead of awaiting the session: the holder
         * may be failed because no initialization claimed it, and that must not fail the notification.
         * Also wait for the update that missed the channel before this one. Its waiter can resume after this
         * update arrives, so without the chain this update could overtake it.
         */
        val delivered = CompletableDeferred<Unit>()
        val previous = lateDeliveries.getAndSet(delivered)
        try {
            previous.join()
            sessionDeferred.join()
            return deliverToSession(item)
        } finally {
            delivered.complete(Unit)
        }
    }

    private suspend fun deliverToSession(item: QueuedItem): Boolean {
        @OptIn(ExperimentalCoroutinesApi::class)
        if (sessionDeferred.getCompletionExceptionOrNull() != null) return false

        @OptIn(ExperimentalCoroutinesApi::class)
        val session = sessionDeferred.getCompleted()
        when (item) {
            is QueuedItem.Update -> session.executeWithSession {
                session.deliverUpdate(item.update, item.meta)
            }
            // markOpened puts the marker only into an open queue, so it never takes this path.
            is QueuedItem.Opened -> error("The opening marker of session ${session.sessionId} missed the queue")
        }
        return true
    }
}
