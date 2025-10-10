package com.agentclientprotocol.client

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion

public class ClientInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: ClientCapabilities = ClientCapabilities(),
)