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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
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

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SessionConfigSelectOptions) {
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

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SessionConfigSelectOptions {
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
}
