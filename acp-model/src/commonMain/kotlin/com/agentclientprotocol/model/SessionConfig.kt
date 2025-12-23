@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
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
    val name: String,
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
internal object SessionConfigSelectOptionsSerializer :
    JsonContentPolymorphicSerializer<SessionConfigSelectOptions>(SessionConfigSelectOptions::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SessionConfigSelectOptions> {
        val array = element.jsonArray
        if (array.isEmpty()) return SessionConfigSelectOptions.Flat.serializer()

        val firstElement = array[0].jsonObject
        return if ("group" in firstElement) {
            SessionConfigSelectOptions.Grouped.serializer()
        } else {
            SessionConfigSelectOptions.Flat.serializer()
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
