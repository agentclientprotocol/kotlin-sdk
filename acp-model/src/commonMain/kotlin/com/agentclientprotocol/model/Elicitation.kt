@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val ELICITATION_MODE_DISCRIMINATOR = "mode"
private const val ELICITATION_ACTION_DISCRIMINATOR = "action"
private const val ELICITATION_PROPERTY_TYPE_DISCRIMINATOR = "type"
private const val ELICITATION_REQUEST_SESSION_ID_FIELD = "sessionId"
private const val ELICITATION_REQUEST_MESSAGE_FIELD = "message"
private const val ELICITATION_META_FIELD = "_meta"
private const val ELICITATION_ENUM_FIELD = "enum"
private const val ELICITATION_ANY_OF_FIELD = "anyOf"
private const val ELICITATION_MODE_FORM = "form"
private const val ELICITATION_MODE_URL = "url"
private const val ELICITATION_TYPE_STRING = "string"
private const val ELICITATION_TYPE_NUMBER = "number"
private const val ELICITATION_TYPE_INTEGER = "integer"
private const val ELICITATION_TYPE_BOOLEAN = "boolean"
private const val ELICITATION_TYPE_ARRAY = "array"
private const val ELICITATION_ACTION_ACCEPT = "accept"
private const val ELICITATION_ACTION_DECLINE = "decline"
private const val ELICITATION_ACTION_CANCEL = "cancel"

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * String format types for string properties in elicitation schemas.
 */
@UnstableApi
@Serializable
public enum class StringFormat {
    @SerialName("email") EMAIL,
    @SerialName("uri") URI,
    @SerialName("date") DATE,
    @SerialName("date-time") DATE_TIME
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Type discriminator for elicitation schemas.
 */
@UnstableApi
@Serializable
public enum class ElicitationSchemaType {
    @SerialName("object") OBJECT
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A titled enum option with a const value and human-readable title.
 */
@UnstableApi
@Serializable
public data class EnumOption(
    @SerialName("const")
    val value: String,
    val title: String
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Schema for string properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class StringPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minLength: UInt? = null,
    val maxLength: UInt? = null,
    val pattern: String? = null,
    val format: StringFormat? = null,
    val default: String? = null,
    @SerialName(ELICITATION_ENUM_FIELD)
    val enumValues: List<String>? = null,
    @SerialName("oneOf")
    val oneOf: List<EnumOption>? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Schema for number (floating-point) properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class NumberPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val default: Double? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Schema for integer properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class IntegerPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minimum: Long? = null,
    val maximum: Long? = null,
    val default: Long? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Schema for boolean properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class BooleanPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val default: Boolean? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Type discriminator for untitled string arrays in multi-select schemas.
 */
@UnstableApi
@Serializable
public enum class ElicitationStringType {
    @SerialName(ELICITATION_TYPE_STRING) STRING
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Items definition for a multi-select property.
 */
@UnstableApi
@Serializable(with = MultiSelectItemsSerializer::class)
public sealed class MultiSelectItems {
    @Serializable
    public data class Untitled(
        @EncodeDefault
        val type: ElicitationStringType = ElicitationStringType.STRING,
        @SerialName(ELICITATION_ENUM_FIELD)
        val values: List<String>
    ) : MultiSelectItems()

    @Serializable
    public data class Titled(
        @SerialName(ELICITATION_ANY_OF_FIELD)
        val options: List<EnumOption>
    ) : MultiSelectItems()
}

@OptIn(UnstableApi::class)
internal object MultiSelectItemsSerializer :
    JsonContentPolymorphicSerializer<MultiSelectItems>(MultiSelectItems::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MultiSelectItems> {
        val obj = element.jsonObject
        return when {
            ELICITATION_ANY_OF_FIELD in obj -> MultiSelectItems.Titled.serializer()
            ELICITATION_ENUM_FIELD in obj -> MultiSelectItems.Untitled.serializer()
            else -> throw SerializationException("Cannot determine MultiSelectItems type; expected '$ELICITATION_ANY_OF_FIELD' or '$ELICITATION_ENUM_FIELD'")
        }
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Schema for multi-select (array) properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class MultiSelectPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minItems: ULong? = null,
    val maxItems: ULong? = null,
    val items: MultiSelectItems,
    val default: List<String>? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Property schema for elicitation form fields.
 */
@UnstableApi
@Serializable(with = ElicitationPropertySchemaSerializer::class)
public sealed class ElicitationPropertySchema {
    public data class StringValue(val schema: StringPropertySchema = StringPropertySchema()) : ElicitationPropertySchema()
    public data class NumberValue(val schema: NumberPropertySchema = NumberPropertySchema()) : ElicitationPropertySchema()
    public data class IntegerValue(val schema: IntegerPropertySchema = IntegerPropertySchema()) : ElicitationPropertySchema()
    public data class BooleanValue(val schema: BooleanPropertySchema = BooleanPropertySchema()) : ElicitationPropertySchema()
    public data class ArrayValue(val schema: MultiSelectPropertySchema) : ElicitationPropertySchema()
}

@OptIn(UnstableApi::class)
internal object ElicitationPropertySchemaSerializer : KSerializer<ElicitationPropertySchema> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ElicitationPropertySchema")

    override fun serialize(encoder: Encoder, value: ElicitationPropertySchema) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ElicitationPropertySchemaSerializer supports only JSON")
        val (type, encodedSchema) = when (value) {
            is ElicitationPropertySchema.StringValue -> ELICITATION_TYPE_STRING to ACPJson.encodeToJsonElement(StringPropertySchema.serializer(), value.schema).jsonObject
            is ElicitationPropertySchema.NumberValue -> ELICITATION_TYPE_NUMBER to ACPJson.encodeToJsonElement(NumberPropertySchema.serializer(), value.schema).jsonObject
            is ElicitationPropertySchema.IntegerValue -> ELICITATION_TYPE_INTEGER to ACPJson.encodeToJsonElement(IntegerPropertySchema.serializer(), value.schema).jsonObject
            is ElicitationPropertySchema.BooleanValue -> ELICITATION_TYPE_BOOLEAN to ACPJson.encodeToJsonElement(BooleanPropertySchema.serializer(), value.schema).jsonObject
            is ElicitationPropertySchema.ArrayValue -> ELICITATION_TYPE_ARRAY to ACPJson.encodeToJsonElement(MultiSelectPropertySchema.serializer(), value.schema).jsonObject
        }
        val payload = buildJsonObject {
            put(ELICITATION_PROPERTY_TYPE_DISCRIMINATOR, type)
            encodedSchema.forEach { (k, v) -> put(k, v) }
        }
        jsonEncoder.encodeJsonElement(payload)
    }

    override fun deserialize(decoder: Decoder): ElicitationPropertySchema {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ElicitationPropertySchemaSerializer supports only JSON")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val type = obj[ELICITATION_PROPERTY_TYPE_DISCRIMINATOR]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing '$ELICITATION_PROPERTY_TYPE_DISCRIMINATOR' discriminator field")

        return when (type) {
            ELICITATION_TYPE_STRING -> ElicitationPropertySchema.StringValue(
                ACPJson.decodeFromJsonElement(StringPropertySchema.serializer(), obj)
            )
            ELICITATION_TYPE_NUMBER -> ElicitationPropertySchema.NumberValue(
                ACPJson.decodeFromJsonElement(NumberPropertySchema.serializer(), obj)
            )
            ELICITATION_TYPE_INTEGER -> ElicitationPropertySchema.IntegerValue(
                ACPJson.decodeFromJsonElement(IntegerPropertySchema.serializer(), obj)
            )
            ELICITATION_TYPE_BOOLEAN -> ElicitationPropertySchema.BooleanValue(
                ACPJson.decodeFromJsonElement(BooleanPropertySchema.serializer(), obj)
            )
            ELICITATION_TYPE_ARRAY -> ElicitationPropertySchema.ArrayValue(
                ACPJson.decodeFromJsonElement(MultiSelectPropertySchema.serializer(), obj)
            )
            else -> throw SerializationException("Unknown elicitation property schema type: '$type'")
        }
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Type-safe elicitation schema for requesting structured user input.
 */
@UnstableApi
@Serializable
public data class ElicitationSchema(
    @EncodeDefault
    val type: ElicitationSchemaType = ElicitationSchemaType.OBJECT,
    val title: String? = null,
    @EncodeDefault
    val properties: Map<String, ElicitationPropertySchema> = emptyMap(),
    val required: List<String>? = null,
    val description: String? = null
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Elicitation capabilities supported by the client.
 */
@UnstableApi
@Serializable
public data class ElicitationCapabilities(
    val form: ElicitationFormCapabilities? = null,
    val url: ElicitationUrlCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Form-based elicitation capabilities.
 */
@UnstableApi
@Serializable
public data class ElicitationFormCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * URL-based elicitation capabilities.
 */
@UnstableApi
@Serializable
public data class ElicitationUrlCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The mode of elicitation, determining how user input is collected.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator(ELICITATION_MODE_DISCRIMINATOR)
public sealed class ElicitationMode {
    @Serializable
    @SerialName(ELICITATION_MODE_FORM)
    public data class Form(
        val requestedSchema: ElicitationSchema
    ) : ElicitationMode()

    @Serializable
    @SerialName(ELICITATION_MODE_URL)
    public data class Url(
        val elicitationId: ElicitationId,
        val url: String
    ) : ElicitationMode()
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request from the agent to elicit structured user input.
 */
@UnstableApi
@Serializable(with = ElicitationRequestSerializer::class)
public data class ElicitationRequest(
    override val sessionId: SessionId,
    val mode: ElicitationMode,
    val message: String,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

@OptIn(UnstableApi::class)
internal object ElicitationRequestSerializer : KSerializer<ElicitationRequest> {
    // Kept custom intentionally: protocol flattens mode-specific fields into the request root
    // (`mode` + `requestedSchema`/`elicitationId`/`url`), while public API keeps `mode` nested.
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ElicitationRequest")

    override fun serialize(encoder: Encoder, value: ElicitationRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ElicitationRequestSerializer supports only JSON")
        val encodedMode = ACPJson.encodeToJsonElement(ElicitationMode.serializer(), value.mode).jsonObject
        val payload = buildJsonObject {
            put(ELICITATION_REQUEST_SESSION_ID_FIELD, ACPJson.encodeToJsonElement(SessionId.serializer(), value.sessionId))
            put(ELICITATION_REQUEST_MESSAGE_FIELD, JsonPrimitive(value.message))
            encodedMode.forEach { (k, v) -> put(k, v) }
            if (value._meta != null) put(ELICITATION_META_FIELD, value._meta)
        }
        jsonEncoder.encodeJsonElement(payload)
    }

    override fun deserialize(decoder: Decoder): ElicitationRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ElicitationRequestSerializer supports only JSON")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val sessionIdElement = obj[ELICITATION_REQUEST_SESSION_ID_FIELD]
            ?: throw SerializationException("Missing '$ELICITATION_REQUEST_SESSION_ID_FIELD'")
        val message = obj[ELICITATION_REQUEST_MESSAGE_FIELD]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing '$ELICITATION_REQUEST_MESSAGE_FIELD'")
        val mode = ACPJson.decodeFromJsonElement(ElicitationMode.serializer(), obj)
        return ElicitationRequest(
            sessionId = ACPJson.decodeFromJsonElement(SessionId.serializer(), sessionIdElement),
            mode = mode,
            message = message,
            _meta = obj[ELICITATION_META_FIELD]
        )
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response from the client to an elicitation request.
 */
@UnstableApi
@Serializable
public data class ElicitationResponse(
    val action: ElicitationAction,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The user's action in response to an elicitation.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator(ELICITATION_ACTION_DISCRIMINATOR)
public sealed class ElicitationAction {
    @Serializable
    @SerialName(ELICITATION_ACTION_ACCEPT)
    public data class Accept(
        val content: Map<String, ElicitationContentValue>? = null
    ) : ElicitationAction()

    @Serializable
    @SerialName(ELICITATION_ACTION_DECLINE)
    public object Decline : ElicitationAction()

    @Serializable
    @SerialName(ELICITATION_ACTION_CANCEL)
    public object Cancel : ElicitationAction()
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A typed value returned in accepted elicitation content.
 */
@UnstableApi
@Serializable(with = ElicitationContentValueSerializer::class)
public sealed class ElicitationContentValue {
    public data class StringValue(val value: String) : ElicitationContentValue()
    public data class IntegerValue(val value: Long) : ElicitationContentValue()
    public data class NumberValue(val value: Double) : ElicitationContentValue()
    public data class BooleanValue(val value: Boolean) : ElicitationContentValue()
    public data class StringArrayValue(val value: List<String>) : ElicitationContentValue()
}

@OptIn(UnstableApi::class)
internal object ElicitationContentValueSerializer : KSerializer<ElicitationContentValue> {
    // Kept custom intentionally: wire format is untagged primitive/array values, not object wrappers.
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ElicitationContentValue")

    override fun serialize(encoder: Encoder, value: ElicitationContentValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ElicitationContentValueSerializer supports only JSON")
        val element: JsonElement = when (value) {
            is ElicitationContentValue.StringValue -> JsonPrimitive(value.value)
            is ElicitationContentValue.IntegerValue -> JsonPrimitive(value.value)
            is ElicitationContentValue.NumberValue -> JsonPrimitive(value.value)
            is ElicitationContentValue.BooleanValue -> JsonPrimitive(value.value)
            is ElicitationContentValue.StringArrayValue -> JsonArray(value.value.map(::JsonPrimitive))
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ElicitationContentValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ElicitationContentValueSerializer supports only JSON")
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> when {
                element.isString -> ElicitationContentValue.StringValue(element.content)
                element.booleanOrNull != null -> ElicitationContentValue.BooleanValue(element.boolean)
                element.longOrNull != null -> ElicitationContentValue.IntegerValue(element.longOrNull!!)
                element.doubleOrNull != null -> ElicitationContentValue.NumberValue(element.doubleOrNull!!)
                else -> throw SerializationException("Unsupported primitive for ElicitationContentValue: $element")
            }
            is JsonArray -> {
                val values = element.map { item ->
                    val primitive = item as? JsonPrimitive
                        ?: throw SerializationException("Expected string array values in ElicitationContentValue")
                    if (!primitive.isString) {
                        throw SerializationException("Expected string array values in ElicitationContentValue")
                    }
                    primitive.content
                }
                ElicitationContentValue.StringArrayValue(values)
            }
            else -> throw SerializationException("Unsupported value for ElicitationContentValue: $element")
        }
    }
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Notification sent by the agent when a URL-based elicitation is complete.
 */
@UnstableApi
@Serializable
public data class ElicitationCompleteNotification(
    val elicitationId: ElicitationId,
    override val _meta: JsonElement? = null
) : AcpNotification

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Data payload for a URL elicitation required error.
 */
@UnstableApi
@Serializable
public data class UrlElicitationRequiredData(
    val elicitations: List<UrlElicitationRequiredItem>
)

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Type discriminator for URL-only elicitation items.
 */
@UnstableApi
@Serializable
public enum class ElicitationUrlOnlyMode {
    @SerialName(ELICITATION_MODE_URL) URL
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A single URL elicitation item in URL-required error data.
 */
@UnstableApi
@Serializable
public data class UrlElicitationRequiredItem(
    @EncodeDefault
    val mode: ElicitationUrlOnlyMode = ElicitationUrlOnlyMode.URL,
    val elicitationId: ElicitationId,
    val url: String,
    val message: String
)
