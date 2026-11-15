@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.acpFail
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * The session map of a [Client]: owns the rules about which [ClientSessionHolder] owns a session id, and delivers
 * `session/update` notifications to the holders.
 *
 * @param globalSessionUpdateHandler receives the updates for sessions that this client is not connected to
 */
internal class ClientSessionRegistry(
    private val globalSessionUpdateHandler: GlobalSessionUpdateHandler?,
) {
    private data class SessionsStorage(val initializingSessionsCount: Int = 0, val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf())

    private val _sessions = atomic(SessionsStorage())

    /**
     * Looks up the holder for [sessionId], creating a new entry only if there are some currently initializing
     * sessions. Returns `null` if the session is neither registered nor being initialized, instead of throwing -
     * callers that need a session to exist (e.g., to service a request against it) should use
     * [getOrCreateSessionHolder] instead.
     *
     * A failed holder counts as missing and is replaced while sessions initialize, so a retry of a failed opening
     * gets a fresh holder. Dropping a failed holder loses nothing, because [failSessionHolder] drains it at once.
     */
    private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
        // Fast path for the common case of an already registered session.
        _sessions.value.sessions[sessionId]?.takeUnless { it.isFailed }?.let { return it }
        var clientSessionHolder: ClientSessionHolder? = null
        /*
         * Every branch below has to look the session up in `currentStorage` rather than rely on the read above:
         * `_sessions` can change between that read and the CAS, and it can change again between two attempts of
         * the CAS loop. A session that a concurrent newSession/loadSession registers in such a window - together
         * with decrementing `initializingSessionsCount` back to zero - would otherwise be reported as missing.
         */
        _sessions.update { currentStorage ->
            val existingHolder = currentStorage.sessions[sessionId]?.takeUnless { it.isFailed }
            when {
                existingHolder != null -> {
                    clientSessionHolder = existingHolder
                    currentStorage
                }
                currentStorage.initializingSessionsCount > 0 -> {
                    val newHolder = ClientSessionHolder()
                    clientSessionHolder = newHolder
                    currentStorage.copy(sessions = currentStorage.sessions.put(sessionId, newHolder))
                }
                else -> {
                    clientSessionHolder = null
                    currentStorage
                }
            }
        }
        return clientSessionHolder
    }

    /**
     * Creates a new entry only if there are some currently initializing sessions. Otherwise, throws in the case of missing session.
     */
    private fun getOrCreateSessionHolder(sessionId: SessionId): ClientSessionHolder =
        getOrCreateSessionHolderOrNull(sessionId) ?: acpFail("Session $sessionId not found")

    fun removeSessionHolder(sessionId: SessionId) {
        _sessions.update { currentMap ->
            currentMap.copy(sessions = currentMap.sessions.remove(sessionId))
        }
    }

    /**
     * Returns the opened session [sessionId], without creating a holder.
     *
     * @throws IllegalStateException if the session is not registered or not initialized yet, or is a subagent session
     */
    fun getSession(sessionId: SessionId): ClientSession {
        val sessionHolder = _sessions.value.sessions[sessionId] ?: error("Session $sessionId not found")
        if (!sessionHolder.session.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return sessionHolder.session.getCompleted() as? ClientSession ?: error("Session $sessionId is a subagent session")
    }

    /**
     * Returns the session [sessionId], waiting until it is created when its opening is in progress.
     *
     * @throws com.agentclientprotocol.protocol.AcpExpectedError if the session is unknown
     */
    suspend fun getSessionOrThrow(sessionId: SessionId): BaseClientSession {
        return getOrCreateSessionHolder(sessionId).session.await()
    }

    /**
     * Whether the holder of [sessionId] exists and its session is completed, with a session or with a failure.
     */
    fun isSessionCompleted(sessionId: SessionId): Boolean {
        val holder = _sessions.value.sessions[sessionId]
        return holder != null && holder.session.isCompleted
    }

    /**
     * Delivers a `session/update` to the holder of [sessionId], or to [globalSessionUpdateHandler] if this client is
     * not connected to the session.
     */
    suspend fun deliverSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        /*
         * The agent may report an update (e.g., a status change on `session/list`) for a session this client
         * never called `session/new` / `session/load` / `session/resume` for. It can live on the server,
         * created from another IDE window, the web, or another machine. That's not a protocol violation, so
         * unlike other session-scoped methods, an unknown/unconnected session here must not fail the call.
         * A speculative holder can also be reaped by `withInitializingSession` after we found it here.
         */
        val delivered = getOrCreateSessionHolderOrNull(sessionId)?.handleOrQueue(QueuedItem.Update(update, _meta)) ?: false
        if (!delivered) {
            handleUnconnectedSessionUpdate(sessionId, update, _meta)
        }
    }

    /**
     * Returns the holder of [sessionId], claimed by an opening call that knows the id before its request: `session/load`
     * with [replayRequested], or `session/resume` without it, with its [parameters]. Must run under
     * [withInitializingSession], which makes sure the holder exists.
     *
     * @throws IllegalStateException if the session is open or being opened
     */
    fun claimSessionHolder(sessionId: SessionId, replayRequested: Boolean, parameters: SessionCreationParameters): ClientSessionHolder {
        val sessionHolder = getOrCreateSessionHolder(sessionId)
        check(sessionHolder.claimForOpening(replayRequested, parameters)) { "Session $sessionId is already open or being opened" }
        return sessionHolder
    }

    /**
     * Claims the holder of [sessionId] for the call whose answer [response] names that id, and marks it opened, in
     * one step, with the [parameters] of the call. This serves `session/new` and `session/fork`, whose session id
     * first appears in the answer; neither asks for a replay.
     *
     * Runs in the response hook of the call, so it must not suspend or block. Must run under
     * [withInitializingSession], which makes sure the holder exists.
     *
     * @return `null` if the session is open or being opened
     */
    fun claimAnsweredSessionHolder(
        sessionId: SessionId,
        response: AcpCreatedSessionResponse,
        parameters: SessionCreationParameters,
    ): ClientSessionHolder? {
        val sessionHolder = getOrCreateSessionHolderOrNull(sessionId) ?: return null
        if (!sessionHolder.claimForOpening(replayRequested = false, parameters)) return null
        sessionHolder.markOpened(response)
        return sessionHolder
    }

    private suspend fun handleUnconnectedSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        val handler = globalSessionUpdateHandler
        if (handler != null) {
            handler.onUnconnectedSessionUpdate(sessionId, update, _meta)
        } else {
            logger.debug { "Ignoring session/update for session $sessionId: client is not connected to it" }
        }
    }

    /**
     * Fails [holder] with [cause] and hands its queued updates to [globalSessionUpdateHandler] at once.
     *
     * Every failure of a holder goes through here, so a failed holder keeps no updates. A failure of the handler is
     * logged per update, so it neither stops the drain nor replaces the failure that the caller sees.
     */
    suspend fun failSessionHolder(sessionId: SessionId, holder: ClientSessionHolder, cause: Throwable) {
        withContext(NonCancellable) {
            holder.failAndDrainQueue(cause) { update, meta ->
                runCatching { handleUnconnectedSessionUpdate(sessionId, update, meta) }
                    .onFailure { logger.error(it) { "Unconnected session update handler failed for $sessionId" } }
            }
        }
    }

    suspend fun <T> withInitializingSession(block: suspend () -> T): T {
        _sessions.update { it.copy(initializingSessionsCount = it.initializingSessionsCount + 1) }
        try {
            return block()
        } finally {
            var hangingSessions: Map<SessionId, ClientSessionHolder>? = null
            _sessions.update { currentStorage ->
                hangingSessions = null
                if (currentStorage.initializingSessionsCount == 0) {
                    logger.error { "Assertion failed: initializingSessionsCount should be positive, got ${currentStorage.initializingSessionsCount}" }
                    return@update currentStorage
                }
                val newCount = currentStorage.initializingSessionsCount - 1
                return@update if (newCount == 0) {
                    /*
                     * this means that currently no sessions can be in initializing state during to ongoing load/new/fork/resume calls
                     * so if on exit from these methods we observe any entries with not completed or failed state we assume that someone sent us events with non-existent session ids
                     * and we have to remove them and report errors
                     */
                    hangingSessions = currentStorage.sessions.filterValues {
                        @OptIn(ExperimentalCoroutinesApi::class)
                        !it.session.isCompleted || it.session.getCompletionExceptionOrNull() != null
                    }
                    var aliveSessions: PersistentMap<SessionId, ClientSessionHolder> = currentStorage.sessions
                    for ((id, _) in hangingSessions) {
                        aliveSessions = aliveSessions.remove(id)
                    }
                    currentStorage.copy(initializingSessionsCount = newCount, sessions = aliveSessions)
                } else {
                    currentStorage.copy(initializingSessionsCount = newCount)
                }
            }
            if (hangingSessions != null) {
                for ((id, holder) in hangingSessions) {
                    logger.trace { "Removing hanging session $id" }
                    /*
                     * Report it as a non-existent session. Its updates were buffered on the assumption they might
                     * belong to this (or another concurrent) initialization; since none claimed `id`, it's an
                     * unconnected session, same as if no initialization had been in progress when they arrived.
                     */
                    failSessionHolder(id, holder, AcpExpectedError("Session $id not found"))
                }
            }
        }
    }
}
