@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionConfigOption as V1SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory as V1SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue as V1SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectGroup as V1SessionConfigSelectGroup
import com.agentclientprotocol.model.SessionConfigSelectOptions as V1SessionConfigSelectOptions
import com.agentclientprotocol.model.v2.SessionConfigKind
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionCategory
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionConfigSelectGroup
import com.agentclientprotocol.model.v2.SessionConfigSelectOptions

/**
 * Converts this v2 category to its v1 equivalent.
 *
 * This conversion is total: the v1 type is an open string wrapper, so every v2 value —
 * including [SessionConfigOptionCategory.Unknown] — has a v1 representation.
 */
@UnstableApi
public fun SessionConfigOptionCategory.toV1(): V1SessionConfigOptionCategory =
    V1SessionConfigOptionCategory(value)

/**
 * Converts this v1 category to its v2 equivalent.
 *
 * This conversion is total: well-known categories map to their v2 variants and any
 * other string maps to [SessionConfigOptionCategory.Unknown].
 */
@UnstableApi
public fun V1SessionConfigOptionCategory.toV2(): SessionConfigOptionCategory = when (value) {
    SessionConfigOptionCategory.Mode.value -> SessionConfigOptionCategory.Mode
    SessionConfigOptionCategory.Model.value -> SessionConfigOptionCategory.Model
    SessionConfigOptionCategory.ModelConfig.value -> SessionConfigOptionCategory.ModelConfig
    SessionConfigOptionCategory.ThoughtLevel.value -> SessionConfigOptionCategory.ThoughtLevel
    else -> SessionConfigOptionCategory.Unknown(value)
}

/**
 * Converts this v2 select group to its v1 equivalent.
 *
 * The v2 `groupId` field maps to v1's `group`.
 */
@UnstableApi
public fun SessionConfigSelectGroup.toV1(): V1SessionConfigSelectGroup = V1SessionConfigSelectGroup(
    group = groupId,
    name = name,
    options = options,
    _meta = _meta,
)

/**
 * Converts this v1 select group to its v2 equivalent.
 *
 * The v1 `group` field maps to v2's `groupId`. v2 requires a group name, so an absent
 * v1 name maps to an empty string.
 */
@UnstableApi
public fun V1SessionConfigSelectGroup.toV2(): SessionConfigSelectGroup = SessionConfigSelectGroup(
    groupId = group,
    name = name.orEmpty(),
    options = options,
    _meta = _meta,
)

/**
 * Converts these v2 select options to their v1 equivalent.
 *
 * This conversion is total: [SessionConfigSelectOptions.Ungrouped] maps to v1's `Flat`.
 */
@UnstableApi
public fun SessionConfigSelectOptions.toV1(): V1SessionConfigSelectOptions = when (this) {
    is SessionConfigSelectOptions.Ungrouped -> V1SessionConfigSelectOptions.Flat(options)
    is SessionConfigSelectOptions.Grouped -> V1SessionConfigSelectOptions.Grouped(groups.map { it.toV1() })
}

/**
 * Converts these v1 select options to their v2 equivalent.
 *
 * This conversion is total: v1's `Flat` maps to [SessionConfigSelectOptions.Ungrouped].
 */
@UnstableApi
public fun V1SessionConfigSelectOptions.toV2(): SessionConfigSelectOptions = when (this) {
    is V1SessionConfigSelectOptions.Flat -> SessionConfigSelectOptions.Ungrouped(options)
    is V1SessionConfigSelectOptions.Grouped -> SessionConfigSelectOptions.Grouped(groups.map { it.toV2() })
}

/**
 * Converts this v2 configuration option to its v1 equivalent.
 *
 * The v2 `configId` field maps to v1's `id`.
 *
 * @throws ProtocolConversionException if [SessionConfigOption.kind] is an
 * [SessionConfigKind.Unknown] payload, which cannot be represented in v1 without
 * data loss
 */
@UnstableApi
public fun SessionConfigOption.toV1(): V1SessionConfigOption = when (val kind = kind) {
    is SessionConfigKind.Select -> V1SessionConfigOption.Select(
        id = configId,
        name = name,
        description = description,
        category = category?.toV1(),
        currentValue = kind.currentValue,
        options = kind.options.toV1(),
        _meta = _meta,
    )
    is SessionConfigKind.Boolean -> V1SessionConfigOption.BooleanOption(
        id = configId,
        name = name,
        description = description,
        category = category?.toV1(),
        currentValue = kind.currentValue,
        _meta = _meta,
    )
    is SessionConfigKind.Unknown -> throw unknownV2EnumVariant("SessionConfigKind", kind.type)
}

/**
 * Converts this v1 configuration option to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation. The v1 `id`
 * field maps to v2's `configId`.
 */
@UnstableApi
public fun V1SessionConfigOption.toV2(): SessionConfigOption = when (this) {
    is V1SessionConfigOption.Select -> SessionConfigOption(
        configId = id,
        name = name,
        description = description,
        category = category?.toV2(),
        kind = SessionConfigKind.Select(currentValue = currentValue, options = options.toV2()),
        _meta = _meta,
    )
    is V1SessionConfigOption.BooleanOption -> SessionConfigOption(
        configId = id,
        name = name,
        description = description,
        category = category?.toV2(),
        kind = SessionConfigKind.Boolean(currentValue = currentValue),
        _meta = _meta,
    )
}

/**
 * Converts this v2 option value to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [SessionConfigOptionValue.Unknown]
 * value, which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun SessionConfigOptionValue.toV1(): V1SessionConfigOptionValue = when (this) {
    is SessionConfigOptionValue.Id -> V1SessionConfigOptionValue.StringValue(value.value)
    is SessionConfigOptionValue.Boolean -> V1SessionConfigOptionValue.BoolValue(value)
    is SessionConfigOptionValue.Unknown -> throw unknownV2EnumVariant("SessionConfigOptionValue", type)
}

/**
 * Converts this v1 option value to its v2 equivalent.
 *
 * @throws ProtocolConversionException if this is a
 * [V1SessionConfigOptionValue.UnknownValue] — its raw untagged payload has no faithful
 * representation in v2's tagged value objects
 */
@UnstableApi
public fun V1SessionConfigOptionValue.toV2(): SessionConfigOptionValue = when (this) {
    is V1SessionConfigOptionValue.StringValue -> SessionConfigOptionValue.Id(SessionConfigValueId(value))
    is V1SessionConfigOptionValue.BoolValue -> SessionConfigOptionValue.Boolean(value)
    is V1SessionConfigOptionValue.UnknownValue ->
        throw removedV1EnumVariant("SessionConfigOptionValue", "UnknownValue")
}
