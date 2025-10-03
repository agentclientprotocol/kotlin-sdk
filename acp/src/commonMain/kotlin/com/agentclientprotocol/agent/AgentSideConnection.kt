@file:Suppress("unused")

package com.agentclientprotocol.agent

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler

import com.agentclientprotocol.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
public class AgentSideConnection(
    private val parentScope: CoroutineScope,
    private val agent: Agent,
    private val transport: Transport,
    options: ProtocolOptions = ProtocolOptions()
) : Client {
    private val protocol = Protocol(parentScope, transport, options)

    public fun start() {

        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize) { params: InitializeRequest ->
            agent.initialize(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.Authenticate) { params: AuthenticateRequest ->
            agent.authenticate(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew) { params: NewSessionRequest ->
            agent.sessionNew(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad) { params: LoadSessionRequest ->
            agent.sessionLoad(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetMode) { params: SetSessionModeRequest ->
            agent.sessionSetMode(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionPrompt) { params: PromptRequest ->
            agent.sessionPrompt(params)
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel) { params: CancelNotification ->
            agent.sessionCancel(params)
        }

        protocol.start()
        logger.info { "Agent-side connection established" }
    }

    override suspend fun sessionUpdate(notification: SessionNotification) {
        protocol.sendNotification(AcpMethod.ClientMethods.SessionUpdate, notification)
    }

    override suspend fun sessionRequestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.SessionRequestPermission, request)
    }

    override suspend fun fsReadTextFile(request: ReadTextFileRequest): ReadTextFileResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.FsReadTextFile, request)
    }

    override suspend fun fsWriteTextFile(request: WriteTextFileRequest): WriteTextFileResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.FsWriteTextFile, request)
    }

    override suspend fun terminalCreate(request: CreateTerminalRequest): CreateTerminalResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalCreate, request)
    }

    override suspend fun terminalOutput(request: TerminalOutputRequest): TerminalOutputResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalOutput, request)
    }

    override suspend fun terminalRelease(request: ReleaseTerminalRequest): ReleaseTerminalResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalRelease, request)
    }

    override suspend fun terminalWaitForExit(request: WaitForTerminalExitRequest): WaitForTerminalExitResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalWaitForExit, request)
    }

    override suspend fun terminalKill(request: KillTerminalCommandRequest): KillTerminalCommandResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalKill, request)
    }
}