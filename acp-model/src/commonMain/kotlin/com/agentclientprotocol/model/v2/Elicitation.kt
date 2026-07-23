@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationSchemaType
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.EnumOption
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * String format types for string properties in elicitation schemas.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing. Implementations that do not understand a format should treat it as an
 * annotation rather than rejecting the schema.
 */
@UnstableApi
@Serializable(with = StringFormatSerializer::class)
public sealed class StringFormat {
    /**
     * The wire-format string for this format.
     */
    public abstract val value: String

    /**
     * Email address format.
     */
    public data object Email : StringFormat() {
        override val value: String = "email"
    }

    /**
     * URI format.
     */
    public data object Uri : StringFormat() {
        override val value: String = "uri"
    }

    /**
     * Date format (YYYY-MM-DD).
     */
    public data object Date : StringFormat() {
        override val value: String = "date"
    }

    /**
     * Date-time format (ISO 8601).
     */
    public data object DateTime : StringFormat() {
        override val value: String = "date-time"
    }

    /**
     * Custom or future string format.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown format SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : StringFormat()

    public companion object {
        /**
         * Creates an implementation-specific extension format.
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
internal object StringFormatSerializer : OpenStringEnumSerializer<StringFormat>(
    serialName = "com.agentclientprotocol.model.v2.StringFormat",
    knownValues = listOf(
        StringFormat.Email,
        StringFormat.Uri,
        StringFormat.Date,
        StringFormat.DateTime,
    ),
    wireValue = StringFormat::value,
    unknown = StringFormat::Unknown,
)

/**
 * Items for a multi-select (array) property schema.
 *
 * This is a hybrid union: [StringItems] is tagged (`{"type":"string","enum":[…]}`),
 * [Titled] is untagged and detected by its `anyOf` field, and any other `type`
 * discriminator deserializes to [Unknown] with the full raw JSON preserved.
 */
@UnstableApi
@Serializable(with = MultiSelectItemsSerializer::class)
public sealed class MultiSelectItems {
    /**
     * Multi-select string items with plain string values.
     */
    @Serializable
    public data class StringItems(
        @SerialName("enum") val values: List<String>,
    ) : MultiSelectItems()

    /**
     * Titled multi-select items with human-readable labels.
     */
    @Serializable
    public data class Titled(
        @SerialName("anyOf") val options: List<EnumOption>,
    ) : MultiSelectItems()

    /**
     * Custom or future typed multi-select items.
     *
     * Type values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : MultiSelectItems()
}

@OptIn(UnstableApi::class)
internal object MultiSelectItemsSerializer : KSerializer<MultiSelectItems> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.MultiSelectItems")

    override fun serialize(encoder: Encoder, value: MultiSelectItems) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is MultiSelectItems.StringItems -> {
                val payload = jsonEncoder.json
                    .encodeToJsonElement(MultiSelectItems.StringItems.serializer(), value).jsonObject
                JsonObject(mapOf("type" to JsonPrimitive("string")) + payload)
            }

            is MultiSelectItems.Titled ->
                jsonEncoder.json.encodeToJsonElement(MultiSelectItems.Titled.serializer(), value).jsonObject

            is MultiSelectItems.Unknown -> value.rawJson
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): MultiSelectItems {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val type = (jsonObject["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            type == "string" ->
                jsonDecoder.json.decodeFromJsonElement(MultiSelectItems.StringItems.serializer(), jsonObject)

            type != null -> MultiSelectItems.Unknown(type, jsonObject)
            "anyOf" in jsonObject ->
                jsonDecoder.json.decodeFromJsonElement(MultiSelectItems.Titled.serializer(), jsonObject)

            else -> throw SerializationException(
                "Cannot determine MultiSelectItems shape; expected a 'type' or 'anyOf' field"
            )
        }
    }
}

/**
 * Property schema for elicitation form fields.
 *
 * Each known variant corresponds to a JSON Schema `type` value.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully. Clients that do not understand a property schema type
 * MUST NOT render it as a known input control.
 */
@UnstableApi
@Serializable(with = ElicitationPropertySchemaSerializer::class)
public sealed class ElicitationPropertySchema {
    /**
     * String property (or single-select enum when `enum`/`oneOf` is set).
     */
    @Serializable
    public data class StringProperty(
        val title: String? = null,
        val description: String? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val format: StringFormat? = null,
        val default: String? = null,
        @SerialName("enum") val enumValues: List<String>? = null,
        @SerialName("oneOf") val oneOf: List<EnumOption>? = null,
    ) : ElicitationPropertySchema()

    /**
     * Number (floating-point) property.
     */
    @Serializable
    public data class NumberProperty(
        val title: String? = null,
        val description: String? = null,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val default: Double? = null,
    ) : ElicitationPropertySchema()

    /**
     * Integer property.
     */
    @Serializable
    public data class IntegerProperty(
        val title: String? = null,
        val description: String? = null,
        val minimum: Long? = null,
        val maximum: Long? = null,
        val default: Long? = null,
    ) : ElicitationPropertySchema()

    /**
     * Boolean property.
     */
    @Serializable
    public data class BooleanProperty(
        val title: String? = null,
        val description: String? = null,
        val default: Boolean? = null,
    ) : ElicitationPropertySchema()

    /**
     * Multi-select array property.
     */
    @Serializable
    public data class ArrayProperty(
        val title: String? = null,
        val description: String? = null,
        val minItems: Long? = null,
        val maxItems: Long? = null,
        val items: MultiSelectItems,
        val default: List<String>? = null,
    ) : ElicitationPropertySchema()

    /**
     * Custom or future elicitation property schema.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : ElicitationPropertySchema()
}

@OptIn(UnstableApi::class)
internal object ElicitationPropertySchemaSerializer : OpenTaggedUnionSerializer<ElicitationPropertySchema>(
    serialName = "com.agentclientprotocol.model.v2.ElicitationPropertySchema",
    discriminatorKey = "type",
    known = mapOf(
        "string" to ElicitationPropertySchema.StringProperty.serializer(),
        "number" to ElicitationPropertySchema.NumberProperty.serializer(),
        "integer" to ElicitationPropertySchema.IntegerProperty.serializer(),
        "boolean" to ElicitationPropertySchema.BooleanProperty.serializer(),
        "array" to ElicitationPropertySchema.ArrayProperty.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is ElicitationPropertySchema.StringProperty -> "string"
            is ElicitationPropertySchema.NumberProperty -> "number"
            is ElicitationPropertySchema.IntegerProperty -> "integer"
            is ElicitationPropertySchema.BooleanProperty -> "boolean"
            is ElicitationPropertySchema.ArrayProperty -> "array"
            is ElicitationPropertySchema.Unknown -> value.type
        }
    },
    unknown = ElicitationPropertySchema::Unknown,
    rawJson = { (it as? ElicitationPropertySchema.Unknown)?.rawJson },
)

/**
 * Type-safe elicitation schema for requesting structured user input.
 */
@UnstableApi
@Serializable
public data class ElicitationSchema(
    @SerialName("type") @EncodeDefault val type: ElicitationSchemaType = ElicitationSchemaType.OBJECT,
    val title: String? = null,
    val properties: Map<String, ElicitationPropertySchema> = emptyMap(),
    val required: List<String>? = null,
    val description: String? = null,
)

/**
 * The user's action in response to an elicitation.
 *
 * This is an open tagged union: an unrecognized `action` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully. Agents that do not understand an action MUST NOT treat
 * it as a known elicitation action — in particular, not as acceptance.
 */
@UnstableApi
@Serializable(with = ElicitationActionSerializer::class)
public sealed class ElicitationAction {
    /**
     * The user accepted and provided content.
     */
    @Serializable
    public data class Accept(
        val content: Map<String, ElicitationContentValue>? = null,
    ) : ElicitationAction()

    /**
     * The user declined the elicitation.
     */
    @Serializable
    public data object Decline : ElicitationAction()

    /**
     * The elicitation was cancelled.
     */
    @Serializable
    public data object Cancel : ElicitationAction()

    /**
     * Custom or future elicitation action.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Agents SHOULD preserve it when storing,
     * replaying, proxying, or forwarding elicitation responses.
     */
    public data class Unknown(val action: String, val rawJson: JsonObject) : ElicitationAction()
}

@OptIn(UnstableApi::class)
internal object ElicitationActionSerializer : OpenTaggedUnionSerializer<ElicitationAction>(
    serialName = "com.agentclientprotocol.model.v2.ElicitationAction",
    discriminatorKey = "action",
    known = mapOf(
        "accept" to ElicitationAction.Accept.serializer(),
        "decline" to ElicitationAction.Decline.serializer(),
        "cancel" to ElicitationAction.Cancel.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is ElicitationAction.Accept -> "accept"
            is ElicitationAction.Decline -> "decline"
            is ElicitationAction.Cancel -> "cancel"
            is ElicitationAction.Unknown -> value.action
        }
    },
    unknown = ElicitationAction::Unknown,
    rawJson = { (it as? ElicitationAction.Unknown)?.rawJson },
)

/**
 * The mode of elicitation, determining how user input is collected.
 *
 * Unlike the v1 Kotlin model, every mode carries its [scope] (flattened on the wire
 * beside `mode`), mirroring the Rust v2 schema — in v1 Kotlin the scope lives on the
 * enclosing request instead.
 *
 * This is an open tagged union: an unrecognized `mode` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully. Clients MUST NOT render an unknown mode as a known
 * elicitation mode.
 *
 * No v1 conversions are provided for this union: the v1 Kotlin mode type does not carry
 * the scope, so neither direction has a faithful counterpart at this level. Conversion
 * belongs at the request level once the v2 request types exist.
 */
@UnstableApi
@Serializable(with = ElicitationModeSerializer::class)
public sealed class ElicitationMode {
    /**
     * The scope this elicitation is tied to, flattened on the wire.
     *
     * Required for every mode — including [Unknown] ones.
     */
    public abstract val scope: ElicitationScope

    /**
     * Form-based elicitation where the client renders a form from the provided schema.
     */
    public data class Form(
        override val scope: ElicitationScope,
        val requestedSchema: ElicitationSchema,
    ) : ElicitationMode()

    /**
     * URL-based elicitation where the client directs the user to a URL.
     */
    public data class Url(
        override val scope: ElicitationScope,
        val elicitationId: ElicitationId,
        val url: String,
    ) : ElicitationMode()

    /**
     * Custom or future elicitation mode.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Even unknown modes must carry a resolvable scope — decoding fails otherwise.
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Clients SHOULD preserve it when storing,
     * replaying, proxying, or forwarding elicitation requests.
     */
    public data class Unknown(
        val mode: String,
        override val scope: ElicitationScope,
        val rawJson: JsonObject,
    ) : ElicitationMode()
}

@OptIn(UnstableApi::class)
internal object ElicitationModeSerializer : KSerializer<ElicitationMode> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.ElicitationMode")

    override fun serialize(encoder: Encoder, value: ElicitationMode) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is ElicitationMode.Unknown -> value.rawJson
            else -> buildJsonObject {
                put(
                    "mode",
                    JsonPrimitive(
                        when (value) {
                            is ElicitationMode.Form -> "form"
                            is ElicitationMode.Url -> "url"
                            is ElicitationMode.Unknown -> error("unreachable")
                        },
                    ),
                )
                jsonEncoder.json.encodeToJsonElement(ElicitationScope.serializer(), value.scope)
                    .jsonObject.forEach { (key, element) -> put(key, element) }
                when (value) {
                    is ElicitationMode.Form -> put(
                        "requestedSchema",
                        jsonEncoder.json.encodeToJsonElement(ElicitationSchema.serializer(), value.requestedSchema),
                    )
                    is ElicitationMode.Url -> {
                        put("elicitationId", JsonPrimitive(value.elicitationId.value))
                        put("url", JsonPrimitive(value.url))
                    }
                    is ElicitationMode.Unknown -> error("unreachable")
                }
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): ElicitationMode {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val mode = (jsonObject["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'mode' discriminator in ElicitationMode")
        // The scope fields sit beside `mode` in the same object; the scope serializer
        // picks out sessionId/toolCallId or requestId and rejects ambiguous payloads.
        val scope = jsonDecoder.json.decodeFromJsonElement(ElicitationScope.serializer(), jsonObject)
        return when (mode) {
            "form" -> ElicitationMode.Form(
                scope = scope,
                requestedSchema = jsonDecoder.json.decodeFromJsonElement(
                    ElicitationSchema.serializer(),
                    jsonObject["requestedSchema"]
                        ?: throw SerializationException("Missing 'requestedSchema' in 'form' ElicitationMode"),
                ),
            )
            "url" -> ElicitationMode.Url(
                scope = scope,
                elicitationId = ElicitationId(
                    (jsonObject["elicitationId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw SerializationException("Missing 'elicitationId' in 'url' ElicitationMode"),
                ),
                url = (jsonObject["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw SerializationException("Missing 'url' in 'url' ElicitationMode"),
            )
            else -> ElicitationMode.Unknown(mode = mode, scope = scope, rawJson = jsonObject)
        }
    }
}
