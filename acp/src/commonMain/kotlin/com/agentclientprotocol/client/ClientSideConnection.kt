@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.RemoteAgent
import com.agentclientprotocol.agent.asContextElement
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import io.github.oshai.kotlinlogging.KotlinLogging

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
) {
    private val remoteAgent = RemoteAgent(protocol)

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile, coroutineContext = remoteAgent.asContextElement()) { params: ReadTextFileRequest ->
            client.fsReadTextFile(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile, coroutineContext = remoteAgent.asContextElement()) { params: WriteTextFileRequest ->
            client.fsWriteTextFile(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission, coroutineContext = remoteAgent.asContextElement()) { params: RequestPermissionRequest ->
            client.sessionRequestPermission(params)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate, coroutineContext = remoteAgent.asContextElement()) { params: SessionNotification ->
            client.sessionUpdate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate, coroutineContext = remoteAgent.asContextElement()) { params: CreateTerminalRequest ->
            client.terminalCreate(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput, coroutineContext = remoteAgent.asContextElement()) { params: TerminalOutputRequest ->
            client.terminalOutput(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease, coroutineContext = remoteAgent.asContextElement()) { params: ReleaseTerminalRequest ->
            client.terminalRelease(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit, coroutineContext = remoteAgent.asContextElement()) { params: WaitForTerminalExitRequest ->
            client.terminalWaitForExit(params)
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill, coroutineContext = remoteAgent.asContextElement()) { params: KillTerminalCommandRequest ->
            client.terminalKill(params)
        }
    }
}