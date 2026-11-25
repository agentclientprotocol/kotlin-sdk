@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.agent.subagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement

/**
 * A prompt that contains this text is relayed to the native agent (scenario 10); any other prompt is delegated.
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
 * - 11. `session/load`: the root tells its history again from its [sessionRecord];
 * - 12. each replayed child tells its own history, grandchildren included;
 * - 13. after a `session/load`, a fresh cancel grant makes a replayed child cancellable again;
 * - 14. a replayed child that does no more work reports its current state after the load.
 *
 * The children are [DelegatedSubagentSession]s built by [DelegatedSubagentFactory], running the tasks of
 * `SampleTasks.kt`, the relayed [NativeRelayedSubagentSession]s, and after a load, `FinishedSubagentSession` for a
 * replayed child that does no more work.
 *
 * @param sessionRecord what this session did, kept by [DelegatingAgentSupport] across a close and a load
 */
internal class DelegatingAgentSession(
    override val sessionId: SessionId,
    private val sessionRecord: SessionRecord,
    private val transcript: Transcript,
) : AgentSession {
    /**
     * The delegated children of this session object that are still working or may get more work. They outlive the
     * turn that started them, and only the root's close stops them (section 9). Its writers run one at a time: first
     * this object's replay, then its turns. `close` reads it from another coroutine.
     */
    @Volatile
    private var children = emptyList<DelegatedSubagentSession>()

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val request = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        val relay = NATIVE_AGENT_REQUEST in request
        val turn = TurnRecord(request).also { sessionRecord.started(it) }

        /*
         * 1. Capability check
         *
         * `subagents` is `null` when the client didn't declare the `subagents` capability. Such a client can't show a
         * child, and the root's client operations reject the updates that name one, so the root does the work without
         * exposing it and reports the results as its own.
         */
        val subagents = currentCoroutineContext().subagents
        when {
            subagents == null -> workInline(relay, turn)
            relay -> relayNativeAgent(subagents, turn, transcript)
            else -> delegate(subagents, turn)
        }
        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }

    private suspend fun FlowCollector<Event>.workInline(relay: Boolean, turn: TurnRecord) {
        if (relay) {
            FakeNativeAgent.events().collect { event ->
                if (event is NativeEvent.Output) say(turn, message("${event.worker}: ${event.text}"))
            }
        } else {
            for (task in sampleTasks) {
                val toolCallId = ToolCallId("inline-${task.id.value}")
                say(turn, SessionUpdate.ToolCall(toolCallId, task.title, status = ToolCallStatus.COMPLETED))
            }
        }
    }

    private suspend fun FlowCollector<Event>.delegate(subagents: AgentSubagents, turn: TurnRecord) {
        val started = sampleTasks.map { task ->
            val child = subagents.announce(task.id, DelegatedSubagentFactory(task, sessionId, transcript))
            children += child
            turn.started(child.record)

            /*
             * 4. Linking the child from the parent's stream
             *
             * `announce` has returned, so the announcement is on the wire: an update the parent sends from now on
             * may name the child. Here the parent records the task it sent as the outgoing view of a
             * `session_message`, linked to the incoming view in the child's stream, and shows a tool call that points
             * at the child.
             */
            say(sentMessage(task))
            say(delegationCall(task))
            child
        }

        val outcomes = started.map { it.task to it.outcome.await() }
        turn.delegationsEnded(outcomes)
        for ((task, outcome) in outcomes) say(delegationResult(task, outcome))
        say(turn, message(outcomes.joinToString("; ") { (task, outcome) -> "${task.title}: ${outcome.describe()}" }))
    }

    /*
     * 9. Closing the root
     *
     * After this returns, and before the response, the SDK removes every subagent below the root and stops
     * collecting their events. It calls no app code for them: stopping their work and freeing their resources is this
     * close's job.
     */
    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        val stopped = children
        stopped.forEach { it.stop() }
        transcript.agentLine(sessionId, "close: stopped the workers of ${stopped.joinToString { it.task.id.value }}")
        return CloseSessionResponse()
    }

    /*
     * 11. Replaying the history
     *
     * `session/load` calls this before its response. Everything sent now is history: a cancel grant in it is never in
     * effect, and a state in it doesn't count as active. The root tells each turn again from its record:
     * - the user's prompt, which the client sent live;
     * - each child the turn started, announced again with the same id. Its factory tells the child's own history and
     *   returns, see [replayedSubagent];
     * - the parent's links to its delegated children, and their results;
     * - what the root said itself.
     *
     * Each stream gets a valid order, the announcement of a child before anything that names it. The interleaving
     * between streams differs from the live run, which the spec allows: a child's whole history now comes before
     * the parent's links to it.
     *
     * A client without the `subagents` capability gets each turn without its children, as `workInline` does live.
     */
    override suspend fun replayHistory() {
        val context = currentCoroutineContext()
        val client = context.client
        val subagents = context.subagents

        for (turn in sessionRecord.turns) {
            client.notify(SessionUpdate.UserMessageChunk(ContentBlock.Text(turn.request)))
            if (subagents != null) {
                for (child in turn.subagents) subagents.announce(child.id, replayedSubagent(child))
                for ((task, _) in turn.delegated) {
                    client.notify(sentMessage(task))
                    client.notify(delegationCall(task))
                }
                for ((task, outcome) in turn.delegated) client.notify(delegationResult(task, outcome))
            }
            turn.said.forEach { client.notify(it) }
        }
    }

    private fun replayedSubagent(record: SubagentRecord): AgentSubagentFactory<AgentSubagentSession> =
        AgentSubagentFactory { context ->
            /*
             * 12. Replaying a child
             *
             * The factory tells the child's history through the child's context, in this order:
             * - the announcement, under the child's latest title;
             * - what the child said and the children it started, in order. Each of those is announced the same way,
             *   under this child;
             * - how the child's work ended, if it did. Like every `subagent_update` of the child, it goes on the
             *   parent's stream, after the announcement.
             */
            context.client.notify(record.announcement)
            for (step in record.steps) {
                when (step) {
                    is SubagentRecord.Step.Said -> context.client.notify(step.update)
                    is SubagentRecord.Step.Started ->
                        context.subagents.announce(step.child.id, replayedSubagent(step.child))
                }
            }
            val outcome = record.outcome
            outcome?.let { context.client.notify(subagentState(record.id, StateUpdate.Idle(it))) }
            transcript.agentLine(record.id, "replayed ${record.steps.size} recorded steps")

            // The returned session is the child after the load. The SDK collects its `events` after the response.
            if (record.id == reviewerFollowUp.id && outcome != null) {
                continueReviewer(context, record)
            } else {
                FinishedSubagentSession(record.id, outcome)
            }
        }

    /*
     * 13. A fresh grant after the load
     *
     * A grant replayed from the history is never in effect, and the client drops it after the load. Here the reviewer
     * continues with a new task after the load, and that task sends a fresh `capabilities.cancel` through the
     * reviewer's `events` (see `DelegatedTaskScope.grantCancel`). The SDK collects those events only after the
     * `session/load` response, so what they send is live, not history.
     *
     * The root sends no outgoing view of this task's message, so the reviewer's incoming view carries no
     * `senderMessageId` to link to.
     *
     * The new task adds to the reviewer's record, so the reviewer continues only if its previous task has ended. A
     * load of the still-open session cancels the old work without waiting for it, and that work must not write the
     * record any more.
     */
    private fun continueReviewer(context: AgentSubagentContext, record: SubagentRecord) =
        DelegatedSubagentSession(reviewerFollowUp, sessionId, null, context, record, transcript)
            .also { children += it }

    private fun sentMessage(task: DelegatedTask) = SessionUpdate.SessionMessage(
        task.sentMessageId,
        senderSessionId = MaybeUndefined.Value(sessionId),
        recipientSessionId = MaybeUndefined.Value(task.id),
        recipientMessageId = MaybeUndefined.Value(task.receivedMessageId),
        content = MaybeUndefined.Value(listOf(ContentBlock.Text(task.instruction))),
    )

    private fun delegationCall(task: DelegatedTask) = SessionUpdate.ToolCall(
        delegationCallId(task), "Delegate: ${task.title}", ToolKind.OTHER, ToolCallStatus.IN_PROGRESS,
        content = listOf(ToolCallContent.Session(task.id)),
    )

    private fun delegationResult(task: DelegatedTask, outcome: IdleStopReason): SessionUpdate.ToolCallUpdate {
        val status = if (outcome == IdleStopReason.EndTurn) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
        return SessionUpdate.ToolCallUpdate(delegationCallId(task), status = status)
    }

    private fun delegationCallId(task: DelegatedTask) = ToolCallId("delegate-${task.id.value}")
}

/**
 * A replayed child that does no more work after the load.
 *
 * ### 14. The current state after the load
 *
 * After a load the client treats every replayed state as unconfirmed, and the agent should report each child's
 * current state when it knows it. A child whose work ended is still `idle` with the same outcome: its events report
 * that once, as a live patch. A child whose work never got an outcome, like the probe whose runner went away, reports
 * nothing: the agent can't tell what it does now, and doesn't invent a state.
 */
private class FinishedSubagentSession(id: SessionId, outcome: IdleStopReason?) : AgentSubagentSession {
    override val events: Flow<SessionUpdate> =
        outcome?.let { flowOf(subagentState(id, StateUpdate.Idle(it))) } ?: flowOf()
}
