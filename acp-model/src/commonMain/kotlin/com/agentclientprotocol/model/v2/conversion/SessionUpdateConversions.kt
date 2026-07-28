@file:Suppress("unused")
@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AvailableCommand as V1AvailableCommand
import com.agentclientprotocol.model.AvailableCommandInput as V1AvailableCommandInput
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate
import com.agentclientprotocol.model.v2.AgentMessage
import com.agentclientprotocol.model.v2.AgentThought
import com.agentclientprotocol.model.v2.AvailableCommand
import com.agentclientprotocol.model.v2.AvailableCommandInput
import com.agentclientprotocol.model.v2.AvailableCommandsUpdate
import com.agentclientprotocol.model.v2.ConfigOptionUpdate
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.SessionInfoUpdate
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UsageUpdate
import com.agentclientprotocol.model.v2.UserMessage
import com.agentclientprotocol.model.MessageId
import kotlinx.serialization.json.JsonElement

/**
 * Converts this v2 command input to its v1 equivalent.
 *
 * The v2 `text` discriminator maps to v1's `unstructured`.
 *
 * @throws ProtocolConversionException if this is an [AvailableCommandInput.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun AvailableCommandInput.toV1(): V1AvailableCommandInput = when (this) {
    is AvailableCommandInput.Text -> V1AvailableCommandInput.Unstructured(hint = hint, _meta = _meta)
    is AvailableCommandInput.Unknown -> throw unknownV2EnumVariant("AvailableCommandInput", type)
}

/**
 * Converts this v1 command input to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1AvailableCommandInput.toV2(): AvailableCommandInput = when (this) {
    is V1AvailableCommandInput.Unstructured -> AvailableCommandInput.Text(hint = hint, _meta = _meta)
}

/**
 * Converts this v2 command to its v1 equivalent.
 *
 * @throws ProtocolConversionException if [input] is an [AvailableCommandInput.Unknown] value
 */
@UnstableApi
public fun AvailableCommand.toV1(): V1AvailableCommand = V1AvailableCommand(
    name = name,
    description = description,
    input = input?.toV1(),
    _meta = _meta,
)

/**
 * Converts this v1 command to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1AvailableCommand.toV2(): AvailableCommand = AvailableCommand(
    name = name,
    description = description,
    input = input?.toV2(),
    _meta = _meta,
)

/**
 * Converts this v2 commands update to the v1 session update that carries it.
 *
 * Commands whose own conversion fails are skipped, mirroring the Rust conversion.
 *
 * @throws ProtocolConversionException if [AvailableCommandsUpdate._meta] is set — v1's
 * commands update has no metadata field, so it would be silently dropped
 */
@UnstableApi
public fun AvailableCommandsUpdate.toV1(): V1SessionUpdate.AvailableCommandsUpdate {
    if (_meta != null) {
        throw ProtocolConversionException(
            "v2 AvailableCommandsUpdate with _meta cannot be represented in v1, " +
                "whose available_commands_update has no _meta field"
        )
    }
    return V1SessionUpdate.AvailableCommandsUpdate(
        availableCommands = availableCommands.mapNotNull { command ->
            try {
                command.toV1()
            } catch (_: ProtocolConversionException) {
                null
            }
        },
    )
}

/**
 * Converts this v1 commands update to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.AvailableCommandsUpdate.toV2(): AvailableCommandsUpdate =
    AvailableCommandsUpdate(availableCommands = availableCommands.map { it.toV2() })

/**
 * Converts this v2 config options update to the v1 session update that carries it.
 *
 * Options whose own conversion fails are skipped, mirroring the Rust conversion.
 */
@UnstableApi
public fun ConfigOptionUpdate.toV1(): V1SessionUpdate.ConfigOptionUpdate =
    V1SessionUpdate.ConfigOptionUpdate(
        configOptions = configOptions.mapNotNull { option ->
            try {
                option.toV1()
            } catch (_: ProtocolConversionException) {
                null
            }
        },
        _meta = _meta,
    )

/**
 * Converts this v1 config options update to its v2 equivalent.
 *
 * Options whose own conversion fails are skipped, mirroring the Rust conversion.
 */
@UnstableApi
public fun V1SessionUpdate.ConfigOptionUpdate.toV2(): ConfigOptionUpdate = ConfigOptionUpdate(
    configOptions = configOptions.mapNotNull { option ->
        try {
            option.toV2()
        } catch (_: ProtocolConversionException) {
            null
        }
    },
    _meta = _meta,
)

/**
 * Converts this v2 session info update to the v1 session update that carries it.
 *
 * v1 has no patch semantics, so both "no update" and an explicit clear become an unset
 * field.
 *
 * @throws ProtocolConversionException if [SessionInfoUpdate._meta] is an explicit clear,
 * which v1 cannot express
 */
@UnstableApi
public fun SessionInfoUpdate.toV1(): V1SessionUpdate.SessionInfoUpdate =
    V1SessionUpdate.SessionInfoUpdate(
        title = title.valueOrNull(),
        updatedAt = updatedAt.valueOrNull(),
        _meta = _meta.metaValueOrThrow("SessionInfoUpdate"),
    )

/**
 * Converts this v1 session info update to its v2 equivalent.
 *
 * An unset v1 field becomes "no update" rather than an explicit clear.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.SessionInfoUpdate.toV2(): SessionInfoUpdate = SessionInfoUpdate(
    title = title.toV2MaybeUndefined(),
    updatedAt = updatedAt.toV2MaybeUndefined(),
    _meta = _meta.toV2MaybeUndefined(),
)

/**
 * Converts this v2 usage update to the v1 session update that carries it.
 *
 * This conversion is total: every v2 value has a v1 representation.
 */
@UnstableApi
public fun UsageUpdate.toV1(): V1SessionUpdate.UsageUpdate =
    V1SessionUpdate.UsageUpdate(used = used, size = size, cost = cost, _meta = _meta)

/**
 * Converts this v1 usage update to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.UsageUpdate.toV2(): UsageUpdate =
    UsageUpdate(used = used, size = size, cost = cost, _meta = _meta)

/**
 * The metadata value of a patch state, rejecting an explicit clear.
 *
 * v1 cannot distinguish "leave metadata alone" from "clear it", so an explicit clear has no
 * faithful v1 form.
 */
private fun MaybeUndefined<JsonElement>.metaValueOrThrow(context: String): JsonElement? =
    when (this) {
        is MaybeUndefined.Value -> value
        MaybeUndefined.Null -> throw ProtocolConversionException(
            "v2 $context with null _meta cannot be represented in v1"
        )

        MaybeUndefined.Undefined -> null
    }

/**
 * Converts this v2 session update to the v1 session updates that represent it.
 *
 * The result is a **list** because one v2 update can require several v1 updates: v1 has no
 * whole-message upsert, so [SessionUpdate.UserMessage], [SessionUpdate.AgentMessage], and
 * [SessionUpdate.AgentThought] expand into one v1 chunk per content block. Every other
 * variant yields exactly one update.
 *
 * @throws ProtocolConversionException if this update has no v1 representation:
 * [SessionUpdate.StateUpdate] (v1 reports completion in the `session/prompt` response),
 * [SessionUpdate.ToolCallContentChunk] (v1 content updates replace rather than append),
 * [SessionUpdate.Unknown], a message upsert whose content is absent, cleared, or empty, or
 * a nested payload that cannot itself be converted
 */
@UnstableApi
public fun SessionUpdate.toV1(): List<V1SessionUpdate> = when (this) {
    is SessionUpdate.UserMessageChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::UserMessageChunk))

    is SessionUpdate.AgentMessageChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::AgentMessageChunk))

    is SessionUpdate.AgentThoughtChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::AgentThoughtChunk))

    is SessionUpdate.UserMessage -> messageToV1Chunks(
        variant = "user_message",
        messageId = message.messageId,
        content = message.content,
        meta = message._meta,
        wrap = V1SessionUpdate::UserMessageChunk,
    )

    is SessionUpdate.AgentMessage -> messageToV1Chunks(
        variant = "agent_message",
        messageId = message.messageId,
        content = message.content,
        meta = message._meta,
        wrap = V1SessionUpdate::AgentMessageChunk,
    )

    is SessionUpdate.AgentThought -> messageToV1Chunks(
        variant = "agent_thought",
        messageId = thought.messageId,
        content = thought.content,
        meta = thought._meta,
        wrap = V1SessionUpdate::AgentThoughtChunk,
    )

    is SessionUpdate.StateUpdate -> throw ProtocolConversionException(
        "v2 SessionUpdate variant `state_update` cannot be represented in v1 because v1 " +
            "reports completion in the session/prompt response"
    )

    is SessionUpdate.ToolCallContentChunk -> throw ProtocolConversionException(
        "v2 SessionUpdate variant `tool_call_content_chunk` cannot be represented in v1 " +
            "because v1 tool-call content updates replace content instead of appending"
    )

    is SessionUpdate.ToolCallUpdate -> listOf(update.toV1())
    is SessionUpdate.PlanUpdate -> listOf(update.toV1())
    is SessionUpdate.PlanRemoved -> listOf(removed.toV1())
    is SessionUpdate.AvailableCommandsUpdate -> listOf(update.toV1())
    is SessionUpdate.ConfigOptionUpdate -> listOf(update.toV1())
    is SessionUpdate.SessionInfoUpdate -> listOf(update.toV1())
    is SessionUpdate.UsageUpdate -> listOf(update.toV1())
    is SessionUpdate.Unknown -> throw unknownV2EnumVariant("SessionUpdate", sessionUpdate)
}

/**
 * Converts this v1 session update to its v2 equivalent.
 *
 * v1's separate `tool_call` and `tool_call_update` both become [SessionUpdate.ToolCallUpdate],
 * and v1's identifier-less `plan` becomes a plan update for [LEGACY_V1_PLAN_ID].
 *
 * An unrecognized v1 update crosses unchanged: both versions preserve one as raw JSON.
 *
 * @throws ProtocolConversionException if this is a `current_mode_update`, which v2 removed,
 * or a message chunk with no `messageId`, which v2 requires
 */
@UnstableApi
public fun V1SessionUpdate.toV2(): SessionUpdate = when (this) {
    is V1SessionUpdate.UserMessageChunk -> SessionUpdate.UserMessageChunk(
        toV2ContentChunk("user_message_chunk", content, messageId, _meta),
    )

    is V1SessionUpdate.AgentMessageChunk -> SessionUpdate.AgentMessageChunk(
        toV2ContentChunk("agent_message_chunk", content, messageId, _meta),
    )

    is V1SessionUpdate.AgentThoughtChunk -> SessionUpdate.AgentThoughtChunk(
        toV2ContentChunk("agent_thought_chunk", content, messageId, _meta),
    )

    is V1SessionUpdate.ToolCall -> SessionUpdate.ToolCallUpdate(toV2())
    is V1SessionUpdate.ToolCallUpdate -> SessionUpdate.ToolCallUpdate(toV2())
    is V1SessionUpdate.PlanUpdate -> SessionUpdate.PlanUpdate(toV2())
    is V1SessionUpdate.PlanUpdateV2 -> SessionUpdate.PlanUpdate(toV2())
    is V1SessionUpdate.PlanRemoved -> SessionUpdate.PlanRemoved(toV2())
    is V1SessionUpdate.AvailableCommandsUpdate -> SessionUpdate.AvailableCommandsUpdate(toV2())
    is V1SessionUpdate.ConfigOptionUpdate -> SessionUpdate.ConfigOptionUpdate(toV2())
    is V1SessionUpdate.SessionInfoUpdate -> SessionUpdate.SessionInfoUpdate(toV2())
    is V1SessionUpdate.UsageUpdate -> SessionUpdate.UsageUpdate(toV2())

    is V1SessionUpdate.CurrentModeUpdate ->
        throw removedV1EnumVariant("SessionUpdate", "current_mode_update")

    // Both versions keep unrecognized updates as raw JSON, so this crosses losslessly.
    is V1SessionUpdate.UnknownSessionUpdate ->
        SessionUpdate.Unknown(sessionUpdate = sessionUpdateType, rawJson = rawJson)
}

private inline fun ContentChunk.toV1ContentChunk(
    wrap: (com.agentclientprotocol.model.ContentBlock, MessageId?, JsonElement?) -> V1SessionUpdate,
): V1SessionUpdate = wrap(content.toV1(), messageId, _meta)

private fun toV2ContentChunk(
    variant: String,
    content: com.agentclientprotocol.model.ContentBlock,
    messageId: MessageId?,
    meta: JsonElement?,
): ContentChunk = ContentChunk(
    messageId = messageId ?: throw ProtocolConversionException(
        "v1 SessionUpdate variant `$variant` without messageId cannot be represented in v2, " +
            "whose content chunks require one"
    ),
    content = content.toV2(),
    _meta = meta,
)

private inline fun messageToV1Chunks(
    variant: String,
    messageId: MessageId,
    content: MaybeUndefined<List<ContentBlock>>,
    meta: MaybeUndefined<JsonElement>,
    wrap: (com.agentclientprotocol.model.ContentBlock, MessageId?, JsonElement?) -> V1SessionUpdate,
): List<V1SessionUpdate> {
    val blocks = when (content) {
        is MaybeUndefined.Value -> content.value.ifEmpty {
            throw ProtocolConversionException(
                "v2 SessionUpdate variant `$variant` with empty content cannot be represented " +
                    "in v1 chunks"
            )
        }

        MaybeUndefined.Null -> throw ProtocolConversionException(
            "v2 SessionUpdate variant `$variant` with null content cannot be represented in v1 chunks"
        )

        MaybeUndefined.Undefined -> throw ProtocolConversionException(
            "v2 SessionUpdate variant `$variant` without content cannot be represented in v1 chunks"
        )
    }
    val v1Meta = when (meta) {
        is MaybeUndefined.Value -> meta.value
        MaybeUndefined.Null -> throw ProtocolConversionException(
            "v2 SessionUpdate variant `$variant` with null _meta cannot be represented in v1 chunks"
        )

        MaybeUndefined.Undefined -> null
    }
    return blocks.map { block -> wrap(block.toV1(), messageId, v1Meta) }
}
