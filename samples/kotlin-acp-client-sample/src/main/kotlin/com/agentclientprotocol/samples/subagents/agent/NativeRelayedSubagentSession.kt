@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.samples.subagents.Transcript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A relayed worker of the native agent. It translates that one worker: the relay tells it what the worker did, and it
 * turns that into the updates of its [events] and into its [record].
 *
 * Scenario in this file: 10. relay, an agent that wraps a native agent with a flat event stream announces nested
 * children with `parent`.
 *
 * Only the relay loop calls [relayOutput] and [relayFinished], one at a time, which keeps the single-writer rule of
 * [record]. The relay loop also builds the tree: it announces the session, and decides under which parent.
 *
 * @param id the worker's session id
 * @param title the title the worker was announced with
 * @property announcement what the worker is announced with: its title, running
 * @property record what the worker did, written as the relay reports its output and end
 */
internal class NativeRelayedSubagentSession(id: SessionId, title: String) : AgentSubagentSession {
    val announcement = SessionUpdate.SubagentUpdate(
        id,
        title = MaybeUndefined.Value(title),
        state = MaybeUndefined.Value(StateUpdate.Running()),
    )

    val record = SubagentRecord(announcement)

    private val updates = Channel<SessionUpdate>(Channel.UNLIMITED)

    private val drained = CompletableDeferred<Unit>()

    override val events: Flow<SessionUpdate> = updates.receiveAsFlow().onCompletion { drained.complete(Unit) }

    /**
     * Records and sends a message of the worker.
     */
    fun relayOutput(text: String) {
        val output = message(text)
        record.said(output)
        updates.trySend(output)
    }

    /**
     * Records that the worker ended its turn, sends the `idle` state and ends [events].
     */
    fun relayFinished() {
        record.ended(IdleStopReason.EndTurn)
        updates.trySend(subagentState(record.id, StateUpdate.Idle(IdleStopReason.EndTurn)))
        updates.close()
    }

    /**
     * Waits until the SDK has sent every event of [events].
     */
    suspend fun awaitSent() {
        drained.await()
    }
}

/*
 * 10. Relay
 *
 * An agent that wraps a native agent may learn about the native agent's workers from one flat event stream, where
 * each event names its worker and the worker's parent. The relay builds the tree from that stream: it announces each
 * worker through the root's `subagents`, naming the parent with `parent`, and the factory sends the announcement.
 * Each `NativeRelayedSubagentSession` then translates its own worker's events into the child's `events`.
 *
 * `parent` defaults to the session that `subagents` belongs to, here the root. It may name any announced subagent
 * below it, and then the announcement goes on that subagent's stream.
 */
internal suspend fun FlowCollector<Event>.relayNativeAgent(
    subagents: AgentSubagents,
    turn: TurnRecord,
    transcript: Transcript,
) {
    val relayed = mutableMapOf<String, NativeRelayedSubagentSession>()
    FakeNativeAgent.events().collect { event ->
        when (event) {
            is NativeEvent.Spawned -> {
                val id = relayedId(event.worker)
                val session = NativeRelayedSubagentSession(id, event.title)
                val parent = event.parent?.let(relayed::getValue)

                relayed[event.worker] = subagents.announce(
                    sessionId = id,
                    factory = AgentSubagentFactory { context ->
                        context.client.notify(session.announcement)
                        session
                    },
                    parent = parent?.record?.id,
                )

                if (parent == null) turn.started(session.record) else parent.record.started(session.record)
                transcript.agentLine(id, "relays native worker ${event.worker} under ${event.parent ?: "the root"}")
            }

            is NativeEvent.Output -> relayed.getValue(event.worker).relayOutput(event.text)

            is NativeEvent.Finished -> relayed.getValue(event.worker).relayFinished()
        }
    }

    // The turn's response then follows every relayed update.
    relayed.values.forEach { it.awaitSent() }
    say(turn, message("Relayed ${relayed.size} workers of the native agent"))
}

private fun relayedId(worker: String) = SessionId("relay-$worker")
