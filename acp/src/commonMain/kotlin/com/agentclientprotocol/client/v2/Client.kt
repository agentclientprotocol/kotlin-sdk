package com.agentclientprotocol.client.v2

import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AuthMethod
import com.agentclientprotocol.model.v2.CompleteElicitationNotification
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.DeleteSessionRequest
import com.agentclientprotocol.model.v2.DeleteSessionResponse
import com.agentclientprotocol.model.v2.DisableProviderRequest
import com.agentclientprotocol.model.v2.DisableProviderResponse
import com.agentclientprotocol.model.v2.ForkSessionRequest
import com.agentclientprotocol.model.v2.ForkSessionResponse
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
import com.agentclientprotocol.model.v2.NewSessionResponse
import com.agentclientprotocol.model.v2.ProviderId
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.ResumeSessionRequest
import com.agentclientprotocol.model.v2.ResumeSessionResponse
import com.agentclientprotocol.model.v2.SetProviderRequest
import com.agentclientprotocol.model.v2.SetProviderResponse
import com.agentclientprotocol.model.v2.StatusAuthRequest
import com.agentclientprotocol.model.v2.StatusAuthResponse
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
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
 * Incoming updates are delivered directly to [onSessionUpdate], including during setup and after a
 * failed or cancelled setup request. The application owns history assembly and any buffering it needs.
 * Without a callback, updates are ignored. Keep the callback short and enqueue longer work yourself.
 *
 * @property protocol the protocol instance whose handlers this client installs
 * @param elicitation answers `elicitation/create` for the whole connection
 * @param operations answers permission requests for any session, identified by the request's sessionId
 * @param onSessionUpdate receives each full notification in arrival order, independently of session handles
 */
@UnstableApi
public class Client(
    public val protocol: Protocol,
    private val elicitation: ElicitationHandler? = null,
    private val operations: ClientSessionOperations? = null,
    private val onSessionUpdate: (UpdateSessionNotification) -> Unit = {},
) {
    private val pendingPermissions = atomic(persistentMapOf<CompletableDeferred<Unit>, SessionId>())
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

    /**
     * Completes initialization from an `initialize` response already received by `ClientNegotiator`.
     */
    internal fun completeInitialize(clientInfo: ClientInfo, rawResponse: JsonElement): AgentInfo {
        val method = AcpMethod.AgentMethods.V2.Initialize
        /*
         * Read the version before decoding: a v1 response has a different shape, and should report a
         * version mismatch rather than a missing v2 field.
         */
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
            onSessionUpdate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V2.SessionRequestPermission) { params: RequestPermissionRequest ->
            handlePermissionRequest(params)
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
     * Creates a session and returns its complete setup response.
     *
     * [cwd] must be absolute. Nonempty [additionalDirectories] require the advertised capability.
     * Updates may arrive through the connection callback before this call returns.
     */
    public suspend fun newSession(
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        _meta: JsonElement? = null,
    ): NewSessionResponse {
        requireAdditionalDirectoriesSupport(additionalDirectories)
        return AcpMethod.AgentMethods.V2.SessionNew(
            protocol,
            NewSessionRequest(cwd, mcpServers, additionalDirectories, _meta)
        )
    }

    /**
     * Creates a command handle for [sessionId] without sending a request or registering a session.
     *
     * Updates and permission requests are handled by the connection, independently of this handle.
     */
    public fun session(sessionId: SessionId): ClientSession = ClientSession(sessionId, this)

    /**
     * Resumes an existing session and returns its complete setup response.
     *
     * [cwd] must be absolute. Nonempty [additionalDirectories] require the advertised capability.
     * History requested by [replayFrom] arrives through the connection callback before the response.
     * A failed or cancelled request does not undo delivered updates or retry the resume.
     */
    public suspend fun resumeSession(
        sessionId: SessionId,
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        replayFrom: ReplayFrom? = null,
        _meta: JsonElement? = null,
    ): ResumeSessionResponse {
        requireAdditionalDirectoriesSupport(additionalDirectories)
        return AcpMethod.AgentMethods.V2.SessionResume(
            protocol,
            ResumeSessionRequest(sessionId, cwd, additionalDirectories, mcpServers, replayFrom, _meta)
        )
    }

    /**
     * Forks a session, returning the new session's id and configuration in the complete response.
     *
     * [cwd] must be absolute. Nonempty [additionalDirectories] require the advertised capability.
     */
    public suspend fun forkSession(
        sessionId: SessionId,
        cwd: String,
        mcpServers: List<McpServer> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        _meta: JsonElement? = null,
    ): ForkSessionResponse {
        requireAdditionalDirectoriesSupport(additionalDirectories)
        return AcpMethod.AgentMethods.V2.SessionFork(
            protocol,
            ForkSessionRequest(sessionId, cwd, additionalDirectories, mcpServers, _meta)
        )
    }

    private suspend fun requireAdditionalDirectoriesSupport(directories: List<String>) {
        if (directories.isEmpty()) return
        if (!_agentInfo.isCompleted) {
            acpFail("Cannot send additionalDirectories before initialization completes")
        }
        if (_agentInfo.await().capabilities.session?.additionalDirectories == null) {
            acpFail("Cannot send additionalDirectories: the agent did not advertise session.additionalDirectories")
        }
    }

    /**
     * Lists configurable providers with `providers/list`.
     */
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
     * Call this only after [initialize] succeeds. Calls made before or during initialization fail locally
     * without waiting or sending a request.
     *
     * [methodId] must be one the agent advertised in [AgentInfo.authMethods]; anything else fails locally
     * without sending a request. That covers every call when the list is empty, since an agent that
     * advertised nothing is not obliged to implement authentication at all.
     */
    public suspend fun login(methodId: AuthMethodId, _meta: JsonElement? = null): LoginAuthResponse {
        val method = AcpMethod.AgentMethods.V2.AuthLogin
        val authMethods = requireAuthenticationSupport(method)
        if (authMethods.none { it.methodId == methodId }) {
            acpFail(
                "Cannot call ${method.methodName.name} with methodId '${methodId.value}': the agent " +
                        "advertised only ${authMethods.joinToString { "'${it.methodId.value}'" }}"
            )
        }
        return method(protocol, LoginAuthRequest(methodId, _meta))
    }

    /**
     * Logs out with `auth/logout`.
     *
     * The same initialization and [AgentInfo.authMethods] requirements as [login] apply.
     */
    public suspend fun logout(_meta: JsonElement? = null): LogoutAuthResponse {
        val method = AcpMethod.AgentMethods.V2.AuthLogout
        requireAuthenticationSupport(method)
        return method(protocol, LogoutAuthRequest(_meta))
    }

    /**
     * Queries whether credentials are configured, not whether they are valid.
     *
     * Requires a completed [initialize] call and `capabilities.auth.status == true`.
     * This query is independent of [AgentInfo.authMethods] and needs no session.
     * Unadvertised calls fail locally without sending a request.
     */
    @UnstableApi
    public suspend fun authStatus(_meta: JsonElement? = null): StatusAuthResponse {
        val method = AcpMethod.AgentMethods.V2.AuthStatus
        if (!_agentInfo.isCompleted) {
            acpFail("Cannot call ${method.methodName.name} before initialization completes")
        }
        if (_agentInfo.await().capabilities.auth?.status != true) {
            acpFail("Cannot call ${method.methodName.name}: the agent did not advertise auth.status")
        }
        return method(protocol, StatusAuthRequest(_meta))
    }

    /**
     * Fails unless [initialize] finished and the agent advertised authentication; returns what it advertised.
     */
    private suspend fun requireAuthenticationSupport(method: AcpMethod): List<AuthMethod> {
        /*
         * Awaiting before successful initialization could hang forever if initialization failed.
         */
        if (!_agentInfo.isCompleted) {
            acpFail("Cannot call ${method.methodName.name} before initialization completes")
        }
        return _agentInfo.await().authMethods.ifEmpty {
            acpFail("Cannot call ${method.methodName.name}: the agent did not advertise any authMethods")
        }
    }

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
    public suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement? = null): DeleteSessionResponse =
        AcpMethod.AgentMethods.V2.SessionDelete(protocol, DeleteSessionRequest(sessionId, _meta))

    internal suspend fun handlePermissionRequest(request: RequestPermissionRequest): RequestPermissionResponse {
        val handler = operations
            ?: acpFail("Pass operations to the Client constructor to handle session/request_permission")
        val cancelled = CompletableDeferred<Unit>()
        pendingPermissions.update { it.put(cancelled, request.sessionId) }
        try {
            return coroutineScope {
                val answer = async { handler.requestPermission(request) }
                select {
                    cancelled.onAwait {
                        answer.cancel()
                        RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }
                    answer.onAwait { it }
                }
            }
        } finally {
            pendingPermissions.update { it.remove(cancelled) }
        }
    }

    internal fun cancelPendingPermissions(sessionId: SessionId) {
        for ((cancelled, id) in pendingPermissions.value) {
            if (id == sessionId) cancelled.complete(Unit)
        }
    }
}
