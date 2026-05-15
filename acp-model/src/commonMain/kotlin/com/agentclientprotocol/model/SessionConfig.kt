@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Category for a session configuration option.
 *
 * Well-known categories are provided as constants. Custom categories are supported
 * via the constructor, matching the protocol's open string-based design.
 */
@UnstableApi
@JvmInline
@Serializable
public value class SessionConfigOptionCategory(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public val MODE: SessionConfigOptionCategory = SessionConfigOptionCategory("mode")
        public val MODEL: SessionConfigOptionCategory = SessionConfigOptionCategory("model")
        public val THOUGHT_LEVEL: SessionConfigOptionCategory = SessionConfigOptionCategory("thought_level")
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A single option for a session configuration select.
 */
@UnstableApi
@Serializable
public data class SessionConfigSelectOption(
    val value: SessionConfigValueId,
    val name: String,
    val description: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A group of options for a session configuration select.
 */
@UnstableApi
@Serializable
public data class SessionConfigSelectGroup(
    val group: SessionConfigGroupId,
    val name: String? = null,
    val options: List<SessionConfigSelectOption>,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Options for a session configuration select, either as a flat list or grouped.
 */
@UnstableApi
@Serializable(with = SessionConfigSelectOptionsSerializer::class)
public sealed class SessionConfigSelectOptions {
    /**
     * A flat list of options.
     */
    @Serializable
    public data class Flat(
        val options: List<SessionConfigSelectOption>
    ) : SessionConfigSelectOptions()

    /**
     * Options organized into groups.
     */
    @Serializable
    public data class Grouped(
        val groups: List<SessionConfigSelectGroup>
    ) : SessionConfigSelectOptions()
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Polymorphic serializer for [SessionConfigSelectOptions].
 */
@OptIn(UnstableApi::class)
internal object SessionConfigSelectOptionsSerializer :
    KSerializer<SessionConfigSelectOptions> {

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        ListSerializer(JsonElement.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: SessionConfigSelectOptions) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SessionConfigSelectOptionsSerializer supports only JSON")
        val json = jsonEncoder.json

        val elements = when (value) {
            is SessionConfigSelectOptions.Flat ->
                value.options.map { json.encodeToJsonElement(SessionConfigSelectOption.serializer(), it) }
            is SessionConfigSelectOptions.Grouped ->
                value.groups.map { json.encodeToJsonElement(SessionConfigSelectGroup.serializer(), it) }
        }

        jsonEncoder.encodeJsonElement(JsonArray(elements))
    }

    override fun deserialize(decoder: Decoder): SessionConfigSelectOptions {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SessionConfigSelectOptionsSerializer supports only JSON")
        val json = jsonDecoder.json
        val element = jsonDecoder.decodeJsonElement()
        val array = element.jsonArray

        if (array.isEmpty()) return SessionConfigSelectOptions.Flat(emptyList())

        val firstElement = array[0].jsonObject
        return if ("group" in firstElement) {
            val groups = array.map { json.decodeFromJsonElement(SessionConfigSelectGroup.serializer(), it) }
            SessionConfigSelectOptions.Grouped(groups)
        } else {
            val options = array.map { json.decodeFromJsonElement(SessionConfigSelectOption.serializer(), it) }
            SessionConfigSelectOptions.Flat(options)
        }
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Configuration option types for sessions.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator("type")
public sealed class SessionConfigOption : AcpWithMeta {
    public abstract val id: SessionConfigId
    public abstract val name: String
    public abstract val description: String?
    public abstract val category: SessionConfigOptionCategory?

    /**
     * A select-type configuration option.
     */
    @Serializable
    @SerialName("select")
    public data class Select(
        override val id: SessionConfigId,
        override val name: String,
        override val description: String? = null,
        override val category: SessionConfigOptionCategory? = null,
        val currentValue: SessionConfigValueId,
        val options: SessionConfigSelectOptions,
        override val _meta: JsonElement? = null
    ) : SessionConfigOption()

    /**
     * A boolean-type configuration option.
     */
    @Serializable
    @SerialName("boolean")
    public data class BooleanOption(
        override val id: SessionConfigId,
        override val name: String,
        override val description: String? = null,
        override val category: SessionConfigOptionCategory? = null,
        val currentValue: Boolean,
        override val _meta: JsonElement? = null
    ) : SessionConfigOption()

    public companion object {
        /**
         * Creates a select-type configuration option.
         */
        public fun select(
            id: String,
            name: String,
            currentValue: String,
            options: SessionConfigSelectOptions,
            description: String? = null,
            category: SessionConfigOptionCategory? = null,
        ): Select = Select(
            id = SessionConfigId(id),
            name = name,
            description = description,
            category = category,
            currentValue = SessionConfigValueId(currentValue),
            options = options,
        )

        /**
         * Creates a boolean-type configuration option.
         */
        public fun boolean(
            id: String,
            name: String,
            currentValue: Boolean,
            description: String? = null,
            category: SessionConfigOptionCategory? = null,
        ): BooleanOption = BooleanOption(
            id = SessionConfigId(id),
            name = name,
            description = description,
            category = category,
            currentValue = currentValue,
        )
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Represents a value that can be either a string (for select options) or a boolean (for boolean options).
 */
@UnstableApi
@Serializable(with = SessionConfigOptionValueSerializer::class)
public sealed class SessionConfigOptionValue {
    /**
     * A string value, used for select-type configuration options.
     */
    public data class StringValue(val value: String) : SessionConfigOptionValue()

    /**
     * A boolean value, used for boolean-type configuration options.
     */
    public data class BoolValue(val value: Boolean) : SessionConfigOptionValue()

    /**
     * An unknown value type, used for forward compatibility with future protocol extensions.
     * Contains the raw JSON element that could not be mapped to a known type.
     */
    public data class UnknownValue(val rawElement: JsonElement) : SessionConfigOptionValue()

    public companion object {
        /**
         * Creates a [StringValue] from the given string.
         */
        public fun of(value: String): SessionConfigOptionValue = StringValue(value)

        /**
         * Creates a [BoolValue] from the given boolean.
         */
        public fun of(value: Boolean): SessionConfigOptionValue = BoolValue(value)
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Serializer for [SessionConfigOptionValue] that handles untagged string | boolean JSON values.
 */
@OptIn(UnstableApi::class)
internal object SessionConfigOptionValueSerializer : KSerializer<SessionConfigOptionValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionConfigOptionValue")

    override fun serialize(encoder: Encoder, value: SessionConfigOptionValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SessionConfigOptionValueSerializer supports only JSON")
        when (value) {
            is SessionConfigOptionValue.StringValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is SessionConfigOptionValue.BoolValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is SessionConfigOptionValue.UnknownValue -> jsonEncoder.encodeJsonElement(value.rawElement)
        }
    }

    override fun deserialize(decoder: Decoder): SessionConfigOptionValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SessionConfigOptionValueSerializer supports only JSON")
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive) {
            return SessionConfigOptionValue.UnknownValue(element)
        }
        return when {
            element.isString -> SessionConfigOptionValue.StringValue(element.content)
            element.booleanOrNull != null -> SessionConfigOptionValue.BoolValue(element.boolean)
            else -> SessionConfigOptionValue.UnknownValue(element)
        }
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Custom serializer for [SetSessionConfigOptionRequest] that flattens the [SessionConfigOptionValue]
 * `type` and `value` fields at the top level of the JSON object.
 *
 * Boolean values serialize as: `{"sessionId":"s","configId":"c","type":"boolean","value":true}`
 * String values serialize as: `{"sessionId":"s","configId":"c","value":"code"}` (no `type` field)
 * This ensures backward compatibility: the select/value-id format is unchanged.
 */
@OptIn(UnstableApi::class)
internal object SetSessionConfigOptionRequestSerializer : KSerializer<SetSessionConfigOptionRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SetSessionConfigOptionRequest")

    override fun serialize(encoder: Encoder, value: SetSessionConfigOptionRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SetSessionConfigOptionRequestSerializer supports only JSON")
        val jsonElement = buildJsonObject {
            put("sessionId", value.sessionId.value)
            put("configId", value.configId.value)
            when (val v = value.value) {
                is SessionConfigOptionValue.BoolValue -> {
                    put("type", "boolean")
                    put("value", v.value)
                }
                is SessionConfigOptionValue.StringValue -> {
                    put("value", v.value)
                }
                is SessionConfigOptionValue.UnknownValue -> {
                    // Preserve unknown fields - if it was an object with type+value, re-flatten them
                    val raw = v.rawElement
                    if (raw is JsonObject) {
                        for ((key, element) in raw) {
                            put(key, element)
                        }
                    } else {
                        put("value", raw)
                    }
                }
            }
            if (value._meta != null) {
                put("_meta", value._meta)
            }
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }

    override fun deserialize(decoder: Decoder): SetSessionConfigOptionRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SetSessionConfigOptionRequestSerializer supports only JSON")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        val sessionId = SessionId(jsonObject["sessionId"]?.let {
            (it as? JsonPrimitive)?.content
        } ?: throw SerializationException("Missing 'sessionId'"))

        val configId = SessionConfigId(jsonObject["configId"]?.let {
            (it as? JsonPrimitive)?.content
        } ?: throw SerializationException("Missing 'configId'"))

        val type = (jsonObject["type"] as? JsonPrimitive)?.content
        val rawValue = jsonObject["value"]
            ?: throw SerializationException("Missing 'value'")

        val value: SessionConfigOptionValue = when (type) {
            "boolean" -> {
                val primitive = rawValue as? JsonPrimitive
                    ?: throw SerializationException("Expected boolean primitive for type 'boolean'")
                if (primitive.booleanOrNull != null) {
                    SessionConfigOptionValue.BoolValue(primitive.boolean)
                } else {
                    SessionConfigOptionValue.UnknownValue(rawValue)
                }
            }
            null -> {
                // No type field = backward-compatible primitive value
                val primitive = rawValue as? JsonPrimitive
                if (primitive != null) {
                    when {
                        primitive.isString ->
                            SessionConfigOptionValue.StringValue(primitive.content)
                        primitive.booleanOrNull != null ->
                            SessionConfigOptionValue.BoolValue(primitive.boolean)
                        else ->
                            SessionConfigOptionValue.UnknownValue(rawValue)
                    }
                } else {
                    SessionConfigOptionValue.UnknownValue(rawValue)
                }
            }
            else -> {
                // Unknown type - forward compatibility: preserve both type and value
                val unknownWrapper = buildJsonObject {
                    put("type", JsonPrimitive(type))
                    put("value", rawValue)
                }
                SessionConfigOptionValue.UnknownValue(unknownWrapper)
            }
        }

        val meta = jsonObject["_meta"]

        return SetSessionConfigOptionRequest(
            sessionId = sessionId,
            configId = configId,
            value = value,
            _meta = meta
        )
    }
}
