package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSupport
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class SimpleAgentTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    @Test
    fun `initialize via client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentInitialized = CompletableDeferred<ClientInfo>()
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSessionApi(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        TODO("Not yet implemented")
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                agentInitialized.complete(clientInfo)
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val testVersion = 10
        val clientInfo = ClientInfo(protocolVersion = testVersion)
        val agentInfo = client.initialize(clientInfo)
        assertEquals(testVersion, agentInfo.protocolVersion)
    }
}