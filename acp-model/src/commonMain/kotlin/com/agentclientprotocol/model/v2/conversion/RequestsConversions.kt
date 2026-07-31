@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.AuthMethod as V1AuthMethod
import com.agentclientprotocol.model.LlmProtocol as V1LlmProtocol
import com.agentclientprotocol.model.McpServer as V1McpServer
import com.agentclientprotocol.model.PermissionOptionKind as V1PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome as V1RequestPermissionOutcome
import com.agentclientprotocol.model.StopReason as V1StopReason
import com.agentclientprotocol.model.v2.AuthMethod
import com.agentclientprotocol.model.v2.LlmProtocol
import com.agentclientprotocol.model.v2.McpServer
import com.agentclientprotocol.model.v2.PermissionOptionKind
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.StopReason

/**
 * Converts this v2 stop reason to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [StopReason.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun StopReason.toV1(): V1StopReason = when (this) {
    StopReason.EndTurn -> V1StopReason.END_TURN
    StopReason.MaxTokens -> V1StopReason.MAX_TOKENS
    StopReason.MaxTurnRequests -> V1StopReason.MAX_TURN_REQUESTS
    StopReason.Refusal -> V1StopReason.REFUSAL
    StopReason.Cancelled -> V1StopReason.CANCELLED
    is StopReason.Unknown -> throw unknownV2EnumVariant("StopReason", value)
}

/**
 * Converts this v1 stop reason to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1StopReason.toV2(): StopReason = when (this) {
    V1StopReason.END_TURN -> StopReason.EndTurn
    V1StopReason.MAX_TOKENS -> StopReason.MaxTokens
    V1StopReason.MAX_TURN_REQUESTS -> StopReason.MaxTurnRequests
    V1StopReason.REFUSAL -> StopReason.Refusal
    V1StopReason.CANCELLED -> StopReason.Cancelled
}

/**
 * Converts this v2 kind to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [PermissionOptionKind.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun PermissionOptionKind.toV1(): V1PermissionOptionKind = when (this) {
    PermissionOptionKind.AllowOnce -> V1PermissionOptionKind.ALLOW_ONCE
    PermissionOptionKind.AllowAlways -> V1PermissionOptionKind.ALLOW_ALWAYS
    PermissionOptionKind.RejectOnce -> V1PermissionOptionKind.REJECT_ONCE
    PermissionOptionKind.RejectAlways -> V1PermissionOptionKind.REJECT_ALWAYS
    is PermissionOptionKind.Unknown -> throw unknownV2EnumVariant("PermissionOptionKind", value)
}

/**
 * Converts this v1 kind to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1PermissionOptionKind.toV2(): PermissionOptionKind = when (this) {
    V1PermissionOptionKind.ALLOW_ONCE -> PermissionOptionKind.AllowOnce
    V1PermissionOptionKind.ALLOW_ALWAYS -> PermissionOptionKind.AllowAlways
    V1PermissionOptionKind.REJECT_ONCE -> PermissionOptionKind.RejectOnce
    V1PermissionOptionKind.REJECT_ALWAYS -> PermissionOptionKind.RejectAlways
}

/**
 * Converts this v2 MCP server configuration to its v1 equivalent.
 *
 * The `_meta` field is dropped: the v1 Kotlin model does not carry metadata on
 * MCP server configurations.
 *
 * @throws ProtocolConversionException if this is an [McpServer.Unknown] transport,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun McpServer.toV1(): V1McpServer = when (this) {
    is McpServer.Http -> V1McpServer.Http(name = name, url = url, headers = headers)
    is McpServer.Stdio -> V1McpServer.Stdio(name = name, command = command, args = args, env = env)
    is McpServer.Unknown -> throw unknownV2EnumVariant("McpServer", type)
}

/**
 * Converts this v1 MCP server configuration to its v2 equivalent.
 *
 * @throws ProtocolConversionException if this is an [V1McpServer.Sse] configuration —
 * the SSE transport was removed in v2
 */
@UnstableApi
public fun V1McpServer.toV2(): McpServer = when (this) {
    is V1McpServer.Http -> McpServer.Http(name = name, url = url, headers = headers)
    is V1McpServer.Sse -> throw removedV1EnumVariant("McpServer", "sse")
    is V1McpServer.Stdio -> McpServer.Stdio(name = name, command = command, args = args, env = env)
}

/**
 * Converts this v2 authentication method to its v1 equivalent.
 *
 * The v2 `methodId` field maps to v1's `id`. For [AuthMethod.Terminal], v2's structured
 * `env` list flattens to v1's string map (per-variable `_meta` is dropped), and empty
 * `args`/`env` map to v1's absent values.
 *
 * @throws ProtocolConversionException if this is an [AuthMethod.Unknown] method — the
 * payload schema of an unknown method type is undefined and the v1 and v2 wire shapes
 * differ (`id` vs `methodId`), so a faithful translation is not possible
 */
@UnstableApi
public fun AuthMethod.toV1(): V1AuthMethod = when (this) {
    is AuthMethod.Agent -> V1AuthMethod.AgentAuth(
        id = methodId,
        name = name,
        description = description,
        _meta = _meta,
    )
    is AuthMethod.EnvVar -> V1AuthMethod.EnvVarAuth(
        id = methodId,
        name = name,
        description = description,
        vars = vars,
        link = link,
        _meta = _meta,
    )
    is AuthMethod.Terminal -> V1AuthMethod.TerminalAuth(
        id = methodId,
        name = name,
        description = description,
        args = args.takeIf { it.isNotEmpty() },
        env = env.takeIf { it.isNotEmpty() }?.associate { it.name to it.value },
        _meta = _meta,
    )
    is AuthMethod.Unknown -> throw unknownV2EnumVariant("AuthMethod", type)
}

/**
 * Converts this v1 authentication method to its v2 equivalent.
 *
 * The v1 `id` field maps to v2's `methodId`.
 *
 * @throws ProtocolConversionException if this is a [V1AuthMethod.UnknownAuthMethod] — the
 * payload schema of an unknown method type is undefined and the v1 and v2 wire shapes
 * differ (`id` vs `methodId`), so a faithful translation is not possible
 */
@UnstableApi
public fun V1AuthMethod.toV2(): AuthMethod = when (this) {
    is V1AuthMethod.AgentAuth -> AuthMethod.Agent(
        methodId = id,
        name = name,
        description = description,
        _meta = _meta,
    )
    is V1AuthMethod.EnvVarAuth -> AuthMethod.EnvVar(
        methodId = id,
        name = name,
        description = description,
        vars = vars,
        link = link,
        _meta = _meta,
    )
    is V1AuthMethod.TerminalAuth -> AuthMethod.Terminal(
        methodId = id,
        name = name,
        description = description,
        args = args.orEmpty(),
        env = env.orEmpty().map { (name, value) -> EnvVariable(name, value) },
        _meta = _meta,
    )
    is V1AuthMethod.UnknownAuthMethod -> throw removedV1EnumVariant("AuthMethod", type)
}

/**
 * Converts this v2 permission outcome to its v1 equivalent.
 *
 * The `_meta` field of [RequestPermissionOutcome.Selected] is dropped: the v1 Kotlin
 * model does not carry metadata on the selected outcome.
 *
 * @throws ProtocolConversionException if this is an [RequestPermissionOutcome.Unknown]
 * outcome, which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun RequestPermissionOutcome.toV1(): V1RequestPermissionOutcome = when (this) {
    is RequestPermissionOutcome.Cancelled -> V1RequestPermissionOutcome.Cancelled
    is RequestPermissionOutcome.Selected -> V1RequestPermissionOutcome.Selected(optionId = optionId)
    is RequestPermissionOutcome.Unknown -> throw unknownV2EnumVariant("RequestPermissionOutcome", outcome)
}

/**
 * Converts this v1 permission outcome to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1RequestPermissionOutcome.toV2(): RequestPermissionOutcome = when (this) {
    is V1RequestPermissionOutcome.Cancelled -> RequestPermissionOutcome.Cancelled
    is V1RequestPermissionOutcome.Selected -> RequestPermissionOutcome.Selected(optionId = optionId)
}

/**
 * Converts this v2 protocol to its v1 equivalent.
 *
 * This conversion is total: the v1 type is an open string wrapper, so every v2 value —
 * including [LlmProtocol.Unknown] — has a v1 representation.
 */
@UnstableApi
public fun LlmProtocol.toV1(): V1LlmProtocol = V1LlmProtocol(value)

/**
 * Converts this v1 protocol to its v2 equivalent.
 *
 * This conversion is total: well-known identifiers map to their v2 variants and any
 * other string maps to [LlmProtocol.Unknown].
 */
@UnstableApi
public fun V1LlmProtocol.toV2(): LlmProtocol = when (this) {
    V1LlmProtocol.ANTHROPIC -> LlmProtocol.Anthropic
    V1LlmProtocol.OPENAI -> LlmProtocol.OpenAi
    V1LlmProtocol.AZURE -> LlmProtocol.Azure
    V1LlmProtocol.VERTEX -> LlmProtocol.Vertex
    V1LlmProtocol.BEDROCK -> LlmProtocol.Bedrock
    else -> LlmProtocol.Unknown(value)
}
