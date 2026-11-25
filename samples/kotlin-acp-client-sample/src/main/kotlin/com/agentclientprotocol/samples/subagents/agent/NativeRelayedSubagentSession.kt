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
 * A relayed worker of the native agent: its events are the updates the relay [feed]s it.
 *
 * Scenario in this file: 10. relay, an agent that wraps a native agent with a flat event stream announces nested
 * children with `parent`.
 *
 * @property record what the worker did, built by the relay from the native agent's events
 */
internal class NativeRelayedSubagentSession(val record: SubagentRecord) : AgentSubagentSession {
    private val updates = Channel<SessionUpdate>(Channel.UNLIMITED)

    /**
     * Completes once the SDK has sent the last of the events.
     */
    val drained = CompletableDeferred<Unit>()

    override val events: Flow<SessionUpdate> = updates.receiveAsFlow().onCompletion { drained.complete(Unit) }

    fun feed(update: SessionUpdate) {
        updates.trySend(update)
    }

    fun end() {
        updates.close()
    }
}

/*
 * 10. Relay
 *
 * An agent that wraps a native agent may learn about the native agent's workers from one flat event stream, where
 * each event names its worker and the worker's parent. The relay announces each worker through the root's
 * `subagents`, naming the parent with `parent`: the factory sends the announcement. It then feeds the worker's events
 * into a channel that becomes the child's `events`.
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
                val announcement = SessionUpdate.SubagentUpdate(
                    id,
                    title = MaybeUndefined.Value(event.title),
                    state = MaybeUndefined.Value(StateUpdate.Running()),
                )
                val session = NativeRelayedSubagentSession(SubagentRecord(announcement))
                val parent = event.parent?.let(relayed::getValue)
                relayed[event.worker] = subagents.announce(id, AgentSubagentFactory { context ->
                    context.client.notify(announcement)
                    session
                }, parent?.record?.id)
                if (parent == null) turn.started(session.record) else parent.record.started(session.record)
                transcript.agentLine(id, "relays native worker ${event.worker} under ${event.parent ?: "the root"}")
            }

            is NativeEvent.Output -> relayed.getValue(event.worker).run {
                val output = message(event.text)
                record.said(output)
                feed(output)
            }

            is NativeEvent.Finished -> relayed.getValue(event.worker).run {
                record.ended(IdleStopReason.EndTurn)
                feed(subagentState(record.id, StateUpdate.Idle(IdleStopReason.EndTurn)))
                end()
            }
        }
    }

    // The turn's response then follows every relayed update.
    relayed.values.forEach { it.drained.await() }
    say(turn, message("Relayed ${relayed.size} workers of the native agent"))
}

private fun relayedId(worker: String) = SessionId("relay-$worker")
