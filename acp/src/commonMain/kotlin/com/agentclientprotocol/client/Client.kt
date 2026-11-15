@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.*
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.util.PaginatedResponseToFlowAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

@Deprecated("Use Client instead", ReplaceWith("Client"))
public typealias ClientInstance = Client

/**
 * A client-side connection to an agent.
 *
 * This class provides the client's view of an ACP connection, allowing
 * clients (such as code editors) to communicate with agents. It implements
 * the {@link Agent} to provide methods for initializing sessions, sending
 * prompts, and managing the agent lifecycle.
 *
 * See protocol docs: [Client](https://agentclientprotocol.com/protocol/overview#client)
 */
@OptIn(UnstableApi::class)
public class Client(
    public val protocol: Protocol,
    @property:UnstableApi
    public val globalElicitationHandler: GlobalElicitationHandler? = null,
    @property:UnstableApi
    public val globalSessionUpdateHandler: GlobalSessionUpdateHandler? = null
) {
    private val sessions = ClientSessionRegistry(globalSessionUpdateHandler)
    private val _nesSessions = atomic(persistentMapOf<SessionId, ClientNesSessionImpl>())
    @OptIn(UnstableApi::class)
    private val _elicitationToSession = ElicitationSessionStore()

    /**
     * Registers a handler of a session-scoped request that the session's operations answer through [Ops].
     */
    private inline fun <reified Ops : Any, P : AcpRequest, R : AcpResponse> handleSessionRequest(
        method: AcpMethod.AcpRequestResponseMethod<P, R>,
        crossinline sessionIdOf: (P) -> SessionId,
        crossinline call: suspend Ops.(P) -> R,
    ) {
        protocol.setRequestHandler(method) { params ->
            val session = sessions.getSessionOrThrow(sessionIdOf(params))
            val ops = session.operations as? Ops ?: sessionMethodNotFound<Ops>(method)
            session.executeWithSession { ops.call(params) }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun createSessionElicitation(params: CreateElicitationRequest, sessionId: SessionId): CreateElicitationResponse {
        val session = sessions.getSessionOrThrow(sessionId)
        val ops = session.operations as? ElicitationOperations
            ?: sessionMethodNotFound<ElicitationOperations>(AcpMethod.ClientMethods.V1.ElicitationCreate)
        val response = session.executeWithSession { ops.createElicitation(params) }
        // Only track URL-mode elicitation → session after a successful Accept response,
        // since elicitation/complete only follows an accepted URL elicitation
        trackUrlElicitationIfAccepted(params.mode, response, sessionId)
        return response
    }

    private fun createClientSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
        operations: ClientSessionOperations,
        sessionResponse: AcpCreatedSessionResponse,
    ): ClientSessionImpl = ClientSessionImpl(
        this, sessionId, sessionParameters, operations, sessionResponse, protocol,
        onClosing = {
            // Nothing to do yet: a session has no descendants whose permission requests need an answer.
        },
        onClosed = { removeClosedSession(sessionId) },
    )

    private fun removeClosedSession(sessionId: SessionId) {
        sessions.removeSessionHolder(sessionId)
        @OptIn(UnstableApi::class)
        _elicitationToSession.removeBySession(sessionId)
    }

    @OptIn(UnstableApi::class)
    internal fun removeNesSession(sessionId: SessionId) {
        _nesSessions.update { it.remove(sessionId) }
    }

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _agentInfo = CompletableDeferred<AgentInfo>()

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = sessions.getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.handlePermissionResponse(params.toolCall, params.options, params._meta)
            }
        }

        handleSessionRequest<FileSystemOperations, _, _>(AcpMethod.ClientMethods.V1.FsReadTextFile, { it.sessionId }) { params ->
            fsReadTextFile(params.path, params.line, params.limit, params._meta)
        }

        handleSessionRequest<FileSystemOperations, _, _>(AcpMethod.ClientMethods.V1.FsWriteTextFile, { it.sessionId }) { params ->
            fsWriteTextFile(params.path, params.content, params._meta)
        }

        handleSessionRequest<TerminalOperations, _, _>(AcpMethod.ClientMethods.V1.TerminalCreate, { it.sessionId }) { params ->
            terminalCreate(params.command, params.args, params.cwd, params.env, params.outputByteLimit, params._meta)
        }

        handleSessionRequest<TerminalOperations, _, _>(AcpMethod.ClientMethods.V1.TerminalKill, { it.sessionId }) { params ->
            terminalKill(params.terminalId, params._meta)
        }

        handleSessionRequest<TerminalOperations, _, _>(AcpMethod.ClientMethods.V1.TerminalOutput, { it.sessionId }) { params ->
            terminalOutput(params.terminalId, params._meta)
        }

        handleSessionRequest<TerminalOperations, _, _>(AcpMethod.ClientMethods.V1.TerminalRelease, { it.sessionId }) { params ->
            terminalRelease(params.terminalId, params._meta)
        }

        handleSessionRequest<TerminalOperations, _, _>(AcpMethod.ClientMethods.V1.TerminalWaitForExit, { it.sessionId }) { params ->
            terminalWaitForExit(params.terminalId, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.V1.SessionUpdate) { params: SessionNotification ->
            sessions.deliverSessionUpdate(params.sessionId, params.update, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.ElicitationCreate) { params: CreateElicitationRequest ->
            when (val scope = params.scope) {
                is ElicitationScope.Session -> createSessionElicitation(params, scope.sessionId)
                is ElicitationScope.Request -> {
                    val sessionId = protocol.getOutgoingRequestSessionId(scope.requestId)
                    if (sessionId != null) {
                        createSessionElicitation(params, sessionId)
                    } else {
                        @OptIn(UnstableApi::class)
                        val handler = globalElicitationHandler
                            ?: acpFail("No handler for non-session elicitation")
                        handler.createElicitation(params)
                    }
                }
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.ClientMethods.V1.ElicitationComplete) { params: CompleteElicitationNotification ->
            // Unknown or already-completed elicitation IDs are silently ignored per the RFD.
            val sessionId = _elicitationToSession.remove(params.elicitationId)
            if (sessionId != null && sessions.isSessionCompleted(sessionId)) {
                // The session may have been closed between the elicitation and the completion.
                val session = runCatching { sessions.getSessionOrThrow(sessionId) }.getOrNull()
                val ops = session?.operations as? ElicitationOperations
                if (session != null && ops != null) {
                    session.executeWithSession { ops.completeElicitation(params) }
                }
            }
        }
    }

    public val clientInfo: ClientInfo
        get() {
            if (!_clientInfo.isCompleted) error("Client is not initialized yet")
            @OptIn(ExperimentalCoroutinesApi::class)
            return _clientInfo.getCompleted()
        }

    public val agentInfo: AgentInfo
        get() {
            if (!_agentInfo.isCompleted) error("Agent is not initialized yet")
            @OptIn(ExperimentalCoroutinesApi::class)
            return _agentInfo.getCompleted()
        }

    /**
     * Initializes the connection and negotiates the protocol version.
     *
     * @throws UnsupportedProtocolVersionException if the agent answers with a version outside
     *   [ClientInfo.supportedProtocolVersions]; the connection is closed before throwing
     */
    public suspend fun initialize(clientInfo: ClientInfo, _meta: JsonElement? = null): AgentInfo {
        val method = AcpMethod.AgentMethods.V1.Initialize
        val rawResponse = protocol.sendRequestRaw(
            method.methodName,
            ACPJson.encodeToJsonElement(
                method.requestSerializer,
                InitializeRequest(clientInfo.protocolVersion, clientInfo.capabilities, clientInfo.implementation, _meta),
            ),
        )
        return completeInitialize(clientInfo, rawResponse)
    }

    /** Completes initialization from an `initialize` response already received by [ClientNegotiator]. */
    internal fun completeInitialize(clientInfo: ClientInfo, rawResponse: JsonElement): AgentInfo {
        val offeredVersion = readProtocolVersionOrNull(rawResponse)
            ?: acpFail("The agent's initialize response is missing the required `protocolVersion` field")
        if (offeredVersion !in clientInfo.supportedProtocolVersions) {
            // see https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation
            logger.error {
                "Agent answered protocol version $offeredVersion, which is not in " +
                    "${clientInfo.supportedProtocolVersions}; closing the connection"
            }
            protocol.close()
            throw UnsupportedProtocolVersionException(
                requestedVersion = clientInfo.protocolVersion,
                offeredVersion = offeredVersion,
                supportedVersions = clientInfo.supportedProtocolVersions,
            )
        }
        val method = AcpMethod.AgentMethods.V1.Initialize
        val initializeResponse = ACPJson.decodeFromJsonElement(method.responseSerializer, rawResponse)
        val negotiatedVersion = protocol.recordNegotiatedProtocolVersion(offeredVersion)
        if (negotiatedVersion != offeredVersion) {
            logger.warn {
                "Repeated initialize answered protocol version $offeredVersion, but this connection already " +
                "speaks $negotiatedVersion; keeping $negotiatedVersion"
            }
        }
        _clientInfo.complete(clientInfo)
        val agentInfo = AgentInfo(offeredVersion, initializeResponse.agentCapabilities, initializeResponse.authMethods, initializeResponse.agentInfo, initializeResponse._meta)
        _agentInfo.complete(agentInfo)
        return agentInfo
    }


    /**
     * Performs authentication of the agent with the specified [methodId].
     * The method may throw an exception if the authentication fails.
     */
    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement? = null): AuthenticateResponse {
        return AcpMethod.AgentMethods.V1.Authenticate(protocol, AuthenticateRequest(methodId, _meta))
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Logs out of the current authenticated state.
     *
     * After a successful logout, all new sessions will require authentication.
     * There is no guarantee about the behavior of already running sessions.
     */
    @UnstableApi
    public suspend fun logout(_meta: JsonElement? = null): LogoutResponse {
        return AcpMethod.AgentMethods.V1.Logout(protocol, LogoutRequest(_meta))
    }

    /**
     * Queries whether credentials are configured, not whether they are valid.
     *
     * Requires a completed [initialize] call and `agentCapabilities.auth.status == true`.
     * Unadvertised calls fail locally without sending a request. No session is required.
     */
    @UnstableApi
    public suspend fun authStatus(_meta: JsonElement? = null): AuthStatusResponse {
        val method = AcpMethod.AgentMethods.V1.AuthStatus
        if (!_agentInfo.isCompleted) {
            acpFail("Cannot call ${method.methodName.name} before initialization completes")
        }
        if (_agentInfo.await().capabilities.auth.status != true) {
            acpFail("Cannot call ${method.methodName.name}: the agent did not advertise auth.status")
        }
        return method(protocol, AuthStatusRequest(_meta))
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Lists providers that can be configured by the client.
     */
    @UnstableApi
    public suspend fun listProviders(_meta: JsonElement? = null): ListProvidersResponse {
        return AcpMethod.AgentMethods.V1.ProvidersList(protocol, ListProvidersRequest(_meta))
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Replaces the full configuration for one provider id.
     *
     * @param id provider id to configure
     * @param apiType protocol type for this provider
     * @param baseUrl base URL for requests sent through this provider
     * @param headers optional headers map for this provider
     * @param _meta optional metadata
     */
    @UnstableApi
    public suspend fun setProvider(
        id: String,
        apiType: LlmProtocol,
        baseUrl: String,
        headers: Map<String, String>? = null,
        _meta: JsonElement? = null
    ): SetProvidersResponse {
        return AcpMethod.AgentMethods.V1.ProvidersSet(protocol, SetProvidersRequest(id, apiType, baseUrl, headers, _meta))
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Disables a provider by id.
     *
     * @param id provider id to disable
     * @param _meta optional metadata
     */
    @UnstableApi
    public suspend fun disableProvider(id: String, _meta: JsonElement? = null): DisableProvidersResponse {
        return AcpMethod.AgentMethods.V1.ProvidersDisable(protocol, DisableProvidersRequest(id, _meta))
    }

    /**
     * Creates a new session with specified [sessionParameters].
     *
     * @param sessionParameters parameters for creating a new session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the new session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the new session
     */
    public suspend fun newSession(sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return sessions.withInitializingSession {
            val newSessionResponse = AcpMethod.AgentMethods.V1.SessionNew(
                protocol,
                NewSessionRequest(
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters.additionalDirectories,
                    sessionParameters._meta
                )
            )
            val sessionId = newSessionResponse.sessionId
            return@withInitializingSession createSession(sessions.claimFactorySessionHolder(sessionId), sessionId, sessionParameters, newSessionResponse, operationsFactory)
        }
    }

    /**
     * Load an existing session with specified [sessionId] and [sessionParameters].
     *
     * @param sessionId the id of the existing session to load
     * @param sessionParameters parameters for creating a new session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the new session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the new session
     * @throws IllegalStateException before the request, if a call with ready operations has opened [sessionId] or
     *   is opening it
     */
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withFactoryInitializingSession(sessionId, sessionParameters, operationsFactory) {
            AcpMethod.AgentMethods.V1.SessionLoad(
                protocol,
                loadSessionRequest(sessionId, sessionParameters)
            )
        }
    }

    /**
     * Loads an existing session with specified [sessionId] and [sessionParameters], and delivers its updates to
     * [operations] while the agent replays the history, instead of buffering them until the answer as the
     * [ClientOperationsFactory] overload does.
     *
     * Each update reaches [ClientSessionOperations.notify] exactly once and in arrival order, before and after the
     * answer. Before the answer, [kotlin.coroutines.CoroutineContext.clientSession] is not available in `notify`,
     * and `notify` must not wait for this call to return. The mode and config options of the returned session are
     * the same as with the [ClientOperationsFactory] overload.
     *
     * If the agent answers with an error, `notify` throws, or the caller is cancelled, the load fails. The updates
     * that [operations] did not receive yet, and all later updates for [sessionId], go to [globalSessionUpdateHandler].
     *
     * @param sessionId the id of the existing session to load
     * @param sessionParameters parameters for loading the session
     * @param operations the operations of the session. It must also implement the interfaces for the extra
     * capabilities that the client declares, like file system or terminal support.
     * @return a [ClientSession] instance for the loaded session
     * @throws IllegalStateException before the request, if [sessionId] already has a session in this client or
     *   another call is opening it
     */
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operations: ClientSessionOperations): ClientSession {
        return withEarlyInitializingSession(sessionId, sessionParameters, operations) {
            AcpMethod.AgentMethods.V1.SessionLoad(
                protocol,
                loadSessionRequest(sessionId, sessionParameters)
            )
        }
    }

    /**
     * Deletes a session from the agent's history.
     *
     * This operation does not modify sessions loaded into this client runtime.
     *
     * @param sessionId the id of the session to delete
     * @param _meta optional metadata
     * @return the agent's [DeleteSessionResponse]
     */
    public suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement? = null): DeleteSessionResponse {
        return AcpMethod.AgentMethods.V1.SessionDelete(protocol, DeleteSessionRequest(sessionId, _meta))
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Lists all existing sessions as a cold flow, automatically handling pagination. The necessary pages are fetched on demand.
     *
     * The flow is cold and finite, so any aggregation operators may be used like `toList`, `take`, etc.
     *
     * Unlike the agent's side, this method returns a cold flow instead of a sequence because remote suspend calls are being done under the hood to fetch pages.
     * Sequences don't support suspending operations between value yields.
     *
     * @param cwd optional current working directory filter
     * @param additionalDirectories optional additional directories filter
     * @param _meta optional metadata
     * @return a cold [Flow] of [SessionInfo] that lazily fetches pages as needed
     */
    @UnstableApi
    public fun listSessions(
        cwd: String? = null,
        additionalDirectories: List<String>? = null,
        _meta: JsonElement? = null
    ): Flow<SessionInfo> {
        return PaginatedResponseToFlowAdapter.asFlow { cursor ->
            AcpMethod.AgentMethods.V1.SessionList(protocol, ListSessionsRequest(cwd, additionalDirectories, cursor, _meta))
        }
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Forks an existing session, creating a new session based on the existing session's context.
     *
     * @param sessionId the id of the session to fork
     * @param sessionParameters parameters for the forked session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the new session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the forked session
     */
    @UnstableApi
    public suspend fun forkSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return sessions.withInitializingSession {
            val forkSessionResponse = AcpMethod.AgentMethods.V1.SessionFork(
                protocol,
                ForkSessionRequest(
                    sessionId,
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters.additionalDirectories,
                    sessionParameters._meta
                )
            )
            val newSessionId = forkSessionResponse.sessionId
            return@withInitializingSession createSession(sessions.claimFactorySessionHolder(newSessionId), newSessionId, sessionParameters, forkSessionResponse, operationsFactory)
        }
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Resumes an existing session without replaying message history.
     *
     * This method is only available if the agent advertises the `session.resume` capability.
     *
     * @param sessionId the id of the session to resume
     * @param sessionParameters parameters for resuming the session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the resumed session
     * @throws IllegalStateException before the request, if a call with ready operations has opened [sessionId] or
     *   is opening it
     */
    @UnstableApi
    public suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withFactoryInitializingSession(sessionId, sessionParameters, operationsFactory) {
            AcpMethod.AgentMethods.V1.SessionResume(
                protocol,
                resumeSessionRequest(sessionId, sessionParameters)
            )
        }
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Resumes an existing session, and delivers the updates that arrive before the answer to [operations] at once.
     *
     * The delivery, the session state, and the failure behavior are the same as for the [loadSession] overload
     * with a [ClientSessionOperations] argument.
     *
     * This method is only available if the agent advertises the `session.resume` capability.
     *
     * @param sessionId the id of the session to resume
     * @param sessionParameters parameters for resuming the session
     * @param operations the operations of the session. It must also implement the interfaces for the extra
     * capabilities that the client declares, like file system or terminal support.
     * @return a [ClientSession] instance for the resumed session
     */
    @UnstableApi
    public suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operations: ClientSessionOperations): ClientSession {
        return withEarlyInitializingSession(sessionId, sessionParameters, operations) {
            AcpMethod.AgentMethods.V1.SessionResume(
                protocol,
                resumeSessionRequest(sessionId, sessionParameters)
            )
        }
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Starts a new NES (Next Edit Suggestions) session.
     *
     * NES sessions are independent from chat sessions and have their own lifecycle.
     *
     * @param workspaceUri optional workspace URI
     * @param workspaceFolders optional list of workspace folders
     * @param repository optional repository information
     * @param _meta optional metadata
     * @return a [ClientNesSession] instance for the new NES session
     */
    @UnstableApi
    public suspend fun startNesSession(
        workspaceUri: String? = null,
        workspaceFolders: List<WorkspaceFolder>? = null,
        repository: NesRepository? = null,
        _meta: JsonElement? = null
    ): ClientNesSession {
        val response = AcpMethod.AgentMethods.V1.NesStart(protocol, StartNesRequest(workspaceUri, workspaceFolders, repository, _meta))
        val session = ClientNesSessionImpl(this, response.sessionId, protocol)
        _nesSessions.update { it.put(response.sessionId, session) }
        return session
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Returns an existing NES session by its session ID.
     *
     * @param sessionId the session ID of the NES session
     * @return a [ClientNesSession] instance
     * @throws IllegalStateException if the session is not found
     */
    @UnstableApi
    public fun getNesSession(sessionId: SessionId): ClientNesSession {
        return _nesSessions.value[sessionId] ?: error("NES session $sessionId not found")
    }

    /**
     * After ClientSessionImpl is created the delayed notifications are drained and pushed into session.notify()
     */
    private suspend fun createSession(
        sessionHolder: ClientSessionHolder,
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
        sessionResponse: AcpCreatedSessionResponse,
        factory: ClientOperationsFactory,
    ): ClientSession {
        return runCatching {
            val operations = factory.createClientOperations(sessionId, sessionResponse)
            val session = createClientSession(sessionId, sessionParameters, operations, sessionResponse)
            sessionHolder.drainEventsAndCompleteSession(session)
            session
        }.getOrElse { throwable ->
            // throw IllegalStateException to pass it as INTERNAL_ERROR to the other side (see in Protocol)
            sessions.failSessionHolder(sessionId, sessionHolder, IllegalStateException("Failed to create session $sessionId", throwable))
            // cleanup of this sessionId entry will be done in finally of withInitializingSession
            throw throwable
        }
    }

    /**
     * Opens the session [sessionId] with [request] under [ClientSessionRegistry.withInitializingSession], and buffers its updates until
     * [factory] creates the operations after the answer.
     *
     * The holder is claimed before [request] sends anything, so a load with ready operations of the same id fails
     * before its own request. If [request] fails, the holder fails, and the updates in its queue go to
     * [globalSessionUpdateHandler].
     */
    private suspend fun withFactoryInitializingSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
        factory: ClientOperationsFactory,
        request: suspend () -> AcpCreatedSessionResponse,
    ): ClientSession = sessions.withInitializingSession {
        val sessionHolder = sessions.claimFactorySessionHolder(sessionId)
        val sessionResponse = try {
            request()
        } catch (throwable: Throwable) {
            sessions.failSessionHolder(sessionId, sessionHolder, IllegalStateException("Failed to create session $sessionId", throwable))
            throw throwable
        }
        createSession(sessionHolder, sessionId, sessionParameters, sessionResponse, factory)
    }

    /**
     * Opens the session [sessionId] with [request] under [ClientSessionRegistry.withInitializingSession], and delivers its updates to
     * [operations] before the answer.
     *
     * The holder is claimed before [request] sends anything, and one consumer hands its queue to [operations] until
     * the answer. If the opening fails, also in [ClientSessionOperations.notify] or by a cancellation, the holder
     * fails, and the updates still in its queue go to [globalSessionUpdateHandler].
     */
    private suspend fun withEarlyInitializingSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
        operations: ClientSessionOperations,
        request: suspend () -> AcpCreatedSessionResponse,
    ): ClientSession = sessions.withInitializingSession {
        val sessionHolder = sessions.claimOperationsSessionHolder(sessionId)

        try {
            coroutineScope {
                val earlyDelivery = launch {
                    try {
                        sessionHolder.deliverEarly(operations)
                    } catch (ce: CancellationException) {
                        /*
                         * A cancellation that does not come from this call, such as a timeout inside `notify`, would
                         * end this child without failing the scope and leave the rest of the queue undelivered.
                         */
                        ensureActive()
                        throw IllegalStateException("ClientSessionOperations.notify was cancelled", ce)
                    }
                }
                val sessionResponse = request()
                sessionHolder.endEarlyDelivery()
                earlyDelivery.join()
                val session = createClientSession(sessionId, sessionParameters, operations, sessionResponse)
                sessionHolder.completeEarlySession(session)
                session
            }
        } catch (throwable: Throwable) {
            // The cleanup of this sessionId entry is done in finally of withInitializingSession.
            sessions.failSessionHolder(sessionId, sessionHolder, IllegalStateException("Failed to create session $sessionId", throwable))
            throw throwable
        }
    }

    private fun loadSessionRequest(sessionId: SessionId, sessionParameters: SessionCreationParameters): LoadSessionRequest =
        LoadSessionRequest(
            sessionId,
            sessionParameters.cwd,
            sessionParameters.mcpServers,
            sessionParameters.additionalDirectories,
            sessionParameters._meta
        )

    private fun resumeSessionRequest(sessionId: SessionId, sessionParameters: SessionCreationParameters): ResumeSessionRequest =
        ResumeSessionRequest(
            sessionId,
            sessionParameters.cwd,
            sessionParameters.mcpServers,
            sessionParameters.additionalDirectories,
            sessionParameters._meta
        )

    public fun getSession(sessionId: SessionId): ClientSession {
        return sessions.getSession(sessionId)
    }

    @OptIn(UnstableApi::class)
    private fun trackUrlElicitationIfAccepted(mode: ElicitationMode, response: CreateElicitationResponse, sessionId: SessionId) {
        if (mode is ElicitationMode.Url && response.action is ElicitationAction.Accept) {
            _elicitationToSession.put(mode.elicitationId, sessionId)
        }
    }

}

private inline fun <reified TInterface> sessionMethodNotFound(method: AcpMethod): Nothing {
    jsonRpcMethodNotFound("Session object does not implement ${TInterface::class.simpleName} to handle method $method")
}
