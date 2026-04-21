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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.uuid.ExperimentalUuidApi

private val logger = KotlinLogging.logger {}

/**
 * Represents an Agent that handles protocol requests and manages sessions.
 *
 * The `Agent` class is responsible for setting up request and notification handlers
 * using the provided `protocol`. It handles session creation, loading, and operations
 * based on client requests. Additionally, it manages client-specific information and
 * ensures proper session lifecycle management.
 *
 * @property protocol The protocol instance used to set up communication handlers.
 * @property agentSupport An `AgentSupport` instance used for executing core agent operations such as session creation and authentication.
 */
public class Agent(
    public val protocol: Protocol,
    private val agentSupport: AgentSupport
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
        private class PromptSession(val currentRequestId: RequestId)
        private val _activePrompt = atomic<PromptSession?>(null)

        suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): PromptResponse {
            val currentRpcRequest = currentCoroutineContext().jsonRpcRequest
            if (!_activePrompt.compareAndSet(null, PromptSession(currentRpcRequest.id))) error("There is already active prompt execution")
            try {
                var response: PromptResponse? = null

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

                return response ?: PromptResponse(StopReason.END_TURN)
            }
            catch (ce: CancellationException) {
                logger.trace(ce) { "Prompt job cancelled" }
                return PromptResponse(StopReason.CANCELLED)
            }
            finally {
                _activePrompt.getAndSet(null)
            }
        }

        suspend fun cancel() {
            // TODO do we need it while the cancellation can be handled by coroutine mechanism (catching CE inside `prompt`)
            agentSession.cancel()

            val activePrompt = _activePrompt.getAndSet(null)
            if (activePrompt != null) {
                // we expect that all nested outgoing jobs will be cancelled automatically due to structured concurrency
                // -> prompt task
                //   <- [request] read file
                //   -> [response] read file
                //   <- [request] permissions
                //   |suspended|
                // cancelling the whole prompt should cancel all nested outgoing requests. These requests on CE will propagate cancellation to the other side
                protocol.cancelPendingIncomingRequest(activePrompt.currentRequestId)
            }
        }
    }

    internal class NesSessionWrapper(
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

    init {
        setHandlers(protocol)
    }


    @OptIn(ExperimentalUuidApi::class, UnstableApi::class)
    private fun setHandlers(protocol: Protocol) {
        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize) { params: InitializeRequest ->
            val clientInfo = ClientInfo(params.protocolVersion, params.clientCapabilities, params.clientInfo, params._meta)
            _clientInfo.complete(clientInfo)
            val agentInfo = agentSupport.initialize(clientInfo)
            // see https://agentclientprotocol.com/protocol/initialization#version-negotiation
            val negotiatedVersion = min(params.protocolVersion, agentInfo.protocolVersion)
            return@setRequestHandler InitializeResponse(negotiatedVersion, agentInfo.capabilities, agentInfo.authMethods, agentInfo.implementation, agentInfo._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.Authenticate) { params: AuthenticateRequest ->
            return@setRequestHandler agentSupport.authenticate(params.methodId, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.Logout) { params: LogoutRequest ->
            return@setRequestHandler agentSupport.logout(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.ProvidersList) { params: ListProvidersRequest ->
            return@setRequestHandler agentSupport.listProviders(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.ProvidersSet) { params: SetProvidersRequest ->
            return@setRequestHandler agentSupport.setProvider(params.id, params.apiType, params.baseUrl, params.headers, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.ProvidersDisable) { params: DisableProvidersRequest ->
            return@setRequestHandler agentSupport.disableProvider(params.id, params._meta)
        }

        protocol.setPaginatedRequestHandler(
            AcpMethod.AgentMethods.SessionList,
            // TODO: move to some global agent/client settings
            batchSize = 10,
            batchedResultFactory = { _, batch, newCursor -> ListSessionsResponse(batch, newCursor) },
            sequenceFactory = { p -> agentSupport.listSessions(p.cwd, p.additionalDirectories, p._meta) }
        )

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew) { params: NewSessionRequest ->
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

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad) { params: LoadSessionRequest ->
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

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionResume) { params: ResumeSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestHandler ResumeSessionResponse(
                modes = session.asModeState(),
                models = session.asModelState()
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetMode) { params: SetSessionModeRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setMode(params.modeId, params._meta)
            }
        }
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetModel) { params: SetSessionModelRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setModel(params.modelId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionPrompt) { params: PromptRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.prompt(params.prompt, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel) { params: CancelNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.executeWithSession {
                session.cancel()
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionClose) { params: CloseSessionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            val response = session.executeWithSession {
                session.agentSession.close(params._meta)
            }
            _sessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionFork) { params: ForkSessionRequest ->
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
        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionResume) { params: ResumeSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val session = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestHandler ResumeSessionResponse(
                modes = session.asModeState(),
                models = session.asModelState(),
                configOptions = session.asConfigOptionsState()
            )
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetConfigOption) { params: SetSessionConfigOptionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setConfigOption(params.configId, params.value, params._meta)
            }
        }

        // NES handlers
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.NesStart) { params: StartNesRequest ->
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
        protocol.setRequestHandler(AcpMethod.AgentMethods.NesSuggest) { params: SuggestNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            return@setRequestHandler wrapper.executeWithSession {
                wrapper.nesSession.suggest(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.NesClose) { params: CloseNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            val response = wrapper.executeWithSession {
                wrapper.nesSession.close(params._meta)
            }
            _nesSessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.NesAccept) { params: AcceptNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.accept(params.id, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.NesReject) { params: RejectNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.reject(params.id, params.reason, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.DocumentDidOpen) { params: DidOpenDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didOpen(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.DocumentDidChange) { params: DidChangeDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didChange(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.DocumentDidClose) { params: DidCloseDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didClose(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.DocumentDidSave) { params: DidSaveDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didSave(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.DocumentDidFocus) { params: DidFocusDocumentNotification ->
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

