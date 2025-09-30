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
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.decodeFromJsonElement
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
    parentScope: CoroutineScope,
    private val transport: Transport,
    private val client: Client,
    options: ProtocolOptions = ProtocolOptions()
) : Agent {
    private val protocol = Protocol(parentScope, transport, options)

    public fun start() {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { request ->
            val params = ACPJson.decodeFromString<ReadTextFileRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.fsReadTextFile(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { request ->
            val params = ACPJson.decodeFromString<WriteTextFileRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.fsWriteTextFile(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { request ->
            val params = ACPJson.decodeFromString<RequestPermissionRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.sessionRequestPermission(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { notification ->
            val params = ACPJson.decodeFromString<SessionNotification>(
                notification.params?.toString() ?: "{}"
            )
            client.sessionUpdate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { request ->
            val params = ACPJson.decodeFromString<CreateTerminalRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.terminalCreate(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { request ->
            val params = ACPJson.decodeFromString<TerminalOutputRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.terminalOutput(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { request ->
            val params = ACPJson.decodeFromString<ReleaseTerminalRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.terminalRelease(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { request ->
            val params = ACPJson.decodeFromString<WaitForTerminalExitRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.terminalWaitForExit(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill) { request ->
            val params = ACPJson.decodeFromString<KillTerminalCommandRequest>(
                request.params?.toString() ?: "{}"
            )
            val response = client.terminalKill(params)
            ACPJson.encodeToJsonElement(response)
        }

        protocol.start()
        logger.info { "Client-side connection established" }
    }

    override suspend fun initialize(request: InitializeRequest): InitializeResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.Initialize, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.Authenticate, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun sessionNew(request: NewSessionRequest): NewSessionResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.SessionNew, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun sessionLoad(request: LoadSessionRequest): LoadSessionResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.SessionLoad, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun sessionSetMode(request: SetSessionModeRequest): SetSessionModeResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.SessionSetMode, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun sessionPrompt(request: PromptRequest): PromptResponse {
        val params = ACPJson.encodeToJsonElement(request)
        val responseJson = protocol.sendRequest(AcpMethod.AgentMethods.SessionPrompt, params)
        return ACPJson.decodeFromJsonElement(responseJson)
    }

    override suspend fun sessionCancel(notification: CancelNotification) {
        val params = ACPJson.encodeToJsonElement(notification)
        protocol.sendNotification(AcpMethod.AgentMethods.SessionCancel, params)
    }
}