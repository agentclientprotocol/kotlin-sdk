package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * The client's slot for one session id: buffers its updates until the session is created, then delivers them to it.
 *
 * [Client] creates a holder when it starts initializing a session, and speculatively for an unknown session id that
 * receives an update while some initialization is in progress.
 *
 * A holder can switch to early delivery with [startEarlyDelivery]. Then it hands every update to the given
 * operations as it arrives instead of buffering it, until [completeEarlySession] switches it to the session.
 */
internal class ClientSessionHolder {
    private val sessionDeferred: CompletableDeferred<ClientSessionImpl> = CompletableDeferred()
    // Don't make the channel limited, because it leads to a deadlock also:
    // when client side makes loadSession/newSession and an agent sends updates more than channel.capacity
    // the message with call response suspends because protocol thread is suspended in handleNotification
    // if to address it we have to somehow reorder events, that's not obvious on the protocol level, so we pay with memory right now to handle it
    private val notifications = Channel<Pair<SessionUpdate, JsonElement?>>(capacity = Channel.UNLIMITED)

    /*
     * Early delivery state. The lock orders deliveries to the early operations and the switch to the session;
     * it is fair, so updates pass it in arrival order. The in-flight counter keeps the lock-free path to the
     * session closed while an update that arrived before the switch still waits for the lock.
     */
    private val earlyOperations = atomic<ClientSessionOperations?>(null)
    private val earlyDeliveryLock = Mutex()
    private val earlyDeliveriesInFlight = atomic(0)
    private val earlyStateUpdates = mutableListOf<SessionUpdate>()

    val session: Deferred<ClientSessionImpl> get() = sessionDeferred

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

    fun completeExceptionally(cause: Throwable) {
        notifications.close(cause)
        sessionDeferred.completeExceptionally(cause)
    }

    /**
     * Like [completeExceptionally], but also hands whatever was queued to [onQueuedUpdate] - for a holder
     * that was speculatively created for a session that never actually got claimed (see the
     * `initializingSessionsCount > 0` branch of `Client.getOrCreateSessionHolderOrNull`), those notifications belong to no live
     * session and would otherwise be silently discarded.
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
     * Switches this holder to early delivery: from now on, updates go to [operations] as they arrive.
     *
     * Updates that this holder has already buffered go to [operations] first, in arrival order.
     *
     * @return `false` if the holder already has a session, has failed, or delivers early already. The holder is
     *   then unchanged.
     */
    suspend fun startEarlyDelivery(operations: ClientSessionOperations): Boolean = earlyDeliveryLock.withLock {
        if (sessionDeferred.isCompleted) return false
        if (!earlyOperations.compareAndSet(null, operations)) return false

        /*
         * An update that missed the channel because of this close sees the early operations and waits for the
         * lock that this drain holds.
         */
        notifications.close()
        for ((update, meta) in notifications) {
            deliverEarly(operations, update, meta)
        }
        true
    }

    /**
     * Ends early delivery: applies the session state that the early updates carried, then makes [session] the
     * receiver of all later updates.
     *
     * Each update before this call went to the early operations only, and each update after it goes to [session]
     * only.
     */
    suspend fun completeEarlySession(session: ClientSessionImpl) {
        earlyDeliveryLock.withLock {
            for (update in earlyStateUpdates) {
                session.trackState(update)
            }
            earlyStateUpdates.clear()
            sessionDeferred.complete(session)
        }
    }

    /**
     * Delivers [notification] to the session, or queues it while the session is initializing.
     *
     * In early delivery, it hands [notification] to the early operations until the session is complete.
     *
     * @return `false` if the holder ended without a session, so the update belongs to no connected session
     */
    suspend fun handleOrQueue(notification: SessionUpdate, _meta: JsonElement?): Boolean {
        if (earlyOperations.value == null && notifications.trySend(Pair(notification, _meta)).isSuccess) return true

        val operations = earlyOperations.value
        if (operations != null) return handleEarly(operations, notification, _meta)

        /*
         * The channel is closed by a drain. Wait for it to finish instead of awaiting the session: the holder
         * may be failed because no initialization claimed it, and that must not fail the notification.
         */
        sessionDeferred.join()
        return deliverToCompletedSession(notification, _meta)
    }

    private suspend fun handleEarly(operations: ClientSessionOperations, notification: SessionUpdate, _meta: JsonElement?): Boolean {
        if (earlyDeliveriesInFlight.value == 0 && sessionDeferred.isCompleted) {
            return deliverToCompletedSession(notification, _meta)
        }

        earlyDeliveriesInFlight.incrementAndGet()
        try {
            earlyDeliveryLock.withLock {
                if (sessionDeferred.isCompleted) return deliverToCompletedSession(notification, _meta)
                deliverEarly(operations, notification, _meta)
            }
        } finally {
            earlyDeliveriesInFlight.decrementAndGet()
        }
        return true
    }

    /**
     * Must run under [earlyDeliveryLock].
     */
    private suspend fun deliverEarly(operations: ClientSessionOperations, notification: SessionUpdate, _meta: JsonElement?) {
        if (notification.changesSessionState()) earlyStateUpdates += notification
        operations.notify(notification, _meta)
    }

    private suspend fun deliverToCompletedSession(notification: SessionUpdate, _meta: JsonElement?): Boolean {
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
