package com.agentclientprotocol

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.Session
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.framework.TestAgent
import com.agentclientprotocol.framework.TestAgentSession
import com.agentclientprotocol.framework.TestClient
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class SimpleAgentTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    @Test
    fun `initialize via client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = TestClient(clientProtocol, ClientInfo())
        val agent = TestAgent(agentProtocol, AgentInfo())
        val agentInfo = client.initialize()
        assertEquals(agent.agentInfo.protocolVersion, agentInfo.protocolVersion)
    }
}