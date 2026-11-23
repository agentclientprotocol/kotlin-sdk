package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.samples.subagents.Side
import com.agentclientprotocol.samples.subagents.Transcript

/**
 * The agent of the subagents sample. It advertises `session/close` and opens a [DelegatingAgentSession], which holds
 * the sample's agent-side scenarios.
 *
 * There is no agent capability for subagents: a client learns that an agent exposes them from the first
 * `subagent_update`.
 *
 * Every line the agent writes to [transcript] is marked [Side.AGENT].
 */
class DelegatingAgentSupport(private val transcript: Transcript) : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        capabilities = AgentCapabilities(sessionCapabilities = SessionCapabilities(close = SessionCloseCapabilities())),
        implementation = Implementation("subagents-sample-agent", "1.0.0"),
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        DelegatingAgentSession(SessionId("root"), transcript)
}
