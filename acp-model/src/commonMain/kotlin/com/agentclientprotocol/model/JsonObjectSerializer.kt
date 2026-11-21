@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Base for hand-written serializers that read and write a model as one JSON object.
 *
 * A value that is not a JSON object fails with [SerializationException]. Subclasses decide which
 * of their fields tolerate malformed input.
 */
internal abstract class JsonObjectSerializer<T : Any>(serialName: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    abstract fun decode(json: Json, obj: JsonObject): T

    abstract fun encode(json: Json, value: T): JsonObject

    final override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON encoding")
        jsonEncoder.encodeJsonElement(encode(jsonEncoder.json, value))
    }

    final override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports only JSON decoding")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("${descriptor.serialName} must be a JSON object")
        return decode(jsonDecoder.json, obj)
    }
}

/**
 * The `_meta` of this object, or `null` when it is absent, `null` or not a JSON object.
 *
 * Mirrors Rust's `DefaultOnError` on `Meta`, which is a JSON object.
 */
internal fun JsonObject.lenientMeta(): JsonObject? = this["_meta"] as? JsonObject

/**
 * Decodes [key] with [deserializer], or returns `null` when it is absent, `null` or fails to decode.
 *
 * Mirrors Rust's `DefaultOnError` on an `Option` field. The catch is broad because kotlinx tree
 * decoding reports malformed shapes through several exception types.
 */
internal fun <T : Any> JsonObject.decodeOrNull(
    json: Json,
    key: String,
    deserializer: DeserializationStrategy<T>,
): T? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return try {
        json.decodeFromJsonElement(deserializer, element)
    } catch (_: Exception) {
        null
    }
}

/**
 * The string value of this element, or `null` when it is not a JSON string.
 */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Serializer for an optional `_meta` property that decodes a value that is not a JSON object as `null`.
 *
 * Mirrors Rust's `DefaultOnError` on `Meta` for models whose other fields use generated serializers.
 */
internal object LenientMetaSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: JsonElement?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("_meta supports only JSON encoding")
        jsonEncoder.encodeJsonElement(value)
    }

    override fun deserialize(decoder: Decoder): JsonElement? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("_meta supports only JSON decoding")
        return jsonDecoder.decodeJsonElement() as? JsonObject
    }
}
