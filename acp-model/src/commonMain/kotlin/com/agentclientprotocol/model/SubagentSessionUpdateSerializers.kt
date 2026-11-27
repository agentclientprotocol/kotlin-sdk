@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Serializers of the subagent session updates. Field semantics follow the Rust schema's serde attributes:
 * `DefaultOnError` fields decode a malformed value as absent, and `cwd` and `additionalDirectories` are
 * strict, decoded by the `decodeStrict*` helpers. A required field that is missing or malformed fails the update.
 */

internal object SubagentUpdateSerializer : JsonObjectSerializer<SessionUpdate.SubagentUpdate>(
    "com.agentclientprotocol.model.SessionUpdate.SubagentUpdate",
) {
    override fun decode(json: Json, obj: JsonObject): SessionUpdate.SubagentUpdate = SessionUpdate.SubagentUpdate(
        sessionId = SessionId(obj.requiredString("sessionId")),
        title = obj.decodeMaybeUndefinedString("title") { it },
        description = obj.decodeMaybeUndefinedString("description") { it },
        capabilities = obj.decodeMaybeUndefined(json, "capabilities", SubagentSessionCapabilities.serializer()),
        state = obj.decodeMaybeUndefined(json, "state", StateUpdate.serializer()),
        cwd = obj.decodeStrictMaybeUndefinedString("cwd"),
        additionalDirectories = obj.decodeStrictMaybeUndefinedStringList("additionalDirectories"),
        _meta = obj.decodeMaybeUndefinedMeta(),
    )

    override fun encode(json: Json, value: SessionUpdate.SubagentUpdate): JsonObject = buildJsonObject {
        put("sessionId", JsonPrimitive(value.sessionId.value))
        putMaybeUndefined(json, "title", value.title, String.serializer())
        putMaybeUndefined(json, "description", value.description, String.serializer())
        putMaybeUndefined(json, "capabilities", value.capabilities, SubagentSessionCapabilities.serializer())
        putMaybeUndefined(json, "state", value.state, StateUpdate.serializer())
        putMaybeUndefined(json, "cwd", value.cwd, String.serializer())
        putMaybeUndefined(
            json,
            "additionalDirectories",
            value.additionalDirectories,
            ListSerializer(String.serializer()),
        )
        putMaybeUndefined(json, "_meta", value._meta, JsonElement.serializer())
    }
}

internal object SessionMessageSerializer : JsonObjectSerializer<SessionUpdate.SessionMessage>(
    "com.agentclientprotocol.model.SessionUpdate.SessionMessage",
) {
    override fun decode(json: Json, obj: JsonObject): SessionUpdate.SessionMessage = SessionUpdate.SessionMessage(
        messageId = MessageId(obj.requiredString("messageId")),
        senderSessionId = obj.decodeMaybeUndefinedString("senderSessionId", ::SessionId),
        recipientSessionId = obj.decodeMaybeUndefinedString("recipientSessionId", ::SessionId),
        senderMessageId = obj.decodeMaybeUndefinedString("senderMessageId", ::MessageId),
        recipientMessageId = obj.decodeMaybeUndefinedString("recipientMessageId", ::MessageId),
        content = obj.decodeMaybeUndefinedList(json, "content", ContentBlock.serializer()),
        _meta = obj.decodeMaybeUndefinedMeta(),
    )

    override fun encode(json: Json, value: SessionUpdate.SessionMessage): JsonObject = buildJsonObject {
        put("messageId", JsonPrimitive(value.messageId.value))
        putMaybeUndefined(json, "senderSessionId", value.senderSessionId, SessionId.serializer())
        putMaybeUndefined(json, "recipientSessionId", value.recipientSessionId, SessionId.serializer())
        putMaybeUndefined(json, "senderMessageId", value.senderMessageId, MessageId.serializer())
        putMaybeUndefined(json, "recipientMessageId", value.recipientMessageId, MessageId.serializer())
        putMaybeUndefined(json, "content", value.content, ListSerializer(ContentBlock.serializer()))
        putMaybeUndefined(json, "_meta", value._meta, JsonElement.serializer())
    }
}

internal object SessionMessageChunkSerializer : JsonObjectSerializer<SessionUpdate.SessionMessageChunk>(
    "com.agentclientprotocol.model.SessionUpdate.SessionMessageChunk",
) {
    override fun decode(json: Json, obj: JsonObject): SessionUpdate.SessionMessageChunk {
        val content = obj["content"] as? JsonObject
            ?: throw SerializationException("'content' must be a content block object in ${descriptor.serialName}")
        return SessionUpdate.SessionMessageChunk(
            messageId = MessageId(obj.requiredString("messageId")),
            content = json.decodeFromJsonElement(ContentBlock.serializer(), content),
            _meta = obj.lenientMeta(),
        )
    }

    override fun encode(json: Json, value: SessionUpdate.SessionMessageChunk): JsonObject = buildJsonObject {
        put("messageId", JsonPrimitive(value.messageId.value))
        put("content", json.encodeToJsonElement(ContentBlock.serializer(), value.content))
        value._meta?.let { put("_meta", it) }
    }
}

private fun JsonObject.requiredString(key: String): String =
    this[key].stringOrNull() ?: throw SerializationException("'$key' must be a string")

/**
 * Decodes a tri-state string field where a value that is not a JSON string decodes as absent.
 *
 * The check is explicit because `ACPJson` is lenient and would read `false` or `42` as text.
 */
private fun <T> JsonObject.decodeMaybeUndefinedString(key: String, wrap: (String) -> T): MaybeUndefined<T> =
    when (val element = this[key]) {
        null -> MaybeUndefined.Undefined
        is JsonNull -> MaybeUndefined.Null
        else -> element.stringOrNull()?.let { MaybeUndefined.Value(wrap(it)) } ?: MaybeUndefined.Undefined
    }

/**
 * Decodes a tri-state string field where a value that is not a JSON string fails the update.
 */
private fun JsonObject.decodeStrictMaybeUndefinedString(key: String): MaybeUndefined<String> =
    when (val element = this[key]) {
        null -> MaybeUndefined.Undefined
        is JsonNull -> MaybeUndefined.Null
        else -> MaybeUndefined.Value(element.stringOrNull() ?: throw SerializationException("'$key' must be a string"))
    }

/**
 * Decodes a tri-state string list field where a non-array value, or any item that is not a JSON string,
 * fails the update.
 */
private fun JsonObject.decodeStrictMaybeUndefinedStringList(key: String): MaybeUndefined<List<String>> =
    when (val element = this[key]) {
        null -> MaybeUndefined.Undefined
        is JsonNull -> MaybeUndefined.Null
        is JsonArray -> MaybeUndefined.Value(
            element.map { it.stringOrNull() ?: throw SerializationException("'$key' must contain only strings") }
        )
        else -> throw SerializationException("'$key' must be an array of strings")
    }

/**
 * Decodes a tri-state `_meta` where a value that is not a JSON object decodes as absent.
 */
private fun JsonObject.decodeMaybeUndefinedMeta(): MaybeUndefined<JsonElement> =
    when (val element = this["_meta"]) {
        null -> MaybeUndefined.Undefined
        is JsonNull -> MaybeUndefined.Null
        is JsonObject -> MaybeUndefined.Value(element)
        else -> MaybeUndefined.Undefined
    }
