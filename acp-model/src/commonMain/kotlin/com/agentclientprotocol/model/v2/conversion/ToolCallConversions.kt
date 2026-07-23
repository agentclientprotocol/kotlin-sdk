@file:Suppress("unused")
@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate
import com.agentclientprotocol.model.ToolCallContent as V1ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus as V1ToolCallStatus
import com.agentclientprotocol.model.ToolKind as V1ToolKind
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.ToolCallContent
import com.agentclientprotocol.model.v2.ToolCallStatus
import com.agentclientprotocol.model.v2.ToolCallUpdate
import com.agentclientprotocol.model.v2.ToolKind

/**
 * Converts this v2 kind to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [ToolKind.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun ToolKind.toV1(): V1ToolKind = when (this) {
    ToolKind.Read -> V1ToolKind.READ
    ToolKind.Edit -> V1ToolKind.EDIT
    ToolKind.Delete -> V1ToolKind.DELETE
    ToolKind.Move -> V1ToolKind.MOVE
    ToolKind.Search -> V1ToolKind.SEARCH
    ToolKind.Execute -> V1ToolKind.EXECUTE
    ToolKind.Think -> V1ToolKind.THINK
    ToolKind.Fetch -> V1ToolKind.FETCH
    ToolKind.SwitchMode -> V1ToolKind.SWITCH_MODE
    ToolKind.Other -> V1ToolKind.OTHER
    is ToolKind.Unknown -> throw unknownV2EnumVariant("ToolKind", value)
}

/**
 * Converts this v1 kind to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ToolKind.toV2(): ToolKind = when (this) {
    V1ToolKind.READ -> ToolKind.Read
    V1ToolKind.EDIT -> ToolKind.Edit
    V1ToolKind.DELETE -> ToolKind.Delete
    V1ToolKind.MOVE -> ToolKind.Move
    V1ToolKind.SEARCH -> ToolKind.Search
    V1ToolKind.EXECUTE -> ToolKind.Execute
    V1ToolKind.THINK -> ToolKind.Think
    V1ToolKind.FETCH -> ToolKind.Fetch
    V1ToolKind.SWITCH_MODE -> ToolKind.SwitchMode
    V1ToolKind.OTHER -> ToolKind.Other
}

/**
 * Converts this v2 status to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [ToolCallStatus.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun ToolCallStatus.toV1(): V1ToolCallStatus = when (this) {
    ToolCallStatus.Pending -> V1ToolCallStatus.PENDING
    ToolCallStatus.InProgress -> V1ToolCallStatus.IN_PROGRESS
    ToolCallStatus.Completed -> V1ToolCallStatus.COMPLETED
    ToolCallStatus.Failed -> V1ToolCallStatus.FAILED
    is ToolCallStatus.Unknown -> throw unknownV2EnumVariant("ToolCallStatus", value)
}

/**
 * Converts this v1 status to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ToolCallStatus.toV2(): ToolCallStatus = when (this) {
    V1ToolCallStatus.PENDING -> ToolCallStatus.Pending
    V1ToolCallStatus.IN_PROGRESS -> ToolCallStatus.InProgress
    V1ToolCallStatus.COMPLETED -> ToolCallStatus.Completed
    V1ToolCallStatus.FAILED -> ToolCallStatus.Failed
}

// Tool call content crosses versions only as plain content blocks: v2's structured diffs
// and v1's `oldText`/`newText` diffs have no faithful mutual representation, v1's
// `terminal` variant was removed in v2, and v1's `content` variant has no `_meta` field.
// Items that cannot cross are dropped, mirroring the Rust conversion's skip-on-error
// handling of tool call content collections. This is why neither `ToolCallContent` union
// exposes a public conversion.

private fun ToolCallContent.toV1OrNull(): V1ToolCallContent? = when (this) {
    // A v2 content item carrying chunk metadata would lose it in v1, so it does not cross.
    is ToolCallContent.Content -> if (_meta != null) {
        null
    } else {
        try {
            V1ToolCallContent.Content(content = content.toV1())
        } catch (_: ProtocolConversionException) {
            null
        }
    }

    is ToolCallContent.Diff, is ToolCallContent.Unknown -> null
}

private fun V1ToolCallContent.toV2OrNull(): ToolCallContent? = when (this) {
    is V1ToolCallContent.Content -> ToolCallContent.Content(content = content.toV2())
    is V1ToolCallContent.Diff, is V1ToolCallContent.Terminal -> null
}

/**
 * Converts this v2 tool call upsert to its v1 equivalent.
 *
 * v1 has no patch semantics, so the tri-state fields collapse: a value becomes a set field,
 * while both "no update" and an explicit clear become an unset field. Collections are the
 * exception — an explicit clear becomes an empty list, because that is how v1 expresses
 * "no content". Fields whose own conversion fails (an [ToolKind.Unknown] kind, say) are
 * dropped rather than failing the whole update, and content items with no v1 representation
 * are skipped.
 *
 * @throws ProtocolConversionException if [ToolCallUpdate._meta] is an explicit clear, which
 * v1 cannot express
 */
@UnstableApi
public fun ToolCallUpdate.toV1(): V1SessionUpdate.ToolCallUpdate = V1SessionUpdate.ToolCallUpdate(
    toolCallId = toolCallId,
    title = title.valueOrNull(),
    kind = kind.toV1OrNull { it.toV1() },
    status = status.toV1OrNull { it.toV1() },
    content = content.toV1ListOrNull { item -> item.toV1OrNull() },
    locations = locations.toV1ListOrNull { it },
    rawInput = rawInput.valueOrNull(),
    rawOutput = rawOutput.valueOrNull(),
    _meta = when (_meta) {
        is MaybeUndefined.Value -> (_meta as MaybeUndefined.Value).value
        MaybeUndefined.Null -> throw ProtocolConversionException(
            "v2 ToolCallUpdate with null _meta cannot be represented in v1"
        )
        MaybeUndefined.Undefined -> null
    },
)

/**
 * Converts this v1 tool call creation to its v2 equivalent.
 *
 * v2 has no separate creation update: a tool call is created by the first upsert carrying
 * its ID. Values that v1 uses as defaults are left as "no update" so they do not overwrite
 * client defaults — an `other` kind, a `pending` status, and empty collections.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.ToolCall.toV2(): ToolCallUpdate = ToolCallUpdate(
    toolCallId = toolCallId,
    title = MaybeUndefined.Value(title),
    kind = if (kind == null || kind == V1ToolKind.OTHER) {
        MaybeUndefined.Undefined
    } else {
        MaybeUndefined.Value(kind.toV2())
    },
    status = if (status == null || status == V1ToolCallStatus.PENDING) {
        MaybeUndefined.Undefined
    } else {
        MaybeUndefined.Value(status.toV2())
    },
    content = content.toV2ValueIfNotEmpty { it.toV2OrNull() },
    locations = locations.toV2ValueIfNotEmpty { it },
    rawInput = rawInput.toV2MaybeUndefined(),
    rawOutput = rawOutput.toV2MaybeUndefined(),
    _meta = _meta.toV2MaybeUndefined(),
)

/**
 * Converts this v1 tool call update to its v2 equivalent.
 *
 * An unset v1 field becomes "no update" rather than an explicit clear, since v1 cannot
 * distinguish the two. Content items with no v2 representation are skipped.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.ToolCallUpdate.toV2(): ToolCallUpdate = ToolCallUpdate(
    toolCallId = toolCallId,
    title = title.toV2MaybeUndefined(),
    kind = kind?.let { MaybeUndefined.Value(it.toV2()) } ?: MaybeUndefined.Undefined,
    status = status?.let { MaybeUndefined.Value(it.toV2()) } ?: MaybeUndefined.Undefined,
    content = content?.let { items -> MaybeUndefined.Value(items.mapNotNull { it.toV2OrNull() }) }
        ?: MaybeUndefined.Undefined,
    locations = locations?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
    rawInput = rawInput.toV2MaybeUndefined(),
    rawOutput = rawOutput.toV2MaybeUndefined(),
    _meta = _meta.toV2MaybeUndefined(),
)

/**
 * A v1 optional value as a v2 patch state. An absent v1 value is "no update", never an
 * explicit clear — v1 cannot express the difference.
 */
internal fun <T> T?.toV2MaybeUndefined(): MaybeUndefined<T> =
    if (this == null) MaybeUndefined.Undefined else MaybeUndefined.Value(this)

/**
 * A v2 patch state as a v1 optional value, dropping values whose conversion fails.
 */
private inline fun <T, R> MaybeUndefined<T>.toV1OrNull(convert: (T) -> R): R? = when (this) {
    is MaybeUndefined.Value -> try {
        convert(value)
    } catch (_: ProtocolConversionException) {
        null
    }

    MaybeUndefined.Null, MaybeUndefined.Undefined -> null
}

/**
 * A v2 patch state over a collection as a v1 optional list: an explicit clear becomes an
 * empty list, "no update" becomes absent, and items that cannot cross are skipped.
 */
private inline fun <T, R> MaybeUndefined<List<T>>.toV1ListOrNull(convert: (T) -> R?): List<R>? =
    when (this) {
        is MaybeUndefined.Value -> value.mapNotNull(convert)
        MaybeUndefined.Null -> emptyList()
        MaybeUndefined.Undefined -> null
    }

/**
 * A v1 collection as a v2 patch state: an empty v1 collection is "no update", so it does not
 * clear whatever the receiver already has.
 */
private inline fun <T, R> List<T>.toV2ValueIfNotEmpty(convert: (T) -> R?): MaybeUndefined<List<R>> =
    if (isEmpty()) MaybeUndefined.Undefined else MaybeUndefined.Value(mapNotNull(convert))
