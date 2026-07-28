@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Role as V1Role
import com.agentclientprotocol.model.v2.Role

/**
 * Converts this v2 role to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [Role.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun Role.toV1(): V1Role = when (this) {
    Role.Assistant -> V1Role.ASSISTANT
    Role.User -> V1Role.USER
    is Role.Unknown -> throw unknownV2EnumVariant("Role", value)
}

/**
 * Converts this v1 role to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1Role.toV2(): Role = when (this) {
    V1Role.ASSISTANT -> Role.Assistant
    V1Role.USER -> Role.User
}
