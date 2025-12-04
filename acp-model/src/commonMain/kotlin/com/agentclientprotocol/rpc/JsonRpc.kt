@file:Suppress("unused")

package com.agentclientprotocol.rpc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

/**
 * JSON-RPC version constant.
 */
public const val JSONRPC_VERSION: String = "2.0"

/**
 * Request ID for JSON-RPC messages.
 */
@JvmInline
@Serializable
public value class RequestId(public val value: Int) {
    override fun toString(): String = value.toString()
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

@OptIn(ExperimentalSerializationApi::class)
public val ACPJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
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