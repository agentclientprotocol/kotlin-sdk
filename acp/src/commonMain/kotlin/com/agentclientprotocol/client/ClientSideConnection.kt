@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AuthenticateRequest
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.model.KillTerminalCommandRequest
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReleaseTerminalRequest
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SetSessionModeRequest
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.TerminalOutputRequest
import com.agentclientprotocol.model.WaitForTerminalExitRequest
import com.agentclientprotocol.model.WriteTextFileRequest
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler

import com.agentclientprotocol.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.encodeToJsonElement

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
public class ClientSideConnection(
    private val client: Client,
    private val protocol: Protocol,
) : Agent {

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { params: ReadTextFileRequest ->
            client.fsReadTextFile(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { params: WriteTextFileRequest ->
            client.fsWriteTextFile(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            client.sessionRequestPermission(params)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            client.sessionUpdate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { params: CreateTerminalRequest ->
            client.terminalCreate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { params: TerminalOutputRequest ->
            client.terminalOutput(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { params: ReleaseTerminalRequest ->
            client.terminalRelease(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { params: WaitForTerminalExitRequest ->
            client.terminalWaitForExit(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill) { params: KillTerminalCommandRequest ->
            client.terminalKill(params)
        }
    }

    override suspend fun initialize(request: InitializeRequest): InitializeResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.Initialize, request)
    }

    override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.Authenticate, request)
    }

    override suspend fun sessionNew(request: NewSessionRequest): NewSessionResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionNew, request)
    }

    override suspend fun sessionLoad(request: LoadSessionRequest): LoadSessionResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionLoad, request)
    }

    override suspend fun sessionSetMode(request: SetSessionModeRequest): SetSessionModeResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionSetMode, request)
    }

    override suspend fun sessionPrompt(request: PromptRequest): PromptResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionPrompt, request)
    }

    override suspend fun sessionCancel(notification: CancelNotification) {
        protocol.sendNotification(AcpMethod.AgentMethods.SessionCancel, notification)
    }
}