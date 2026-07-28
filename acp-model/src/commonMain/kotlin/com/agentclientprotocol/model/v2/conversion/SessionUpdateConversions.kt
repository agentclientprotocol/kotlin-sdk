@file:Suppress("unused")
@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AvailableCommand as V1AvailableCommand
import com.agentclientprotocol.model.AvailableCommandInput as V1AvailableCommandInput
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate
import com.agentclientprotocol.model.v2.AvailableCommand
import com.agentclientprotocol.model.v2.AvailableCommandInput
import com.agentclientprotocol.model.v2.AvailableCommandsUpdate
import com.agentclientprotocol.model.v2.ConfigOptionUpdate
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UsageUpdate
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
 * Converts this v2 session update to the v1 session updates that represent it.
 *
 * The result is a **list** because the two unions are not one-to-one: a single v2 update can
 * require several v1 updates, which the Rust schema models as `IntoV1Many`.
 *
 * @throws ProtocolConversionException if this update has no v1 representation:
 * [SessionUpdate.StateUpdate] (v1 reports completion in the `session/prompt` response),
 * [SessionUpdate.ToolCallContentChunk] (v1 content updates replace rather than append),
 * [SessionUpdate.Unknown], or a nested payload that cannot itself be converted
 */
@UnstableApi
public fun SessionUpdate.toV1(): List<V1SessionUpdate> = when (this) {
    is SessionUpdate.UserMessageChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::UserMessageChunk))

    is SessionUpdate.AgentMessageChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::AgentMessageChunk))

    is SessionUpdate.AgentThoughtChunk ->
        listOf(chunk.toV1ContentChunk(V1SessionUpdate::AgentThoughtChunk))

    is SessionUpdate.StateUpdate -> throw ProtocolConversionException(
        "v2 SessionUpdate variant `state_update` cannot be represented in v1 because v1 " +
            "reports completion in the session/prompt response"
    )

    is SessionUpdate.ToolCallContentChunk -> throw ProtocolConversionException(
        "v2 SessionUpdate variant `tool_call_content_chunk` cannot be represented in v1 " +
            "because v1 tool-call content updates replace content instead of appending"
    )

    is SessionUpdate.PlanUpdate -> listOf(update.toV1())
    is SessionUpdate.PlanRemoved -> listOf(removed.toV1())
    is SessionUpdate.AvailableCommandsUpdate -> listOf(update.toV1())
    is SessionUpdate.ConfigOptionUpdate -> listOf(update.toV1())
    is SessionUpdate.UsageUpdate -> listOf(update.toV1())
    is SessionUpdate.Unknown -> throw unknownV2EnumVariant("SessionUpdate", sessionUpdate)
}

/**
 * Converts this v1 session update to its v2 equivalent.
 *
 * v1's identifier-less `plan` becomes a plan update for [LEGACY_V1_PLAN_ID].
 *
 * An unrecognized v1 update crosses unchanged: both versions preserve one as raw JSON.
 *
 * @throws ProtocolConversionException if this is a `current_mode_update`, which v2 removed, a
 * message chunk with no `messageId`, which v2 requires, or an update whose v2 counterpart is
 * one of the upsert payloads this model does not cover yet
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

    is V1SessionUpdate.PlanUpdate -> SessionUpdate.PlanUpdate(toV2())
    is V1SessionUpdate.PlanUpdateV2 -> SessionUpdate.PlanUpdate(toV2())
    is V1SessionUpdate.PlanRemoved -> SessionUpdate.PlanRemoved(toV2())
    is V1SessionUpdate.AvailableCommandsUpdate -> SessionUpdate.AvailableCommandsUpdate(toV2())
    is V1SessionUpdate.ConfigOptionUpdate -> SessionUpdate.ConfigOptionUpdate(toV2())
    is V1SessionUpdate.UsageUpdate -> SessionUpdate.UsageUpdate(toV2())

    // v2 collapses v1's two tool-call variants into a single upsert and gives its session
    // info update patch semantics. Those payloads are not modeled here yet.
    is V1SessionUpdate.ToolCall -> throw v2UpsertNotModeled("tool_call")
    is V1SessionUpdate.ToolCallUpdate -> throw v2UpsertNotModeled("tool_call_update")
    is V1SessionUpdate.SessionInfoUpdate -> throw v2UpsertNotModeled("session_info_update")

    is V1SessionUpdate.CurrentModeUpdate ->
        throw removedV1EnumVariant("SessionUpdate", "current_mode_update")

    // Both versions keep unrecognized updates as raw JSON, so this crosses losslessly.
    is V1SessionUpdate.UnknownSessionUpdate ->
        SessionUpdate.Unknown(sessionUpdate = sessionUpdateType, rawJson = rawJson)
}

/**
 * The error for a v1 update whose v2 counterpart is an upsert payload with patch semantics,
 * which this model does not cover yet.
 */
private fun v2UpsertNotModeled(variant: String): ProtocolConversionException =
    ProtocolConversionException(
        "v1 SessionUpdate variant `$variant` cannot be represented in v2 yet: its v2 " +
            "counterpart is an upsert payload with patch semantics, which is not modeled"
    )

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
