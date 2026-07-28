@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.AuthEnvVar
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.PermissionOptionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Describes an available authentication method.
 *
 * The `type` field acts as the discriminator in the serialized JSON form. Unlike v1,
 * every variant identifies itself with `methodId` on the wire (renamed from v1's `id`),
 * and the discriminator is **required** — a method without it is rejected instead of
 * assumed to be agent auth.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = AuthMethodSerializer::class)
public sealed class AuthMethod {
    /**
     * Unique identifier for this authentication method.
     */
    public abstract val methodId: AuthMethodId

    /**
     * Human-readable name of the authentication method.
     */
    public abstract val name: String

    /**
     * Optional description providing more details about this authentication method.
     */
    public abstract val description: String?

    /**
     * Agent handles authentication itself.
     */
    @Serializable
    public data class Agent(
        override val methodId: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        override val _meta: JsonElement? = null,
    ) : AuthMethod(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * User provides a key that the client passes to the agent as an environment variable.
     */
    @Serializable
    public data class EnvVar(
        override val methodId: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val vars: List<AuthEnvVar>,
        val link: String? = null,
        override val _meta: JsonElement? = null,
    ) : AuthMethod(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Client runs an interactive terminal for the user to authenticate via a TUI.
     */
    @Serializable
    public data class Terminal(
        override val methodId: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val args: List<String> = emptyList(),
        val env: List<EnvVariable> = emptyList(),
        override val _meta: JsonElement? = null,
    ) : AuthMethod(), AcpWithMeta

    /**
     * Custom or future authentication method.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Even unknown methods must carry `methodId` and `name` — decoding fails otherwise.
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Clients that do not understand this
     * method type SHOULD preserve it when storing, replaying, proxying, or forwarding
     * initialization data, and otherwise ignore the method or display it generically.
     */
    public data class Unknown(
        val type: String,
        override val methodId: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val rawJson: JsonObject,
    ) : AuthMethod()
}

@OptIn(UnstableApi::class)
internal object AuthMethodSerializer : OpenTaggedUnionSerializer<AuthMethod>(
    serialName = "com.agentclientprotocol.model.v2.AuthMethod",
    discriminatorKey = "type",
    known = mapOf(
        "agent" to AuthMethod.Agent.serializer(),
        "env_var" to AuthMethod.EnvVar.serializer(),
        "terminal" to AuthMethod.Terminal.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is AuthMethod.Agent -> "agent"
            is AuthMethod.EnvVar -> "env_var"
            is AuthMethod.Terminal -> "terminal"
            is AuthMethod.Unknown -> value.type
        }
    },
    unknown = { type, rawJson ->
        val methodId = (rawJson["methodId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'methodId' in unknown AuthMethod")
        val name = (rawJson["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'name' in unknown AuthMethod")
        AuthMethod.Unknown(
            type = type,
            methodId = AuthMethodId(methodId),
            name = name,
            description = (rawJson["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            rawJson = rawJson,
        )
    },
    rawJson = { (it as? AuthMethod.Unknown)?.rawJson },
)

/**
 * Configuration for connecting to an MCP (Model Context Protocol) server.
 *
 * MCP servers provide tools and context that the agent can use when
 * processing prompts.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully. Unlike v1, the `type` discriminator is **required** —
 * a configuration without it is rejected instead of silently assumed to be stdio.
 *
 * See protocol docs: [MCP Servers](https://agentclientprotocol.com/protocol/session-setup#mcp-servers)
 */
@UnstableApi
@Serializable(with = McpServerSerializer::class)
public sealed class McpServer {
    /**
     * HTTP transport configuration
     *
     * Only available when the Agent capabilities include `session.mcp.http`.
     */
    @Serializable
    public data class Http(
        val name: String,
        val url: String,
        val headers: List<HttpHeader> = emptyList(),
        override val _meta: JsonElement? = null,
    ) : McpServer(), AcpWithMeta

    /**
     * Stdio transport configuration
     *
     * Only available when the Agent capabilities include `session.mcp.stdio`.
     */
    @Serializable
    public data class Stdio(
        val name: String,
        val command: String,
        val args: List<String> = emptyList(),
        val env: List<EnvVariable> = emptyList(),
        override val _meta: JsonElement? = null,
    ) : McpServer(), AcpWithMeta

    /**
     * Custom or future MCP server transport configuration.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Receivers that do not understand this
     * transport SHOULD preserve it when storing, replaying, proxying, or forwarding
     * session setup data, and otherwise ignore it or reject the server configuration.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : McpServer()
}

@OptIn(UnstableApi::class)
internal object McpServerSerializer : OpenTaggedUnionSerializer<McpServer>(
    serialName = "com.agentclientprotocol.model.v2.McpServer",
    discriminatorKey = "type",
    known = mapOf(
        "http" to McpServer.Http.serializer(),
        "stdio" to McpServer.Stdio.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is McpServer.Http -> "http"
            is McpServer.Stdio -> "stdio"
            is McpServer.Unknown -> value.type
        }
    },
    unknown = McpServer::Unknown,
    rawJson = { (it as? McpServer.Unknown)?.rawJson },
)

/**
 * Reasons why an agent stops active session work.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 *
 * See protocol docs: [Stop Reasons](https://agentclientprotocol.com/protocol/prompt-lifecycle#stop-reasons)
 */
@UnstableApi
@Serializable(with = StopReasonSerializer::class)
public sealed class StopReason {
    /**
     * The wire-format string for this stop reason.
     */
    public abstract val value: String

    /**
     * The active work ended successfully.
     */
    public data object EndTurn : StopReason() {
        override val value: String = "end_turn"
    }

    /**
     * The active work ended because the agent reached the maximum number of tokens.
     */
    public data object MaxTokens : StopReason() {
        override val value: String = "max_tokens"
    }

    /**
     * The active work ended because the agent reached the maximum number of
     * allowed agent requests before returning idle.
     */
    public data object MaxTurnRequests : StopReason() {
        override val value: String = "max_turn_requests"
    }

    /**
     * The active work ended because the agent refused to continue. The user
     * prompt and everything that comes after it won't be included in the next
     * prompt, so this should be reflected in the UI.
     */
    public data object Refusal : StopReason() {
        override val value: String = "refusal"
    }

    /**
     * Active session work was cancelled by the client via `session/cancel`.
     */
    public data object Cancelled : StopReason() {
        override val value: String = "cancelled"
    }

    /**
     * Custom or future stop reason.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown stop reason SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : StopReason()

    public companion object {
        /**
         * Creates an implementation-specific extension stop reason.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object StopReasonSerializer : OpenStringEnumSerializer<StopReason>(
    serialName = "com.agentclientprotocol.model.v2.StopReason",
    knownValues = listOf(
        StopReason.EndTurn,
        StopReason.MaxTokens,
        StopReason.MaxTurnRequests,
        StopReason.Refusal,
        StopReason.Cancelled,
    ),
    wireValue = StopReason::value,
    unknown = StopReason::Unknown,
)

/**
 * The type of permission option being presented to the user.
 *
 * Helps clients choose appropriate icons and UI treatment.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = PermissionOptionKindSerializer::class)
public sealed class PermissionOptionKind {
    /**
     * The wire-format string for this kind.
     */
    public abstract val value: String

    /**
     * Allow this operation only this time.
     */
    public data object AllowOnce : PermissionOptionKind() {
        override val value: String = "allow_once"
    }

    /**
     * Allow this operation and remember the choice.
     */
    public data object AllowAlways : PermissionOptionKind() {
        override val value: String = "allow_always"
    }

    /**
     * Reject this operation only this time.
     */
    public data object RejectOnce : PermissionOptionKind() {
        override val value: String = "reject_once"
    }

    /**
     * Reject this operation and remember the choice.
     */
    public data object RejectAlways : PermissionOptionKind() {
        override val value: String = "reject_always"
    }

    /**
     * Custom or future permission option kind.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown kind SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics — in particular, an unknown kind MUST NOT be
     * treated as any form of approval.
     */
    public data class Unknown(override val value: String) : PermissionOptionKind()

    public companion object {
        /**
         * Creates an implementation-specific extension kind.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object PermissionOptionKindSerializer : OpenStringEnumSerializer<PermissionOptionKind>(
    serialName = "com.agentclientprotocol.model.v2.PermissionOptionKind",
    knownValues = listOf(
        PermissionOptionKind.AllowOnce,
        PermissionOptionKind.AllowAlways,
        PermissionOptionKind.RejectOnce,
        PermissionOptionKind.RejectAlways,
    ),
    wireValue = PermissionOptionKind::value,
    unknown = PermissionOptionKind::Unknown,
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Well-known API protocol identifiers for LLM providers.
 *
 * Agents and clients MUST handle unknown protocol identifiers gracefully.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = LlmProtocolSerializer::class)
public sealed class LlmProtocol {
    /**
     * The wire-format string for this protocol.
     */
    public abstract val value: String

    /**
     * Anthropic API protocol.
     */
    public data object Anthropic : LlmProtocol() {
        override val value: String = "anthropic"
    }

    /**
     * OpenAI API protocol.
     */
    public data object OpenAi : LlmProtocol() {
        override val value: String = "openai"
    }

    /**
     * Azure OpenAI API protocol.
     */
    public data object Azure : LlmProtocol() {
        override val value: String = "azure"
    }

    /**
     * Google Vertex AI API protocol.
     */
    public data object Vertex : LlmProtocol() {
        override val value: String = "vertex"
    }

    /**
     * AWS Bedrock API protocol.
     */
    public data object Bedrock : LlmProtocol() {
        override val value: String = "bedrock"
    }

    /**
     * Custom or future protocol.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown protocol SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : LlmProtocol()

    public companion object {
        /**
         * Creates an implementation-specific extension protocol.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object LlmProtocolSerializer : OpenStringEnumSerializer<LlmProtocol>(
    serialName = "com.agentclientprotocol.model.v2.LlmProtocol",
    knownValues = listOf(
        LlmProtocol.Anthropic,
        LlmProtocol.OpenAi,
        LlmProtocol.Azure,
        LlmProtocol.Vertex,
        LlmProtocol.Bedrock,
    ),
    wireValue = LlmProtocol::value,
    unknown = LlmProtocol::Unknown,
)

/**
 * The outcome of a permission request.
 *
 * This is an open tagged union: an unrecognized `outcome` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = RequestPermissionOutcomeSerializer::class)
public sealed class RequestPermissionOutcome {
    /**
     * Active session work was cancelled before the user responded.
     *
     * When a client sends a `session/cancel` notification to cancel active
     * session work, it MUST respond to all pending `session/request_permission`
     * requests with this `Cancelled` outcome.
     *
     * See protocol docs: [Cancellation](https://agentclientprotocol.com/protocol/prompt-lifecycle#cancellation)
     */
    @Serializable
    public data object Cancelled : RequestPermissionOutcome()

    /**
     * The user selected one of the provided options.
     */
    @Serializable
    public data class Selected(
        val optionId: PermissionOptionId,
        override val _meta: JsonElement? = null,
    ) : RequestPermissionOutcome(), AcpWithMeta

    /**
     * Custom or future permission outcome.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Agents that do not understand this outcome MUST NOT treat it as approval.
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Agents should preserve it when storing,
     * replaying, proxying, or forwarding permission responses, and otherwise fail or
     * decline the permission request according to policy.
     */
    public data class Unknown(val outcome: String, val rawJson: JsonObject) : RequestPermissionOutcome()
}

@OptIn(UnstableApi::class)
internal object RequestPermissionOutcomeSerializer : OpenTaggedUnionSerializer<RequestPermissionOutcome>(
    serialName = "com.agentclientprotocol.model.v2.RequestPermissionOutcome",
    discriminatorKey = "outcome",
    known = mapOf(
        "cancelled" to RequestPermissionOutcome.Cancelled.serializer(),
        "selected" to RequestPermissionOutcome.Selected.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is RequestPermissionOutcome.Cancelled -> "cancelled"
            is RequestPermissionOutcome.Selected -> "selected"
            is RequestPermissionOutcome.Unknown -> value.outcome
        }
    },
    unknown = RequestPermissionOutcome::Unknown,
    rawJson = { (it as? RequestPermissionOutcome.Unknown)?.rawJson },
)
