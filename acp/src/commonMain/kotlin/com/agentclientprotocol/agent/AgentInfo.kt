package com.agentclientprotocol.agent

import com.agentclientprotocol.model.*
import kotlinx.serialization.json.JsonElement

public class AgentInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: AgentCapabilities = AgentCapabilities(),
    public val authMethods: List<AuthMethod> = emptyList(),
    public val implementation: Implementation? = null,
    public val _meta: JsonElement? = null
)