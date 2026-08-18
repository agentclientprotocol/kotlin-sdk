@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ElicitationOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.util.PaginatedResponseToFlowAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
    public val globalElicitationHandler: GlobalElicitationHandler? = null
) {
    private class ClientSessionHolder {
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

        suspend fun handleOrQueue(notification: SessionUpdate, _meta: JsonElement?) {
            val sendResult = notifications.trySend(Pair(notification, _meta))

            // means that `close` was called in drain
            if (!sendResult.isSuccess) {
                // probably it will suspend for the period of loop with `handleNotification` above
                val session = this@ClientSessionHolder.session.await()
                session.executeWithSession {
                    session.handleNotification(notification, _meta)
                }
            }
        }
    }

    private data class SessionsStorage(val initializingSessionsCount: Int = 0, val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf())

    private val _sessions = atomic(SessionsStorage())
    private val _nesSessions = atomic(persistentMapOf<SessionId, ClientNesSessionImpl>())
    @OptIn(UnstableApi::class)
    private val _elicitationToSession = ElicitationSessionStore()

    /**
     * Looks up the holder for [sessionId], creating a new entry only if there are some currently initializing
     * sessions. Returns `null` if the session is neither registered nor being initialized, instead of throwing -
     * callers that need a session to exist (e.g. to service a request against it) should use
     * [getOrCreateSessionHolder] instead.
     */
    private fun findSessionHolder(sessionId: SessionId): ClientSessionHolder? {
        // Fast path for the common case of an already registered session.
        _sessions.value.sessions[sessionId]?.let { return it }
        var clientSessionHolder: ClientSessionHolder? = null
        // Every branch below has to look the session up in `currentStorage` rather than rely on the read above:
        // `_sessions` can change between that read and the CAS, and it can change again between two attempts of
        // the CAS loop. A session that a concurrent newSession/loadSession registers in such a window - together
        // with decrementing `initializingSessionsCount` back to zero - would otherwise be reported as missing.
        _sessions.update { currentStorage ->
            val existingHolder = currentStorage.sessions[sessionId]
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
        findSessionHolder(sessionId) ?: acpFail("Session $sessionId not found")

    internal fun removeSessionHolder(sessionId: SessionId) {
        _sessions.update { currentMap ->
            currentMap.copy(sessions = currentMap.sessions.remove(sessionId))
        }
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
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.handlePermissionResponse(params.toolCall, params.options, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.FsReadTextFile) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val fs = session.operations as? FileSystemOperations
                ?: sessionMethodNotFound<FileSystemOperations>(AcpMethod.ClientMethods.V1.FsReadTextFile)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession fs.fsReadTextFile(params.path, params.line, params.limit, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.FsWriteTextFile) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val fs = session.operations as? FileSystemOperations
                ?: sessionMethodNotFound<FileSystemOperations>(AcpMethod.ClientMethods.V1.FsWriteTextFile)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession fs.fsWriteTextFile(params.path, params.content, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.TerminalCreate) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.V1.TerminalCreate)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalCreate(params.command, params.args, params.cwd, params.env, params.outputByteLimit, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.TerminalKill) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.V1.TerminalKill)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalKill(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.TerminalOutput) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.V1.TerminalOutput)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalOutput(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.TerminalRelease) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.V1.TerminalRelease)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalRelease(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.TerminalWaitForExit) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.V1.TerminalWaitForExit)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalWaitForExit(params.terminalId, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.V1.SessionUpdate) { params: SessionNotification ->
            // The agent may report an update (e.g. a status change on `session/list`) for a session this client
            // never called `session/new` / `session/load` / `session/resume` for - it can live on the server,
            // created from another IDE window, the web, or another machine. That's not a protocol violation, so
            // unlike other session-scoped methods, an unknown/unconnected session here must not fail the call.
            val sessionHolder = findSessionHolder(params.sessionId)
            if (sessionHolder == null) {
                logger.debug { "Ignoring session/update for session ${params.sessionId}: client is not connected to it" }
                return@setNotificationHandler
            }
            sessionHolder.handleOrQueue(params.update, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.ClientMethods.V1.ElicitationCreate) { params: CreateElicitationRequest ->
            when (val scope = params.scope) {
                is ElicitationScope.Session -> {
                    val session = getSessionOrThrow(scope.sessionId)
                    val ops = session.operations as? ElicitationOperations
                        ?: sessionMethodNotFound<ElicitationOperations>(AcpMethod.ClientMethods.V1.ElicitationCreate)
                    val response = session.executeWithSession { ops.createElicitation(params) }
                    // Only track URL-mode elicitation → session after a successful Accept response,
                    // since elicitation/complete only follows an accepted URL elicitation
                    trackUrlElicitationIfAccepted(params.mode, response, scope.sessionId)
                    response
                }
                is ElicitationScope.Request -> {
                    val sessionId = protocol.getOutgoingRequestSessionId(scope.requestId)
                    if (sessionId != null) {
                        val session = getSessionOrThrow(sessionId)
                        val ops = session.operations as? ElicitationOperations
                            ?: sessionMethodNotFound<ElicitationOperations>(AcpMethod.ClientMethods.V1.ElicitationCreate)
                        val response = session.executeWithSession { ops.createElicitation(params) }
                        trackUrlElicitationIfAccepted(params.mode, response, sessionId)
                        response
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
            var sessionId: SessionId? = _elicitationToSession.remove(params.elicitationId)

            if (sessionId != null) {
                // Session may have been closed between the elicitation and the completion;
                // per the RFD, ignore unknown/already-completed IDs gracefully


                val holder = _sessions.value.sessions[sessionId]
                if (holder != null && holder.session.isCompleted) {
                    val session = runCatching { getSessionOrThrow(sessionId) }.getOrNull()
                    if (session != null) {
                        val ops = session.operations as? ElicitationOperations
                        if (ops != null) {
                            session.executeWithSession { ops.completeElicitation(params) }
                        }
                    }
                }
            }
            // else: unknown or already-completed elicitation ID — silently ignore per RFD
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
        return withInitializingSession {
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
            return@withInitializingSession createSession(sessionId, sessionParameters, newSessionResponse, operationsFactory)
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
     */
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withInitializingSession {
            val loadSessionResponse = AcpMethod.AgentMethods.V1.SessionLoad(
                protocol,
                LoadSessionRequest(
                    sessionId,
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters.additionalDirectories,
                    sessionParameters._meta
                )
            )
            return@withInitializingSession createSession(sessionId, sessionParameters, loadSessionResponse, operationsFactory)
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
        return withInitializingSession {
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
            return@withInitializingSession createSession(newSessionId, sessionParameters, forkSessionResponse, operationsFactory)
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
     */
    @UnstableApi
    public suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withInitializingSession {
            val resumeSessionResponse = AcpMethod.AgentMethods.V1.SessionResume(
                protocol,
                ResumeSessionRequest(
                    sessionId,
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters.additionalDirectories,
                    sessionParameters._meta
                )
            )
            return@withInitializingSession createSession(sessionId, sessionParameters, resumeSessionResponse, operationsFactory)
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
    private suspend fun createSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, sessionResponse: AcpCreatedSessionResponse, factory: ClientOperationsFactory): ClientSession {
        // doesn't throw if executing under `withInitializingSession` because creates a new entry
        val sessionHolder = getOrCreateSessionHolder(sessionId)
        return runCatching {
            val operations = factory.createClientOperations(sessionId, sessionResponse)
            val session = ClientSessionImpl(this, sessionId, sessionParameters, operations, sessionResponse, protocol)
            sessionHolder.drainEventsAndCompleteSession(session)
            session
        }.getOrElse { throwable ->
            // throw IllegalStateException to pass it as INTERNAL_ERROR to the other side (see in Protocol)
            sessionHolder.completeExceptionally(IllegalStateException("Failed to create session $sessionId", throwable))
            // cleanup of this sessionId entry will be done in finally of withInitializingSession
            throw throwable
        }
    }

    public fun getSession(sessionId: SessionId): ClientSession {
        val sessionHolder = _sessions.value.sessions[sessionId] ?: error("Session $sessionId not found")
        if (!sessionHolder.session.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return sessionHolder.session.getCompleted()
    }

    private suspend fun getSessionOrThrow(sessionId: SessionId): ClientSessionImpl {
        return getOrCreateSessionHolder(sessionId).session.await()
    }

    @OptIn(UnstableApi::class)
    private fun trackUrlElicitationIfAccepted(mode: ElicitationMode, response: CreateElicitationResponse, sessionId: SessionId) {
        if (mode is ElicitationMode.Url && response.action is ElicitationAction.Accept) {
            _elicitationToSession.put(mode.elicitationId, sessionId)
        }
    }

    private suspend fun<T> withInitializingSession(block: suspend () -> T): T {
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
                    // this means that currently no sessions can be in initializing state during to ongoing load/new/fork/resume calls
                    // so if on exit from these methods we observe any entries with not completed or failed state we assume that someone sent us events with non-existent session ids
                    // and we have to remove them and report errors
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
                    // report it as non existent session
                    holder.completeExceptionally(AcpExpectedError("Session $id not found"))
                }
            }
        }
    }
}

private inline fun <reified TInterface> sessionMethodNotFound(method: AcpMethod): Nothing {
    jsonRpcMethodNotFound("Session object does not implement ${TInterface::class.simpleName} to handle method $method")
}
