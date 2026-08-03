package com.agentclientprotocol.model.v2.conversion

import kotlinx.serialization.json.JsonElement

/**
 * Thrown when converting between the v1 and v2 protocol type namespaces fails because a
 * value cannot be represented in the target version without data loss.
 *
 * For example, a v2 open-enum [Unknown][com.agentclientprotocol.model.v2.ToolCallStatus.Unknown]
 * value has no v1 equivalent, so v2 → v1 conversion fails instead of silently dropping it.
 */
public class ProtocolConversionException(message: String) : RuntimeException(message)

/**
 * Creates the error for a v2 open-enum value that has no v1 representation.
 *
 * The message format matches the reference Rust implementation.
 */
internal fun unknownV2EnumVariant(typeName: String, value: String): ProtocolConversionException =
    ProtocolConversionException("v2 $typeName variant `$value` cannot be represented in v1")

/**
 * Creates the error for a v1 value whose variant was removed in v2.
 *
 * The message format matches the reference Rust implementation.
 */
internal fun removedV1EnumVariant(typeName: String, value: String): ProtocolConversionException =
    ProtocolConversionException("v1 $typeName variant `$value` cannot be represented in v2")

/**
 * Creates the error for a v1 field that was removed in v2.
 *
 * The message format matches the reference Rust implementation.
 */
internal fun unrepresentableV1Field(typeName: String, field: String): ProtocolConversionException =
    ProtocolConversionException("v1 $typeName.$field cannot be represented in v2")

/**
 * Creates the error for a v2 field that has no v1 counterpart.
 *
 * The message format matches the reference Rust implementation.
 */
internal fun unrepresentableV2Field(typeName: String, field: String): ProtocolConversionException =
    ProtocolConversionException("v2 $typeName.$field cannot be represented in v1")

/**
 * Fails when a v2 capability marker carries `_meta` that the corresponding v1 boolean or
 * synthesized marker cannot hold.
 */
internal fun rejectV2MarkerMeta(typeName: String, field: String, meta: JsonElement?) {
    if (meta != null) {
        throw ProtocolConversionException("v2 $typeName.$field metadata cannot be represented in v1")
    }
}

/**
 * Fails when a v1 capability marker carries `_meta` that the corresponding v2 baseline —
 * which has no marker object to hang it off — cannot hold.
 */
internal fun rejectV1MarkerMeta(typeName: String, field: String, meta: JsonElement?) {
    if (meta != null) {
        throw ProtocolConversionException("v1 $typeName.$field metadata cannot be represented in v2")
    }
}
