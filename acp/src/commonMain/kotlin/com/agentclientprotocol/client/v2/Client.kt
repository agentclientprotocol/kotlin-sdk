package com.agentclientprotocol.client.v2

import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CompleteElicitationNotification
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.DeleteSessionRequest
import com.agentclientprotocol.model.v2.DeleteSessionResponse
import com.agentclientprotocol.model.v2.DisableProviderRequest
import com.agentclientprotocol.model.v2.DisableProviderResponse
import com.agentclientprotocol.model.v2.ForkSessionRequest
import com.agentclientprotocol.model.v2.InitializeRequest
import com.agentclientprotocol.model.v2.ListProvidersRequest
import com.agentclientprotocol.model.v2.ListProvidersResponse
import com.agentclientprotocol.model.v2.ListSessionsRequest
import com.agentclientprotocol.model.v2.ListSessionsResponse
import com.agentclientprotocol.model.v2.LlmProtocol
import com.agentclientprotocol.model.v2.LoginAuthRequest
import com.agentclientprotocol.model.v2.LoginAuthResponse
import com.agentclientprotocol.model.v2.LogoutAuthRequest
import com.agentclientprotocol.model.v2.LogoutAuthResponse
import com.agentclientprotocol.model.v2.McpServer
import com.agentclientprotocol.model.v2.NewSessionRequest
import com.agentclientprotocol.model.v2.ProviderId
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.ResumeSessionRequest
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SetProviderRequest
import com.agentclientprotocol.model.v2.SetProviderResponse
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.protocol.readProtocolVersionOrNull
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.ACPJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * **UNSTABLE**
 *
 * A client-side connection to an agent that speaks protocol version 2.
 *
 * Which version a client asks for is its own decision, so it is expressed by the class it puts on the
 * connection: this one sends the v2 handshake and serves the v2 client methods. Nothing is shared with
 * [com.agentclientprotocol.client.Client] but the [protocol] underneath — the two claim the same handler
 * names, so a client that wants to fall back to v1 constructs the v1 class after this one has failed.
 *
 * ```kotlin
 * val client = Client(protocol, elicitation = myElicitationHandler)
 * val agentInfo = client.initialize(clientInfo)
 * ```
 *
 * @property protocol the protocol instance whose handlers this client installs.
 * @property elicitation answers `elicitation/create`. Registered for the whole connection rather than per
 *   session, because a v2 elicitation carries its own scope, which may be a request outside any session.
 */
@UnstableApi
public class Client(
    public val protocol: Protocol,
    private val elicitation: ElicitationHandler? = null,
) {
    /**
     * The client-side inbox for one session.
     *
     * [Client] writes incoming `session/update` notifications to [updates]. The same channel is later passed
     * to [ClientSession], which exposes its read side as [ClientSession.updates]. The inbox can therefore
     * start buffering updates before the corresponding [ClientSession] has been created.
     */
    private class Inbox {
        /**
         * The update channel owned and written to by [Client].
         *
         * It is unlimited so the protocol read loop never blocks while an opening call is waiting for its
         * response. This is particularly important for `session/resume`, whose replayed updates arrive before
         * the response by design.
         */
        val updates = Channel<ClientSession.UpdateWithMeta>(capacity = Channel.UNLIMITED)
    }

    /**
     * @property opening how many `session/new`, `session/resume` or `session/fork` calls are in flight, which
     *   is what tells an update for an unknown id apart from one for a session that is about to exist.
     * @property inboxes a buffer per session id, kept from the first update until the session is closed.
     * @property sessions the sessions of this connection, once their opening call has answered.
     */
    private class Sessions(
        val opening: Int = 0,
        val inboxes: PersistentMap<SessionId, Inbox> = persistentMapOf(),
        val sessions: PersistentMap<SessionId, ClientSession> = persistentMapOf(),
    ) {
        fun copy(
            opening: Int = this.opening,
            inboxes: PersistentMap<SessionId, Inbox> = this.inboxes,
            sessions: PersistentMap<SessionId, ClientSession> = this.sessions,
        ) = Sessions(opening, inboxes, sessions)
    }

    private val _sessions = atomic(Sessions())
    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _agentInfo = CompletableDeferred<AgentInfo>()

    /**
     * What this client reported in `initialize`.
     *
     * Completes when initialization succeeds.
     */
    public val clientInfo: Deferred<ClientInfo>
        get() = _clientInfo

    /**
     * What the agent answered with in `initialize`.
     *
     * Completes when initialization succeeds.
     */
    public val agentInfo: Deferred<AgentInfo>
        get() = _agentInfo

    init {
        setHandlers()
    }

    /**
     * Sends the v2 handshake.
     *
     * @throws UnsupportedProtocolVersionException if the agent answers with a different version. The
     *   connection is **not** closed; [com.agentclientprotocol.client.ClientNegotiator] can select the
     *   matching client directly from the raw response without repeating the handshake.
     */
    public suspend fun initialize(clientInfo: ClientInfo, _meta: JsonElement? = null): AgentInfo {
        protocol.negotiatedProtocolVersion?.let { version ->
            acpFail("Connection is already initialized with protocol version $version")
        }
        val method = AcpMethod.AgentMethods.V2.Initialize
        val rawResponse = protocol.sendRequestRaw(
            method.methodName,
            ACPJson.encodeToJsonElement(
                method.requestSerializer,
                InitializeRequest(clientInfo.protocolVersion, clientInfo.implementation, clientInfo.capabilities, _meta)
            ),
        )
        return completeInitialize(clientInfo, rawResponse)
    }

    /** Completes initialization from an `initialize` response already received by `ClientNegotiator`. */
    internal fun completeInitialize(clientInfo: ClientInfo, rawResponse: JsonElement): AgentInfo {
        val method = AcpMethod.AgentMethods.V2.Initialize
        // The version is read before the payload is decoded, because an agent that speaks another version
        // answers in that version's shape: v1's response carries `agentInfo` where v2 requires `info`, so
        // decoding first would report a missing field instead of the version mismatch it really is.
        val offeredVersion = readProtocolVersionOrNull(rawResponse)
            ?: acpFail("The agent's initialize response is missing the required `protocolVersion` field")
        if (offeredVersion != PROTOCOL_VERSION_V2) {
            throw UnsupportedProtocolVersionException(
                requestedVersion = clientInfo.protocolVersion,
                offeredVersion = offeredVersion,
                supportedVersions = setOf(PROTOCOL_VERSION_V2),
            )
        }
        val response = ACPJson.decodeFromJsonElement(method.responseSerializer, rawResponse)
        val negotiated = protocol.recordNegotiatedProtocolVersion(response.protocolVersion)
        if (negotiated != response.protocolVersion) {
            acpFail("Connection is already initialized with protocol version $negotiated")
        }
        _clientInfo.complete(clientInfo)
        val agentInfo = AgentInfo(response.info, response.capabilities, response.authMethods, response._meta)
        _agentInfo.complete(agentInfo)
        return agentInfo
    }

    private fun setHandlers() {
        protocol.setNotificationHandler(AcpMethod.ClientMethods.V2.SessionUpdate) { params: UpdateSessionNotification ->
            val inbox = inboxFor(params.sessionId)
            if (inbox == null) {
                logger.warn { "Received a v2 session/update for unknown session ${params.sessionId}" }
                return@setNotificationHandler
            }
            inbox.updates.send(ClientSession.UpdateWithMeta(params.update, params._meta))
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V2.SessionRequestPermission) { params: RequestPermissionRequest ->
            // Not buffered the way updates are, and deliberately: answering takes the session's operations,
            // and a turn cannot have started before the client knew the id, so this is the agent's mistake.
            val session = _sessions.value.sessions[params.sessionId]
                ?: acpFail("Received session/request_permission for unknown session ${params.sessionId}")
            return@setRequestHandler session.handlePermissionRequest(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V2.ElicitationCreate) { params: CreateElicitationRequest ->
            val handler = elicitation
                ?: acpFail(
                    "This client has no elicitation handler, so it cannot answer elicitation/create. " +
                        "Pass one to the Client constructor to handle elicitations"
                )
            return@setRequestHandler handler.createElicitation(params)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.V2.ElicitationComplete) { params: CompleteElicitationNotification ->
            val handler = elicitation
            if (handler == null) {
                logger.debug { "Ignoring elicitation/complete for ${params.elicitationId}: no handler" }
                return@setNotificationHandler
            }
            handler.elicitationCompleted(params.elicitationId, params._meta)
        }
    }

    /**
     * Creates a session.
     *
     * Updates the agent sends before this call returns are kept and delivered through the returned session's
     * [ClientSession.updates], so the beginning of a session is not lost to the round trip.
     */
    public suspend fun newSession(
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        operations: ClientSessionOperations? = null,
        _meta: JsonElement? = null,
    ): ClientSession = whileOpeningSession {
        val response = AcpMethod.AgentMethods.V2.SessionNew(
            protocol,
            NewSessionRequest(cwd, mcpServers, additionalDirectories, _meta)
        )
        register(response.sessionId, response.configOptions, operations)
    }

    /** The session with [sessionId], or `null` if this connection has no such session. */
    public fun getSession(sessionId: SessionId): ClientSession? = _sessions.value.sessions[sessionId]

    /**
     * Resumes an existing session with `session/resume`, v2's replacement for `session/load`.
     *
     * History replayed for [replayFrom] arrives as `session/update` while this call is still in flight, and
     * is kept for the returned session's [ClientSession.updates] rather than dropped.
     */
    public suspend fun resumeSession(
        sessionId: SessionId,
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        replayFrom: ReplayFrom? = null,
        operations: ClientSessionOperations? = null,
        _meta: JsonElement? = null,
    ): ClientSession = whileOpeningSession {
        val response = AcpMethod.AgentMethods.V2.SessionResume(
            protocol,
            ResumeSessionRequest(sessionId, cwd, additionalDirectories, mcpServers, replayFrom, _meta)
        )
        register(sessionId, response.configOptions, operations)
    }

    /**
     * Forks a session with `session/fork`, starting a new one from an existing session's history.
     *
     * The returned session has the **new** id the agent minted, not [sessionId].
     */
    public suspend fun forkSession(
        sessionId: SessionId,
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        operations: ClientSessionOperations? = null,
        _meta: JsonElement? = null,
    ): ClientSession = whileOpeningSession {
        val response = AcpMethod.AgentMethods.V2.SessionFork(
            protocol,
            ForkSessionRequest(sessionId, cwd, additionalDirectories, mcpServers, _meta)
        )
        register(response.sessionId, response.configOptions, operations)
    }

    /** Lists configurable providers with `providers/list`. */
    public suspend fun listProviders(_meta: JsonElement? = null): ListProvidersResponse =
        AcpMethod.AgentMethods.V2.ProvidersList(protocol, ListProvidersRequest(_meta))

    /**
     * Replaces one provider's configuration with `providers/set`.
     *
     * [headers] is the full map for that provider, not a patch.
     */
    public suspend fun setProvider(
        providerId: ProviderId,
        apiType: LlmProtocol,
        baseUrl: String,
        headers: Map<String, String> = emptyMap(),
        _meta: JsonElement? = null,
    ): SetProviderResponse = AcpMethod.AgentMethods.V2.ProvidersSet(
        protocol,
        SetProviderRequest(providerId, apiType, baseUrl, headers, _meta)
    )

    /**
     * Disables a provider with `providers/disable`.
     *
     * Not to be called for a provider that `providers/list` reported as `required`.
     */
    public suspend fun disableProvider(
        providerId: ProviderId,
        _meta: JsonElement? = null,
    ): DisableProviderResponse =
        AcpMethod.AgentMethods.V2.ProvidersDisable(protocol, DisableProviderRequest(providerId, _meta))

    /**
     * Authenticates with `auth/login`.
     *
     * Only call this when the initialize response listed [AgentInfo.authMethods]; when it is empty, the
     * agent is not obliged to implement authentication at all.
     */
    public suspend fun login(methodId: AuthMethodId, _meta: JsonElement? = null): LoginAuthResponse =
        AcpMethod.AgentMethods.V2.AuthLogin(protocol, LoginAuthRequest(methodId, _meta))

    /** Logs out with `auth/logout`. See [login]. */
    public suspend fun logout(_meta: JsonElement? = null): LogoutAuthResponse =
        AcpMethod.AgentMethods.V2.AuthLogout(protocol, LogoutAuthRequest(_meta))

    /**
     * Lists sessions with `session/list`, one page at a time.
     *
     * Pass [ListSessionsResponse.nextCursor] back as [cursor] for the next page; a `null` cursor in the
     * response means this was the last one.
     */
    public suspend fun listSessions(
        cwd: String? = null,
        cursor: String? = null,
        _meta: JsonElement? = null,
    ): ListSessionsResponse =
        AcpMethod.AgentMethods.V2.SessionList(protocol, ListSessionsRequest(cwd, cursor, _meta))

    /**
     * Deletes a session with `session/delete`, dropping it from what [listSessions] reports.
     *
     * Takes an id rather than a session object because a session can be deleted without ever being open on
     * this connection.
     */
    public suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement? = null): DeleteSessionResponse {
        val response = AcpMethod.AgentMethods.V2.SessionDelete(protocol, DeleteSessionRequest(sessionId, _meta))
        removeSession(sessionId)
        return response
    }

    /**
     * Builds the session for [sessionId] over the updates already buffered for it, and registers it.
     *
     * Two steps rather than one: the inbox is claimed first so that whatever arrives between the two is
     * still written to the buffer this session reads.
     */
    private fun register(
        sessionId: SessionId,
        configOptions: List<SessionConfigOption>,
        operations: ClientSessionOperations?,
    ): ClientSession {
        val inbox = claimInbox(sessionId)
        val session = ClientSession(
            sessionId = sessionId,
            configOptions = configOptions,
            protocol = protocol,
            operations = operations,
            updatesFlow = inbox.updates.consumeAsFlow(),
            onClosed = { removeSession(sessionId) },
        )
        _sessions.update { it.copy(sessions = it.sessions.put(sessionId, session)) }
        return session
    }

    /**
     * The buffer to put an update in: an open session's, or a new one while a session is being opened and
     * this may be the id it is about to get. `null` for anything else, and the update is dropped.
     */
    private fun inboxFor(sessionId: SessionId): Inbox? {
        // Fast path for the common case of a session that has been open for a while.
        _sessions.value.inboxes[sessionId]?.let { return it }
        var inbox: Inbox? = null

        // Every branch looks the inbox up in `current` rather than trusting the read above: a concurrent
        // newSession can register one, or stop the opening window, in between.
        _sessions.update { current ->
            val existing = current.inboxes[sessionId]
            when {
                existing != null -> {
                    inbox = existing
                    current
                }
                current.opening > 0 -> {
                    val opened = Inbox()
                    inbox = opened
                    current.copy(inboxes = current.inboxes.put(sessionId, opened))
                }
                else -> {
                    inbox = null
                    current
                }
            }
        }
        return inbox
    }

    /**
     * The inbox for a session about to be registered: the one already filled for it during the opening
     * window, or a fresh one if nothing arrived that early.
     *
     * A session registered a second time — two `session/resume` calls for one id, or an agent answering with
     * an id it has already handed out — gets a fresh buffer, and the buffer the previous session was reading
     * is closed. Otherwise the two would compete over one channel, each swallowing some of the updates.
     */
    private fun claimInbox(sessionId: SessionId): Inbox {
        var claimed: Inbox? = null
        var replaced: Inbox? = null
        _sessions.update { current ->
            val buffered = current.inboxes[sessionId]
            replaced = if (sessionId in current.sessions) buffered else null
            if (buffered != null && replaced == null) {
                claimed = buffered
                current
            } else {
                val fresh = Inbox()
                claimed = fresh
                current.copy(inboxes = current.inboxes.put(sessionId, fresh))
            }
        }
        replaced?.updates?.close()
        return claimed!!
    }

    /**
     * Runs a call that opens a session, and marks it as in flight for as long as it lasts.
     *
     * That mark is what lets [inboxFor] keep an update for an id this client has never seen: it can only be
     * the session this call is about to return. When the last such call is done, buffers that no session
     * claimed are dropped — a failed `session/new` would leak one otherwise, and a peer sending updates for
     * ids that never materialise would grow the map without bound.
     */
    private suspend fun whileOpeningSession(open: suspend () -> ClientSession): ClientSession {
        _sessions.update { it.copy(opening = it.opening + 1) }
        try {
            return open()
        } finally {
            var unclaimed: List<SessionId> = emptyList()
            _sessions.update { current ->
                unclaimed = emptyList()
                if (current.opening == 0) {
                    logger.error { "Assertion failed: no session is being opened, so the count cannot be decremented" }
                    return@update current
                }
                val opening = current.opening - 1
                if (opening > 0) return@update current.copy(opening = opening)
                unclaimed = current.inboxes.keys.filter { it !in current.sessions }
                current.copy(
                    opening = opening,
                    inboxes = unclaimed.fold(current.inboxes) { inboxes, id -> inboxes.remove(id) },
                )
            }
            for (id in unclaimed) {
                logger.warn { "Dropping buffered v2 session/update notifications for unknown session $id" }
            }
        }
    }

    /**
     * Forgets a session locally; the wire call is [ClientSession.close] or [deleteSession].
     *
     * Closing the inbox ends the session's [ClientSession.updates] flow, so a collector finishes instead of
     * waiting for updates that can no longer come.
     */
    private fun removeSession(sessionId: SessionId) {
        var inbox: Inbox? = null
        _sessions.update { current ->
            inbox = current.inboxes[sessionId]
            current.copy(inboxes = current.inboxes.remove(sessionId), sessions = current.sessions.remove(sessionId))
        }
        inbox?.updates?.close()
    }
}
