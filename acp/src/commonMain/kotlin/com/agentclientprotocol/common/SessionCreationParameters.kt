package com.agentclientprotocol.common

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.json.JsonElement

@Deprecated("Use SessionCreationParameters instead")
public typealias SessionParameters = SessionCreationParameters

public class SessionCreationParameters(
    public val cwd: String,
    public val mcpServers: List<McpServer>,
    @property:UnstableApi
    public val additionalDirectories: List<String>? = null,
    public val _meta: JsonElement? = null
)