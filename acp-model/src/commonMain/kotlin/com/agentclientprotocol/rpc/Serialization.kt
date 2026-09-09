package com.agentclientprotocol.rpc

import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * ACP payloads JSON configuration.
 */
public val ACPJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
        serializersModule = SerializersModule {
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
    }
}

/**
 * JSON configuration for JSON-RPC wire envelopes, separate from the lenient ACP payload configuration.
 **/
public val JsonRpcJson: Json = Json(ACPJson) {
    isLenient = false
}

/**
 * Decodes one wire frame, converting JSON syntax failures into [TransportFrame.Malformed].
 * The outer catch also covers trailing-input errors raised after the frame serializer returns.
 * Envelope errors are handled per entry by the serializer, preserving valid batch siblings.
 *
 * Uses kotlinx.serialization's syntax rules, including its acceptance of some non-standard
 * unquoted primitive tokens. No additional lexical validation is performed.
 */
public fun parseTransportFrame(text: String): TransportFrame = try {
    JsonRpcJson.decodeFromString(TransportFrame.serializer(), text)
} catch (_: SerializationException) {
    TransportFrame.Malformed(JsonRpcErrorCode.PARSE_ERROR.asError())
}

public object RequestIdSerializer : KSerializer<RequestId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RequestId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RequestId {
        val jsonDecoder = decoder.jsonDecoder()

        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> RequestId.Null

            is JsonPrimitive -> {
                if (element.isString) {
                    RequestId.StringId(element.content)
                } else {
                    try {
                        RequestId.IntId(element.content.toInt())
                    } catch (e: NumberFormatException) {
                        throw SerializationException("RequestId must be an int or string", e)
                    }
                }
            }

            else -> throw SerializationException("RequestId must be a primitive (int or string)")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: RequestId) {
        when (value) {
            RequestId.Null -> encoder.encodeNull()
            is RequestId.IntId -> encoder.encodeInt(value.value)
            is RequestId.StringId -> encoder.encodeString(value.value)
        }
    }
}


/**
 * Validates the envelope and delegates field encoding/decoding to concrete message serializers.
 **/
public object JsonRpcMessageSerializer : KSerializer<JsonRpcMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.agentclientprotocol.rpc.JsonRpcMessage")

    override fun deserialize(decoder: Decoder): JsonRpcMessage {
        val jsonDecoder = decoder.jsonDecoder()
        val obj = jsonDecoder.decodeJsonElement().envelope()

        if ("method" !in obj) {
            return jsonDecoder.json.decodeFromJsonElement(JsonRpcResponseSerializer, obj)
        }

        requireWire((obj["method"] as? JsonPrimitive)?.isString == true, "method must be a string")
        requireWire("result" !in obj && "error" !in obj, "A call cannot contain result or error")
        validateParams(obj["params"])

        return if ("id" in obj) {
            jsonDecoder.json.decodeFromJsonElement(JsonRpcRequest.serializer(), obj)
        } else {
            jsonDecoder.json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj)
        }
    }

    override fun serialize(encoder: Encoder, value: JsonRpcMessage) {
        requireWire(value.jsonrpc == JSONRPC_VERSION, "jsonrpc must be 2.0")
        when (value) {
            is JsonRpcRequest -> {
                validateParams(value.params)
                encoder.encodeSerializableValue(JsonRpcRequest.serializer(), value)
            }
            is JsonRpcNotification -> {
                validateParams(value.params)
                encoder.encodeSerializableValue(JsonRpcNotification.serializer(), value)
            }
            is JsonRpcResponse -> encoder.encodeSerializableValue(JsonRpcResponseSerializer, value)
        }
    }
}

/**
 * Enforces the result/error distinction even when a response is decoded without a frame.
 **/
public object JsonRpcResponseSerializer : KSerializer<JsonRpcResponse> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.agentclientprotocol.rpc.JsonRpcResponse")

    override fun deserialize(decoder: Decoder): JsonRpcResponse {
        val jsonDecoder = decoder.jsonDecoder()
        val obj = jsonDecoder.decodeJsonElement().envelope()

        requireWire("id" in obj, "A response must contain id")
        requireWire("method" !in obj && "params" !in obj, "A response cannot contain method or params")
        requireWire(("result" in obj) != ("error" in obj), "A response must contain exactly one of result or error")

        return if ("error" in obj) {
            val error = obj["error"] as? JsonObject ?: throw SerializationException("error must be an object")
            val code = error["code"] as? JsonPrimitive

            requireWire(code != null && !code.isString && code.intOrNull != null, "error.code must be an integer")
            requireWire((error["message"] as? JsonPrimitive)?.isString == true, "error.message must be a string")

            jsonDecoder.json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), obj)
        } else {
            jsonDecoder.json.decodeFromJsonElement(JsonRpcSuccessResponse.serializer(), obj)
        }
    }

    override fun serialize(encoder: Encoder, value: JsonRpcResponse) {
        requireWire(value.jsonrpc == JSONRPC_VERSION, "jsonrpc must be 2.0")
        when (value) {
            is JsonRpcSuccessResponse -> encoder.encodeSerializableValue(JsonRpcSuccessResponse.serializer(), value)
            is JsonRpcErrorResponse -> encoder.encodeSerializableValue(JsonRpcErrorResponse.serializer(), value)
        }
    }
}

/**
 * Preserves frame shape and isolates invalid entries; malformed outcomes are receive-only.
 **/
public object TransportFrameSerializer : KSerializer<TransportFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.agentclientprotocol.rpc.TransportFrame")

    override fun deserialize(decoder: Decoder): TransportFrame {
        val jsonDecoder = decoder.jsonDecoder()
        val value = jsonDecoder.decodeJsonElement()

        return if (value is JsonArray && value.isNotEmpty()) {
            TransportFrame.Batch(value.map { decodeEntry(jsonDecoder.json, it) })
        } else {
            decodeEntry(jsonDecoder.json, value)
        }
    }

    private fun decodeEntry(json: Json, value: JsonElement): TransportFrame.Entry = try {
        TransportFrame.Single(json.decodeFromJsonElement(JsonRpcMessageSerializer, value))
    } catch (_: SerializationException) {
        val isResponse = value is JsonObject && "method" !in value && ("result" in value || "error" in value)
        TransportFrame.Malformed(JsonRpcErrorCode.INVALID_REQUEST.asError(), isResponse = isResponse)
    }

    override fun serialize(encoder: Encoder, value: TransportFrame) {
        val jsonEncoder = encoder.jsonEncoder()

        // Assemble the complete JSON value before writing, so an invalid member cannot emit a partial batch.
        val element = when (value) {
            is TransportFrame.Single -> jsonEncoder.json.encodeToJsonElement(JsonRpcMessageSerializer, value.message)
            is TransportFrame.Batch -> JsonArray(value.entries.map { jsonEncoder.json.encodeToJsonElement(this, it) })
            is TransportFrame.Malformed -> throw SerializationException("Cannot serialize a malformed transport frame")
        }
        jsonEncoder.encodeJsonElement(element)
    }
}

private fun Decoder.jsonDecoder(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("JSON-RPC Decoder must be a JsonDecoder")

private fun Encoder.jsonEncoder(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("JSON-RPC Encoder must be a JsonEncoder")

private fun JsonElement.envelope(): JsonObject {
    val obj = this as? JsonObject ?: throw SerializationException("A JSON-RPC message must be an object")
    requireWire(obj["jsonrpc"] == JsonPrimitive(JSONRPC_VERSION), "jsonrpc must be 2.0")
    return obj
}

private fun validateParams(params: JsonElement?) {
    requireWire(params == null || params == JsonNull || params is JsonObject || params is JsonArray,
        "params must be an object, array or null")
}

private fun requireWire(condition: Boolean, message: String) {
    if (!condition) throw SerializationException(message)
}

private fun JsonRpcErrorCode.asError(): JsonRpcError = JsonRpcError(code, message)
