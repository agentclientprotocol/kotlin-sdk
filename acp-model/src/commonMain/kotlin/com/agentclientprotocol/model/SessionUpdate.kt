@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Input specification for a command.
 *
 * Specifies how the agent should collect input for this command.
 *
 * Note: Default deserializer for this sealed class is configured in [com.agentclientprotocol.rpc.ACPJson]
 * to fall back to [Unstructured] when no type discriminator is present.
 */
@Serializable
@JsonClassDiscriminator(TYPE_DISCRIMINATOR)
public sealed class AvailableCommandInput {
    /**
     * All text typed after the command name is provided as unstructured input.
     *
     * @param hint A hint to display when input hasn't been provided yet
     */
    @Serializable
    @SerialName("unstructured")
    public data class Unstructured(
        val hint: String,
        override val _meta: JsonElement? = null
    ) : AvailableCommandInput(), AcpWithMeta
}

/**
 * Information about a command.
 */
@Serializable
public data class AvailableCommand(
    val name: String,
    val description: String,
    val input: AvailableCommandInput? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Cost information for token usage.
 */
@UnstableApi
@Serializable
public data class Cost(
    val amount: Double,
    val currency: String,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Different types of updates that can be sent during session processing.
 *
 * These updates provide real-time feedback about the agent's progress.
 *
 * See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/prompt-turn#3-agent-reports-output)
 */
@Serializable(with = SessionUpdateSerializer::class)
@JsonClassDiscriminator("sessionUpdate")
public sealed class SessionUpdate {
    /**
     * A chunk of the user's message being streamed.
     */
    @OptIn(UnstableApi::class)
    @Serializable
    @SerialName("user_message_chunk")
    public data class UserMessageChunk(
        val content: ContentBlock,
        @property:UnstableApi
        val messageId: MessageId? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * A chunk of the agent's response being streamed.
     */
    @OptIn(UnstableApi::class)
    @Serializable
    @SerialName("agent_message_chunk")
    public data class AgentMessageChunk(
        val content: ContentBlock,
        @property:UnstableApi
        val messageId: MessageId? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * A chunk of the agent's internal reasoning being streamed.
     */
    @OptIn(UnstableApi::class)
    @Serializable
    @SerialName("agent_thought_chunk")
    public data class AgentThoughtChunk(
        val content: ContentBlock,
        @property:UnstableApi
        val messageId: MessageId? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * Notification that a new tool call has been initiated.
     */
    @Serializable
    @SerialName("tool_call")
    public data class ToolCall(
        val toolCallId: ToolCallId,
        val title: String,
        val kind: ToolKind? = null,
        val status: ToolCallStatus? = null,
        val content: List<ToolCallContent> = emptyList(),
        val locations: List<ToolCallLocation> = emptyList(),
        val rawInput: JsonElement? = null,
        val rawOutput: JsonElement? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * Update on the status or results of a tool call.
     */
    @Serializable
    @SerialName("tool_call_update")
    public data class ToolCallUpdate(
        val toolCallId: ToolCallId,
        val title: String? = null,
        val kind: ToolKind? = null,
        val status: ToolCallStatus? = null,
        val content: List<ToolCallContent>? = null,
        val locations: List<ToolCallLocation>? = null,
        val rawInput: JsonElement? = null,
        val rawOutput: JsonElement? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * The agent's execution plan for complex tasks.
     *
     * See protocol docs: [Agent Plan](https://agentclientprotocol.com/protocol/agent-plan)
     */
    @Serializable
    @SerialName("plan")
    public data class PlanUpdate(
        val entries: List<PlanEntry>,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A plan update that supports multiple plan formats and concurrent plans.
     *
     * Uses the `plan_update` session update type. Requires the client to advertise
     * `planCapabilities` in `ClientCapabilities`.
     *
     * See [PlanVariant] for available plan formats.
     */
    @UnstableApi
    @Serializable
    @SerialName("plan_update")
    public data class PlanUpdateV2(
        val plan: PlanVariant,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Signals that a plan has been dismissed/removed.
     *
     * Uses the `plan_removed` session update type. The `id` identifies
     * which plan to remove.
     */
    @UnstableApi
    @Serializable
    @SerialName("plan_removed")
    public data class PlanRemoved(
        val id: String,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * Available commands are ready or have changed
     */
    @Serializable
    @SerialName("available_commands_update")
    public data class AvailableCommandsUpdate(
        val availableCommands: List<AvailableCommand>
    ) : SessionUpdate()

    /**
     * The current mode of the session has changed
     *
     * See protocol docs: [Session Modes](https://agentclientprotocol.com/protocol/session-modes)
     */
    @Serializable
    @SerialName("current_mode_update")
    public data class CurrentModeUpdate(
        val currentModeId: SessionModeId
    ) : SessionUpdate()

    /**
     * Configuration options have been updated.
     */
    @Serializable
    @SerialName("config_option_update")
    public data class ConfigOptionUpdate(
        val configOptions: List<SessionConfigOption>,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Session information has been updated.
     */
    @UnstableApi
    @Serializable
    @SerialName("session_info_update")
    public data class SessionInfoUpdate(
        val title: String? = null,
        val updatedAt: String? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Token usage update for the session.
     */
    @UnstableApi
    @Serializable
    @SerialName("usage_update")
    public data class UsageUpdate(
        val used: Long,
        val size: Long,
        val cost: Cost? = null,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A patch of a subagent session's state, sent by the agent as `subagent_update`.
     *
     * Every field except [sessionId] is a [MaybeUndefined] patch: omitted leaves the stored value unchanged,
     * `null` clears it, and a value replaces it. [capabilities] replaces the stored capabilities as a whole,
     * so `{}` and `null` are different patches. The SDK does not validate or rewrite patch contents.
     *
     * Decoding is lenient for [title], [description], [capabilities], [state] and [_meta]: a malformed value
     * decodes as [MaybeUndefined.Undefined]. [cwd] and [additionalDirectories] are strict, because the client
     * acts on them: a malformed value fails the whole update.
     *
     * This type does not implement [AcpWithMeta], because [_meta] is a patch field.
     *
     * @property sessionId the subagent session this update describes
     * @property title human-readable name of the subagent session
     * @property description short description of what the subagent session is doing
     * @property capabilities controls the client may use on the session; replaced as a whole
     * @property state the work state of the session; a malformed known state decodes as
     * [MaybeUndefined.Undefined], never as [StateUpdate.Other]
     * @property cwd absolute working directory of the session
     * @property additionalDirectories absolute additional roots of the session
     * @property _meta extension metadata; a value that is not a JSON object decodes as
     * [MaybeUndefined.Undefined]
     */
    @UnstableApi
    @Serializable(with = SubagentUpdateSerializer::class)
    public data class SubagentUpdate(
        val sessionId: SessionId,
        val title: MaybeUndefined<String> = MaybeUndefined.Undefined,
        val description: MaybeUndefined<String> = MaybeUndefined.Undefined,
        val capabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined,
        val state: MaybeUndefined<StateUpdate> = MaybeUndefined.Undefined,
        val cwd: MaybeUndefined<String> = MaybeUndefined.Undefined,
        val additionalDirectories: MaybeUndefined<List<String>> = MaybeUndefined.Undefined,
        val _meta: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
    ) : SessionUpdate()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A message exchanged between sessions, sent as `session_message`. Needs the `subagents` client
     * capability.
     *
     * Messages are keyed by [messageId]: repeated updates with the same ID patch the stored message. Every
     * other field is a [MaybeUndefined] patch: omitted leaves the stored value unchanged, `null` clears it,
     * and a value replaces it. For [content], `[]` clears it too, but stays distinct from `null` on the wire
     * and round-trips as it came in. A [content] value replaces everything accumulated for the message so
     * far, including chunks of [SessionMessageChunk].
     *
     * Decoding is lenient: a malformed patch field decodes as [MaybeUndefined.Undefined], an unparseable
     * content block is skipped, and a content array whose items are all invalid decodes as `[]`.
     *
     * This type does not implement [AcpWithMeta], because [_meta] is a patch field.
     *
     * @property messageId identifies the message across updates and chunks
     * @property senderSessionId the session that sent the message
     * @property recipientSessionId the session the message is addressed to
     * @property senderMessageId the message in the sender's session that this one corresponds to
     * @property recipientMessageId the message in the recipient's session that this one corresponds to
     * @property content complete replacement content of the message
     * @property _meta extension metadata; a value that is not a JSON object decodes as
     * [MaybeUndefined.Undefined]
     */
    @UnstableApi
    @Serializable(with = SessionMessageSerializer::class)
    public data class SessionMessage(
        val messageId: MessageId,
        val senderSessionId: MaybeUndefined<SessionId> = MaybeUndefined.Undefined,
        val recipientSessionId: MaybeUndefined<SessionId> = MaybeUndefined.Undefined,
        val senderMessageId: MaybeUndefined<MessageId> = MaybeUndefined.Undefined,
        val recipientMessageId: MaybeUndefined<MessageId> = MaybeUndefined.Undefined,
        val content: MaybeUndefined<List<ContentBlock>> = MaybeUndefined.Undefined,
        val _meta: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
    ) : SessionUpdate()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A chunk of a message exchanged between sessions, sent as `session_message_chunk`. Needs the
     * `subagents` client capability.
     *
     * The chunk appends [content] to the message with the same [messageId]. It carries no participant fields:
     * any such field on the wire is ignored.
     *
     * @property messageId the message the chunk belongs to
     * @property content the appended content block
     * @property _meta extension metadata; omitted, `null` and values that are not a JSON object all decode
     * as `null`
     */
    @UnstableApi
    @Serializable(with = SessionMessageChunkSerializer::class)
    public data class SessionMessageChunk(
        val messageId: MessageId,
        val content: ContentBlock,
        override val _meta: JsonElement? = null,
    ) : SessionUpdate(), AcpWithMeta

    /**
     * Unknown session update for forward compatibility.
     * 
     * Captures any session update type not recognized by this SDK version.
     */
    @Serializable
    public data class UnknownSessionUpdate(
        val sessionUpdateType: String,
        val rawJson: JsonObject,
        override val _meta: JsonElement? = null
    ) : SessionUpdate(), AcpWithMeta
}

/**
 * Type discriminator key for SessionUpdate serialization.
 */
private const val SESSION_UPDATE_DISCRIMINATOR = "sessionUpdate"

/**
 * Serializer for SessionUpdate that handles:
 * - All known SessionUpdate types
 * - Unknown types → UnknownSessionUpdate (forward compatibility)
 */
@OptIn(UnstableApi::class)
internal object SessionUpdateSerializer : KSerializer<SessionUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionUpdate")

    override fun serialize(encoder: Encoder, value: SessionUpdate) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is SessionUpdate.UserMessageChunk -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.UserMessageChunk.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "user_message_chunk")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.AgentMessageChunk -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.AgentMessageChunk.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "agent_message_chunk")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.AgentThoughtChunk -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.AgentThoughtChunk.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "agent_thought_chunk")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.ToolCall -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.ToolCall.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "tool_call")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.ToolCallUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.ToolCallUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "tool_call_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.PlanUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.PlanUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "plan")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.PlanUpdateV2 -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.PlanUpdateV2.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "plan_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.PlanRemoved -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.PlanRemoved.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "plan_removed")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.AvailableCommandsUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.AvailableCommandsUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "available_commands_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.CurrentModeUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.CurrentModeUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "current_mode_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.ConfigOptionUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.ConfigOptionUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "config_option_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.SessionInfoUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.SessionInfoUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "session_info_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.UsageUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.UsageUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "usage_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.SubagentUpdate -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.SubagentUpdate.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "subagent_update")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.SessionMessage -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.SessionMessage.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "session_message")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.SessionMessageChunk -> {
                val base = ACPJson.encodeToJsonElement(SessionUpdate.SessionMessageChunk.serializer(), value).jsonObject
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, "session_message_chunk")
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is SessionUpdate.UnknownSessionUpdate -> {
                // For unknown, use the stored type and raw JSON
                buildJsonObject {
                    put(SESSION_UPDATE_DISCRIMINATOR, value.sessionUpdateType)
                    value.rawJson.forEach { (k, v) ->
                        if (k != SESSION_UPDATE_DISCRIMINATOR) put(k, v)
                    }
                }
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): SessionUpdate {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val updateType = jsonObject[SESSION_UPDATE_DISCRIMINATOR]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing '$SESSION_UPDATE_DISCRIMINATOR' discriminator field")

        return when (updateType) {
            "user_message_chunk" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.UserMessageChunk.serializer(),
                jsonObject
            )
            "agent_message_chunk" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.AgentMessageChunk.serializer(),
                jsonObject
            )
            "agent_thought_chunk" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.AgentThoughtChunk.serializer(),
                jsonObject
            )
            "tool_call" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.ToolCall.serializer(),
                jsonObject
            )
            "tool_call_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.ToolCallUpdate.serializer(),
                jsonObject
            )
            "plan" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.PlanUpdate.serializer(),
                jsonObject
            )
            "plan_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.PlanUpdateV2.serializer(),
                jsonObject
            )
            "plan_removed" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.PlanRemoved.serializer(),
                jsonObject
            )
            "available_commands_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.AvailableCommandsUpdate.serializer(),
                jsonObject
            )
            "current_mode_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.CurrentModeUpdate.serializer(),
                jsonObject
            )
            "config_option_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.ConfigOptionUpdate.serializer(),
                jsonObject
            )
            "session_info_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.SessionInfoUpdate.serializer(),
                jsonObject
            )
            "usage_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.UsageUpdate.serializer(),
                jsonObject
            )
            "subagent_update" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.SubagentUpdate.serializer(),
                jsonObject
            )
            "session_message" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.SessionMessage.serializer(),
                jsonObject
            )
            "session_message_chunk" -> ACPJson.decodeFromJsonElement(
                SessionUpdate.SessionMessageChunk.serializer(),
                jsonObject
            )
            else -> {
                // Unknown session update type - capture it for forward compatibility
                SessionUpdate.UnknownSessionUpdate(
                    sessionUpdateType = updateType,
                    rawJson = jsonObject,
                    _meta = jsonObject["_meta"]
                )
            }
        }
    }
}
