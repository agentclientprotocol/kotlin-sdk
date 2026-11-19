package com.agentclientprotocol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

/**
 * How the agent combines a client-provided system prompt with its own instructions.
 *
 * Well-known modes are provided as constants. Custom and future modes are supported via the constructor,
 * matching the protocol's open string-based design: values beginning with `_` are reserved for
 * implementation-specific modes, and other unknown values for future ACP modes.
 */
@JvmInline
@Serializable
public value class SystemPromptMode(public val value: String) {
    override fun toString(): String = value

    public companion object {
        /**
         * Adds the content to the agent's own instructions. Every agent that advertises
         * `sessionCapabilities.systemPrompt` supports it.
         */
        public val APPEND: SystemPromptMode = SystemPromptMode("append")

        /**
         * Replaces the agent's built-in default instructions. Only for agents that advertise
         * `sessionCapabilities.systemPrompt.mode.override`.
         */
        public val OVERRIDE: SystemPromptMode = SystemPromptMode("override")
    }
}

/**
 * Client-provided system-level instructions for a session.
 *
 * Clients must only send this when the agent advertises `sessionCapabilities.systemPrompt`, and must not send
 * an empty [content] list. Only [ContentBlock.Text] blocks are currently supported.
 *
 * See protocol docs: [Session Setup](https://agentclientprotocol.com/protocol/session-setup)
 *
 * @property mode how the agent combines [content] with its own instructions
 */
@Serializable
public data class SystemPrompt(
    val mode: SystemPromptMode,
    val content: List<ContentBlock>,
    override val _meta: JsonElement? = null
) : AcpWithMeta
