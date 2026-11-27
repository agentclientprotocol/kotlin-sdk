package com.agentclientprotocol.samples.subagents

import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate

/**
 * Where both sides of the sample write what they see.
 *
 * [add] is called from several coroutines at once, so an implementation must be thread-safe.
 */
fun interface Transcript {
    fun add(line: TranscriptLine)
}

/**
 * Which side of the connection wrote a [TranscriptLine].
 */
enum class Side { AGENT, CLIENT }

/**
 * One line of the transcript.
 *
 * @property sessionId the session the line is about, `null` for the connection
 * @property update the update the client shows on this line, if any
 */
data class TranscriptLine(
    val side: Side,
    val sessionId: SessionId?,
    val text: String,
    val update: SessionUpdate? = null,
) {
    fun render(): String = "${side.name.lowercase().padEnd(6)} ${(sessionId?.value ?: "-").padEnd(14)} $text"
}

internal fun StateUpdate?.describe(): String = when (this) {
    null -> "no confirmed state"
    is StateUpdate.Running -> "running"
    is StateUpdate.RequiresAction -> "requires action"
    is StateUpdate.Idle -> stopReason?.let { "idle (${it.describe()})" } ?: "idle"
    is StateUpdate.Unknown -> "unknown"
    is StateUpdate.Other -> state
}

internal fun IdleStopReason.describe(): String = when (this) {
    IdleStopReason.EndTurn -> "end_turn"
    IdleStopReason.MaxTokens -> "max_tokens"
    IdleStopReason.MaxTurnRequests -> "max_turn_requests"
    IdleStopReason.Refusal -> "refusal"
    IdleStopReason.Cancelled -> "cancelled"
    is IdleStopReason.Error -> "error: ${error?.message}"
    is IdleStopReason.Other -> stopReason
}
