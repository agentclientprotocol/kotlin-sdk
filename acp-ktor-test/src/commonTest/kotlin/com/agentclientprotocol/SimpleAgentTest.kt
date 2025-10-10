package com.agentclientprotocol

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.framework.ClientAgentTest
import com.agentclientprotocol.framework.TestAgent
import com.agentclientprotocol.framework.TestClient
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class SimpleAgentTest : ClientAgentTest() {
    @Test
    fun `initialize via client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = TestClient(clientProtocol, ClientInfo())
        val agent = TestAgent(agentProtocol, AgentInfo())
        val agentInfo = client.initialize()
        assertEquals(agent.agentInfo.protocolVersion, agentInfo.protocolVersion)
    }
}