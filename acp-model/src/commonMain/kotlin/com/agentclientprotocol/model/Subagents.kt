@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Subagent capabilities supported by the client.
 *
 * By supplying `{}` in `clientCapabilities.subagents` the client opts in to `subagent_update`,
 * `session_message` and `session_message_chunk` session updates and to `session` tool call content. Without it the
 * agent must not send those updates, and sends no `session` content.
 * An omitted or `null` value means unsupported, and so does a malformed one.
 *
 * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
 */
@Serializable(with = SubagentCapabilitiesSerializer::class)
public data class SubagentCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Controls the client may use on a subagent session, as announced by the agent.
 *
 * Every field decodes leniently: a malformed value decodes as `null` and the rest of the object
 * still decodes.
 *
 * @property cancel present when the client may send `session/cancel` for the subagent session;
 * omitted, `null` and malformed values all mean it may not
 * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
 */
@Serializable(with = SubagentSessionCapabilitiesSerializer::class)
public data class SubagentSessionCapabilities(
    val cancel: SessionCancelCapabilities? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Marker for the `cancel` control: its presence means the client may send `session/cancel` for the
 * subagent session.
 *
 * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
 */
@Serializable(with = SessionCancelCapabilitiesSerializer::class)
public data class SessionCancelCapabilities(
    override val _meta: JsonElement? = null,
) : AcpWithMeta

internal object SubagentCapabilitiesSerializer : JsonObjectSerializer<SubagentCapabilities>(
    "com.agentclientprotocol.model.SubagentCapabilities",
) {
    override fun decode(json: Json, obj: JsonObject): SubagentCapabilities =
        SubagentCapabilities(_meta = obj.lenientMeta())

    override fun encode(json: Json, value: SubagentCapabilities): JsonObject = buildJsonObject {
        value._meta?.let { put("_meta", it) }
    }
}

internal object SessionCancelCapabilitiesSerializer : JsonObjectSerializer<SessionCancelCapabilities>(
    "com.agentclientprotocol.model.SessionCancelCapabilities",
) {
    override fun decode(json: Json, obj: JsonObject): SessionCancelCapabilities =
        SessionCancelCapabilities(_meta = obj.lenientMeta())

    override fun encode(json: Json, value: SessionCancelCapabilities): JsonObject = buildJsonObject {
        value._meta?.let { put("_meta", it) }
    }
}

internal object SubagentSessionCapabilitiesSerializer : JsonObjectSerializer<SubagentSessionCapabilities>(
    "com.agentclientprotocol.model.SubagentSessionCapabilities",
) {
    override fun decode(json: Json, obj: JsonObject): SubagentSessionCapabilities = SubagentSessionCapabilities(
        cancel = obj.decodeOrNull(json, "cancel", SessionCancelCapabilities.serializer()),
        _meta = obj.lenientMeta(),
    )

    override fun encode(json: Json, value: SubagentSessionCapabilities): JsonObject = buildJsonObject {
        value.cancel?.let { put("cancel", json.encodeToJsonElement(SessionCancelCapabilities.serializer(), it)) }
        value._meta?.let { put("_meta", it) }
    }
}

/**
 * Serializer for `ClientCapabilities.subagents`: a malformed value decodes as `null` instead of failing the
 * enclosing capabilities, since a client that advertises something unreadable has not opted in.
 */
internal object LenientSubagentCapabilitiesSerializer : KSerializer<SubagentCapabilities?> {
    private val delegate: KSerializer<SubagentCapabilities> = SubagentCapabilities.serializer()

    override val descriptor: SerialDescriptor = delegate.nullable.descriptor

    override fun serialize(encoder: Encoder, value: SubagentCapabilities?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(delegate, value)
    }

    override fun deserialize(decoder: Decoder): SubagentCapabilities? {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return try {
            jsonDecoder.json.decodeFromJsonElement(delegate, element)
        } catch (_: Exception) {
            null
        }
    }
}
