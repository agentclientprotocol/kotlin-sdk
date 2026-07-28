@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.SessionConfigGroupId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Semantic category for a session configuration option.
 *
 * This is intended to help clients distinguish broadly common selectors (e.g. model selector vs
 * session mode selector vs thought/reasoning level) for UX purposes (keyboard shortcuts, icons,
 * placement). It MUST NOT be required for correctness. Clients MUST handle missing or unknown
 * categories gracefully.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = SessionConfigOptionCategorySerializer::class)
public sealed class SessionConfigOptionCategory {
    /**
     * The wire-format string for this category.
     */
    public abstract val value: String

    /**
     * Session mode selector.
     */
    public data object Mode : SessionConfigOptionCategory() {
        override val value: String = "mode"
    }

    /**
     * Model selector.
     */
    public data object Model : SessionConfigOptionCategory() {
        override val value: String = "model"
    }

    /**
     * Model-related configuration parameter.
     */
    public data object ModelConfig : SessionConfigOptionCategory() {
        override val value: String = "model_config"
    }

    /**
     * Thought/reasoning level selector.
     */
    public data object ThoughtLevel : SessionConfigOptionCategory() {
        override val value: String = "thought_level"
    }

    /**
     * Custom or future category.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown category SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : SessionConfigOptionCategory()

    public companion object {
        /**
         * Creates an implementation-specific extension category.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object SessionConfigOptionCategorySerializer : OpenStringEnumSerializer<SessionConfigOptionCategory>(
    serialName = "com.agentclientprotocol.model.v2.SessionConfigOptionCategory",
    knownValues = listOf(
        SessionConfigOptionCategory.Mode,
        SessionConfigOptionCategory.Model,
        SessionConfigOptionCategory.ModelConfig,
        SessionConfigOptionCategory.ThoughtLevel,
    ),
    wireValue = SessionConfigOptionCategory::value,
    unknown = SessionConfigOptionCategory::Unknown,
)

/**
 * A group of options for a session configuration select.
 *
 * Unlike v1, the group identifier field is `groupId` on the wire (renamed from v1's
 * `group`) and [name] is required.
 */
@UnstableApi
@Serializable
public data class SessionConfigSelectGroup(
    val groupId: SessionConfigGroupId,
    val name: String,
    val options: List<SessionConfigSelectOption>,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * The options of a select-type session configuration option.
 *
 * Serialized as a plain JSON array; the shape of the first element decides between
 * [Ungrouped] and [Grouped] (a `groupId` key marks a group). This union is untagged and
 * stays **closed** in v2 — without a discriminator there is nothing safe to key a
 * fallback on.
 */
@UnstableApi
@Serializable(with = SessionConfigSelectOptionsSerializer::class)
public sealed class SessionConfigSelectOptions {
    /**
     * A flat list of options with no grouping.
     */
    public data class Ungrouped(
        val options: List<SessionConfigSelectOption>,
    ) : SessionConfigSelectOptions()

    /**
     * A list of options grouped under headers.
     */
    public data class Grouped(
        val groups: List<SessionConfigSelectGroup>,
    ) : SessionConfigSelectOptions()
}

@OptIn(UnstableApi::class)
internal object SessionConfigSelectOptionsSerializer : KSerializer<SessionConfigSelectOptions> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.SessionConfigSelectOptions")

    override fun serialize(encoder: Encoder, value: SessionConfigSelectOptions) {
        val jsonEncoder = encoder as JsonEncoder
        val elements = when (value) {
            is SessionConfigSelectOptions.Ungrouped -> value.options.map {
                jsonEncoder.json.encodeToJsonElement(SessionConfigSelectOption.serializer(), it)
            }
            is SessionConfigSelectOptions.Grouped -> value.groups.map {
                jsonEncoder.json.encodeToJsonElement(SessionConfigSelectGroup.serializer(), it)
            }
        }
        jsonEncoder.encodeJsonElement(JsonArray(elements))
    }

    override fun deserialize(decoder: Decoder): SessionConfigSelectOptions {
        val jsonDecoder = decoder as JsonDecoder
        val array = jsonDecoder.decodeJsonElement().jsonArray

        if (array.isEmpty()) return SessionConfigSelectOptions.Ungrouped(emptyList())

        return if ("groupId" in array[0].jsonObject) {
            SessionConfigSelectOptions.Grouped(
                array.map { jsonDecoder.json.decodeFromJsonElement(SessionConfigSelectGroup.serializer(), it) },
            )
        } else {
            SessionConfigSelectOptions.Ungrouped(
                array.map { jsonDecoder.json.decodeFromJsonElement(SessionConfigSelectOption.serializer(), it) },
            )
        }
    }
}

/**
 * Type-specific payload of a [SessionConfigOption], flattened into the option object
 * on the wire and discriminated by `type`.
 *
 * This is an open union: an unrecognized `type` deserializes to [Unknown] preserving
 * its extra fields.
 */
@UnstableApi
public sealed class SessionConfigKind {
    /**
     * Single-value selector (dropdown).
     */
    public data class Select(
        val currentValue: SessionConfigValueId,
        val options: SessionConfigSelectOptions,
    ) : SessionConfigKind()

    /**
     * Boolean on/off toggle.
     */
    public data class Boolean(
        val currentValue: kotlin.Boolean,
    ) : SessionConfigKind()

    /**
     * Custom or future session configuration option payload.
     *
     * Type values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [fields] holds the kind-specific payload as received, excluding the fields of the
     * enclosing [SessionConfigOption]. Clients that do not understand this option type
     * SHOULD preserve it when storing, replaying, proxying, or forwarding configuration
     * data, and otherwise ignore the option or display it generically.
     */
    public data class Unknown(val type: String, val fields: JsonObject) : SessionConfigKind()
}

/**
 * A session configuration option selector and its current state.
 *
 * Unlike v1, this is a struct with a flattened [kind] payload, and the identifier field
 * is `configId` on the wire (renamed from v1's `id`).
 */
@UnstableApi
@Serializable(with = SessionConfigOptionSerializer::class)
public data class SessionConfigOption(
    val configId: SessionConfigId,
    val name: String,
    val description: String? = null,
    val category: SessionConfigOptionCategory? = null,
    val kind: SessionConfigKind,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

@OptIn(UnstableApi::class)
internal object SessionConfigOptionSerializer : KSerializer<SessionConfigOption> {
    private val optionKeys = setOf("type", "configId", "name", "description", "category", "_meta")

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.SessionConfigOption")

    override fun serialize(encoder: Encoder, value: SessionConfigOption) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = buildJsonObject {
            put(
                "type",
                JsonPrimitive(
                    when (val kind = value.kind) {
                        is SessionConfigKind.Select -> "select"
                        is SessionConfigKind.Boolean -> "boolean"
                        is SessionConfigKind.Unknown -> kind.type
                    },
                ),
            )
            put("configId", JsonPrimitive(value.configId.value))
            put("name", JsonPrimitive(value.name))
            value.description?.let { put("description", JsonPrimitive(it)) }
            value.category?.let { put("category", JsonPrimitive(it.value)) }
            when (val kind = value.kind) {
                is SessionConfigKind.Select -> {
                    put("currentValue", JsonPrimitive(kind.currentValue.value))
                    put(
                        "options",
                        jsonEncoder.json.encodeToJsonElement(SessionConfigSelectOptions.serializer(), kind.options),
                    )
                }
                is SessionConfigKind.Boolean -> put("currentValue", JsonPrimitive(kind.currentValue))
                is SessionConfigKind.Unknown -> kind.fields.forEach { (key, element) ->
                    if (key !in optionKeys) put(key, element)
                }
            }
            value._meta?.let { put("_meta", it) }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): SessionConfigOption {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val type = requireString(jsonObject, "type")
        val kind = when (type) {
            "select" -> SessionConfigKind.Select(
                currentValue = SessionConfigValueId(requireString(jsonObject, "currentValue")),
                options = jsonDecoder.json.decodeFromJsonElement(
                    SessionConfigSelectOptions.serializer(),
                    jsonObject["options"]
                        ?: throw SerializationException("Missing 'options' in 'select' SessionConfigOption"),
                ),
            )
            "boolean" -> SessionConfigKind.Boolean(
                currentValue = (jsonObject["currentValue"] as? JsonPrimitive)?.booleanOrNull
                    ?: throw SerializationException("Missing 'currentValue' in 'boolean' SessionConfigOption"),
            )
            else -> SessionConfigKind.Unknown(
                type = type,
                fields = JsonObject(jsonObject.filterKeys { it !in optionKeys }),
            )
        }
        return SessionConfigOption(
            configId = SessionConfigId(requireString(jsonObject, "configId")),
            name = requireString(jsonObject, "name"),
            description = (jsonObject["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            category = (jsonObject["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                jsonDecoder.json.decodeFromJsonElement(SessionConfigOptionCategory.serializer(), JsonPrimitive(it))
            },
            kind = kind,
            _meta = jsonObject["_meta"],
        )
    }

    private fun requireString(jsonObject: JsonObject, key: String): String =
        (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing '$key' in SessionConfigOption")
}

/**
 * The value of a session configuration option.
 *
 * Unlike v1's raw string-or-boolean, v2 values are tagged objects:
 * `{"type":"id","value":…}` or `{"type":"boolean","value":…}`.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = SessionConfigOptionValueSerializer::class)
public sealed class SessionConfigOptionValue {
    /**
     * A [SessionConfigValueId] string value, used for select-type options.
     */
    @Serializable
    public data class Id(val value: SessionConfigValueId) : SessionConfigOptionValue()

    /**
     * A boolean value, used for boolean-type options.
     */
    @Serializable
    public data class Boolean(val value: kotlin.Boolean) : SessionConfigOptionValue()

    /**
     * Custom or future session configuration option value.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Even unknown values must carry a `value` payload — decoding fails otherwise.
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically.
     */
    public data class Unknown(
        val type: String,
        val value: JsonElement,
        val rawJson: JsonObject,
    ) : SessionConfigOptionValue()
}

@OptIn(UnstableApi::class)
internal object SessionConfigOptionValueSerializer : OpenTaggedUnionSerializer<SessionConfigOptionValue>(
    serialName = "com.agentclientprotocol.model.v2.SessionConfigOptionValue",
    discriminatorKey = "type",
    known = mapOf(
        "id" to SessionConfigOptionValue.Id.serializer(),
        "boolean" to SessionConfigOptionValue.Boolean.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is SessionConfigOptionValue.Id -> "id"
            is SessionConfigOptionValue.Boolean -> "boolean"
            is SessionConfigOptionValue.Unknown -> value.type
        }
    },
    unknown = { type, rawJson ->
        val value = rawJson["value"] ?: throw SerializationException("Missing 'value' in unknown SessionConfigOptionValue")
        SessionConfigOptionValue.Unknown(type = type, value = value, rawJson = rawJson)
    },
    rawJson = { (it as? SessionConfigOptionValue.Unknown)?.rawJson },
)
