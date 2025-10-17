package com.agentclientprotocol.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.common.asContextElement
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.jsonRpcRequest
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.RequestId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
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
    private class SessionWrapper(val agentSession: AgentSession, val clientOperations: ClientSessionOperations, val protocol: Protocol) {
        private class PromptSession(val currentRequestId: RequestId)
        private val _activePrompt = atomic<PromptSession?>(null)

        suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): PromptResponse {
            val currentRpcRequest = currentCoroutineContext().jsonRpcRequest
            if (!_activePrompt.compareAndSet(null, PromptSession(currentRpcRequest.id))) error("There is already active prompt execution")
            try {
                var response: PromptResponse? = null
                withContext(clientOperations.asContextElement()) {
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
            withContext(clientOperations.asContextElement()) {
                // TODO do we need it while the cancellation can be handled by coroutine mechanism (catching CE inside `prompt`)
                agentSession.cancel()
            }
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

    init {
        setHandlers(protocol)
    }

    private fun setHandlers(protocol: Protocol) {
        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize) { params: InitializeRequest ->
            val clientInfo = ClientInfo(params.protocolVersion, params.clientCapabilities, params._meta)
            _clientInfo.complete(clientInfo)
            val agentInfo = agentSupport.initialize(clientInfo)
            // see https://agentclientprotocol.com/protocol/initialization#version-negotiation
            val negotiatedVersion = min(params.protocolVersion, agentInfo.protocolVersion)
            return@setRequestHandler InitializeResponse(negotiatedVersion, agentInfo.capabilities, agentInfo.authMethods, agentInfo._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.Authenticate) { params: AuthenticateRequest ->
            return@setRequestHandler agentSupport.authenticate(params.methodId, params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew) { params: NewSessionRequest ->
            val session = createSession(SessionParameters(params.cwd, params.mcpServers, params._meta))

            return@setRequestHandler NewSessionResponse(
                sessionId = session.sessionId,
                modes = null,//session.currentMode.value?.let { SessionModeState(it, session.availableModes) },
                models = null//session.currentModel.value?.let { SessionModelState(it, session.availableModels) }
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad) { params: LoadSessionRequest ->
            val session = loadSession(params.sessionId, SessionParameters(params.cwd, params.mcpServers, params._meta))
            return@setRequestHandler LoadSessionResponse(
                // maybe unify result of these two methods to have sessionId in both
//                sessionId = session.sessionId,
                modes = null,//session.currentMode.value?.let { SessionModeState(it, session.availableModes) },
                models = null//session.currentModel.value?.let { SessionModelState(it, session.availableModels) }
            )
        }
        // TODO: to extensions
//
//        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetMode) { params: SetSessionModeRequest ->
//            val session = getSessionOrThrow(params.sessionId)
//            session.changeMode(params.modeId)
//            return@setRequestHandler SetSessionModeResponse()
//        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionPrompt) { params: PromptRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.prompt(params.prompt, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel) { params: CancelNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.cancel()
        }
    }

    private suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
        val session = agentSupport.createSession(sessionParameters)
        _sessions.update { it.put(session.sessionId, SessionWrapper(session, RemoteClientSessionOperations(protocol, session.sessionId), protocol)) }
        return session
    }

    private suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): AgentSession {
        val session = agentSupport.loadSession(sessionId, sessionParameters)
        _sessions.update { it.put(session.sessionId, SessionWrapper(session, RemoteClientSessionOperations(protocol, session.sessionId), protocol)) }
        return session
    }

    private fun getSessionOrThrow(sessionId: SessionId): SessionWrapper = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")
}