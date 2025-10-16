package com.agentclientprotocol.agent

import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.json.JsonElement

public class AgentInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: AgentCapabilities = AgentCapabilities(),
    public val authMethods: List<AuthMethod> = emptyList(),
    public val _meta: JsonElement? = null
)