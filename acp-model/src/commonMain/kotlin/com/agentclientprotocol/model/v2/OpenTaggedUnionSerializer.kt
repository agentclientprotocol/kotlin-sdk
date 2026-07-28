@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model.v2

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Base serializer for open tagged unions in the v2 protocol schema.
 *
 * Open tagged unions are JSON objects discriminated by a string field (usually `type`).
 * Known discriminators delegate to the matching variant serializer; an unrecognized
 * discriminator deserializes to an `Unknown` fallback variant that preserves the **entire
 * raw JSON object**, so it re-serializes byte-identically and survives storing, replaying,
 * proxying, and forwarding:
 *
 * - Discriminator values beginning with `_` are reserved for implementation-specific
 *   extensions. Values that do not begin with `_` are reserved for ACP, including future
 *   ACP variants.
 * - A **known** discriminator whose payload fails its variant serializer still fails —
 *   the fallback must not mask malformed data.
 * - A missing or non-string discriminator fails instead of guessing a variant.
 *
 * See the v2 [Enum Variant Extension](https://agentclientprotocol.com/rfds/v2/enum-variant-extension) RFD.
 *
 * @param serialName fully-qualified serial name of the union type
 * @param discriminatorKey name of the JSON field holding the variant discriminator
 * @param known variant serializers by discriminator value, excluding the `Unknown` fallback
 * @param discriminator extracts the wire discriminator value of a union value
 * @param unknown creates the fallback variant from an unrecognized discriminator and the
 *   full raw JSON object as received
 * @param rawJson returns the preserved raw JSON of a fallback value, or `null` for known variants
 */
internal abstract class OpenTaggedUnionSerializer<T : Any>(
    serialName: String,
    private val discriminatorKey: String,
    private val known: Map<String, KSerializer<out T>>,
    private val discriminator: (T) -> String,
    private val unknown: (type: String, rawJson: JsonObject) -> T,
    private val rawJson: (T) -> JsonObject?,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as JsonEncoder
        val raw = rawJson(value)
        val jsonObject = if (raw != null) {
            raw
        } else {
            val type = discriminator(value)

            @Suppress("UNCHECKED_CAST")
            val serializer = known.getValue(type) as KSerializer<T>
            val payload = jsonEncoder.json.encodeToJsonElement(serializer, value).jsonObject
            buildJsonObject {
                put(discriminatorKey, JsonPrimitive(type))
                payload.forEach { (key, element) ->
                    if (key != discriminatorKey) put(key, element)
                }
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val discriminatorElement = jsonObject[discriminatorKey]
            ?: throw SerializationException(
                "Missing '$discriminatorKey' discriminator in ${descriptor.serialName}"
            )
        val type = (discriminatorElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException(
                "'$discriminatorKey' discriminator must be a string in ${descriptor.serialName}"
            )
        // Known discriminator: delegate — a malformed payload must fail, not fall back.
        val serializer = known[type] ?: return unknown(type, jsonObject)
        return jsonDecoder.json.decodeFromJsonElement(serializer, jsonObject)
    }
}
