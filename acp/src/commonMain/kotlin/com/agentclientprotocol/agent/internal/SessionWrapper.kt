package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol

/**
 * A session with client operations: a root session or a subagent. NES sessions have none.
 */
internal abstract class SessionWrapper(
    agent: Agent,
    protocol: Protocol
) : BaseSessionWrapper(agent, protocol) {
    abstract val sessionId: SessionId
    abstract val clientOperations: ClientSessionOperations

    /**
     * The root session at the top of this session's tree: itself for a root session.
     */
    abstract val root: RootSessionWrapper

    /**
     * This session's subagents, or `null` when the client did not advertise `subagents`.
     */
    @OptIn(UnstableApi::class)
    abstract val subagents: AgentSubagents?
}
