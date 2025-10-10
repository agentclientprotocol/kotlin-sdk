@file:Suppress("unused")

package com.agentclientprotocol.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Session
import com.agentclientprotocol.common.SessionParameters
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
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * An agent-side connection to a client.
 *
 * This class provides the agent's view of an ACP connection, allowing
 * agents to communicate with clients. It implements the {@link Client} interface
 * to provide methods for requesting permissions, accessing the file system,
 * and sending session updates.
 *
 * See protocol docs: [Agent](https://agentclientprotocol.com/protocol/overview#agent)
 */
public abstract class AgentInstance(
    public val protocol: Protocol,
    public val agentInfo: AgentInfo
) {
    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, Session>())

    init {
        setHandlers(protocol)
    }

    private fun setHandlers(protocol: Protocol) {
        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize) { params: InitializeRequest ->
            _clientInfo.complete(ClientInfo(params.protocolVersion, params.clientCapabilities))
            val initializeResponse = initializeImpl(ClientInfo(params.protocolVersion, params.clientCapabilities))
            return@setRequestHandler initializeResponse
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.Authenticate) { params: AuthenticateRequest ->
            return@setRequestHandler authenticateImpl(params.methodId, params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew) { params: NewSessionRequest ->
            val session = createSession(SessionParameters(params.cwd, params.mcpServers, params._meta))
            return@setRequestHandler NewSessionResponse(
                sessionId = session.sessionId,
                modes = session.currentMode.value?.let { SessionModeState(it, session.availableModes) },
                models = session.currentModel.value?.let { SessionModelState(it, session.availableModels) }
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad) { params: LoadSessionRequest ->
            val session = loadSession(params.sessionId, SessionParameters(params.cwd, params.mcpServers, params._meta))
            return@setRequestHandler LoadSessionResponse(
                // maybe unify result of these two methods to have sessionId in both
//                sessionId = session.sessionId,
                modes = session.currentMode.value?.let { SessionModeState(it, session.availableModes) },
                models = session.currentModel.value?.let { SessionModelState(it, session.availableModels) }
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetMode) { params: SetSessionModeRequest ->
            val session = getSessionOrThrow(params.sessionId)
            session.changeMode(params.modeId)
            return@setRequestHandler SetSessionModeResponse()
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionPrompt) { params: PromptRequest ->
            val session = getSession(params.sessionId) ?: acpFail("Session ${params.sessionId} not found")
            return@setRequestHandler session.prompt(params.prompt, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel) { params: CancelNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.cancel()
        }
    }

    protected open fun authenticateImpl(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse {
        return AuthenticateResponse()
    }

    protected open suspend fun initializeImpl(clientInfo: ClientInfo): InitializeResponse {
        return InitializeResponse(agentInfo.protocolVersion, agentInfo.capabilities, agentInfo.authMethods)
    }

    private suspend fun createSession(sessionParameters: SessionParameters): Session {
        val session = createSessionImpl(sessionParameters)
        _sessions.update { it.put(session.sessionId, session) }
        // TODO listen changes in session state
        return session
    }

    protected abstract suspend fun createSessionImpl(sessionParameters: SessionParameters): Session

    private suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): Session {
        val session = loadSessionImpl(sessionId, sessionParameters)
        _sessions.update { it.put(session.sessionId, session) }
        return session
    }

    protected abstract suspend fun loadSessionImpl(sessionId: SessionId, sessionParameters: SessionParameters): Session

    private fun getSessionOrThrow(sessionId: SessionId): Session = getSession(sessionId) ?: acpFail("Session $sessionId not found")

    public fun getSession(sessionId: SessionId): Session? = _sessions.value[sessionId]
}