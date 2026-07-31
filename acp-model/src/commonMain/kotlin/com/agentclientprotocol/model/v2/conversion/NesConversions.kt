@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.NesDiagnosticSeverity as V1NesDiagnosticSeverity
import com.agentclientprotocol.model.NesRejectReason as V1NesRejectReason
import com.agentclientprotocol.model.NesSuggestion as V1NesSuggestion
import com.agentclientprotocol.model.NesTriggerKind as V1NesTriggerKind
import com.agentclientprotocol.model.v2.NesDiagnosticSeverity
import com.agentclientprotocol.model.v2.NesRejectReason
import com.agentclientprotocol.model.v2.NesSuggestion
import com.agentclientprotocol.model.v2.NesTriggerKind

/**
 * Converts this v2 trigger kind to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [NesTriggerKind.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun NesTriggerKind.toV1(): V1NesTriggerKind = when (this) {
    NesTriggerKind.Automatic -> V1NesTriggerKind.AUTOMATIC
    NesTriggerKind.Diagnostic -> V1NesTriggerKind.DIAGNOSTIC
    NesTriggerKind.Manual -> V1NesTriggerKind.MANUAL
    is NesTriggerKind.Unknown -> throw unknownV2EnumVariant("NesTriggerKind", value)
}

/**
 * Converts this v1 trigger kind to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1NesTriggerKind.toV2(): NesTriggerKind = when (this) {
    V1NesTriggerKind.AUTOMATIC -> NesTriggerKind.Automatic
    V1NesTriggerKind.DIAGNOSTIC -> NesTriggerKind.Diagnostic
    V1NesTriggerKind.MANUAL -> NesTriggerKind.Manual
}

/**
 * Converts this v2 reject reason to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [NesRejectReason.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun NesRejectReason.toV1(): V1NesRejectReason = when (this) {
    NesRejectReason.Rejected -> V1NesRejectReason.REJECTED
    NesRejectReason.Ignored -> V1NesRejectReason.IGNORED
    NesRejectReason.Replaced -> V1NesRejectReason.REPLACED
    NesRejectReason.Cancelled -> V1NesRejectReason.CANCELLED
    is NesRejectReason.Unknown -> throw unknownV2EnumVariant("NesRejectReason", value)
}

/**
 * Converts this v1 reject reason to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1NesRejectReason.toV2(): NesRejectReason = when (this) {
    V1NesRejectReason.REJECTED -> NesRejectReason.Rejected
    V1NesRejectReason.IGNORED -> NesRejectReason.Ignored
    V1NesRejectReason.REPLACED -> NesRejectReason.Replaced
    V1NesRejectReason.CANCELLED -> NesRejectReason.Cancelled
}

/**
 * Converts this v2 severity to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [NesDiagnosticSeverity.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun NesDiagnosticSeverity.toV1(): V1NesDiagnosticSeverity = when (this) {
    NesDiagnosticSeverity.Error -> V1NesDiagnosticSeverity.ERROR
    NesDiagnosticSeverity.Warning -> V1NesDiagnosticSeverity.WARNING
    NesDiagnosticSeverity.Information -> V1NesDiagnosticSeverity.INFORMATION
    NesDiagnosticSeverity.Hint -> V1NesDiagnosticSeverity.HINT
    is NesDiagnosticSeverity.Unknown -> throw unknownV2EnumVariant("NesDiagnosticSeverity", value)
}

/**
 * Converts this v1 severity to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1NesDiagnosticSeverity.toV2(): NesDiagnosticSeverity = when (this) {
    V1NesDiagnosticSeverity.ERROR -> NesDiagnosticSeverity.Error
    V1NesDiagnosticSeverity.WARNING -> NesDiagnosticSeverity.Warning
    V1NesDiagnosticSeverity.INFORMATION -> NesDiagnosticSeverity.Information
    V1NesDiagnosticSeverity.HINT -> NesDiagnosticSeverity.Hint
}

/**
 * Converts this v2 suggestion to its v1 equivalent.
 *
 * The v2 `suggestionId` field maps to v1's `id`, and an absent `isRegex` maps to
 * v1's `false` default.
 *
 * @throws ProtocolConversionException if this is an [NesSuggestion.Unknown] suggestion,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun NesSuggestion.toV1(): V1NesSuggestion = when (this) {
    is NesSuggestion.Edit -> V1NesSuggestion.Edit(
        id = suggestionId,
        uri = uri,
        edits = edits,
        cursorPosition = cursorPosition,
    )
    is NesSuggestion.Jump -> V1NesSuggestion.Jump(
        id = suggestionId,
        uri = uri,
        position = position,
    )
    is NesSuggestion.Rename -> V1NesSuggestion.Rename(
        id = suggestionId,
        uri = uri,
        position = position,
        newName = newName,
    )
    is NesSuggestion.SearchAndReplace -> V1NesSuggestion.SearchAndReplace(
        id = suggestionId,
        uri = uri,
        search = search,
        replace = replace,
        isRegex = isRegex ?: false,
    )
    is NesSuggestion.Unknown -> throw unknownV2EnumVariant("NesSuggestion", kind)
}

/**
 * Converts this v1 suggestion to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation. The v1 `id`
 * field maps to v2's `suggestionId`.
 */
@UnstableApi
public fun V1NesSuggestion.toV2(): NesSuggestion = when (this) {
    is V1NesSuggestion.Edit -> NesSuggestion.Edit(
        suggestionId = id,
        uri = uri,
        edits = edits,
        cursorPosition = cursorPosition,
    )
    is V1NesSuggestion.Jump -> NesSuggestion.Jump(
        suggestionId = id,
        uri = uri,
        position = position,
    )
    is V1NesSuggestion.Rename -> NesSuggestion.Rename(
        suggestionId = id,
        uri = uri,
        position = position,
        newName = newName,
    )
    is V1NesSuggestion.SearchAndReplace -> NesSuggestion.SearchAndReplace(
        suggestionId = id,
        uri = uri,
        search = search,
        replace = replace,
        isRegex = isRegex,
    )
}
