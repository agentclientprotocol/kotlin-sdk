@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * A three-state field for upsert payloads: absent, JSON `null`, or a value.
 *
 * v2 upsert payloads (e.g. `ToolCallUpdate`, the message upserts) give optional fields
 * patch semantics: an omitted field leaves the existing value unchanged, `null` explicitly
 * clears it, and a concrete value replaces it. Kotlin's `T?` collapses the first two states,
 * so such fields are modeled with this type instead.
 *
 * This type intentionally has no [kotlinx.serialization.KSerializer]: whether a key is
 * present on the wire is decided by the containing object, not the field value, and
 * `ACPJson`'s `explicitNulls = false` would strip an explicit `JsonNull` emitted by a field
 * serializer. Structs with [MaybeUndefined] fields use hand-written serializers that decode
 * by key presence (absent → [Undefined], `null` → [Null]) and encode by skipping [Undefined]
 * and emitting `JsonNull` for [Null].
 */
@UnstableApi
public sealed class MaybeUndefined<out T> {
    /**
     * The field is not present; an existing value is left unchanged.
     */
    public data object Undefined : MaybeUndefined<Nothing>()

    /**
     * The field is present with a JSON `null` value; an existing value is cleared.
     */
    public data object Null : MaybeUndefined<Nothing>()

    /**
     * The field is present with a non-null value; it replaces an existing value.
     */
    public data class Value<out T>(public val value: T) : MaybeUndefined<T>()

    /**
     * The contained value, or `null` if the field is [Undefined] or [Null].
     */
    public fun valueOrNull(): T? = when (this) {
        is Value -> value
        Undefined, Null -> null
    }

    /**
     * Transforms the contained value; [Undefined] and [Null] pass through unchanged.
     */
    public inline fun <R> map(transform: (T) -> R): MaybeUndefined<R> = when (this) {
        is Value -> Value(transform(value))
        Undefined -> Undefined
        Null -> Null
    }
}

/**
 * Applies this field's patch semantics to a previous value: [MaybeUndefined.Undefined]
 * keeps [previous], [MaybeUndefined.Null] clears it, and [MaybeUndefined.Value] replaces it.
 */
@UnstableApi
public fun <T> MaybeUndefined<T>.update(previous: T?): T? = when (this) {
    is MaybeUndefined.Value -> value
    MaybeUndefined.Undefined -> previous
    MaybeUndefined.Null -> null
}

/**
 * This patch state unless it is [MaybeUndefined.Undefined], in which case [previous] is kept.
 *
 * Unlike [update], an explicit clear stays [MaybeUndefined.Null] so callers can decide how to
 * render an explicitly cleared value (mirrors Rust's `apply_update` field handling).
 */
@UnstableApi
internal fun <T> MaybeUndefined<T>.orPrevious(previous: MaybeUndefined<T>): MaybeUndefined<T> =
    if (this is MaybeUndefined.Undefined) previous else this

// Shared building blocks for the hand-written serializers of structs with [MaybeUndefined]
// fields. Field semantics mirror the Rust schema's serde attributes:
// `#[serde(default, skip_serializing_if = "MaybeUndefined::is_undefined")]` plus
// `DefaultOnError` (and `VecSkipError` for collections).

/**
 * Decodes a tri-state field: absent key → [MaybeUndefined.Undefined], JSON `null` →
 * [MaybeUndefined.Null], otherwise the decoded value.
 *
 * A present-but-malformed value degrades to [MaybeUndefined.Undefined] instead of failing,
 * mirroring Rust's `DefaultOnError` on every `MaybeUndefined` field of the v2 schema.
 */
@OptIn(UnstableApi::class)
internal fun <T> JsonObject.decodeMaybeUndefined(
    json: Json,
    key: String,
    deserializer: DeserializationStrategy<T>,
): MaybeUndefined<T> {
    val element = this[key] ?: return MaybeUndefined.Undefined
    if (element is JsonNull) return MaybeUndefined.Null
    return try {
        MaybeUndefined.Value(json.decodeFromJsonElement(deserializer, element))
    } catch (_: Exception) {
        // DefaultOnError parity: kotlinx tree decoding reports malformed shapes through a
        // variety of RuntimeExceptions, not just SerializationException.
        MaybeUndefined.Undefined
    }
}

/**
 * Decodes a tri-state collection field like [decodeMaybeUndefined], with Rust's `VecSkipError`
 * semantics on top: items that fail to decode are skipped instead of failing the field, and a
 * present-but-non-array value degrades to [MaybeUndefined.Undefined].
 */
@OptIn(UnstableApi::class)
internal fun <T> JsonObject.decodeMaybeUndefinedList(
    json: Json,
    key: String,
    itemDeserializer: DeserializationStrategy<T>,
): MaybeUndefined<List<T>> {
    val element = this[key] ?: return MaybeUndefined.Undefined
    if (element is JsonNull) return MaybeUndefined.Null
    val array = element as? JsonArray ?: return MaybeUndefined.Undefined
    return MaybeUndefined.Value(
        array.mapNotNull { item ->
            try {
                json.decodeFromJsonElement(itemDeserializer, item)
            } catch (_: Exception) {
                // VecSkipError parity; see decodeMaybeUndefined for why the catch is broad.
                null
            }
        }
    )
}

/**
 * Encodes a tri-state field: [MaybeUndefined.Undefined] emits nothing, [MaybeUndefined.Null]
 * emits an explicit JSON `null` (building the [JsonObject] directly bypasses `ACPJson`'s
 * `explicitNulls = false`), and [MaybeUndefined.Value] emits the encoded value.
 */
@OptIn(UnstableApi::class)
internal fun <T> JsonObjectBuilder.putMaybeUndefined(
    json: Json,
    key: String,
    value: MaybeUndefined<T>,
    serializer: SerializationStrategy<T>,
) {
    when (value) {
        MaybeUndefined.Undefined -> {}
        MaybeUndefined.Null -> put(key, JsonNull)
        is MaybeUndefined.Value -> put(key, json.encodeToJsonElement(serializer, value.value))
    }
}
