@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.TitledMultiSelectItems
import com.agentclientprotocol.model.UntitledMultiSelectItems
import com.agentclientprotocol.model.ElicitationAction as V1ElicitationAction
import com.agentclientprotocol.model.ElicitationPropertySchema as V1ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationSchema as V1ElicitationSchema
import com.agentclientprotocol.model.MultiSelectItems as V1MultiSelectItems
import com.agentclientprotocol.model.StringFormat as V1StringFormat
import com.agentclientprotocol.model.v2.ElicitationAction
import com.agentclientprotocol.model.v2.ElicitationPropertySchema
import com.agentclientprotocol.model.v2.ElicitationSchema
import com.agentclientprotocol.model.v2.MultiSelectItems
import com.agentclientprotocol.model.v2.StringFormat

/**
 * Converts this v2 format to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [StringFormat.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun StringFormat.toV1(): V1StringFormat = when (this) {
    StringFormat.Email -> V1StringFormat.EMAIL
    StringFormat.Uri -> V1StringFormat.URI
    StringFormat.Date -> V1StringFormat.DATE
    StringFormat.DateTime -> V1StringFormat.DATE_TIME
    is StringFormat.Unknown -> throw unknownV2EnumVariant("StringFormat", value)
}

/**
 * Converts this v1 format to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1StringFormat.toV2(): StringFormat = when (this) {
    V1StringFormat.EMAIL -> StringFormat.Email
    V1StringFormat.URI -> StringFormat.Uri
    V1StringFormat.DATE -> StringFormat.Date
    V1StringFormat.DATE_TIME -> StringFormat.DateTime
}

/**
 * Converts these v2 multi-select items to their v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [MultiSelectItems.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun MultiSelectItems.toV1(): V1MultiSelectItems = when (this) {
    is MultiSelectItems.StringItems ->
        V1MultiSelectItems.Untitled(UntitledMultiSelectItems(values = values))
    is MultiSelectItems.Titled ->
        V1MultiSelectItems.Titled(TitledMultiSelectItems(options = options))
    is MultiSelectItems.Unknown -> throw unknownV2EnumVariant("MultiSelectItems", type)
}

/**
 * Converts these v1 multi-select items to their v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1MultiSelectItems.toV2(): MultiSelectItems = when (this) {
    is V1MultiSelectItems.Untitled -> MultiSelectItems.StringItems(values = items.values)
    is V1MultiSelectItems.Titled -> MultiSelectItems.Titled(options = items.options)
}

/**
 * Converts this v2 property schema to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [ElicitationPropertySchema.Unknown]
 * schema, or a nested value ([StringFormat.Unknown], [MultiSelectItems.Unknown]) has no
 * v1 representation
 */
@UnstableApi
public fun ElicitationPropertySchema.toV1(): V1ElicitationPropertySchema = when (this) {
    is ElicitationPropertySchema.StringProperty -> V1ElicitationPropertySchema.StringProperty(
        title = title,
        description = description,
        minLength = minLength,
        maxLength = maxLength,
        pattern = pattern,
        format = format?.toV1(),
        default = default,
        enumValues = enumValues,
        oneOf = oneOf,
    )
    is ElicitationPropertySchema.NumberProperty -> V1ElicitationPropertySchema.NumberProperty(
        title = title,
        description = description,
        minimum = minimum,
        maximum = maximum,
        default = default,
    )
    is ElicitationPropertySchema.IntegerProperty -> V1ElicitationPropertySchema.IntegerProperty(
        title = title,
        description = description,
        minimum = minimum,
        maximum = maximum,
        default = default,
    )
    is ElicitationPropertySchema.BooleanProperty -> V1ElicitationPropertySchema.BooleanProperty(
        title = title,
        description = description,
        default = default,
    )
    is ElicitationPropertySchema.ArrayProperty -> V1ElicitationPropertySchema.ArrayProperty(
        title = title,
        description = description,
        minItems = minItems,
        maxItems = maxItems,
        items = items.toV1(),
        default = default,
    )
    is ElicitationPropertySchema.Unknown -> throw unknownV2EnumVariant("ElicitationPropertySchema", type)
}

/**
 * Converts this v1 property schema to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ElicitationPropertySchema.toV2(): ElicitationPropertySchema = when (this) {
    is V1ElicitationPropertySchema.StringProperty -> ElicitationPropertySchema.StringProperty(
        title = title,
        description = description,
        minLength = minLength,
        maxLength = maxLength,
        pattern = pattern,
        format = format?.toV2(),
        default = default,
        enumValues = enumValues,
        oneOf = oneOf,
    )
    is V1ElicitationPropertySchema.NumberProperty -> ElicitationPropertySchema.NumberProperty(
        title = title,
        description = description,
        minimum = minimum,
        maximum = maximum,
        default = default,
    )
    is V1ElicitationPropertySchema.IntegerProperty -> ElicitationPropertySchema.IntegerProperty(
        title = title,
        description = description,
        minimum = minimum,
        maximum = maximum,
        default = default,
    )
    is V1ElicitationPropertySchema.BooleanProperty -> ElicitationPropertySchema.BooleanProperty(
        title = title,
        description = description,
        default = default,
    )
    is V1ElicitationPropertySchema.ArrayProperty -> ElicitationPropertySchema.ArrayProperty(
        title = title,
        description = description,
        minItems = minItems,
        maxItems = maxItems,
        items = items.toV2(),
        default = default,
    )
}

/**
 * Converts this v2 elicitation schema to its v1 equivalent.
 *
 * @throws ProtocolConversionException if a nested property schema has no v1 representation
 */
@UnstableApi
public fun ElicitationSchema.toV1(): V1ElicitationSchema = V1ElicitationSchema(
    type = type,
    title = title,
    properties = properties.mapValues { (_, property) -> property.toV1() },
    required = required,
    description = description,
)

/**
 * Converts this v1 elicitation schema to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ElicitationSchema.toV2(): ElicitationSchema = ElicitationSchema(
    type = type,
    title = title,
    properties = properties.mapValues { (_, property) -> property.toV2() },
    required = required,
    description = description,
)

/**
 * Converts this v2 action to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [ElicitationAction.Unknown] action,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun ElicitationAction.toV1(): V1ElicitationAction = when (this) {
    is ElicitationAction.Accept -> V1ElicitationAction.Accept(content = content)
    is ElicitationAction.Decline -> V1ElicitationAction.Decline
    is ElicitationAction.Cancel -> V1ElicitationAction.Cancel
    is ElicitationAction.Unknown -> throw unknownV2EnumVariant("ElicitationAction", action)
}

/**
 * Converts this v1 action to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ElicitationAction.toV2(): ElicitationAction = when (this) {
    is V1ElicitationAction.Accept -> ElicitationAction.Accept(content = content)
    is V1ElicitationAction.Decline -> ElicitationAction.Decline
    is V1ElicitationAction.Cancel -> ElicitationAction.Cancel
}
