@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.Serializable

/**
 * The sender or recipient of messages and data in a conversation.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = RoleSerializer::class)
public sealed class Role {
    /**
     * The wire-format string for this role.
     */
    public abstract val value: String

    /**
     * The assistant side of a conversation.
     */
    public data object Assistant : Role() {
        override val value: String = "assistant"
    }

    /**
     * The user side of a conversation.
     */
    public data object User : Role() {
        override val value: String = "user"
    }

    /**
     * Custom or future role.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown role SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : Role()

    public companion object {
        /**
         * Creates an implementation-specific extension role.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object RoleSerializer : OpenStringEnumSerializer<Role>(
    serialName = "com.agentclientprotocol.model.v2.Role",
    knownValues = listOf(
        Role.Assistant,
        Role.User,
    ),
    wireValue = Role::value,
    unknown = Role::Unknown,
)
