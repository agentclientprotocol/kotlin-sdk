@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.agent.subagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.describe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A prompt that mentions it is relayed to the native agent (scenario 10); any other prompt is delegated.
 */
const val NATIVE_AGENT_REQUEST = "native agent"

/**
 * The root session of the sample's agent, and the place to start reading it. A turn either delegates the request to
 * three subagents, or relays it to a native agent whose workers become subagents.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 1. capability check: without the client's `subagents` capability, the root does the work itself;
 * - 4. the parent links a child from its own stream, once `announce` has returned;
 * - 9. `session/close`: the root's `close` stops its children's work;
 * - 13. after a `session/load`, a fresh cancel grant makes a replayed child cancellable again.
 *
 * The root records its history and its children's in a [SessionHistory] (scenarios 11, 12 and 14), and replays it from
 * [replayHistory].
 *
 * The children are [DelegatedSubagentSession]s built by [DelegatedSubagentFactory], running the tasks of
 * `SampleTasks.kt`, and the relayed [NativeRelayedSubagentSession]s.
 */
internal class DelegatingAgentSession(
    override val sessionId: SessionId,
    private val history: SessionHistory,
    private val transcript: Transcript,
) : AgentSession {
    /*
     * The delegated children of this session object that are still working or may get more work. They outlive the
     * turn that started them, and only the root's close stops them (section 9).
     */
    private val children = CopyOnWriteArrayList<DelegatedSubagentSession>()

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = recording(flow {
        val request = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        val relay = NATIVE_AGENT_REQUEST in request

        // The user's prompt is part of the history a load replays (section 11), though the client sent it live.
        history.send(SessionUpdate.UserMessageChunk(ContentBlock.Text(request))) {}

        /*
         * 1. Capability check
         *
         * `subagents` is `null` when the client didn't declare the `subagents` capability. Such a client can't show a
         * child, and the root's client operations reject the updates that name one, so the root does the work without
         * exposing it and reports the results as its own.
         */
        val subagents = currentCoroutineContext().subagents
        when {
            subagents == null -> workInline(relay)
            relay -> relayNativeAgent(subagents, history, transcript)
            else -> delegate(subagents)
        }
        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    })

    /**
     * Sends every update of a turn through [history], so it is recorded on the root's stream as it goes out.
     */
    private fun recording(turn: Flow<Event>): Flow<Event> = flow {
        turn.collect { event ->
            if (event is Event.SessionUpdateEvent) history.send(event.update) { emit(event) } else emit(event)
        }
    }

    private suspend fun FlowCollector<Event>.workInline(relay: Boolean) {
        if (relay) {
            FakeNativeAgent.events().collect { event ->
                if (event is NativeEvent.Output) say(message("${event.worker}: ${event.text}"))
            }
        } else {
            for (task in sampleTasks) {
                val toolCallId = ToolCallId("inline-${task.id.value}")
                say(SessionUpdate.ToolCall(toolCallId, task.title, status = ToolCallStatus.COMPLETED))
            }
        }
    }

    private suspend fun FlowCollector<Event>.delegate(subagents: AgentSubagents) {
        val started = sampleTasks.map { task ->
            val factory = DelegatedSubagentFactory(task, sessionId, history.child(task.id), transcript)
            val child = subagents.announce(task.id, factory)
            children += child

            /*
             * 4. Linking the child from the parent's stream
             *
             * `announce` has returned, so the announcement is on the wire: an update the parent sends from now on
             * may name the child. Here the parent records the task it sent as the outgoing view of a
             * `session_message`, linked to the incoming view in the child's stream, and shows a tool call that points
             * at the child.
             */
            say(
                SessionUpdate.SessionMessage(
                    task.sentMessageId,
                    senderSessionId = MaybeUndefined.Value(sessionId),
                    recipientSessionId = MaybeUndefined.Value(task.id),
                    recipientMessageId = MaybeUndefined.Value(task.receivedMessageId),
                    content = MaybeUndefined.Value(listOf(ContentBlock.Text(task.instruction))),
                )
            )
            say(
                SessionUpdate.ToolCall(
                    delegationCallId(task), "Delegate: ${task.title}", ToolKind.OTHER, ToolCallStatus.IN_PROGRESS,
                    content = listOf(ToolCallContent.Session(task.id)),
                )
            )
            child
        }

        val outcomes = started.map { it.task to it.outcome.await() }
        for ((task, outcome) in outcomes) {
            val status = if (outcome == IdleStopReason.EndTurn) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
            say(SessionUpdate.ToolCallUpdate(delegationCallId(task), status = status))
        }
        say(message(outcomes.joinToString("; ") { (task, outcome) -> "${task.title}: ${outcome.describe()}" }))
    }

    /*
     * 9. Closing the root
     *
     * After this returns, and before the response, the SDK removes every subagent below the root and stops
     * collecting their events. It calls no app code for them: stopping their work and freeing their resources is this
     * close's job.
     */
    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        children.forEach { it.stop() }
        transcript.agentLine(sessionId, "close: stopped the workers of ${children.joinToString { it.task.id.value }}")
        return CloseSessionResponse()
    }

    override suspend fun replayHistory() {
        val context = currentCoroutineContext()
        history.replay(context.client, context.subagents, transcript) { childHistory, childContext ->
            if (childHistory.sessionId != reviewerFollowUp.id) return@replay null

            /*
             * 13. A fresh grant after the load
             *
             * A grant replayed from the history is never in effect, and the client drops it after the load. Here the
             * reviewer continues with a new task after the load, and that task sends a fresh `capabilities.cancel`
             * through the reviewer's `events` (see `DelegatedTaskScope.grantCancel`). The SDK collects those events
             * only after the `session/load` response, so what they send is live, not history.
             *
             * The root sends no outgoing view of this task's message, so the reviewer's incoming view carries no
             * `senderMessageId` to link to.
             */
            DelegatedSubagentSession(reviewerFollowUp, sessionId, null, childContext, childHistory, transcript)
                .also { children += it }
        }
    }

    private fun delegationCallId(task: DelegatedTask) = ToolCallId("delegate-${task.id.value}")
}
