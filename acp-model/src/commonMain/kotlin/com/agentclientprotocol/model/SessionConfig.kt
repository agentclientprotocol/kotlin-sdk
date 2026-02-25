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
import kotlinx.serialization.json.jsonObject

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

    /**
     * A select-type configuration option.
     */
    @Serializable
    @SerialName("select")
    public data class Select(
        override val id: SessionConfigId,
        override val name: String,
        override val description: String? = null,
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
        ): Select = Select(
            id = SessionConfigId(id),
            name = name,
            description = description,
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
        ): BooleanOption = BooleanOption(
            id = SessionConfigId(id),
            name = name,
            description = description,
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
