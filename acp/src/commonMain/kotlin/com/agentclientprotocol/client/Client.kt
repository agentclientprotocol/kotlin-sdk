@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
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
public class Client(
    public val protocol: Protocol,
    private val clientSupport: ClientSupport
) {
    private val _sessions = atomic(persistentMapOf<SessionId, ClientSessionImpl>())

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.handlePermissionResponse(params.toolCall, params.options, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.handleNotification(params.update, params._meta)
        }

        // should be handled by extensions
//        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile, coroutineContext = remoteAgent.asContextElement()) { params: ReadTextFileRequest ->
//            client.fsReadTextFile(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile, coroutineContext = remoteAgent.asContextElement()) { params: WriteTextFileRequest ->
//            client.fsWriteTextFile(params)
//        }
//
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate, coroutineContext = remoteAgent.asContextElement()) { params: CreateTerminalRequest ->
//            client.terminalCreate(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput, coroutineContext = remoteAgent.asContextElement()) { params: TerminalOutputRequest ->
//            client.terminalOutput(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease, coroutineContext = remoteAgent.asContextElement()) { params: ReleaseTerminalRequest ->
//            client.terminalRelease(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit, coroutineContext = remoteAgent.asContextElement()) { params: WaitForTerminalExitRequest ->
//            client.terminalWaitForExit(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill, coroutineContext = remoteAgent.asContextElement()) { params: KillTerminalCommandRequest ->
//            client.terminalKill(params)
//        }
    }

    public suspend fun initialize(clientInfo: ClientInfo, _meta: JsonElement? = null): AgentInfo {
        val initializeResponse = AcpMethod.AgentMethods.Initialize(protocol, InitializeRequest(clientInfo.protocolVersion, clientInfo.capabilities, _meta))
        val agentInfo = AgentInfo(initializeResponse.protocolVersion, initializeResponse.agentCapabilities, initializeResponse.authMethods, initializeResponse._meta)
        return agentInfo
    }

    public suspend fun newSession(sessionParameters: SessionParameters): ClientSession {
        val newSessionResponse = AcpMethod.AgentMethods.SessionNew(protocol,
            NewSessionRequest(
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            )
        )

        val session = createSessionImpl(newSessionResponse.sessionId, sessionParameters, newSessionResponse.modes, newSessionResponse.models)
        val sessionApi = clientSupport.createClientSession(session, newSessionResponse._meta)
        session.setApi(sessionApi)
        _sessions.update { it.put(newSessionResponse.sessionId, session) }
        return session
    }

    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): ClientSession {
        val loadSessionResponse = AcpMethod.AgentMethods.SessionLoad(protocol,
            LoadSessionRequest(
                sessionId,
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            ))
        val session = createSessionImpl(sessionId, sessionParameters, loadSessionResponse.modes, loadSessionResponse.models)

        val sessionApi = clientSupport.createClientSession(session, loadSessionResponse._meta)
        session.setApi(sessionApi)
        _sessions.update { it.put(sessionId, session) }
        return session
    }

    private fun createSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?
    ): ClientSessionImpl {
        return ClientSessionImpl(sessionId, sessionParameters, protocol/*, modeState, modelState*/)
    }

    public fun getSession(sessionId: SessionId): ClientSession? = _sessions.value[sessionId]

    private fun getSessionOrThrow(sessionId: SessionId): ClientSessionImpl = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")
}