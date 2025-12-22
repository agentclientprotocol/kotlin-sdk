@file:Suppress("unused")

package com.agentclientprotocol.rpc

import com.agentclientprotocol.model.AvailableCommandInput
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.jvm.JvmInline

/**
 * JSON-RPC version constant.
 */
public const val JSONRPC_VERSION: String = "2.0"

/**
 * Request ID for JSON-RPC messages.
 * Can be either an integer or a string according to JSON-RPC 2.0 spec.
 */
@Serializable(with = RequestIdSerializer::class)
public sealed interface RequestId {
    public val value: Any

    public companion object {
        public fun create(value: Int): RequestId = IntRequestId(value)
        public fun create(value: String): RequestId = StringRequestId(value)
    }
}

/**
 * Integer-based request ID.
 */
@Serializable
private data class IntRequestId(override val value: Int) : RequestId {
    override fun toString(): String = value.toString()
}

/**
 * String-based request ID.
 */
@Serializable
private data class StringRequestId(override val value: String) : RequestId {
    override fun toString(): String = value
}

/**
 * Custom serializer for RequestId that handles both int and string values.
 */
internal object RequestIdSerializer : KSerializer<RequestId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RequestId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RequestId) {
        when (value) {
            is IntRequestId -> encoder.encodeInt(value.value)
            is StringRequestId -> encoder.encodeString(value.value)
        }
    }

    override fun deserialize(decoder: Decoder): RequestId {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RequestId can only be deserialized from JSON")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (element.isString) {
                    StringRequestId(element.content)
                } else {
                    try {
                        IntRequestId(element.content.toInt())
                    } catch (e: NumberFormatException) {
                        throw SerializationException("RequestId must be an int or string", e)
                    }
                }
            }
            else -> throw SerializationException("RequestId must be a primitive (int or string)")
        }
    }
}

@JvmInline
@Serializable
public value class MethodName(public val name: String)

@Serializable
public sealed interface JsonRpcMessage

/**
 * JSON-RPC request message.
 */
@Serializable
public data class JsonRpcRequest(
    val id: RequestId,
    val method: MethodName,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/**
 * JSON-RPC notification message.
 */
@Serializable
public data class JsonRpcNotification(
    val method: MethodName,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/**
 * JSON-RPC response message.
 */
@Serializable
public data class JsonRpcResponse(
    val id: RequestId,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/**
 * JSON-RPC error object.
 */
@Serializable
public data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * Standard JSON-RPC error codes.
 */
public enum class JsonRpcErrorCode(public val code: Int, public val message: String) {
    /** Invalid JSON was received by the server.
     * An error occurred on the server while parsing the JSON text. */
    PARSE_ERROR(-32700, "Parse error"),

    /** The JSON sent is not a valid Request object. */
    INVALID_REQUEST(-32600, "Invalid Request"),

    /** The method does not exist or is not available. */
    METHOD_NOT_FOUND(-32601, "Method not found"),

    /** Invalid method parameter(s). */
    INVALID_PARAMS(-32602, "Invalid params"),

    /** Internal JSON-RPC error.
     * Reserved for implementation-defined server errors. */
    INTERNAL_ERROR(-32603, "Internal error"),

    /** The same code as in LSP */
    CANCELLED(-32800, "Request cancelled"),

    /** Authentication is required before this operation can be performed.
     * This is an ACP-specific error code in the reserved range. */
    AUTH_REQUIRED(-32000, "Authentication required"),

    /** A given resource, such as a file, was not found.
     * This is an ACP-specific error code in the reserved range. */
    RESOURCE_NOT_FOUND(-32002, "Resource not found")
}

private val acpSerializersModule = SerializersModule {
    polymorphic(AvailableCommandInput::class) {
        subclass(AvailableCommandInput.Unstructured::class, AvailableCommandInput.Unstructured.serializer())
        defaultDeserializer { AvailableCommandInput.Unstructured.serializer() }
    }
}

@OptIn(ExperimentalSerializationApi::class)
public val ACPJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
        serializersModule = acpSerializersModule
    }
}

/**
 * Helper function to decode JSON-RPC messages based on field presence.
 * JSON-RPC 2.0 spec distinguishes message types by which fields are present:
 * - Response: has "id" and ("result" or "error")
 * - Request: has "id" and "method"
 * - Notification: has "method" but no "id"
 */
public fun decodeJsonRpcMessage(jsonString: String): JsonRpcMessage {
    val element = try {
        ACPJson.parseToJsonElement(jsonString)
    } catch (e: SerializationException) {
        // maybe there is some garbage output at the beginning of the like, try to find where JSON starts
        val jsonStart = jsonString.indexOfFirst { it == '{' }
        if (jsonStart == -1) {
            throw e
        }
        val jsonStartTrimmed = jsonString.substring(jsonStart)
        ACPJson.parseToJsonElement(jsonStartTrimmed)
    }
    require(element is JsonObject) { "Expected JSON object" }

    val hasId = element.containsKey("id")
    val hasMethod = element.containsKey("method")
    val hasResult = element.containsKey("result")
    val hasError = element.containsKey("error")

    return when {
        hasId && (hasResult || hasError) -> ACPJson.decodeFromJsonElement(JsonRpcResponse.serializer(), element)
        hasId && hasMethod -> ACPJson.decodeFromJsonElement(JsonRpcRequest.serializer(), element)
        hasMethod -> ACPJson.decodeFromJsonElement(JsonRpcNotification.serializer(), element)
        else -> error("Unable to determine JsonRpcMessage type from JSON structure")
    }
}