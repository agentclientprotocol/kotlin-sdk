package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.samples.subagents.Side
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.TranscriptLine
import kotlinx.coroutines.flow.FlowCollector

/*
 * Small builders for the updates the agent sends, and its transcript lines.
 */

/**
 * Sends [update] on the stream of the session whose prompt flow this is.
 */
internal suspend fun FlowCollector<Event>.say(update: SessionUpdate) = emit(Event.SessionUpdateEvent(update))

internal fun message(text: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

/**
 * A `subagent_update` that patches only the state of the subagent [id].
 */
internal fun subagentState(id: SessionId, state: StateUpdate) =
    SessionUpdate.SubagentUpdate(id, state = MaybeUndefined.Value(state))

internal fun Transcript.agentLine(sessionId: SessionId, text: String) = add(TranscriptLine(Side.AGENT, sessionId, text))
