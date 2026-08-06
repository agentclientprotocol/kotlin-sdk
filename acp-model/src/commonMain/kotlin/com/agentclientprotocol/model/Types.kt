@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmInline

/**
 * Protocol version identifier.
 * 
 * This version is only bumped for breaking changes.
 * Non-breaking changes should be introduced via capabilities.
 */
public typealias ProtocolVersion = Int

/**
 * The latest protocol version supported.
 */
public const val LATEST_PROTOCOL_VERSION: ProtocolVersion = 1

/**
 * **UNSTABLE**
 *
 * Version `2` of the protocol: an unstable draft used for protocol iteration.
 *
 * [LATEST_PROTOCOL_VERSION] deliberately stays at `1` and this version is deliberately absent
 * from [SUPPORTED_PROTOCOL_VERSIONS], so speaking v2 is always an explicit choice by the caller.
 *
 * A connection speaks v2 only when a v2 implementation is supplied to the agent or client; v1 and v2
 * are separate handler sets with their own payload types, and nothing is translated between them.
 */
@UnstableApi
public const val PROTOCOL_VERSION_V2: ProtocolVersion = 2

/**
 * The stable protocol versions this SDK speaks.
 *
 * An unstable draft such as [PROTOCOL_VERSION_V2] is absent, so it is never spoken unless a peer
 * supplies an implementation for it.
 */
public val SUPPORTED_PROTOCOL_VERSIONS: Set<ProtocolVersion> = setOf(LATEST_PROTOCOL_VERSION)

/**
 * **UNSTABLE**
 *
 * Every protocol version this SDK knows how to negotiate, including unstable drafts.
 *
 * A version outside this set cannot be spoken by the SDK at all. Membership here means the SDK has the
 * types for it, which is weaker than a given peer having an implementation of it.
 */
@UnstableApi
public val KNOWN_PROTOCOL_VERSIONS: Set<ProtocolVersion> = SUPPORTED_PROTOCOL_VERSIONS + PROTOCOL_VERSION_V2

/**
 * Chooses the protocol version to speak, given the version a client [requested] and the versions
 * the responding peer [supported].
 *
 * Implements the rule from
 * [version negotiation](https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation):
 * if the requested version is supported it MUST be echoed back, otherwise the latest supported
 * version MUST be returned.
 *
 * Note that the result may be *higher* than [requested]: a peer that only speaks newer versions
 * answers with its own latest, and it is then up to the client to close the connection.
 *
 * @throws IllegalStateException if [supported] is empty, since there is no version to answer with
 */
public fun negotiateProtocolVersion(
    requested: ProtocolVersion,
    supported: Set<ProtocolVersion>,
): ProtocolVersion {
    if (supported.isEmpty()) error("Cannot negotiate a protocol version: no supported versions were declared")
    return if (requested in supported) requested else supported.max()
}

/**
 * Reads the `protocolVersion` field out of a raw `initialize` payload, or `null` if it is absent or
 * not an integer.
 *
 * `initialize` is what establishes the version of a connection, so it is the one message that has to be
 * routed by a number read out of the payload rather than by what the connection already speaks.
 */
public fun readProtocolVersionOrNull(payload: JsonElement?): ProtocolVersion? {
    val field = (payload as? JsonObject)?.get("protocolVersion") ?: return null
    return runCatching { field.jsonPrimitive.intOrNull }.getOrNull()
}

/**
 * A unique identifier for a conversation session between a client and agent.
 *
 * Sessions maintain their own context, conversation history, and state,
 * allowing multiple independent interactions with the same agent.
 */
@JvmInline
@Serializable
public value class SessionId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a tool call within a session.
 */
@JvmInline
@Serializable
public value class ToolCallId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for an authentication method.
 */
@JvmInline
@Serializable
public value class AuthMethodId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a permission option.
 */
@JvmInline
@Serializable
public value class PermissionOptionId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a Session Mode.
 */
@JvmInline
@Serializable
public value class SessionModeId(public val value: String) {
    override fun toString(): String = value
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A unique identifier for a model.
 */
@UnstableApi
@JvmInline
@Serializable
public value class ModelId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a session configuration option.
 */
@JvmInline
@Serializable
public value class SessionConfigId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a session configuration value.
 */
@JvmInline
@Serializable
public value class SessionConfigValueId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Unique identifier for a session configuration group.
 */
@JvmInline
@Serializable
public value class SessionConfigGroupId(public val value: String) {
    override fun toString(): String = value
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A unique message identifier for [PromptRequest], session update message chunks, and [PromptResponse].
 *
 * If provided by the client, the Agent SHOULD echo this value as `userMessageId` in the
 * [PromptResponse] to confirm it was recorded.
 * Both clients and agents MUST use UUID format for message IDs.
 */
@UnstableApi
@JvmInline
@Serializable
public value class MessageId(public val value: String) {
    override fun toString(): String = value
}

/**
 * The sender or recipient of messages and data in a conversation.
 */
@Serializable
public enum class Role {
    @SerialName("assistant") ASSISTANT,
    @SerialName("user") USER
}

/**
 * Describes the name and version of an ACP implementation.
 *
 * Used by both clients and agents to identify themselves during initialization.
 */
@Serializable
public data class Implementation(
    val name: String,
    val version: String,
    val title: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Optional annotations for the client. The client can use annotations to inform how objects are used or displayed.
 */
@Serializable
public data class Annotations(
    val audience: List<Role>? = null,
    val priority: Double? = null,
    val lastModified: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Information about an existing session.
 */
@UnstableApi
@Serializable
public data class SessionInfo(
    val sessionId: SessionId,
    val cwd: String,
    val title: String? = null,
    val updatedAt: String? = null,
    val additionalDirectories: List<String>? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta