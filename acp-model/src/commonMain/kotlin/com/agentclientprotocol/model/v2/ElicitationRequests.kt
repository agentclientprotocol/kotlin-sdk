@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.ElicitationId
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
 * Request parameters for the v2 `elicitation/create` method.
 *
 * The [mode] is **flattened** into this object: its discriminator, its own fields, and the scope it carries
 * all sit next to [message] on the wire. Note there is no session id of its own — what the elicitation
 * belongs to comes from the scope inside the mode, which can be a session, a tool call within one, or a
 * request outside any session at all.
 */
@UnstableApi
@Serializable(with = CreateElicitationRequestSerializer::class)
public data class CreateElicitationRequest(
    val message: String,
    val mode: ElicitationMode,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response to the v2 `elicitation/create` method.
 *
 * The [action] is flattened in the same way: `action` and whatever it carries sit at the top level.
 */
@UnstableApi
@Serializable(with = CreateElicitationResponseSerializer::class)
public data class CreateElicitationResponse(
    val action: ElicitationAction,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * The v2 `elicitation/complete` notification: a URL-based elicitation has finished.
 *
 * Sent by the agent for the [ElicitationMode.Url] flow, where the user acts outside the client and the
 * client would otherwise have no way to know the form is done.
 */
@UnstableApi
@Serializable
public data class CompleteElicitationNotification(
    val elicitationId: ElicitationId,
    override val _meta: JsonElement? = null
) : AcpNotification

/**
 * Flattens [ElicitationMode] into the request object.
 *
 * Delegates to the mode's serializer in both directions rather than repeating its variants and its scope
 * handling, which is what keeps this in step when a mode or a scope is added.
 */
@OptIn(UnstableApi::class)
internal object CreateElicitationRequestSerializer : KSerializer<CreateElicitationRequest> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.CreateElicitationRequest")

    private val OWN_KEYS = setOf("message", "_meta")

    override fun serialize(encoder: Encoder, value: CreateElicitationRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val flattenedMode = jsonEncoder.json
            .encodeToJsonElement(ElicitationMode.serializer(), value.mode)
            .jsonObject
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("message", JsonPrimitive(value.message))
                for ((key, element) in flattenedMode) put(key, element)
                value._meta?.let { put("_meta", it) }
            }
        )
    }

    override fun deserialize(decoder: Decoder): CreateElicitationRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val message = (jsonObject["message"] as? JsonPrimitive)?.content
            ?: throw SerializationException("Missing 'message' in ${descriptor.serialName}")
        val mode = jsonDecoder.json.decodeFromJsonElement(
            ElicitationMode.serializer(),
            JsonObject(jsonObject.filterKeys { it !in OWN_KEYS }),
        )
        return CreateElicitationRequest(message = message, mode = mode, _meta = jsonObject["_meta"])
    }
}

/** Flattens [ElicitationAction] into the response object; see [CreateElicitationRequestSerializer]. */
@OptIn(UnstableApi::class)
internal object CreateElicitationResponseSerializer : KSerializer<CreateElicitationResponse> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.CreateElicitationResponse")

    override fun serialize(encoder: Encoder, value: CreateElicitationResponse) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val flattenedAction = jsonEncoder.json
            .encodeToJsonElement(ElicitationAction.serializer(), value.action)
            .jsonObject
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                for ((key, element) in flattenedAction) put(key, element)
                value._meta?.let { put("_meta", it) }
            }
        )
    }

    override fun deserialize(decoder: Decoder): CreateElicitationResponse {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val action = jsonDecoder.json.decodeFromJsonElement(
            ElicitationAction.serializer(),
            JsonObject(jsonObject.filterKeys { it != "_meta" }),
        )
        return CreateElicitationResponse(action = action, _meta = jsonObject["_meta"])
    }
}
