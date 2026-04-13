@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.RequestId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline

// === Error Code ===

/**
 * Error code for URL elicitation required errors.
 */
@UnstableApi
public const val URL_ELICITATION_REQUIRED_ERROR_CODE: Int = -32042

// === Value Types ===

/**
 * **UNSTABLE**
 *
 * Unique identifier for an elicitation.
 */
@UnstableApi
@JvmInline
@Serializable
public value class ElicitationId(public val value: String) {
    override fun toString(): String = value
}

// === Enums ===

/**
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
 * Type discriminator for elicitation schemas. Always "object".
 */
@UnstableApi
@Serializable
public enum class ElicitationSchemaType {
    @SerialName("object") OBJECT
}

/**
 * Type discriminator for URL-only elicitation error items.
 */
@UnstableApi
@Serializable
public enum class ElicitationUrlOnlyMode {
    @SerialName("url") URL
}

// === Property Schemas ===

/**
 * A titled enum option with a const value and human-readable title.
 */
@UnstableApi
@Serializable
public data class EnumOption(
    @SerialName("const") val value: String,
    val title: String
)

/**
 * Schema for string properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class StringPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val format: StringFormat? = null,
    val default: String? = null,
    @SerialName("enum") val enumValues: List<String>? = null,
    @SerialName("oneOf") val oneOf: List<EnumOption>? = null
)

/**
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
 * Schema for boolean properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class BooleanPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val default: Boolean? = null
)

// === Multi-Select Items ===

/**
 * Type discriminator for string items in multi-select. Always "string".
 */
@UnstableApi
@Serializable
public enum class ElicitationStringType {
    @SerialName("string") STRING
}

/**
 * Items definition for untitled multi-select enum properties.
 */
@UnstableApi
@Serializable
public data class UntitledMultiSelectItems(
    @SerialName("type") val type: ElicitationStringType = ElicitationStringType.STRING,
    @SerialName("enum") val values: List<String>
)

/**
 * Items definition for titled multi-select enum properties.
 */
@UnstableApi
@Serializable
public data class TitledMultiSelectItems(
    @SerialName("anyOf") val options: List<EnumOption>
)

/**
 * Items for a multi-select (array) property schema.
 * Untagged: distinguished by field presence ("enum" for Untitled, "anyOf" for Titled).
 */
@UnstableApi
@Serializable(with = MultiSelectItemsSerializer::class)
public sealed class MultiSelectItems {
    public data class Untitled(val items: UntitledMultiSelectItems) : MultiSelectItems()
    public data class Titled(val items: TitledMultiSelectItems) : MultiSelectItems()
}

@OptIn(UnstableApi::class)
internal object MultiSelectItemsSerializer : KSerializer<MultiSelectItems> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MultiSelectItems")

    override fun serialize(encoder: Encoder, value: MultiSelectItems) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("MultiSelectItemsSerializer supports only JSON")
        val json = jsonEncoder.json
        val element = when (value) {
            is MultiSelectItems.Untitled -> json.encodeToJsonElement(UntitledMultiSelectItems.serializer(), value.items)
            is MultiSelectItems.Titled -> json.encodeToJsonElement(TitledMultiSelectItems.serializer(), value.items)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MultiSelectItems {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("MultiSelectItemsSerializer supports only JSON")
        val json = jsonDecoder.json
        val element = jsonDecoder.decodeJsonElement().jsonObject

        return if ("anyOf" in element) {
            MultiSelectItems.Titled(json.decodeFromJsonElement(TitledMultiSelectItems.serializer(), JsonObject(element)))
        } else {
            MultiSelectItems.Untitled(json.decodeFromJsonElement(UntitledMultiSelectItems.serializer(), JsonObject(element)))
        }
    }
}

/**
 * Schema for multi-select (array) properties in an elicitation form.
 */
@UnstableApi
@Serializable
public data class MultiSelectPropertySchema(
    val title: String? = null,
    val description: String? = null,
    val minItems: Long? = null,
    val maxItems: Long? = null,
    val items: MultiSelectItems,
    val default: List<String>? = null
)

// === Property Schema (Tagged Union) ===

/**
 * Property schema for elicitation form fields.
 * Each variant corresponds to a JSON Schema "type" value.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator("type")
public sealed class ElicitationPropertySchema {
    @Serializable
    @SerialName("string")
    public data class StringProperty(
        val title: String? = null,
        val description: String? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val format: StringFormat? = null,
        val default: String? = null,
        @SerialName("enum") val enumValues: List<String>? = null,
        @SerialName("oneOf") val oneOf: List<EnumOption>? = null
    ) : ElicitationPropertySchema()

    @Serializable
    @SerialName("number")
    public data class NumberProperty(
        val title: String? = null,
        val description: String? = null,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val default: Double? = null
    ) : ElicitationPropertySchema()

    @Serializable
    @SerialName("integer")
    public data class IntegerProperty(
        val title: String? = null,
        val description: String? = null,
        val minimum: Long? = null,
        val maximum: Long? = null,
        val default: Long? = null
    ) : ElicitationPropertySchema()

    @Serializable
    @SerialName("boolean")
    public data class BooleanProperty(
        val title: String? = null,
        val description: String? = null,
        val default: Boolean? = null
    ) : ElicitationPropertySchema()

    @Serializable
    @SerialName("array")
    public data class ArrayProperty(
        val title: String? = null,
        val description: String? = null,
        val minItems: Long? = null,
        val maxItems: Long? = null,
        val items: MultiSelectItems,
        val default: List<String>? = null
    ) : ElicitationPropertySchema()
}

// === Elicitation Schema ===

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
    val description: String? = null
)

// === Elicitation Scope (Untagged) ===

/**
 * The scope of an elicitation request, determining what context it's tied to.
 */
@UnstableApi
@Serializable(with = ElicitationScopeSerializer::class)
public sealed class ElicitationScope {
    public data class Session(
        val sessionId: SessionId,
        val toolCallId: ToolCallId? = null
    ) : ElicitationScope()

    public data class Request(
        val requestId: RequestId
    ) : ElicitationScope()
}

@OptIn(UnstableApi::class)
internal object ElicitationScopeSerializer : KSerializer<ElicitationScope> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ElicitationScope")

    override fun serialize(encoder: Encoder, value: ElicitationScope) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ElicitationScopeSerializer supports only JSON")
        val json = jsonEncoder.json
        val element = when (value) {
            is ElicitationScope.Session -> buildJsonObject {
                put("sessionId", value.sessionId.value)
                if (value.toolCallId != null) {
                    put("toolCallId", value.toolCallId.value)
                }
            }
            is ElicitationScope.Request -> buildJsonObject {
                put("requestId", json.encodeToJsonElement(RequestId.serializer(), value.requestId))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ElicitationScope {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ElicitationScopeSerializer supports only JSON")
        val json = jsonDecoder.json
        val element = jsonDecoder.decodeJsonElement().jsonObject

        val hasSessionId = "sessionId" in element
        val hasRequestId = "requestId" in element
        if (hasSessionId && hasRequestId) {
            throw SerializationException("ElicitationScope must have either 'sessionId' or 'requestId', not both")
        }
        return if (hasSessionId) {
            val sessionId = SessionId(element["sessionId"]!!.jsonPrimitive.content)
            val toolCallId = element["toolCallId"]?.let { ToolCallId(it.jsonPrimitive.content) }
            ElicitationScope.Session(sessionId, toolCallId)
        } else if (hasRequestId) {
            val requestId = json.decodeFromJsonElement(RequestId.serializer(), element["requestId"]!!)
            ElicitationScope.Request(requestId)
        } else {
            throw SerializationException("ElicitationScope must have either 'sessionId' or 'requestId'")
        }
    }
}

// === Elicitation Mode (Tagged by "mode") ===

/**
 * The mode of elicitation, determining how user input is collected.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator("mode")
public sealed class ElicitationMode {
    @Serializable
    @SerialName("form")
    public data class Form(
        val requestedSchema: ElicitationSchema
    ) : ElicitationMode()

    @Serializable
    @SerialName("url")
    public data class Url(
        val elicitationId: ElicitationId,
        val url: String
    ) : ElicitationMode()
}

// === Create Elicitation Request (Flattened) ===

/**
 * Request from the agent to elicit structured user input.
 */
@UnstableApi
@Serializable(with = CreateElicitationRequestSerializer::class)
public data class CreateElicitationRequest(
    val scope: ElicitationScope,
    val mode: ElicitationMode,
    val message: String,
    override val _meta: JsonElement? = null
) : AcpRequest

@OptIn(UnstableApi::class)
internal object CreateElicitationRequestSerializer : KSerializer<CreateElicitationRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CreateElicitationRequest")

    override fun serialize(encoder: Encoder, value: CreateElicitationRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("CreateElicitationRequestSerializer supports only JSON")
        val json = jsonEncoder.json

        val scopeElement = json.encodeToJsonElement(ElicitationScopeSerializer, value.scope).jsonObject
        val modeElement = json.encodeToJsonElement(ElicitationMode.serializer(), value.mode).jsonObject

        val element = buildJsonObject {
            // Flatten scope fields
            for ((key, v) in scopeElement) {
                put(key, v)
            }
            // Flatten mode fields
            for ((key, v) in modeElement) {
                put(key, v)
            }
            put("message", value.message)
            if (value._meta != null) {
                put("_meta", value._meta)
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): CreateElicitationRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CreateElicitationRequestSerializer supports only JSON")
        val json = jsonDecoder.json
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        // Deserialize scope — the two variants are mutually exclusive per the RFD
        val hasSessionId = "sessionId" in jsonObject
        val hasRequestId = "requestId" in jsonObject
        if (hasSessionId && hasRequestId) {
            throw SerializationException("CreateElicitationRequest must have either 'sessionId' or 'requestId', not both")
        }
        val scope = if (hasSessionId) {
            val sessionId = SessionId(jsonObject["sessionId"]!!.jsonPrimitive.content)
            val toolCallId = jsonObject["toolCallId"]?.let { ToolCallId(it.jsonPrimitive.content) }
            ElicitationScope.Session(sessionId, toolCallId)
        } else if (hasRequestId) {
            val requestId = json.decodeFromJsonElement(RequestId.serializer(), jsonObject["requestId"]!!)
            ElicitationScope.Request(requestId)
        } else {
            throw SerializationException("CreateElicitationRequest must have either 'sessionId' or 'requestId'")
        }

        // Deserialize mode
        val modeStr = jsonObject["mode"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing 'mode' field")
        val mode = when (modeStr) {
            "form" -> {
                val requestedSchema = json.decodeFromJsonElement(ElicitationSchema.serializer(), jsonObject["requestedSchema"]
                    ?: throw SerializationException("Missing 'requestedSchema' for form mode"))
                ElicitationMode.Form(requestedSchema)
            }
            "url" -> {
                val elicitationId = ElicitationId(jsonObject["elicitationId"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing 'elicitationId' for url mode"))
                val url = jsonObject["url"]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing 'url' for url mode")
                ElicitationMode.Url(elicitationId, url)
            }
            else -> throw SerializationException("Unknown elicitation mode: $modeStr")
        }

        val message = jsonObject["message"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing 'message'")
        val meta = jsonObject["_meta"]

        return CreateElicitationRequest(scope, mode, message, meta)
    }
}

// === Elicitation Content Value (Untagged) ===

/**
 * A value in elicitation response content. Can be string, integer, number, boolean, or string array.
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
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ElicitationContentValue")

    override fun serialize(encoder: Encoder, value: ElicitationContentValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ElicitationContentValueSerializer supports only JSON")
        when (value) {
            is ElicitationContentValue.StringValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ElicitationContentValue.IntegerValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ElicitationContentValue.NumberValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ElicitationContentValue.BooleanValue -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is ElicitationContentValue.StringArrayValue -> {
                val array = JsonArray(value.value.map { JsonPrimitive(it) })
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ElicitationContentValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ElicitationContentValueSerializer supports only JSON")
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonPrimitive -> when {
                element.isString -> ElicitationContentValue.StringValue(element.content)
                element.booleanOrNull != null -> ElicitationContentValue.BooleanValue(element.boolean)
                element.longOrNull != null -> ElicitationContentValue.IntegerValue(element.long)
                element.doubleOrNull != null -> ElicitationContentValue.NumberValue(element.double)
                else -> throw SerializationException("Unsupported primitive type for ElicitationContentValue: $element")
            }
            is JsonArray -> {
                val strings = element.map {
                    val primitive = it as? JsonPrimitive
                        ?: throw SerializationException("ElicitationContentValue StringArray items must be primitives, got: $it")
                    if (!primitive.isString) {
                        throw SerializationException("ElicitationContentValue StringArray items must be strings, got: $primitive")
                    }
                    primitive.content
                }
                ElicitationContentValue.StringArrayValue(strings)
            }
            else -> throw SerializationException("Unsupported JSON type for ElicitationContentValue: $element")
        }
    }
}

// === Elicitation Action (Tagged by "action") ===

/**
 * The user's action in response to an elicitation.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator("action")
public sealed class ElicitationAction {
    @Serializable
    @SerialName("accept")
    public data class Accept(
        val content: Map<String, ElicitationContentValue>? = null
    ) : ElicitationAction()

    @Serializable
    @SerialName("decline")
    public data object Decline : ElicitationAction()

    @Serializable
    @SerialName("cancel")
    public data object Cancel : ElicitationAction()
}

// === Create Elicitation Response (Flattened) ===

/**
 * Response from the client to an elicitation request.
 */
@UnstableApi
@Serializable(with = CreateElicitationResponseSerializer::class)
public data class CreateElicitationResponse(
    val action: ElicitationAction,
    override val _meta: JsonElement? = null
) : AcpResponse

@OptIn(UnstableApi::class)
internal object CreateElicitationResponseSerializer : KSerializer<CreateElicitationResponse> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CreateElicitationResponse")

    override fun serialize(encoder: Encoder, value: CreateElicitationResponse) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("CreateElicitationResponseSerializer supports only JSON")
        val json = jsonEncoder.json

        val actionElement = json.encodeToJsonElement(ElicitationAction.serializer(), value.action).jsonObject

        val element = buildJsonObject {
            for ((key, v) in actionElement) {
                put(key, v)
            }
            if (value._meta != null) {
                put("_meta", value._meta)
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): CreateElicitationResponse {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CreateElicitationResponseSerializer supports only JSON")
        val json = jsonDecoder.json
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        val action = json.decodeFromJsonElement(ElicitationAction.serializer(), JsonObject(jsonObject))
        val meta = jsonObject["_meta"]

        return CreateElicitationResponse(action, meta)
    }
}

// === Complete Elicitation Notification ===

/**
 * Notification sent by the agent when a URL-based elicitation is complete.
 */
@UnstableApi
@Serializable
public data class CompleteElicitationNotification(
    val elicitationId: ElicitationId,
    override val _meta: JsonElement? = null
) : AcpNotification

// === Capabilities ===

/**
 * Elicitation capabilities supported by the client.
 */
@UnstableApi
@Serializable
public data class ElicitationCapabilities(
    val form: ElicitationFormCapabilities? = null,
    val url: ElicitationUrlCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta {
    /**
     * Whether form-based elicitation is supported.
     *
     * Per the RFD, `"elicitation": {}` (both fields absent) is equivalent to form-only support.
     * This property returns true when [form] is explicitly set OR when neither mode is declared
     * (i.e., the capability object exists but is empty).
     */
    val supportsForm: Boolean get() = form != null || (form == null && url == null)

    /**
     * Whether URL-based elicitation is supported.
     */
    val supportsUrl: Boolean get() = url != null
}

/**
 * Form-based elicitation capabilities.
 */
@UnstableApi
@Serializable
public data class ElicitationFormCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * URL-based elicitation capabilities.
 */
@UnstableApi
@Serializable
public data class ElicitationUrlCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

// === URL Elicitation Required Error Data ===

/**
 * Data payload for the UrlElicitationRequired error.
 */
@UnstableApi
@Serializable
public data class UrlElicitationRequiredData(
    val elicitations: List<UrlElicitationRequiredItem>
)

/**
 * A single URL elicitation item within the UrlElicitationRequired error data.
 */
@UnstableApi
@Serializable
public data class UrlElicitationRequiredItem(
    @EncodeDefault val mode: ElicitationUrlOnlyMode = ElicitationUrlOnlyMode.URL,
    val elicitationId: ElicitationId,
    val url: String,
    val message: String
)
