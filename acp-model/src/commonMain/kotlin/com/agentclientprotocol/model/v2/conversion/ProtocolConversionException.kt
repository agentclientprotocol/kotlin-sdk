package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi

/**
 * Thrown when converting between the v1 and v2 protocol type namespaces fails because a
 * value cannot be represented in the target version without data loss.
 *
 * For example, a v2 open-enum [Unknown][com.agentclientprotocol.model.v2.ToolCallStatus.Unknown]
 * value has no v1 equivalent, so v2 → v1 conversion fails instead of silently dropping it.
 */
@UnstableApi
public class ProtocolConversionException(message: String) : RuntimeException(message)

/**
 * Creates the error for a v2 open-enum value that has no v1 representation.
 *
 * The message format matches the reference Rust implementation.
 */
@UnstableApi
internal fun unknownV2EnumVariant(typeName: String, value: String): ProtocolConversionException =
    ProtocolConversionException("v2 $typeName variant `$value` cannot be represented in v1")

/**
 * Creates the error for a v1 value whose variant was removed in v2.
 *
 * The message format matches the reference Rust implementation.
 */
@UnstableApi
internal fun removedV1EnumVariant(typeName: String, value: String): ProtocolConversionException =
    ProtocolConversionException("v1 $typeName variant `$value` cannot be represented in v2")
