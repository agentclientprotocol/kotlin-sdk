package com.agentclientprotocol.agent

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

    internal class SessionWrapper(
        val agent: Agent,
        val agentSession: AgentSession,
        val clientOperations: ClientSessionOperations,
        val protocol: Protocol
    ) {
        private class PromptSession(val currentRequestId: RequestId)
        private val _activePrompt = atomic<PromptSession?>(null)

        internal suspend fun <T> executeWithSession(block: suspend () -> T): T {
            return withContext(this.asContextElement()) {
                return@withContext block()
            }
        }

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

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, SessionWrapper>())

    internal fun getClientInfoOrThrow(): ClientInfo {
        if (!_clientInfo.isCompleted) error("Agent is not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return _clientInfo.getCompleted()
    }

    init {
        setHandlers(protocol)
    }

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

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew) { params: NewSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params._meta)
            val session = createSession(sessionParameters) { agentSupport.createSession(it) }

            return@setRequestHandler NewSessionResponse(
                sessionId = session.sessionId,
                modes = session.asModeState(),
                models = session.asModelState()
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad) { params: LoadSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params._meta)
            val session = createSession(sessionParameters) { agentSupport.loadSession(params.sessionId, sessionParameters) }
            return@setRequestHandler LoadSessionResponse(
                // maybe unify result of these two methods to have sessionId in both
//                sessionId = session.sessionId,
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

        _sessions.update {
            it.put(session.sessionId, sessionWrapper)
        }
        return session
    }

    private fun getSessionOrThrow(sessionId: SessionId): SessionWrapper = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")
}


internal class SessionWrapperContextElement(val sessionWrapper: Agent.SessionWrapper) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SessionWrapperContextElement>
}

internal fun Agent.SessionWrapper.asContextElement() = SessionWrapperContextElement(this)

public val CoroutineContext.agent: Agent
    get() = this[SessionWrapperContextElement.Key]?.sessionWrapper?.agent ?: error("No agent data found in context")
/**
 * Returns client info associated with the current protocol. Throws an exception if the agent is still not initialized from the client side.
 */
public val CoroutineContext.clientInfo: ClientInfo
    get() = agent.getClientInfoOrThrow()

/**
 * Returns a remote client connected to the counterpart via the current protocol
 */
public val CoroutineContext.client: ClientSessionOperations
    get() = this[SessionWrapperContextElement.Key]?.sessionWrapper?.clientOperations ?: error("No remote client found in context")

