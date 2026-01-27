@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * File system capabilities that a client may support.
 *
 * See protocol docs: [FileSystem](https://agentclientprotocol.com/protocol/initialization#filesystem)
 */
@Serializable
public data class FileSystemCapability(
    @EncodeDefault val readTextFile: Boolean = false,
    @EncodeDefault val writeTextFile: Boolean = false,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Prompt capabilities supported by the agent in `session/prompt` requests.
 *
 * Baseline agent functionality requires support for text and resource links in prompt requests.
 * Other variants must be explicitly opted in to.
 *
 * See protocol docs: [Prompt Capabilities](https://agentclientprotocol.com/protocol/initialization#prompt-capabilities)
 */
@Serializable
public data class PromptCapabilities(
    @EncodeDefault val audio: Boolean = false,
    @EncodeDefault val image: Boolean = false,
    @EncodeDefault val embeddedContext: Boolean = false,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Capabilities supported by the client.
 *
 * Advertised during initialization to inform the agent about
 * available features and methods.
 *
 * See protocol docs: [Client Capabilities](https://agentclientprotocol.com/protocol/initialization#client-capabilities)
 */
@Serializable
public data class ClientCapabilities(
    @EncodeDefault val fs: FileSystemCapability? = null,
    @EncodeDefault val terminal: Boolean = false,
    override val _meta: JsonElement? = null
) : AcpCapabilities, AcpWithMeta

/**
 * MCP capabilities supported by the agent
 */
@Serializable
public data class McpCapabilities(
    @EncodeDefault val http: Boolean = false,
    @EncodeDefault val sse: Boolean = false,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for forking sessions.
 */
@UnstableApi
@Serializable
public data class SessionForkCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for listing sessions.
 */
@UnstableApi
@Serializable
public data class SessionListCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for resuming sessions.
 */
@UnstableApi
@Serializable
public data class SessionResumeCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Session capabilities supported by the agent.
 */
@OptIn(UnstableApi::class)
@Serializable
public data class SessionCapabilities(
    val fork: SessionForkCapabilities? = null,
    val list: SessionListCapabilities? = null,
    val resume: SessionResumeCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Capabilities supported by the agent.
 *
 * Advertised during initialization to inform the client about
 * available features and content types.
 *
 * See protocol docs: [Agent Capabilities](https://agentclientprotocol.com/protocol/initialization#agent-capabilities)
 */
@OptIn(UnstableApi::class)
@Serializable
public data class AgentCapabilities(
    @EncodeDefault val loadSession: Boolean = false,
    @EncodeDefault val promptCapabilities: PromptCapabilities = PromptCapabilities(),
    @EncodeDefault val mcpCapabilities: McpCapabilities = McpCapabilities(),
    @EncodeDefault val sessionCapabilities: SessionCapabilities = SessionCapabilities(),
    override val _meta: JsonElement? = null
) : AcpCapabilities, AcpWithMeta