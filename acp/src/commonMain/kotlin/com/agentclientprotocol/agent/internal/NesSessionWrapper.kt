package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.NesAgentSession
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.protocol.Protocol

internal class NesSessionWrapper @OptIn(UnstableApi::class) constructor(
    agent: Agent,
    val nesSession: NesAgentSession,
    protocol: Protocol
) : BaseSessionWrapper(agent, protocol)
