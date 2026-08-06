package com.agentclientprotocol.agent

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
 *   informational: the version an agent can speak is declared by
 *   [AgentSupport.supportedProtocolVersions], and the negotiated version replaces this value in
 *   the `initialize` response — so returning the client's requested version here is harmless.
 */
@Serializable
public class AgentInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: AgentCapabilities = AgentCapabilities(),
    public val authMethods: List<AuthMethod> = emptyList(),
    public val implementation: Implementation? = null,
    public val _meta: JsonElement? = null
)