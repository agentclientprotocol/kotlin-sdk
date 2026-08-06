package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.rpc.RequestId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Represents an Agent that handles protocol requests and manages sessions.
 *
 * The `Agent` class is responsible for setting up request and notification handlers
 * using the provided `protocol`. It handles session creation, loading, and operations
 * based on client requests. Additionally, it manages client-specific information and
 * ensures proper session lifecycle management.
 *
 * This class serves protocol version 1. Version 2 is a separate
 * [com.agentclientprotocol.agent.v2.Agent]: a connection speaks one version, decided by which of the two
 * was put on it, and the two never share a connection because they claim the same handler names.
 *
 * @property protocol The protocol instance used to set up communication handlers.
 * @property agentSupport An `AgentSupport` instance used for executing core agent operations such as session creation and authentication.
 */
public class Agent(
    public val protocol: Protocol,
    private val agentSupport: AgentSupport,
) {

    internal open class BaseSessionWrapper(
        val agent: Agent,
        val protocol: Protocol
    ) {
        internal suspend fun <T> executeWithSession(block: suspend () -> T): T {
            return withContext(this.asContextElement()) {
                return@withContext block()
            }
        }
    }

    internal class SessionWrapper(
        agent: Agent,
        val agentSession: AgentSession,
        val clientOperations: ClientSessionOperations,
        protocol: Protocol
    ) : BaseSessionWrapper(agent, protocol) {
        private class PromptSession(val currentRequestId: RequestId, val promptJob: Job)
        private val _activePrompt = atomic<PromptSession?>(null)

        suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): PromptResponse {
            val currentRpcRequest = currentCoroutineContext().jsonRpcRequest
            var response: PromptResponse? = null
            return coroutineScope {
                try {
                    val promptJob = launch(start = CoroutineStart.LAZY) {
                        agentSession.prompt(content, _meta).collect { event ->
                            when (event) {
                                is Event.PromptResponseEvent -> {
                                    if (response != null) {
                                        logger.error { "Received repeated prompt response: ${event.response} (previous: $response). The last is used" }
                                    }
                                    response = event.response
                                }

                                is Event.SessionUpdateEvent -> {
                                    clientOperations.notify(event.update, _meta)
                                }
                            }
                        }
                    }

                    val promptSession = PromptSession(currentRpcRequest.id, promptJob)
                    if (!_activePrompt.compareAndSet(null, promptSession)) {
                        error("There is already active prompt execution")
                    }
                    promptJob.join()
                    response ?: PromptResponse(
                        stopReason = if (promptJob.isCancelled) StopReason.CANCELLED else StopReason.END_TURN
                    )
                } finally {
                    _activePrompt.getAndSet(null)
                }
            }
        }

        suspend fun cancel() {
            // notify AgentSession about upcoming cancellation, this way implementations can gracefully stop ongoing requests
            agentSession.cancel()

            val activePrompt = _activePrompt.getAndSet(null)
            if (activePrompt != null) {
                logger.trace { "Cancelling prompt" }
                // we expect that all nested outgoing jobs will be cancelled automatically due to structured concurrency
                // -> prompt task
                //   <- [request] read file
                //   -> [response] read file
                //   <- [request] permissions
                //   |suspended|
                // cancelling the whole prompt should cancel all nested outgoing requests. These requests on CE will propagate cancellation to the other side
                activePrompt.promptJob.cancel()
            }
        }
    }

    internal class NesSessionWrapper @OptIn(UnstableApi::class) constructor(
        agent: Agent,
        val nesSession: NesAgentSession,
        protocol: Protocol
    ) : BaseSessionWrapper(agent, protocol)

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, SessionWrapper>())

    private val _nesSessions = atomic(persistentMapOf<SessionId, NesSessionWrapper>())

    internal fun getClientInfoOrThrow(): ClientInfo {
        if (!_clientInfo.isCompleted) error("Agent is not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return _clientInfo.getCompleted()
    }

    /**
     * The protocol version this connection speaks, as negotiated during `initialize`.
     *
     * A connection settles on exactly one version, so this value never changes once set — a
     * repeated `initialize` does not move it. Held by [protocol], which is what needs it to pick
     * the wire format.
     *
     * @throws IllegalStateException if the client has not sent `initialize` yet
     */
    public val negotiatedProtocolVersion: ProtocolVersion
        get() = protocol.negotiatedProtocolVersionOrNull ?: error("Agent is not initialized yet")

    /**
     * The versions [agentSupport] declares, narrowed to the ones this agent can actually speak.
     *
     * This class serves v1, so any other declared version is dropped with a warning rather than negotiated.
     * Computed once so bogus declarations are reported once rather than on every `initialize`.
     */
    private val effectiveSupportedProtocolVersions: Set<ProtocolVersion> by lazy {
        val declared = agentSupport.supportedProtocolVersions
        val notSpoken = declared - SUPPORTED_PROTOCOL_VERSIONS
        if (notSpoken.isNotEmpty()) {
            logger.warn {
                "AgentSupport declares protocol version(s) $notSpoken that this Agent does not speak; they " +
                    "will not be negotiated"
            }
        }

        declared intersect SUPPORTED_PROTOCOL_VERSIONS
    }

    init {
        setHandlers(protocol)
    }


    /**
     * Serves `initialize`.
     *
     * A request for another version is answered with v1 all the same: the payload is read with v1 types,
     * the only shape this class has ever accepted, and negotiation answers with the latest version it
     * declares
     * ([version negotiation](https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation)).
     * A client that cannot speak the answer closes the connection, or retries with an agent for its own
     * version installed on the same one.
     */
    private suspend fun initialize(params: InitializeRequest): InitializeResponse {
        val clientInfo = ClientInfo(params.protocolVersion, params.clientCapabilities, params.clientInfo, params._meta)
        _clientInfo.complete(clientInfo)

        if (!effectiveSupportedProtocolVersions.contains(LATEST_PROTOCOL_VERSION)) {
            acpFail(
                "Protocol version $LATEST_PROTOCOL_VERSION is not declared in " +
                    "AgentSupport.supportedProtocolVersions=${agentSupport.supportedProtocolVersions}"
            )
        }

        val negotiatedVersion = recordNegotiated(protocol, LATEST_PROTOCOL_VERSION)
        val agentInfo = agentSupport.initialize(clientInfo)
        if (agentInfo.protocolVersion != negotiatedVersion) {
            logger.debug {
                "AgentSupport returned protocol version ${agentInfo.protocolVersion}, but $negotiatedVersion was " +
                    "negotiated with the client (requested ${params.protocolVersion}); the negotiated version is used"
            }
        }
        return InitializeResponse(negotiatedVersion, agentInfo.capabilities, agentInfo.authMethods, agentInfo.implementation, agentInfo._meta)
    }

    /** A repeated `initialize` cannot move the connection: the version recorded first wins. */
    private fun recordNegotiated(protocol: Protocol, negotiated: ProtocolVersion): ProtocolVersion {
        val recorded = protocol.recordNegotiatedProtocolVersion(negotiated)
        if (recorded != negotiated) {
            logger.warn {
                "Repeated initialize request would negotiate $negotiated, but this connection already speaks " +
                    "protocol version $recorded; keeping $recorded"
            }
        }
        return recorded
    }

    @OptIn(UnstableApi::class)
    private fun setHandlers(protocol: Protocol) {
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Initialize) { params: InitializeRequest ->
            return@setRequestHandler initialize(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Authenticate) { params: AuthenticateRequest ->
            return@setRequestHandler agentSupport.authenticate(params.methodId, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Logout) { params: LogoutRequest ->
            return@setRequestHandler agentSupport.logout(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersList) { params: ListProvidersRequest ->
            return@setRequestHandler agentSupport.listProviders(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersSet) { params: SetProvidersRequest ->
            return@setRequestHandler agentSupport.setProvider(params.id, params.apiType, params.baseUrl, params.headers, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersDisable) { params: DisableProvidersRequest ->
            return@setRequestHandler agentSupport.disableProvider(params.id, params._meta)
        }

        protocol.setPaginatedRequestHandler(
            AcpMethod.AgentMethods.V1.SessionList,
            // TODO: move to some global agent/client settings
            batchSize = 10,
            batchedResultFactory = { _, batch, newCursor -> ListSessionsResponse(batch, newCursor) },
            sequenceFactory = { p -> agentSupport.listSessions(p.cwd, p.additionalDirectories, p._meta) }
        )

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionDelete) { params: DeleteSessionRequest ->
            return@setRequestHandler agentSupport.deleteSession(params.sessionId, params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionNew) { params: NewSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.createSession(it) }

            @OptIn(UnstableApi::class)
            return@setRequestHandler NewSessionResponse(
                sessionId = session.sessionId,
                modes = session.asModeState(),
                models = session.asModelState(),
                configOptions = session.asConfigOptionsState()
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionLoad) { params: LoadSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.loadSession(params.sessionId, sessionParameters) }
            @OptIn(UnstableApi::class)
            return@setRequestHandler LoadSessionResponse(
                // maybe unify result of these two methods to have sessionId in both
//                sessionId = session.sessionId,
                modes = session.asModeState(),
                models = session.asModelState(),
                configOptions = session.asConfigOptionsState()
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionResume) { params: ResumeSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestHandler ResumeSessionResponse(
                modes = session.asModeState(),
                models = session.asModelState()
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetMode) { params: SetSessionModeRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setMode(params.modeId, params._meta)
            }
        }
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetModel) { params: SetSessionModelRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setModel(params.modelId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionPrompt) { params: PromptRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.prompt(params.prompt, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.SessionCancel) { params: CancelNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.executeWithSession {
                session.cancel()
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionClose) { params: CloseSessionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            val response = session.executeWithSession {
                session.agentSession.close(params._meta)
            }
            _sessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionFork) { params: ForkSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.forkSession(params.sessionId, sessionParameters) }
            return@setRequestHandler ForkSessionResponse(
                sessionId = session.sessionId,
                modes = session.asModeState(),
                models = session.asModelState(),
                configOptions = session.asConfigOptionsState()
            )
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionResume) { params: ResumeSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestHandler ResumeSessionResponse(
                modes = session.asModeState(),
                models = session.asModelState(),
                configOptions = session.asConfigOptionsState()
            )
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetConfigOption) { params: SetSessionConfigOptionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setConfigOption(params.configId, params.value, params._meta)
            }
        }

        // NES handlers
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesStart) { params: StartNesRequest ->
            val nesSession = agentSupport.createNesSession(params)
            val wrapper = NesSessionWrapper(
                this@Agent,
                nesSession,
                protocol
            )
            _nesSessions.update { it.put(nesSession.nesSessionId, wrapper) }
            return@setRequestHandler StartNesResponse(nesSession.nesSessionId)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesSuggest) { params: SuggestNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            return@setRequestHandler wrapper.executeWithSession {
                wrapper.nesSession.suggest(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesClose) { params: CloseNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            val response = wrapper.executeWithSession {
                wrapper.nesSession.close(params._meta)
            }
            _nesSessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.NesAccept) { params: AcceptNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.accept(params.id, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.NesReject) { params: RejectNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.reject(params.id, params.reason, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidOpen) { params: DidOpenDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didOpen(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidChange) { params: DidChangeDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didChange(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidClose) { params: DidCloseDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didClose(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidSave) { params: DidSaveDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didSave(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidFocus) { params: DidFocusDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didFocus(params)
            }
        }
    }

    private suspend fun createSession(sessionParameters: SessionCreationParameters, sessionFactory: suspend (SessionCreationParameters) -> AgentSession): AgentSession {
        val session = sessionFactory(sessionParameters)
        val clientInfo = getClientInfoOrThrow()

        val sessionWrapper = SessionWrapper(
            this,
            session,
            RemoteClientSessionOperations(protocol, session.sessionId, clientInfo.capabilities),
            protocol
        )
        currentCoroutineContext().executeAfterCurrentRequest { sessionWrapper.executeWithSession { session.postInitialize() } }

        _sessions.update {
            it.put(session.sessionId, sessionWrapper)
        }
        return session
    }

    private fun getSessionOrThrow(sessionId: SessionId): SessionWrapper = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")

    private fun getNesSessionOrThrow(sessionId: SessionId): NesSessionWrapper = _nesSessions.value[sessionId] ?: acpFail("NES session $sessionId not found")
}


internal class SessionWrapperContextElement(val sessionWrapper: Agent.BaseSessionWrapper) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SessionWrapperContextElement>
}

internal fun Agent.BaseSessionWrapper.asContextElement() = SessionWrapperContextElement(this)

public val CoroutineContext.agent: Agent
    get() = this[SessionWrapperContextElement.Key]?.sessionWrapper?.agent ?: error("No agent data found in context")
/**
 * Returns client info associated with the current protocol. Throws an exception if the agent is still not initialized from the client side.
 */
public val CoroutineContext.clientInfo: ClientInfo
    get() = agent.getClientInfoOrThrow()

/**
 * Returns a remote client connected to the counterpart via the current protocol.
 * Only available for chat sessions. NES sessions do not have access to client operations.
 *
 * @throws IllegalStateException if called from a NES session context or outside a session context
 */
public val CoroutineContext.client: ClientSessionOperations
    get() {
        val wrapper = this[SessionWrapperContextElement.Key]?.sessionWrapper
            ?: error("No session found in context")
        return (wrapper as? Agent.SessionWrapper)?.clientOperations
            ?: error("Client operations are not available for NES sessions. Only chat sessions have access to client operations.")
    }
