@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

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
 * Different types of updates that can be sent during session processing.
 *
 * These updates provide real-time feedback about the agent's progress.
 *
 * See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/prompt-turn#3-agent-reports-output)
 */
@Serializable
@JsonClassDiscriminator("sessionUpdate")
public sealed class SessionUpdate {
    /**
     * A chunk of the user's message being streamed.
     */
    @Serializable
    @SerialName("user_message_chunk")
    public data class UserMessageChunk(
        val content: ContentBlock
    ) : SessionUpdate()

    /**
     * A chunk of the agent's response being streamed.
     */
    @Serializable
    @SerialName("agent_message_chunk")
    public data class AgentMessageChunk(
        val content: ContentBlock
    ) : SessionUpdate()

    /**
     * A chunk of the agent's internal reasoning being streamed.
     */
    @Serializable
    @SerialName("agent_thought_chunk")
    public data class AgentThoughtChunk(
        val content: ContentBlock
    ) : SessionUpdate()

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
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Configuration options have been updated.
     */
    @UnstableApi
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
}