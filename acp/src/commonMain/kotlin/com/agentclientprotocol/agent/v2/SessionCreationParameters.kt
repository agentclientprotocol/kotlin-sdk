package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.McpServer
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * What `session/new` was asked for.
 *
 * Separate from the v1 [com.agentclientprotocol.common.SessionCreationParameters] because v2 has its own
 * `McpServer` union.
 */
@UnstableApi
public class SessionCreationParameters(
    public val cwd: String,
    public val mcpServers: List<McpServer> = emptyList(),
    public val additionalDirectories: List<String> = emptyList(),
    public val _meta: JsonElement? = null,
)
