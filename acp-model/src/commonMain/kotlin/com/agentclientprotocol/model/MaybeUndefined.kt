@file:Suppress("unused")

package com.agentclientprotocol.model

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
 * Upsert payloads (e.g. the v2 `ToolCallUpdate` and message upserts, and the v1 subagent
 * session updates) give optional fields patch semantics: an omitted field leaves the existing
 * value unchanged, `null` explicitly clears it, and a concrete value replaces it. Kotlin's
 * `T?` collapses the first two states, so such fields are modeled with this type instead.
 *
 * This type has no [kotlinx.serialization.KSerializer], because whether a key is present is
 * decided by the containing object. A model with [MaybeUndefined] fields needs a hand-written
 * serializer that omits [Undefined] and writes an explicit JSON `null` for [Null].
 */
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

    /**
     * This state unless it is [Undefined], in which case [other].
     *
     * An explicit [Null] wins over [other], so `newer.orElse(older)` folds a patch onto a stored
     * state and keeps an explicit clear visible, as Rust's `apply_update` does. Use [update] to
     * fold onto a plain nullable instead.
     */
    public fun orElse(other: MaybeUndefined<@UnsafeVariance T>): MaybeUndefined<T> =
        if (this is Undefined) other else this
}

/**
 * Applies this field's patch semantics to a previous value: [MaybeUndefined.Undefined]
 * keeps [previous], [MaybeUndefined.Null] clears it, and [MaybeUndefined.Value] replaces it.
 */
public fun <T> MaybeUndefined<T>.update(previous: T?): T? = when (this) {
    is MaybeUndefined.Value -> value
    MaybeUndefined.Undefined -> previous
    MaybeUndefined.Null -> null
}

/*
 * Helpers for the hand-written serializers of models with [MaybeUndefined] fields. They follow the Rust
 * schema's `#[serde(default, skip_serializing_if = "MaybeUndefined::is_undefined")]` with `DefaultOnError`,
 * and `VecSkipError` for lists.
 */

/**
 * Decodes a tri-state field. A malformed value decodes as [MaybeUndefined.Undefined], as with Rust's
 * `DefaultOnError`.
 *
 * `ACPJson` is lenient and decodes a JSON number or boolean as a string, so a string field that must
 * reject them checks the element itself.
 */
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
        // Broad: tree decoding reports malformed shapes through several exception types.
        MaybeUndefined.Undefined
    }
}

/**
 * Decodes a tri-state list field, skipping items that fail to decode, as Rust's `VecSkipError` does.
 * A value that is not an array decodes as [MaybeUndefined.Undefined].
 */
internal fun <T> JsonObject.decodeMaybeUndefinedList(
    json: Json,
    key: String,
    itemDeserializer: DeserializationStrategy<T>,
): MaybeUndefined<List<T>> {
    val element = this[key] ?: return MaybeUndefined.Undefined
    if (element is JsonNull) return MaybeUndefined.Null
    val array = element as? JsonArray ?: return MaybeUndefined.Undefined
    return MaybeUndefined.Value(array.decodeSkippingInvalid(json, itemDeserializer))
}

/**
 * Decodes the items of this array, skipping those that fail to decode, as Rust's `VecSkipError` does.
 */
internal fun <T> JsonArray.decodeSkippingInvalid(
    json: Json,
    itemDeserializer: DeserializationStrategy<T>,
): List<T> = mapNotNull { item ->
    try {
        json.decodeFromJsonElement(itemDeserializer, item)
    } catch (_: Exception) {
        // Broad: tree decoding reports malformed shapes through several exception types.
        null
    }
}

/**
 * Encodes a tri-state field: [MaybeUndefined.Undefined] writes nothing and [MaybeUndefined.Null]
 * writes an explicit JSON `null`.
 */
internal fun <T> JsonObjectBuilder.putMaybeUndefined(
    json: Json,
    key: String,
    value: MaybeUndefined<T>,
    serializer: SerializationStrategy<T>,
) {
    when (value) {
        MaybeUndefined.Undefined -> {}
        // Put into the object directly, so `ACPJson`'s `explicitNulls = false` can't drop it.
        MaybeUndefined.Null -> put(key, JsonNull)
        is MaybeUndefined.Value -> put(key, json.encodeToJsonElement(serializer, value.value))
    }
}
