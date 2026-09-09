@file:Suppress("unused")

package com.agentclientprotocol.rpc

import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.McpServer
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.jvm.JvmInline

/**
 * JSON-RPC version constant.
 */
public const val JSONRPC_VERSION: String = "2.0"

/**
 * Request ID for JSON-RPC messages.
 * An integer, string, or explicit null. An absent ID, unlike null, denotes a notification.
 */
@Serializable(with = RequestIdSerializer::class)
public sealed interface RequestId {
    public val value: Any?

    public data object Null : RequestId {
        override val value: Any? = null
        override fun toString(): String = "null"
    }

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
 * Custom serializer for integer, string and null IDs.
 */
internal object RequestIdSerializer : KSerializer<RequestId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RequestId", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: RequestId) {
        when (value) {
            RequestId.Null -> encoder.encodeNull()
            is IntRequestId -> encoder.encodeInt(value.value)
            is StringRequestId -> encoder.encodeString(value.value)
        }
    }

    override fun deserialize(decoder: Decoder): RequestId {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RequestId can only be deserialized from JSON")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> RequestId.Null
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
    polymorphic(McpServer::class) {
        subclass(McpServer.Stdio::class, McpServer.Stdio.serializer())
        subclass(McpServer.Http::class, McpServer.Http.serializer())
        subclass(McpServer.Sse::class, McpServer.Sse.serializer())
        defaultDeserializer { McpServer.Stdio.serializer() }

    }
}

public val ACPJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
        serializersModule = acpSerializersModule
    }
}
