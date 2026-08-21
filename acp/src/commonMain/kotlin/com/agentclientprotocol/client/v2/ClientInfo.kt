package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.ProtocolVersion
import com.agentclientprotocol.model.v2.ClientCapabilities
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * What a client reports to an agent during v2 initialization.
 *
 * Separate from [com.agentclientprotocol.client.ClientInfo] because v2's capability tree is not v1's — it
 * has no `fs` and no `terminal`, those move to MCP-over-ACP — and because `implementation` is required here.
 */
@UnstableApi
public class ClientInfo(
    public val protocolVersion: ProtocolVersion,
    public val implementation: Implementation,
    public val capabilities: ClientCapabilities = ClientCapabilities(),
    public val _meta: JsonElement? = null,
)
