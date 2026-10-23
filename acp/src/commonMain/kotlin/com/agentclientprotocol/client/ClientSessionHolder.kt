package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement

/**
 * The client's slot for one session id: buffers its updates until the session is created, then delivers them to it.
 *
 * [Client] creates a holder when it starts initializing a session, and speculatively for an unknown session id that
 * receives an update while some initialization is in progress.
 *
 * Every update that arrives before the session exists goes into one queue, and the queue has one consumer: the drain
 * of [drainEventsAndCompleteSession], or [deliverEarly] for a load with ready operations. The consumer closes the
 * queue to switch to the session. An update that misses the closed queue waits until the consumer finished and
 * until the update that missed the queue before it is delivered, and then goes to the session. So the updates
 * keep the order in which their handlers reach the holder.
 */
internal class ClientSessionHolder {
    /**
     * The kind of call that owns a holder. One holder has at most one kind of owner, see [claim].
     */
    enum class Opener {
        /**
         * A call with a [ClientOperationsFactory]. It drains the queue after the answer.
         */
        FACTORY,

        /**
         * A `session/load` or `session/resume` with ready [ClientSessionOperations]. It consumes the queue before the
         * answer.
         */
        OPERATIONS,
    }

    private val sessionDeferred: CompletableDeferred<ClientSessionImpl> = CompletableDeferred()
    // Don't make the channel limited, because it leads to a deadlock also:
    // when client side makes loadSession/newSession and an agent sends updates more than channel.capacity
    // the message with call response suspends because protocol thread is suspended in handleNotification
    // if to address it we have to somehow reorder events, that's not obvious on the protocol level, so we pay with memory right now to handle it
    private val notifications = Channel<Pair<SessionUpdate, JsonElement?>>(capacity = Channel.UNLIMITED)
    private val opener = atomic<Opener?>(null)
    // The delivery of the last update that missed the closed queue, see [handleOrQueue].
    private val lateDeliveries = atomic<Job>(Job().apply { complete() })

    /*
     * The last session state that the updates of an early delivery carried. Only the consumer in [deliverEarly]
     * writes them, and [completeEarlySession] reads them after that consumer has finished. Unlike the other fields,
     * they need no atomic: the join of the consumer orders its writes before the read, and nothing reads them
     * concurrently.
     */
    private var earlyMode: SessionUpdate.CurrentModeUpdate? = null
    @OptIn(UnstableApi::class)
    private var earlyConfigOptions: SessionUpdate.ConfigOptionUpdate? = null

    val session: Deferred<ClientSessionImpl> get() = sessionDeferred

    /**
     * Whether the opening of this holder failed, so a new opening of the session id needs a new holder.
     */
    val isFailed: Boolean
        @OptIn(ExperimentalCoroutinesApi::class)
        get() = sessionDeferred.isCompleted && sessionDeferred.getCompletionExceptionOrNull() != null

    /**
     * Makes [kind] the owner of this holder.
     *
     * A [Opener.FACTORY] call may claim a holder that another factory call owns: two factory openings of one id keep
     * their old behavior. A [Opener.OPERATIONS] call needs a holder that has no owner and no session. A reopen of an
     * open session claims nothing, see [isOpen].
     *
     * @return `false` if another kind of call owns the holder, or if [kind] is [Opener.OPERATIONS] and the holder
     *   has an owner or has completed. The holder is then unchanged.
     */
    fun claim(kind: Opener): Boolean = when (kind) {
        Opener.FACTORY -> opener.compareAndSet(null, Opener.FACTORY) || opener.value == Opener.FACTORY
        Opener.OPERATIONS -> !sessionDeferred.isCompleted && opener.compareAndSet(null, Opener.OPERATIONS)
    }

    /**
     * Whether the holder has an open session. Once `true`, it stays `true`.
     */
    val isOpen: Boolean
        get() = sessionDeferred.isCompleted && !isFailed

    /**
     * Whether [session] is the open session of this holder.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun holds(session: ClientSessionImpl): Boolean = isOpen && sessionDeferred.getCompleted() === session

    suspend fun drainEventsAndCompleteSession(session: ClientSessionImpl) {
        @OptIn(ExperimentalCoroutinesApi::class)
        notifications.close()
        for ((notification, meta) in notifications) {
            session.executeWithSession {
                session.handleNotification(notification, meta)
            }
        }

        sessionDeferred.complete(session)
    }

    /**
     * Fails the holder with [cause] and hands whatever was queued to [onQueuedUpdate]. The queued updates belong
     * to no live session: either no initialization claimed the holder, or its opening failed.
     *
     * The channel is closed before draining, so an update can't be queued after the drain and get lost.
     * The session is failed only after the drain, so [handleOrQueue] callers that missed the channel see
     * their updates delivered after the queued ones.
     */
    suspend fun failAndDrainQueue(cause: Throwable, onQueuedUpdate: suspend (SessionUpdate, JsonElement?) -> Unit) {
        notifications.close(cause)
        try {
            while (true) {
                val (update, meta) = notifications.tryReceive().getOrNull() ?: break
                onQueuedUpdate(update, meta)
            }
        } finally {
            sessionDeferred.completeExceptionally(cause)
        }
    }

    /**
     * Hands each queued update and each arriving update to [operations], one at a time and in arrival order, until
     * [endEarlyDelivery] closes the queue and the queue is empty.
     *
     * The holder must be claimed for [Opener.OPERATIONS]. If [operations] throws, the remaining updates stay in the
     * queue for [failAndDrainQueue].
     */
    suspend fun deliverEarly(operations: ClientSessionOperations) {
        for ((update, meta) in notifications) {
            @OptIn(UnstableApi::class)
            when (update) {
                is SessionUpdate.CurrentModeUpdate -> earlyMode = update
                is SessionUpdate.ConfigOptionUpdate -> earlyConfigOptions = update
                else -> {}
            }
            operations.notify(update, meta)
        }
    }

    /**
     * Closes the queue, so [deliverEarly] returns when it has delivered the queued updates.
     */
    fun endEarlyDelivery() {
        notifications.close()
    }

    /**
     * Applies the session state that the early updates carried, then makes [session] the receiver of all later
     * updates. Must run after [deliverEarly] has returned.
     */
    fun completeEarlySession(session: ClientSessionImpl) {
        earlyMode?.let(session::trackState)
        earlyConfigOptions?.let(session::trackState)
        check(sessionDeferred.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    /**
     * Delivers [notification] to the session, or queues it while the session is initializing.
     *
     * @return `false` if the holder ended without a session, so the update belongs to no connected session
     */
    suspend fun handleOrQueue(notification: SessionUpdate, _meta: JsonElement?): Boolean {
        if (notifications.trySend(Pair(notification, _meta)).isSuccess) return true

        // The common case: the session is open and no update before this one still waits for it.
        if (sessionDeferred.isCompleted && lateDeliveries.value.isCompleted) return deliverToSession(notification, _meta)

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
            return deliverToSession(notification, _meta)
        } finally {
            delivered.complete(Unit)
        }
    }

    private suspend fun deliverToSession(notification: SessionUpdate, _meta: JsonElement?): Boolean {
        @OptIn(ExperimentalCoroutinesApi::class)
        if (sessionDeferred.getCompletionExceptionOrNull() != null) return false

        @OptIn(ExperimentalCoroutinesApi::class)
        val session = sessionDeferred.getCompleted()
        session.executeWithSession {
            session.handleNotification(notification, _meta)
        }
        return true
    }
}
