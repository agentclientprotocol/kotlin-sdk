@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpCapabilities
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.ClientNesCapabilities
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.NesCapabilities
import com.agentclientprotocol.model.PositionEncodingKind
import com.agentclientprotocol.model.ProvidersCapabilities
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.model.SessionForkCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// === Prompt capabilities ===

/**
 * Capabilities for image content in prompt requests.
 *
 * Supplying `{}` means the agent supports [ContentBlock.Image] in prompts.
 */
@UnstableApi
@Serializable
public data class PromptImageCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Capabilities for audio content in prompt requests.
 *
 * Supplying `{}` means the agent supports [ContentBlock.Audio] in prompts.
 */
@UnstableApi
@Serializable
public data class PromptAudioCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Capabilities for embedded context in prompt requests.
 *
 * Supplying `{}` means the agent supports [ContentBlock.Resource] in prompts.
 */
@UnstableApi
@Serializable
public data class PromptEmbeddedContextCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Prompt capabilities supported by the agent in `session/prompt` requests.
 *
 * Baseline agent functionality requires support for [ContentBlock.Text] and
 * [ContentBlock.ResourceLink]; other content types must be explicitly opted in to.
 *
 * Unlike v1, each content type is an optional marker object rather than a boolean:
 * a `null` (or omitted) value means unsupported, and `{}` means supported. Test with
 * `!= null`, never with truthiness — an explicit `null` on the wire is a legal
 * "unsupported" encoding.
 *
 * See protocol docs: [Prompt Capabilities](https://agentclientprotocol.com/protocol/v2/initialization#prompt-capabilities)
 */
@UnstableApi
@Serializable
public data class PromptCapabilities(
    val image: PromptImageCapabilities? = null,
    val audio: PromptAudioCapabilities? = null,
    val embeddedContext: PromptEmbeddedContextCapabilities? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

// === MCP capabilities ===

/**
 * Capabilities for stdio MCP server transports.
 *
 * Supplying `{}` means the agent supports [McpServer.Stdio].
 *
 * New in v2: stdio support was implicit in v1, so agents that cannot spawn
 * subprocesses now have a way to opt out.
 */
@UnstableApi
@Serializable
public data class McpStdioCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Capabilities for HTTP MCP server transports.
 *
 * Supplying `{}` means the agent supports [McpServer.Http].
 */
@UnstableApi
@Serializable
public data class McpHttpCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for ACP MCP server transports.
 *
 * Supplying `{}` means the agent supports MCP-over-ACP server transports.
 */
@UnstableApi
@Serializable
public data class McpAcpCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * MCP capabilities supported by the agent for session lifecycle requests.
 *
 * Unlike v1, each transport is an optional marker object rather than a boolean, and the
 * deprecated `sse` transport was removed. Stdio — implicit in v1 — is now advertised
 * explicitly via [stdio].
 */
@UnstableApi
@Serializable
public data class McpCapabilities(
    val stdio: McpStdioCapabilities? = null,
    val http: McpHttpCapabilities? = null,
    val acp: McpAcpCapabilities? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

// === Session capabilities ===

/**
 * Capabilities for the `session/delete` method.
 *
 * Supplying `{}` means the agent supports deleting sessions returned by `session/list`.
 */
@UnstableApi
@Serializable
public data class SessionDeleteCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Session capabilities supported by the agent.
 *
 * Supplying `{}` commits the agent to the baseline session methods: `session/new`,
 * `session/list`, `session/resume`, `session/close`, `session/prompt`, `session/cancel`
 * and `session/update`. The v1 markers that used to gate `session/list`,
 * `session/resume`, `session/close` and `session/load` are therefore gone; the remaining
 * fields advertise capabilities beyond that baseline.
 *
 * Prompt and MCP capabilities — top-level fields of v1's `AgentCapabilities` — are
 * nested here in v2.
 *
 * See protocol docs: [Session Capabilities](https://agentclientprotocol.com/protocol/v2/initialization#session-capabilities)
 */
@UnstableApi
@Serializable
public data class SessionCapabilities(
    val prompt: PromptCapabilities? = null,
    val mcp: McpCapabilities? = null,
    val delete: SessionDeleteCapabilities? = null,
    val additionalDirectories: SessionAdditionalDirectoriesCapabilities? = null,
    val fork: SessionForkCapabilities? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta {
    public companion object
}

// === Agent capabilities ===

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Authentication-related extension capabilities supported by the agent.
 *
 * Unlike v1, this object does **not** advertise support for `auth/login` or `auth/logout`
 * — the v1 `logout` marker is gone. Those methods are advertised by a non-empty
 * [InitializeResponse.authMethods] list instead.
 */
@UnstableApi
@Serializable
public data class AgentAuthCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Capabilities supported by the agent.
 *
 * Advertised during initialization to inform the client about available features and
 * content types.
 *
 * Unlike v1, everything session-scoped is nested under [session], and [session] itself is
 * optional: omitted or `null` means the agent does not support the session method
 * surface at all (reserved for non-session agents, such as an NES-only agent), while `{}`
 * means it supports the full session baseline.
 *
 * See protocol docs: [Agent Capabilities](https://agentclientprotocol.com/protocol/v2/initialization#agent-capabilities)
 */
@UnstableApi
@Serializable
public data class AgentCapabilities(
    val session: SessionCapabilities? = null,
    val auth: AgentAuthCapabilities? = null,
    val providers: ProvidersCapabilities? = null,
    val nes: NesCapabilities? = null,
    val positionEncoding: PositionEncodingKind? = null,
    override val _meta: JsonElement? = null,
) : AcpCapabilities, AcpWithMeta

// === Client capabilities ===

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for terminal authentication methods.
 *
 * Supplying `{}` means the client can reproduce the configured agent invocation in an
 * interactive terminal, and so the agent may offer [AuthMethod.Terminal] entries.
 */
@UnstableApi
@Serializable
public data class TerminalAuthCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Authentication capabilities supported by the client.
 *
 * Determines which authentication method types the agent may include in its
 * [InitializeResponse].
 */
@UnstableApi
@Serializable
public data class AuthCapabilities(
    val terminal: TerminalAuthCapabilities? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Capabilities supported by the client.
 *
 * Advertised during initialization to inform the agent about available features and
 * methods.
 *
 * The v2 client surface is drastically smaller than v1's: `fs` and `terminal` were
 * removed along with the `fs` and `terminal` method families, `session.configOptions.boolean`
 * and `plan` became baseline, and [elicitation] is the only stable field left.
 *
 * See protocol docs: [Client Capabilities](https://agentclientprotocol.com/protocol/v2/initialization#client-capabilities)
 */
@UnstableApi
@Serializable
public data class ClientCapabilities(
    val auth: AuthCapabilities? = null,
    val elicitation: ElicitationCapabilities? = null,
    val nes: ClientNesCapabilities? = null,
    val positionEncodings: List<PositionEncodingKind> = emptyList(),
    override val _meta: JsonElement? = null,
) : AcpCapabilities, AcpWithMeta
