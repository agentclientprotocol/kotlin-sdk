@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.Usage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

// The SessionUpdate variants are named after their payloads. Inside
// the sealed class those names resolve to the variants themselves, so the payload types get
// file-local aliases. (An aliased `import` cannot be used here: these types live in this
// same package, and importing them would shadow their own declarations below.)
@OptIn(UnstableApi::class) private typealias StateUpdatePayload = StateUpdate
@OptIn(UnstableApi::class) private typealias ToolCallContentChunkPayload = ToolCallContentChunk
@OptIn(UnstableApi::class) private typealias PlanUpdatePayload = PlanUpdate
@OptIn(UnstableApi::class) private typealias PlanRemovedPayload = PlanRemoved
@OptIn(UnstableApi::class) private typealias AvailableCommandsUpdatePayload = AvailableCommandsUpdate
@OptIn(UnstableApi::class) private typealias ConfigOptionUpdatePayload = ConfigOptionUpdate
@OptIn(UnstableApi::class) private typealias UsageUpdatePayload = UsageUpdate

/**
 * The input specification for a command.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = AvailableCommandInputSerializer::class)
public sealed class AvailableCommandInput {
    /**
     * All text that was typed after the command name is provided as input.
     *
     * The v2 wire discriminator is `text` (renamed from v1's `unstructured`).
     */
    @Serializable
    public data class Text(
        val hint: String,
        override val _meta: JsonElement? = null,
    ) : AvailableCommandInput(), AcpWithMeta

    /**
     * Custom or future command input specification.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Clients that do not understand this input
     * type SHOULD preserve it when storing, replaying, proxying, or forwarding command
     * metadata, and otherwise ignore the input specification or display the command
     * without structured input.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : AvailableCommandInput()
}

@OptIn(UnstableApi::class)
internal object AvailableCommandInputSerializer : OpenTaggedUnionSerializer<AvailableCommandInput>(
    serialName = "com.agentclientprotocol.model.v2.AvailableCommandInput",
    discriminatorKey = "type",
    known = mapOf(
        "text" to AvailableCommandInput.Text.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is AvailableCommandInput.Text -> "text"
            is AvailableCommandInput.Unknown -> value.type
        }
    },
    unknown = AvailableCommandInput::Unknown,
    rawJson = { (it as? AvailableCommandInput.Unknown)?.rawJson },
)

/**
 * Information about a command.
 *
 * Same shape as v1's command, but [input] references the v2 [AvailableCommandInput].
 */
@UnstableApi
@Serializable
public data class AvailableCommand(
    /**
     * Command name (e.g., `create_plan`, `research_codebase`).
     */
    val name: String,
    /**
     * Human-readable description of what the command does.
     */
    val description: String,
    /**
     * Input for the command if required.
     */
    val input: AvailableCommandInput? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * A streamed item of message content.
 *
 * Carries one [content] item of the message identified by [messageId]; all chunks of a
 * message share the same ID, and a change in [messageId] starts a new message.
 *
 * Unlike v1's message chunks, [messageId] is required.
 */
@UnstableApi
@Serializable
public data class ContentChunk(
    val messageId: MessageId,
    val content: ContentBlock,
    /**
     * Chunk-scoped metadata; it describes this chunk, not the message as a whole.
     */
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Available commands are ready or have changed.
 *
 * [availableCommands] is the full set of commands the agent can execute, replacing any
 * previously reported set.
 */
@UnstableApi
@Serializable
public data class AvailableCommandsUpdate(
    val availableCommands: List<AvailableCommand>,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Session configuration options have been updated.
 *
 * [configOptions] is the full set of options and their current values, replacing any
 * previously reported set.
 */
@UnstableApi
@Serializable
public data class ConfigOptionUpdate(
    val configOptions: List<SessionConfigOption>,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Context window and cost update for a session.
 *
 * Has no v1 counterpart: v1 reports token usage only in the prompt response.
 */
@UnstableApi
@Serializable
public data class UsageUpdate(
    /**
     * Tokens currently in context.
     */
    val used: Long,
    /**
     * Total context window size in tokens.
     */
    val size: Long,
    /**
     * Cumulative session cost.
     */
    val cost: Cost? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * The agent's session state has changed.
 *
 * This is v2's mechanism for reporting session activity transitions. A `session/prompt`
 * response only acknowledges that the prompt was accepted; agents report that processing
 * started, that the session went idle, or that progress is blocked on user action through
 * this update. v1 instead reported the outcome of a turn in the prompt response, so this
 * union has no v1 counterpart.
 *
 * This is an open tagged union discriminated by `state`: an unrecognized state
 * deserializes to [Unknown] with the full raw JSON preserved.
 */
@UnstableApi
@Serializable(with = StateUpdateSerializer::class)
public sealed class StateUpdate {
    /**
     * The agent is actively processing work in the session.
     */
    @Serializable
    public data class Running(
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * The agent is not currently processing work in the session.
     */
    @Serializable
    public data class Idle(
        /**
         * Why the agent stopped processing active session work.
         *
         * Omitted and `null` both mean no stop reason is being reported. Agents SHOULD
         * include this when the idle transition ends active work.
         */
        val stopReason: StopReason? = null,
        /**
         * **UNSTABLE**
         *
         * This capability is not part of the spec yet, and may be removed or changed at
         * any point.
         *
         * Token usage for completed session work. Omitted and `null` both mean no usage
         * is being reported.
         */
        @property:UnstableApi
        val usage: Usage? = null,
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * The agent is waiting on user action before it can continue.
     */
    @Serializable
    public data class RequiresAction(
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * Custom or future session state.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the `state`
     * discriminator), so re-serializing emits it byte-identically. Clients that do not
     * understand this state SHOULD preserve it when storing, replaying, proxying, or
     * forwarding session history.
     */
    public data class Unknown(val state: String, val rawJson: JsonObject) : StateUpdate()
}

@OptIn(UnstableApi::class)
internal object StateUpdateSerializer : OpenTaggedUnionSerializer<StateUpdate>(
    serialName = "com.agentclientprotocol.model.v2.StateUpdate",
    discriminatorKey = "state",
    known = mapOf(
        "running" to StateUpdate.Running.serializer(),
        "idle" to StateUpdate.Idle.serializer(),
        "requires_action" to StateUpdate.RequiresAction.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is StateUpdate.Running -> "running"
            is StateUpdate.Idle -> "idle"
            is StateUpdate.RequiresAction -> "requires_action"
            is StateUpdate.Unknown -> value.state
        }
    },
    unknown = StateUpdate::Unknown,
    rawJson = { (it as? StateUpdate.Unknown)?.rawJson },
)

/**
 * Different types of updates that can be sent during session processing.
 *
 * These updates provide real-time feedback about the agent's progress. Each variant wraps
 * its payload, whose fields are flattened alongside the `sessionUpdate` discriminator on
 * the wire.
 *
 * This is an open tagged union: an unrecognized `sessionUpdate` deserializes to [Unknown]
 * with the full raw JSON preserved, so newer ACP variants and `_`-prefixed extensions
 * degrade gracefully.
 *
 * Restructured from v1 in several ways:
 * - [StateUpdate] replaces reporting a turn's outcome through the prompt response.
 * - Tool call output can be appended chunk by chunk ([ToolCallContentChunk]) instead of
 *   resending the whole content collection.
 * - Plans are identified by ID and can be removed.
 *
 * See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/prompt-turn#3-agent-reports-output)
 */
@UnstableApi
@Serializable(with = SessionUpdateSerializer::class)
public sealed class SessionUpdate {
    /**
     * A chunk of the user's message being streamed.
     */
    public data class UserMessageChunk(val chunk: ContentChunk) : SessionUpdate()

    /**
     * A chunk of the agent's response being streamed.
     */
    public data class AgentMessageChunk(val chunk: ContentChunk) : SessionUpdate()

    /**
     * A chunk of the agent's internal reasoning being streamed.
     */
    public data class AgentThoughtChunk(val chunk: ContentChunk) : SessionUpdate()

    /**
     * The agent's session state has changed.
     */
    public data class StateUpdate(val state: StateUpdatePayload) : SessionUpdate()

    /**
     * A chunk of tool call content, appended to the tool call's current content.
     */
    public data class ToolCallContentChunk(val chunk: ToolCallContentChunkPayload) : SessionUpdate()

    /**
     * A plan's content changed.
     */
    public data class PlanUpdate(val update: PlanUpdatePayload) : SessionUpdate()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A plan was removed.
     */
    public data class PlanRemoved(val removed: PlanRemovedPayload) : SessionUpdate()

    /**
     * Available commands are ready or have changed.
     */
    public data class AvailableCommandsUpdate(val update: AvailableCommandsUpdatePayload) : SessionUpdate()

    /**
     * Session configuration options have been updated.
     */
    public data class ConfigOptionUpdate(val update: ConfigOptionUpdatePayload) : SessionUpdate()

    /**
     * Context window and cost usage has been updated.
     */
    public data class UsageUpdate(val update: UsageUpdatePayload) : SessionUpdate()

    /**
     * Custom or future session update.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Clients that do not understand this update
     * SHOULD preserve it when storing, replaying, proxying, or forwarding session history,
     * and otherwise ignore it.
     */
    public data class Unknown(val sessionUpdate: String, val rawJson: JsonObject) : SessionUpdate()
}

/**
 * Adapts a payload serializer to the [SessionUpdate] variant that wraps it.
 *
 * Variants carry their payload rather than duplicating its fields, but the wire form
 * flattens the payload next to the `sessionUpdate` discriminator, so the wrapper must
 * serialize as the payload itself.
 */
@OptIn(UnstableApi::class)
private class SessionUpdateVariantSerializer<P, V : SessionUpdate>(
    variantName: String,
    private val payload: KSerializer<P>,
    private val wrap: (P) -> V,
    private val unwrap: (V) -> P,
) : KSerializer<V> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.SessionUpdate.$variantName")

    override fun serialize(encoder: Encoder, value: V) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(payload, unwrap(value)))
    }

    override fun deserialize(decoder: Decoder): V {
        val jsonDecoder = decoder as JsonDecoder
        return wrap(jsonDecoder.json.decodeFromJsonElement(payload, jsonDecoder.decodeJsonElement()))
    }
}

@OptIn(UnstableApi::class)
internal object SessionUpdateSerializer : OpenTaggedUnionSerializer<SessionUpdate>(
    serialName = "com.agentclientprotocol.model.v2.SessionUpdate",
    discriminatorKey = "sessionUpdate",
    known = mapOf(
        "user_message_chunk" to SessionUpdateVariantSerializer(
            "UserMessageChunk", ContentChunk.serializer(),
            SessionUpdate::UserMessageChunk, SessionUpdate.UserMessageChunk::chunk,
        ),
        "agent_message_chunk" to SessionUpdateVariantSerializer(
            "AgentMessageChunk", ContentChunk.serializer(),
            SessionUpdate::AgentMessageChunk, SessionUpdate.AgentMessageChunk::chunk,
        ),
        "agent_thought_chunk" to SessionUpdateVariantSerializer(
            "AgentThoughtChunk", ContentChunk.serializer(),
            SessionUpdate::AgentThoughtChunk, SessionUpdate.AgentThoughtChunk::chunk,
        ),
        "state_update" to SessionUpdateVariantSerializer(
            "StateUpdate", StateUpdatePayload.serializer(),
            SessionUpdate::StateUpdate, SessionUpdate.StateUpdate::state,
        ),
        "tool_call_content_chunk" to SessionUpdateVariantSerializer(
            "ToolCallContentChunk", ToolCallContentChunkPayload.serializer(),
            SessionUpdate::ToolCallContentChunk, SessionUpdate.ToolCallContentChunk::chunk,
        ),
        "plan_update" to SessionUpdateVariantSerializer(
            "PlanUpdate", PlanUpdatePayload.serializer(),
            SessionUpdate::PlanUpdate, SessionUpdate.PlanUpdate::update,
        ),
        "plan_removed" to SessionUpdateVariantSerializer(
            "PlanRemoved", PlanRemovedPayload.serializer(),
            SessionUpdate::PlanRemoved, SessionUpdate.PlanRemoved::removed,
        ),
        "available_commands_update" to SessionUpdateVariantSerializer(
            "AvailableCommandsUpdate", AvailableCommandsUpdatePayload.serializer(),
            SessionUpdate::AvailableCommandsUpdate, SessionUpdate.AvailableCommandsUpdate::update,
        ),
        "config_option_update" to SessionUpdateVariantSerializer(
            "ConfigOptionUpdate", ConfigOptionUpdatePayload.serializer(),
            SessionUpdate::ConfigOptionUpdate, SessionUpdate.ConfigOptionUpdate::update,
        ),
        "usage_update" to SessionUpdateVariantSerializer(
            "UsageUpdate", UsageUpdatePayload.serializer(),
            SessionUpdate::UsageUpdate, SessionUpdate.UsageUpdate::update,
        ),
    ),
    discriminator = { value ->
        when (value) {
            is SessionUpdate.UserMessageChunk -> "user_message_chunk"
            is SessionUpdate.AgentMessageChunk -> "agent_message_chunk"
            is SessionUpdate.AgentThoughtChunk -> "agent_thought_chunk"
            is SessionUpdate.StateUpdate -> "state_update"
            is SessionUpdate.ToolCallContentChunk -> "tool_call_content_chunk"
            is SessionUpdate.PlanUpdate -> "plan_update"
            is SessionUpdate.PlanRemoved -> "plan_removed"
            is SessionUpdate.AvailableCommandsUpdate -> "available_commands_update"
            is SessionUpdate.ConfigOptionUpdate -> "config_option_update"
            is SessionUpdate.UsageUpdate -> "usage_update"
            is SessionUpdate.Unknown -> value.sessionUpdate
        }
    },
    unknown = SessionUpdate::Unknown,
    rawJson = { (it as? SessionUpdate.Unknown)?.rawJson },
)
