@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.AcpWithSessionId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Where replayed session history should begin, inclusive of the position it names.
 *
 * Open union: an unrecognized `type` deserializes to [Unknown] with the payload preserved. A receiver that
 * does not understand a cursor should reject the request rather than guess where to replay from.
 */
@UnstableApi
@Serializable(with = ReplayFromSerializer::class)
public sealed class ReplayFrom {
    /** Replay the whole conversation from its first replayable entry. */
    @Serializable
    public data class Start(
        override val _meta: JsonElement? = null,
    ) : ReplayFrom(), AcpWithMeta {
        public companion object {
            internal const val DISCRIMINATOR: String = "start"
        }
    }

    /** Custom or future replay cursor; [rawJson] keeps it intact for forwarding or storing. */
    public data class Unknown(val type: String, val rawJson: JsonObject) : ReplayFrom()
}

@OptIn(UnstableApi::class)
internal object ReplayFromSerializer : OpenTaggedUnionSerializer<ReplayFrom>(
    serialName = "com.agentclientprotocol.model.v2.ReplayFrom",
    discriminatorKey = "type",
    known = mapOf(ReplayFrom.Start.DISCRIMINATOR to ReplayFrom.Start.serializer()),
    discriminator = { value ->
        when (value) {
            is ReplayFrom.Start -> ReplayFrom.Start.DISCRIMINATOR
            is ReplayFrom.Unknown -> value.type
        }
    },
    unknown = ReplayFrom::Unknown,
    rawJson = { (it as? ReplayFrom.Unknown)?.rawJson },
)

/**
 * Request parameters for the v2 `session/resume` method.
 *
 * v2 has no `session/load`: resuming replaces it, and [replayFrom] says how much history to replay.
 */
@UnstableApi
@Serializable
public data class ResumeSessionRequest(
    override val sessionId: SessionId,
    val cwd: String,
    val additionalDirectories: List<String> = emptyList(),
    val mcpServers: List<McpServer> = emptyList(),
    val replayFrom: ReplayFrom? = null,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/** Response to the v2 `session/resume` method. */
@UnstableApi
@Serializable
public data class ResumeSessionResponse(
    val configOptions: List<SessionConfigOption> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request parameters for the v2 `session/set_config_option` method.
 *
 * The chosen value is **flattened** into this object as `type` and `value` rather than nested, and unlike v1
 * both keys are always present — v1 omitted `type` for a plain id value.
 */
@UnstableApi
@Serializable(with = SetSessionConfigOptionRequestSerializer::class)
public data class SetSessionConfigOptionRequest(
    override val sessionId: SessionId,
    val configId: SessionConfigId,
    val value: SessionConfigOptionValue,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/** Response to the v2 `session/set_config_option` method: the options as they now stand. */
@UnstableApi
@Serializable
public data class SetSessionConfigOptionResponse(
    val configOptions: List<SessionConfigOption>,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Flattens [SessionConfigOptionValue] into the request object.
 *
 * Delegates both ways to the union's own serializer instead of listing its variants again, so a new variant
 * cannot be handled here and forgotten there — including the `Unknown` case, whose extra keys ride along.
 */
@OptIn(UnstableApi::class)
internal object SetSessionConfigOptionRequestSerializer : KSerializer<SetSessionConfigOptionRequest> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.SetSessionConfigOptionRequest")

    private val OWN_KEYS = setOf("sessionId", "configId", "_meta")

    override fun serialize(encoder: Encoder, value: SetSessionConfigOptionRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val flattenedValue = jsonEncoder.json
            .encodeToJsonElement(SessionConfigOptionValue.serializer(), value.value)
            .jsonObject
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("sessionId", value.sessionId.value)
                put("configId", value.configId.value)
                for ((key, element) in flattenedValue) put(key, element)
                value._meta?.let { put("_meta", it) }
            }
        )
    }

    override fun deserialize(decoder: Decoder): SetSessionConfigOptionRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        fun string(key: String): String = (jsonObject[key] as? JsonPrimitive)?.content
            ?: throw SerializationException("Missing '$key' in ${descriptor.serialName}")

        val value = jsonDecoder.json.decodeFromJsonElement(
            SessionConfigOptionValue.serializer(),
            JsonObject(jsonObject.filterKeys { it !in OWN_KEYS }),
        )
        return SetSessionConfigOptionRequest(
            sessionId = SessionId(string("sessionId")),
            configId = SessionConfigId(string("configId")),
            value = value,
            _meta = jsonObject["_meta"],
        )
    }
}

/**
 * Request parameters for the v2 `session/fork` method: start a new session from an existing one's history.
 */
@UnstableApi
@Serializable
public data class ForkSessionRequest(
    override val sessionId: SessionId,
    val cwd: String,
    val additionalDirectories: List<String> = emptyList(),
    val mcpServers: List<McpServer> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Response to the v2 `session/fork` method.
 *
 * The id is the **new** session's, not the one that was forked.
 */
@UnstableApi
@Serializable
public data class ForkSessionResponse(
    val sessionId: SessionId,
    val configOptions: List<SessionConfigOption> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpResponse
