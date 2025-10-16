package com.agentclientprotocol.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.common.asContextElement
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

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
    private class SessionHolder(val agentSession: AgentSession, val clientApi: ClientSessionOperations)

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, SessionHolder>())

    init {
        setHandlers(protocol)
    }

    private fun setHandlers(protocol: Protocol) {
        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize) { params: InitializeRequest ->
            val clientInfo = ClientInfo(params.protocolVersion, params.clientCapabilities, params._meta)
            _clientInfo.complete(clientInfo)
            val agentInfo = agentSupport.initialize(clientInfo)
            return@setRequestHandler InitializeResponse(agentInfo.protocolVersion, agentInfo.capabilities, agentInfo.authMethods, agentInfo._meta)
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
            return@setRequestHandler withContext(session.clientApi.asContextElement()) {
                var response: PromptResponse? = null
                session.agentSession.prompt(params.prompt, params._meta).collect { event ->
                    when (event) {
                        is Event.PromptResponseEvent -> {
                            if (response != null) {
                                logger.error { "Received repeated prompt response: ${event.response} (previous: $response). The last is used" }
                            }
                            response = event.response
                        }

                        is Event.SessionUpdateEvent -> {
                            session.clientApi.notify(event.update, params._meta)
                        }
                    }
                }
                return@withContext response ?: PromptResponse(StopReason.END_TURN)
            }
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel) { params: CancelNotification ->
            val session = getSessionOrThrow(params.sessionId)
            withContext(session.clientApi.asContextElement()) {
                session.agentSession.cancel()
            }
        }
    }

    private suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
        val session = agentSupport.createSession(sessionParameters)
        _sessions.update { it.put(session.sessionId, SessionHolder(session, RemoteClientSessionOperations(protocol, session.sessionId))) }
        return session
    }

    private suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): AgentSession {
        val session = agentSupport.loadSession(sessionId, sessionParameters)
        _sessions.update { it.put(session.sessionId, SessionHolder(session, RemoteClientSessionOperations(protocol, session.sessionId))) }
        return session
    }

    private fun getSessionOrThrow(sessionId: SessionId): SessionHolder = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")
}