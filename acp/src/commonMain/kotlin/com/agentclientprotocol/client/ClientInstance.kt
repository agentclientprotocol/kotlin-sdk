@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.Session
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

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
public abstract class ClientInstance(
    public val protocol: Protocol,
    private val clientInfo: ClientInfo,
) {
    private val _serverInfo = CompletableDeferred<AgentInfo>()

    private val _sessions = atomic(persistentMapOf<SessionId, Session>())

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.requestPermissions(params.toolCall, params.options, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.update(params.update, params._meta)
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

    public suspend fun initialize(_meta: JsonElement? = null): AgentInfo {
        val initializeResponse = AcpMethod.AgentMethods.Initialize(protocol, InitializeRequest(clientInfo.protocolVersion, clientInfo.capabilities, _meta))
        val agentInfo = AgentInfo(initializeResponse.protocolVersion, initializeResponse.agentCapabilities)
        _serverInfo.complete(agentInfo)
        return agentInfo
    }

    public suspend fun newSession(sessionParameters: SessionParameters): Session {
        val newSessionResponse = AcpMethod.AgentMethods.SessionNew(protocol,
            NewSessionRequest(
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            )
        )

        val session = createSessionImpl(newSessionResponse.sessionId, sessionParameters, newSessionResponse.modes, newSessionResponse.models)
        _sessions.update { it.put(newSessionResponse.sessionId, session) }
        return session
    }

    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): Session {
        val loadSessionResponse = AcpMethod.AgentMethods.SessionLoad(protocol,
            LoadSessionRequest(
                sessionId,
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            ))
        // TODO pass
        val session = loadSessionImpl(sessionId, sessionParameters, loadSessionResponse.modes, loadSessionResponse.models)
        _sessions.update { it.put(sessionId, session) }
        return session
    }

    protected abstract suspend fun createSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?
    ): ClientSessionBase

    protected abstract suspend fun loadSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?
    ): ClientSessionBase

    public fun getSession(sessionId: SessionId): Session? = _sessions.value[sessionId]

    private fun getSessionOrThrow(sessionId: SessionId): Session = getSession(sessionId) ?: acpFail("Session $sessionId not found")

}

