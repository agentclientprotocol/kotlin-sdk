@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.AgentAuthCapabilities
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthStatusResponse
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.v2.SessionCapabilities
import com.agentclientprotocol.model.v2.StatusAuthResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.client.v2.Client as V2Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.model.v2.AgentAuthCapabilities as V2AgentAuthCapabilities
import com.agentclientprotocol.model.v2.AgentCapabilities as V2AgentCapabilities

private const val API_KEY_VARIABLE = "SAMPLE_AGENT_API_KEY"

/**
 * Queries the unstable `auth/status` method over ACP v1 and v2 without creating a session.
 *
 * Set `SAMPLE_AGENT_API_KEY` before the run to see the agents report configured credentials.
 */
suspend fun main() = coroutineScope {
    val v1 = createInMemoryTransportPair()
    val v1AgentProtocol = Protocol(this, v1.agent)
    Agent(v1AgentProtocol, AuthStatusV1AgentSupport())
    v1AgentProtocol.start()

    try {
        queryV1AuthStatus(v1.client)
    } finally {
        v1AgentProtocol.close()
    }

    val v2 = createInMemoryTransportPair()
    val v2AgentProtocol = Protocol(this, v2.agent)
    V2Agent(v2AgentProtocol, AuthStatusV2AgentSupport())
    v2AgentProtocol.start()

    try {
        queryV2AuthStatus(v2.client)
    } finally {
        v2AgentProtocol.close()
    }
}

/**
 * The v1 echo agent, extended to advertise and answer `auth/status`.
 */
private class AuthStatusV1AgentSupport : AgentSupport by SimpleAgentSupport() {
    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        capabilities = AgentCapabilities(auth = AgentAuthCapabilities(status = true)),
        implementation = Implementation("auth-status-agent", "1.0.0"),
    )

    override suspend fun authStatus(_meta: JsonElement?): AuthStatusResponse =
        AuthStatusResponse(authenticated = credentialsConfigured(), message = credentialsMessage())
}

/**
 * The v2 echo agent, extended to advertise and answer `auth/status`.
 */
private class AuthStatusV2AgentSupport : V2AgentSupport by SimpleV2AgentSupport() {
    override suspend fun initialize(clientInfo: V2ClientInfo) = V2AgentInfo(
        implementation = Implementation("v2-auth-status-agent", "1.0.0"),
        capabilities = V2AgentCapabilities(
            session = SessionCapabilities(),
            auth = V2AgentAuthCapabilities(status = true),
        ),
    )

    override suspend fun authStatus(_meta: JsonElement?): StatusAuthResponse =
        StatusAuthResponse(authenticated = credentialsConfigured(), message = credentialsMessage())
}

/**
 * Reports whether credentials are configured. As `auth/status` specifies, it does not validate them.
 */
private fun credentialsConfigured(): Boolean = !System.getenv(API_KEY_VARIABLE).isNullOrBlank()

private fun credentialsMessage(): String =
    if (credentialsConfigured()) "$API_KEY_VARIABLE is set" else "Set $API_KEY_VARIABLE to configure credentials"

private suspend fun CoroutineScope.queryV1AuthStatus(transport: Transport) {
    val protocol = Protocol(this, transport)
    val client = Client(protocol)
    protocol.start()

    try {
        val agentInfo = client.initialize(ClientInfo(implementation = Implementation("sample-client", "1.0.0")))
        if (agentInfo.capabilities.auth.status == true) {
            val status = client.authStatus()
            println("ACP v1 auth/status: authenticated=${status.authenticated}, message=${status.message}")
        } else {
            println("The ACP v1 agent does not advertise auth/status")
        }
    } finally {
        protocol.close()
    }
}

private suspend fun CoroutineScope.queryV2AuthStatus(transport: Transport) {
    val protocol = Protocol(this, transport)
    val client = V2Client(protocol)
    protocol.start()

    try {
        val agentInfo = client.initialize(V2ClientInfo(PROTOCOL_VERSION_V2, Implementation("sample-client", "2.0.0")))
        if (agentInfo.capabilities.auth?.status == true) {
            val status = client.authStatus()
            println("ACP v2 auth/status: authenticated=${status.authenticated}, message=${status.message}")
        } else {
            println("The ACP v2 agent does not advertise auth/status")
        }
    } finally {
        protocol.close()
    }
}
