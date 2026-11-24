@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.samples.subagents.Side
import com.agentclientprotocol.samples.subagents.Transcript
import java.util.concurrent.ConcurrentHashMap

/**
 * The agent of the subagents sample. It advertises `session/load` and `session/close`, and opens a
 * [DelegatingAgentSession], which holds the sample's agent-side scenarios.
 *
 * There is no agent capability for subagents: a client learns that an agent exposes them from the first
 * `subagent_update`.
 *
 * Every line the agent writes to [transcript] is marked [Side.AGENT].
 */
class DelegatingAgentSupport(private val transcript: Transcript) : AgentSupport {
    /*
     * The history of every session this agent opened, kept after a close so that the session can be loaded again.
     */
    private val histories = ConcurrentHashMap<SessionId, SessionHistory>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        capabilities = AgentCapabilities(
            loadSession = true,
            sessionCapabilities = SessionCapabilities(close = SessionCloseCapabilities()),
        ),
        implementation = Implementation("subagents-sample-agent", "1.0.0"),
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val id = SessionId("root")
        val history = SessionHistory(id).also { histories[id] = it }
        return DelegatingAgentSession(id, history, transcript)
    }

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val history = histories[sessionId]
            ?: throw JsonRpcException(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, "Unknown session ${sessionId.value}")
        return DelegatingAgentSession(sessionId, history, transcript)
    }
}
