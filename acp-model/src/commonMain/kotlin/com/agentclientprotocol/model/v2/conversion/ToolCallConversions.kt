@file:Suppress("unused")
@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ToolCallStatus as V1ToolCallStatus
import com.agentclientprotocol.model.ToolKind as V1ToolKind
import com.agentclientprotocol.model.v2.ToolCallStatus
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
