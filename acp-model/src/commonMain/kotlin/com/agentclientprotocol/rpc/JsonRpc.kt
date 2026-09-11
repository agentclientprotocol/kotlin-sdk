@file:Suppress("unused")

package com.agentclientprotocol.rpc

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /**
     * Integer-based request ID.
     */
    @Serializable
    public data class IntId(override val value: Int) : RequestId {
        override fun toString(): String = value.toString()
    }

    /**
     * String-based request ID.
     */
    @Serializable
    public data class StringId(override val value: String) : RequestId {
        override fun toString(): String = value
    }

    public data object Null : RequestId {
        override val value: Any? = null
        override fun toString(): String = "null"
    }

    public companion object {
        public fun create(value: Int): RequestId = IntId(value)
        public fun create(value: String): RequestId = StringId(value)
    }
}


@JvmInline
@Serializable
public value class MethodName(public val name: String)

@Serializable(with = JsonRpcMessageSerializer::class)
public sealed interface JsonRpcMessage {
    public val jsonrpc: String
}

/**
 * JSON-RPC request message.
 */
@Serializable
public data class JsonRpcRequest(
    val id: RequestId,
    val method: MethodName,
    val params: JsonElement? = null,
    @Required override val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/**
 * JSON-RPC notification message.
 */
@Serializable
public data class JsonRpcNotification(
    val method: MethodName,
    val params: JsonElement? = null,
    @Required override val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/**
 * A JSON-RPC reply containing either a result or an error, never both.
 */
@Serializable(with = JsonRpcResponseSerializer::class)
public sealed interface JsonRpcResponse : JsonRpcMessage {
    public val id: RequestId
}

/** A successful reply. [result] is required; use [JsonNull] for a JSON null result. */
@Serializable
public data class JsonRpcSuccessResponse(
    override val id: RequestId,
    val result: JsonElement,
    @Required override val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcResponse

/** A failed reply. Use [RequestId.Null] when the error cannot be correlated with a request. */
@Serializable
public data class JsonRpcErrorResponse(
    override val id: RequestId,
    val error: JsonRpcError,
    @Required override val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcResponse

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
