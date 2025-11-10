package com.agentclientprotocol.common

import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.json.JsonElement

@Deprecated("Use SessionCreationParameters instead")
public typealias SessionParameters = SessionCreationParameters

public class SessionCreationParameters(
    public val cwd: String,
    public val mcpServers: List<McpServer>,
    public val _meta: JsonElement? = null
)