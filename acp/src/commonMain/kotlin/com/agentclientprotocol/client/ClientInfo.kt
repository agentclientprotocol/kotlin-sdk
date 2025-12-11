package com.agentclientprotocol.client

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public class ClientInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: ClientCapabilities = ClientCapabilities(),
    public val implementation: Implementation? = null,
    public val _meta: JsonElement? = null
)