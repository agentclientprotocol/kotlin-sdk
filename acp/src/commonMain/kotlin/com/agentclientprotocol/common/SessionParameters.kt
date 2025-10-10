package com.agentclientprotocol.common

import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.json.JsonElement

public class SessionParameters(
    public val cwd: String,
    public val mcpServers: List<McpServer>,
    public val _meta: JsonElement? = null
)