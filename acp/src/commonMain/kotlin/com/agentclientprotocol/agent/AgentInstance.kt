@file:Suppress("unused")

package com.agentclientprotocol.agent

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.RemoteClient
import com.agentclientprotocol.client.asContextElement
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import io.github.oshai.kotlinlogging.KotlinLogging

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
public class AgentInstance(
    private val protocol: Protocol,
    private val agent: Agent,
    private val remoteClient: Client = RemoteClient(protocol)
) {
    init {
        // Set up request handlers for incoming client requests
        protocol.setRequestHandler(AcpMethod.AgentMethods.Initialize, coroutineContext = remoteClient.asContextElement()) { params: InitializeRequest ->
            agent.initialize(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.Authenticate, coroutineContext = remoteClient.asContextElement()) { params: AuthenticateRequest ->
            agent.authenticate(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionNew, coroutineContext = remoteClient.asContextElement()) { params: NewSessionRequest ->
            agent.sessionNew(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionLoad, coroutineContext = remoteClient.asContextElement()) { params: LoadSessionRequest ->
            agent.sessionLoad(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionSetMode, coroutineContext = remoteClient.asContextElement()) { params: SetSessionModeRequest ->
            agent.sessionSetMode(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.SessionPrompt, coroutineContext = remoteClient.asContextElement()) { params: PromptRequest ->
            agent.sessionPrompt(params)
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.SessionCancel, coroutineContext = remoteClient.asContextElement()) { params: CancelNotification ->
            agent.sessionCancel(params)
        }
    }
}