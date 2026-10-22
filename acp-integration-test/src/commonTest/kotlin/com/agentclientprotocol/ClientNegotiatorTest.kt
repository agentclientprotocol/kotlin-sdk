@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession as V2AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations as V2ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters as V2SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientNegotiator
import com.agentclientprotocol.client.NegotiatedClient
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.client.V1ClientConfig
import com.agentclientprotocol.client.V2ClientConfig
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.acpFail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame

abstract class ClientNegotiatorTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    private fun v1Config() = V1ClientConfig(
        clientInfo = ClientInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            implementation = Implementation("v1-client", "1.0.0"),
        )
    )

    private fun v2Config() = V2ClientConfig(
        clientInfo = V2ClientInfo(
            protocolVersion = PROTOCOL_VERSION_V2,
            implementation = Implementation("v2-client", "2.0.0"),
        )
    )

    /** Selects v2 when the agent accepts the preferred version. */
    @Test
    fun `selects v2 when the agent supports it`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V2Support()
        V2Agent(agentProtocol, support)
        val negotiator = ClientNegotiator(clientProtocol, v1Config(), v2Config())

        val negotiated = assertIs<NegotiatedClient.V2>(negotiator.negotiate())

        assertEquals(PROTOCOL_VERSION_V2, negotiated.protocolVersion)
        assertEquals("v2-agent", negotiated.agentInfo.implementation.name)
        assertEquals(listOf(PROTOCOL_VERSION_V2), support.initializedVersions)
    }

    /** Selects v1 from the raw response without sending `initialize` again. */
    @Test
    fun `selects v1 without repeating initialize`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V1Support()
        Agent(agentProtocol, support)
        val v1 = v1Config()
        val negotiator = ClientNegotiator(clientProtocol, v1, v2Config())

        val negotiated = assertIs<NegotiatedClient.V1>(negotiator.negotiate())

        assertEquals(LATEST_PROTOCOL_VERSION, negotiated.protocolVersion)
        assertEquals("v1-agent", negotiated.agentInfo.implementation?.name)
        assertSame(v1.clientInfo, negotiated.client.clientInfo)
        assertEquals(listOf(PROTOCOL_VERSION_V2), support.initializedVersions)
    }

    /** Uses v1 directly when no v2 configuration was supplied. */
    @Test
    fun `uses v1 directly when v2 is not configured`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V1Support()
        Agent(agentProtocol, support)
        val negotiator = ClientNegotiator(clientProtocol, v1Config())

        val negotiated = assertIs<NegotiatedClient.V1>(negotiator.negotiate())

        assertEquals(LATEST_PROTOCOL_VERSION, negotiated.protocolVersion)
        assertEquals(listOf(LATEST_PROTOCOL_VERSION), support.initializedVersions)
    }

    /** Performs one handshake for concurrent and repeated negotiation calls. */
    @Test
    fun `negotiates only once`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V2Support()
        V2Agent(agentProtocol, support)
        val negotiator = ClientNegotiator(clientProtocol, v1Config(), v2Config())

        val results = coroutineScope {
            List(5) { async { negotiator.negotiate() } }.awaitAll()
        }
        val repeated = negotiator.negotiate()

        results.forEach { assertSame(results.first(), it) }
        assertSame(results.first(), repeated)
        assertEquals(1, support.initializedVersions.size)
    }

    /** Rejects and reports an offered version unsupported by either configured client. */
    @Test
    fun `rejects an unknown version`() = testWithProtocols { clientProtocol, agentProtocol ->
        agentProtocol.setRequestHandlerRaw(AcpMethod.AgentMethods.V2.Initialize) {
            buildJsonObject { put("protocolVersion", 99) }
        }
        val negotiator = ClientNegotiator(clientProtocol, v1Config(), v2Config())

        val failure = assertIs<UnsupportedProtocolVersionException>(assertFails { negotiator.negotiate() })

        assertEquals(PROTOCOL_VERSION_V2, failure.requestedVersion)
        assertEquals(99, failure.offeredVersion)
        assertEquals(setOf(LATEST_PROTOCOL_VERSION, PROTOCOL_VERSION_V2), failure.supportedVersions)
    }

    /** Does not treat an ordinary initialize failure as a reason to try v1. */
    @Test
    fun `does not fall back after an initialize error`() = testWithProtocols { clientProtocol, agentProtocol ->
        var initializeCalls = 0
        agentProtocol.setRequestHandlerRaw(AcpMethod.AgentMethods.V2.Initialize) {
            initializeCalls += 1
            acpFail("initialize failed")
        }
        val negotiator = ClientNegotiator(clientProtocol, v1Config(), v2Config())

        val first = assertFails { negotiator.negotiate() }
        val repeated = assertFails { negotiator.negotiate() }

        assertSame(first, repeated)
        assertEquals(1, initializeCalls)
    }

    private class V1Support : AgentSupport {
        val initializedVersions = mutableListOf<Int>()

        override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
            initializedVersions += clientInfo.protocolVersion
            return AgentInfo(implementation = Implementation("v1-agent", "1.0.0"))
        }

        override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
            error("sessions are not used in negotiation tests")

        override suspend fun loadSession(
            sessionId: SessionId,
            sessionParameters: SessionCreationParameters,
        ): AgentSession = error("sessions are not used in negotiation tests")
    }

    private class V2Support : V2AgentSupport {
        val initializedVersions = mutableListOf<Int>()

        override suspend fun initialize(clientInfo: V2ClientInfo): V2AgentInfo {
            initializedVersions += clientInfo.protocolVersion
            return V2AgentInfo(Implementation("v2-agent", "2.0.0"))
        }

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = error("sessions are not used in negotiation tests")
    }
}
