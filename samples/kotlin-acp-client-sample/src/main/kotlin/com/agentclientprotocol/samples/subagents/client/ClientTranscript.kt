@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.samples.subagents.Side
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.TranscriptLine
import com.agentclientprotocol.samples.subagents.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * What the client shows: it writes the client's lines to the transcript and renders sessions and updates.
 */
internal class ClientTranscript(private val transcript: Transcript) {
    /*
     * Counts the lines shown, so the script can wait for a condition on the getters to change. An application that
     * wants a flow of a subagent's values keeps its own like this, updated from the events.
     */
    private val shown = MutableStateFlow(0)

    fun show(sessionId: SessionId?, text: String, update: SessionUpdate? = null) {
        transcript.add(TranscriptLine(Side.CLIENT, sessionId, text, update))
        shown.update { it + 1 }
    }

    /**
     * Waits until [condition] holds, checking it again after each line shown.
     */
    suspend fun awaitUntil(condition: () -> Boolean) {
        shown.first { condition() }
    }

    fun showTree(subagents: List<ClientSubagentSession>, moment: String) {
        for (subagent in subagents.descendants()) {
            show(subagent.sessionId, "$moment: ${subagent.describe()}, under ${subagent.parentSessionId.value}")
        }
    }
}

internal fun List<ClientSubagentSession>.descendants(): List<ClientSubagentSession> =
    flatMap { listOf(it) + it.subagents.descendants() }

internal fun ClientSubagentSession.describe(): String =
    "${sessionId.value} \"$title\": ${state.describe()}, ${if (canCancel) "can cancel" else "can't cancel"}"

internal fun SessionUpdate.describe(): String = when (this) {
    is SessionUpdate.SubagentUpdate -> "subagent ${sessionId.value}: " + listOfNotNull(
        title.valueOrNull()?.let { "title \"$it\"" },
        capabilities.valueOrNull()?.let { if (it.cancel != null) "cancel granted" else "no cancel" },
        state.valueOrNull()?.let { "state ${it.describe()}" },
    ).joinToString(", ")

    is SessionUpdate.SessionMessage -> {
        val route = "${senderSessionId.valueOrNull()?.value} -> ${recipientSessionId.valueOrNull()?.value}"
        "message $route: ${content.valueOrNull().orEmpty().text()}"
    }

    is SessionUpdate.ToolCall -> "tool call \"$title\": ${status?.name?.lowercase()}" + sessionLinks(content)
    is SessionUpdate.ToolCallUpdate -> "tool call ${toolCallId.value}: ${status?.name?.lowercase()}"
    is SessionUpdate.AgentMessageChunk -> "says: ${listOf(content).text()}"
    else -> this::class.simpleName ?: "update"
}

private fun sessionLinks(content: List<ToolCallContent>): String {
    val sessions = content.filterIsInstance<ToolCallContent.Session>()
    return if (sessions.isEmpty()) "" else sessions.joinToString(prefix = ", links to ") { it.sessionId.value }
}

private fun List<ContentBlock>.text(): String = filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
