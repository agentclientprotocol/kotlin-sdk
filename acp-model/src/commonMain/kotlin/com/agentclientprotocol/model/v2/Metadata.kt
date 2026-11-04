package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Applies entity metadata patches without interpreting nested values or changing wire payloads.
 */
@OptIn(UnstableApi::class)
internal fun applyMetaUpdate(
    stored: MaybeUndefined<JsonElement>,
    update: MaybeUndefined<JsonElement>,
): MaybeUndefined<JsonElement> = when (update) {
    MaybeUndefined.Undefined -> stored
    MaybeUndefined.Null -> MaybeUndefined.Null
    is MaybeUndefined.Value -> {
        val patch = update.value
        if (patch is JsonObject) {
            val meta = (stored.valueOrNull() as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            for ((key, value) in patch) {
                if (value is JsonNull) meta.remove(key) else meta[key] = value
            }
            MaybeUndefined.Value(JsonObject(meta))
        } else {
            // Preserve the SDK's existing handling of non-object metadata values.
            update
        }
    }
}
