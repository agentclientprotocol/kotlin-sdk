package com.agentclientprotocol.client

import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement

/**
 * The client's slot for one session id: buffers its updates until the session is created, then delivers them to it.
 *
 * [Client] creates a holder when it starts initializing a session, and speculatively for an unknown session id that
 * receives an update while some initialization is in progress.
 */
internal class ClientSessionHolder {
    private val sessionDeferred: CompletableDeferred<ClientSessionImpl> = CompletableDeferred()
    // Don't make the channel limited, because it leads to a deadlock also:
    // when client side makes loadSession/newSession and an agent sends updates more than channel.capacity
    // the message with call response suspends because protocol thread is suspended in handleNotification
    // if to address it we have to somehow reorder events, that's not obvious on the protocol level, so we pay with memory right now to handle it
    private val notifications = Channel<Pair<SessionUpdate, JsonElement?>>(capacity = Channel.UNLIMITED)

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
     * Delivers [notification] to the session, or queues it while the session is initializing.
     *
     * @return `false` if the holder ended without a session, so the update belongs to no connected session
     */
    suspend fun handleOrQueue(notification: SessionUpdate, _meta: JsonElement?): Boolean {
        if (notifications.trySend(Pair(notification, _meta)).isSuccess) return true

        /*
         * The channel is closed by a drain. Wait for it to finish instead of awaiting the session: the holder
         * may be failed because no initialization claimed it, and that must not fail the notification.
         */
        sessionDeferred.join()
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
