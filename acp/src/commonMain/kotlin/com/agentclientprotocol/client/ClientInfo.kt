package com.agentclientprotocol.client

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.json.JsonElement

public class ClientInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: ClientCapabilities = ClientCapabilities(),
    public val _meta: JsonElement? = null
)