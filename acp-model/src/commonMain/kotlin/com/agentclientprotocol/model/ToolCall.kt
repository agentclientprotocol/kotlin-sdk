@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Categories of tools that can be invoked.
 *
 * Tool kinds help clients choose appropriate icons and optimize how they
 * display tool execution progress.
 *
 * See protocol docs: [Creating](https://agentclientprotocol.com/protocol/tool-calls#creating)
 */
@Serializable
public enum class ToolKind {
    @SerialName("read") READ,
    @SerialName("edit") EDIT,
    @SerialName("delete") DELETE,
    @SerialName("move") MOVE,
    @SerialName("search") SEARCH,
    @SerialName("execute") EXECUTE,
    @SerialName("think") THINK,
    @SerialName("fetch") FETCH,
    @SerialName("switch_mode") SWITCH_MODE,
    @SerialName("other") OTHER
}

/**
 * Execution status of a tool call.
 *
 * Tool calls progress through different statuses during their lifecycle.
 *
 * See protocol docs: [Status](https://agentclientprotocol.com/protocol/tool-calls#status)
 */
@Serializable
public enum class ToolCallStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED
}

/**
 * A file location being accessed or modified by a tool.
 *
 * Enables clients to implement "follow-along" features that track
 * which files the agent is working with in real-time.
 *
 * See protocol docs: [Following the Agent](https://agentclientprotocol.com/protocol/tool-calls#following-the-agent)
 */
@Serializable
public data class ToolCallLocation(
    val path: String,
    val line: UInt? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Content produced by a tool call.
 *
 * Tool calls can produce different types of content including
 * standard content blocks (text, images) or file diffs.
 *
 * See protocol docs: [Content](https://agentclientprotocol.com/protocol/tool-calls#content)
 */
@Serializable
public sealed class ToolCallContent {
    /**
     * Standard content block (text, images, resources).
     */
    @Serializable
    @SerialName("content")
    public data class Content(
        val content: ContentBlock
    ) : ToolCallContent()

    /**
     * File modification shown as a diff.
     */
    @Serializable
    @SerialName("diff")
    public data class Diff(
        val path: String,
        val newText: String,
        val oldText: String? = null,
        override val _meta: JsonElement? = null
    ) : ToolCallContent(), AcpWithMeta

    /**
     * Terminal output reference.
     */
    @Serializable
    @SerialName("terminal")
    public data class Terminal(
        val terminalId: String,
        override val _meta: JsonElement? = null
    ) : ToolCallContent(), AcpWithMeta

    /**
     * A display-only reference to a session that this tool call operates on, such as a child session it
     * spawned or waits on.
     *
     * In v1 the agent may send it only when the client advertised the `subagents` client capability. The
     * referenced session must already be known to the client, through session setup or an announcement.
     * The reference grants no controls and says nothing about the referenced session's work.
     *
     * @property _meta item metadata; omitted, `null` and values that are not a JSON object all decode as `null`
     */
    @Serializable
    @SerialName("session")
    public data class Session(
        val sessionId: SessionId,
        @Serializable(with = LenientMetaSerializer::class)
        override val _meta: JsonElement? = null
    ) : ToolCallContent(), AcpWithMeta
}