package com.agentclientprotocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * One complete JSON-RPC wire value: a single message, a batch, or malformed input.
 * Transports preserve frame boundaries so the protocol can collect batch replies into one array.
 */
public sealed interface TransportFrame {
    /**
     * A single message or malformed value, used either on its own or as a batch member.
     * Nested batches are not supported; an array inside a batch is represented as [Malformed].
     */
    public sealed interface Entry : TransportFrame

    /**
     * One JSON-RPC message, encoded as an object rather than a one-element batch array.
     *
     * @property message The request, notification, or response carried by this entry.
     */
    public data class Single(public val message: JsonRpcMessage) : Entry

    /**
     * A non-empty JSON-RPC array whose members retain their individual parsing outcomes.
     * Malformed members do not discard valid siblings. Even a one-element batch remains an array.
     *
     * @property entries Members in source order, copied from the constructor argument so later
     * changes to the input list do not affect this batch. Members cannot themselves be batches.
     * @throws IllegalArgumentException if the supplied list is empty.
     */
    public class Batch(entries: List<Entry>) : TransportFrame {
        public val entries: List<Entry> = entries.toList()

        init {
            require(this.entries.isNotEmpty()) { "A JSON-RPC batch must not be empty" }
        }

        override fun equals(other: Any?): Boolean = other is Batch && entries == other.entries
        override fun hashCode(): Int = entries.hashCode()
        override fun toString(): String = "Batch($entries)"
    }

    /**
     * Input that cannot be decoded as a valid JSON-RPC message, retained for error handling or relaying.
     * This may represent an entire invalid frame or one invalid member of an otherwise valid batch.
     *
     * @property raw Input text returned unchanged by [toJson]. Parsing preserves the original text
     * for standalone failures; malformed batch members contain re-encoded JSON, not original formatting.
     * @property error The associated protocol error: parsing uses Parse Error (-32700) for input
     * rejected by kotlinx.serialization and Invalid Request (-32600) for invalid JSON-RPC envelopes
     * or batch members.
     * This error is not necessarily sent to the peer; see [isResponse].
     * @property isResponse Whether the invalid value has a response-only shape: an object with
     * `result` or `error` but no `method`. The protocol suppresses replies to these values to avoid
     * responding to malformed responses, following the Rust SDK's handling. This is a shape
     * classification, not confirmation of a valid response; unparseable JSON leaves it false.
     */
    public data class Malformed(
        public val raw: String,
        public val error: JsonRpcError,
        public val isResponse: Boolean = false,
    ) : Entry

    public companion object {
        /**
         * Parses JSON with [Json.parseToJsonElement], then validates the JSON-RPC envelope.
         *
         * Known limitation: kotlinx.serialization accepts some non-standard JSON, including
         * arbitrary unquoted primitive tokens, even with `isLenient = false`. For simplicity,
         * we accept its JSON syntax rules and do not implement custom JSON validation.
         */
        public fun parse(text: String): TransportFrame {
            val value = try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                return Malformed(text, JsonRpcErrorCode.PARSE_ERROR.asError())
            }
            return if (value is JsonArray && value.isNotEmpty()) {
                Batch(value.map { parseEntry(it, it.toString()) })
            } else {
                parseEntry(value, text)
            }
        }
    }
}

/** Encode a complete frame without polymorphic discriminators or implicit null omission. */
public fun TransportFrame.toJson(): String = when (this) {
    is TransportFrame.Single -> message.toWireJson().toString()
    is TransportFrame.Batch -> entries.joinToString(prefix = "[", postfix = "]") { it.toJson() }
    is TransportFrame.Malformed -> raw
}

private fun JsonRpcErrorCode.asError(): JsonRpcError = JsonRpcError(code, message)

private fun parseEntry(value: JsonElement, raw: String): TransportFrame.Entry {
    val obj = value as? JsonObject
    val responseOnly = obj != null && "method" !in obj && ("result" in obj || "error" in obj)
    fun invalid() = TransportFrame.Malformed(raw, JsonRpcErrorCode.INVALID_REQUEST.asError(), responseOnly)
    if (obj == null || obj["jsonrpc"] != JsonPrimitive(JSONRPC_VERSION)) return invalid()
    val id = if ("id" in obj) {
        try {
            ACPJson.decodeFromJsonElement(RequestId.serializer(), obj.getValue("id"))
        } catch (_: SerializationException) {
            return invalid()
        }
    } else null
    if ("method" in obj) {
        val method = obj["method"] as? JsonPrimitive ?: return invalid()
        if (!method.isString || "result" in obj || "error" in obj) return invalid()
        val params = obj["params"]
        if (params != null && params != JsonNull && params !is JsonObject && params !is JsonArray) return invalid()
        return TransportFrame.Single(
            if (id == null) {
                JsonRpcNotification(MethodName(method.content), params)
            } else {
                JsonRpcRequest(id, MethodName(method.content), params)
            }
        )
    }
    if (id == null || ("result" in obj) == ("error" in obj) || "params" in obj) return invalid()
    val error = if ("error" in obj) {
        val errorObj = obj["error"] as? JsonObject ?: return invalid()
        val code = errorObj["code"] as? JsonPrimitive ?: return invalid()
        val message = errorObj["message"] as? JsonPrimitive ?: return invalid()
        if (code.isString || code.intOrNull == null || !message.isString) return invalid()
        JsonRpcError(code.int, message.content, errorObj["data"])
    } else null
    return TransportFrame.Single(JsonRpcResponse(id, obj["result"], error))
}

private fun JsonRpcMessage.toWireJson(): JsonObject = buildJsonObject {
    put("jsonrpc", JSONRPC_VERSION)
    when (val message = this@toWireJson) {
        is JsonRpcRequest -> {
            put("id", ACPJson.encodeToJsonElement(RequestId.serializer(), message.id))
            put("method", message.method.name)
            message.params?.let { put("params", it) }
        }

        is JsonRpcNotification -> {
            put("method", message.method.name)
            message.params?.let { put("params", it) }
        }

        is JsonRpcResponse -> {
            require(message.error == null || message.result == null) { "Response cannot contain both result and error" }
            put("id", ACPJson.encodeToJsonElement(RequestId.serializer(), message.id))
            if (message.error != null) {
                put("error", ACPJson.encodeToJsonElement(JsonRpcError.serializer(), message.error))
            } else {
                put("result", message.result ?: JsonNull)
            }
        }
    }
}
