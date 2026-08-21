package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.AuthMethod
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * What an agent reports to a client during v2 initialization.
 *
 * Two differences from [com.agentclientprotocol.agent.AgentInfo] are worth noting:
 * - [implementation] is required, because v2's `info` field is. A v2 agent cannot forget it.
 * - there is no protocol version: negotiation decides it and the SDK fills it into the response.
 */
@UnstableApi
public class AgentInfo(
    public val implementation: Implementation,
    public val capabilities: AgentCapabilities = AgentCapabilities(),
    public val authMethods: List<AuthMethod> = emptyList(),
    public val _meta: JsonElement? = null,
)
