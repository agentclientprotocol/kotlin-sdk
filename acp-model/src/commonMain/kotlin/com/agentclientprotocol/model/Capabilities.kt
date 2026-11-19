@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
 * Authentication capabilities supported by the client.
 *
 * @property terminal whether the client can reproduce the configured agent invocation in an
 * interactive terminal. Only `true` opts in to terminal authentication methods
 */
@UnstableApi
@Serializable
public data class AuthCapabilities(
    override val _meta: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = TerminalAuthFlagSerializer::class)
    val terminal: Boolean? = null,
) : AcpWithMeta {
    /**
     * Keeps the original JVM constructor available to existing callers.
     */
    public constructor(_meta: JsonElement? = null) : this(_meta, terminal = null)

    /**
     * Copies metadata through the original JVM signature and preserves the terminal flag.
     */
    public fun copy(_meta: JsonElement? = this._meta): AuthCapabilities = AuthCapabilities(_meta, terminal)
}

private object TerminalAuthFlagSerializer : KSerializer<Boolean?> {
    private val delegate = Boolean.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Boolean? {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val value = decoder.decodeJsonElement() as? JsonPrimitive
        return value?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        delegate.serialize(encoder, value)
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Plan capabilities supported by the client.
 *
 * When present, signals that the client supports `plan_update` and `plan_removed`
 * session update types. When absent, the agent must fall back to the existing
 * `plan` session update type.
 */
@UnstableApi
@Serializable
public data class PlanCapabilities(
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
@OptIn(UnstableApi::class)
public data class ClientCapabilities(
    @EncodeDefault val fs: FileSystemCapability? = null,
    @EncodeDefault val terminal: Boolean = false,
    @property:UnstableApi
    val planCapabilities: PlanCapabilities? = null,
    @property:UnstableApi
    @EncodeDefault val auth: AuthCapabilities? = null,
    @property:UnstableApi
    val nes: ClientNesCapabilities? = null,
    @property:UnstableApi
    val positionEncodings: List<PositionEncodingKind>? = null,
    @property:UnstableApi
    val elicitation: ElicitationCapabilities? = null,
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
 * Capabilities for deleting sessions from history.
 *
 * By supplying `{}` it means that the agent supports the `session/delete` method.
 */
@Serializable
public data class SessionDeleteCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for the `session/close` method.
 *
 * By supplying `{}` it means that the agent supports closing of sessions.
 */
@UnstableApi
@Serializable
public data class SessionCloseCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Capabilities for additional session directories support.
 *
 * By supplying `{}` it means that the agent supports the `additionalDirectories` field
 * on session lifecycle requests.
 */
@UnstableApi
@Serializable
public data class SessionAdditionalDirectoriesCapabilities(
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
    val delete: SessionDeleteCapabilities? = null,
    @property:UnstableApi
    val close: SessionCloseCapabilities? = null,
    @property:UnstableApi
    val additionalDirectories: SessionAdditionalDirectoriesCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Logout capabilities supported by the agent.
 *
 * By supplying `{}` it means that the agent supports the logout method.
 */
@UnstableApi
@Serializable
public data class LogoutCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Authentication-related capabilities supported by the agent.
 *
 * @property status only `true` advertises support for the `auth/status` query
 */
@UnstableApi
@Serializable
public data class AgentAuthCapabilities(
    val logout: LogoutCapabilities? = null,
    @property:UnstableApi
    val status: Boolean? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Provider configuration capabilities supported by the agent.
 *
 * By supplying `{}` it means that the agent supports provider configuration methods.
 */
@UnstableApi
@Serializable
public data class ProvidersCapabilities(
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
    @property:UnstableApi
    @EncodeDefault val auth: AgentAuthCapabilities = AgentAuthCapabilities(),
    @property:UnstableApi
    val nes: NesCapabilities? = null,
    @property:UnstableApi
    val positionEncoding: PositionEncodingKind? = null,
    @property:UnstableApi
    val providers: ProvidersCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpCapabilities, AcpWithMeta
