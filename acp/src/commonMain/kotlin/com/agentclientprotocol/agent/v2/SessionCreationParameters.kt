package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.McpServer
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * The session environment a client sent with `session/new`, `session/resume` or `session/fork`.
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
