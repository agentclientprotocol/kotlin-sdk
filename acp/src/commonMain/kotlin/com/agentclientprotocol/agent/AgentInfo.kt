package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What an agent reports to a client during initialization.
 *
 * @property protocolVersion the protocol version of the connection.
 *   On the client side this is the version the agent negotiated. On the agent side it is
 *   informational: the version-specific [Agent] runtime replaces this value with the negotiated
 *   version in the `initialize` response.
 */
@Serializable
public class AgentInfo @OptIn(UnstableApi::class) constructor(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: AgentCapabilities = AgentCapabilities(),
    public val authMethods: List<AuthMethod> = emptyList(),
    public val implementation: Implementation? = null,
    public val _meta: JsonElement? = null
)
