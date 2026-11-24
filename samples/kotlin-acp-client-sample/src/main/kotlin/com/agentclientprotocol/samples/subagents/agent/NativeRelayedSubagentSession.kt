@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
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
 * A relayed worker of the native agent: its events are the updates the relay [feed]s it, which [history] records on
 * the stream they go on as the SDK sends them.
 *
 * Scenario in this file: 10. relay, an agent that wraps a native agent with a flat event stream announces nested
 * children with `parent`.
 */
internal class NativeRelayedSubagentSession(val history: SessionHistory) : AgentSubagentSession {
    private val updates = Channel<SessionUpdate>(Channel.UNLIMITED)

    /**
     * Completes once the SDK has sent the last of the events.
     */
    val drained = CompletableDeferred<Unit>()

    override val events: Flow<SessionUpdate> =
        history.recording(updates.receiveAsFlow()).onCompletion { drained.complete(Unit) }

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
 * `subagents`, naming the parent with `parent`, and feeds the worker's events into a channel that becomes the child's
 * `events`.
 *
 * `parent` defaults to the session that `subagents` belongs to, here the root. It may name any announced subagent
 * below it, and then the announcement goes on that subagent's stream.
 */
internal suspend fun FlowCollector<Event>.relayNativeAgent(
    subagents: AgentSubagents,
    rootHistory: SessionHistory,
    transcript: Transcript,
) {
    val relayed = mutableMapOf<String, NativeRelayedSubagentSession>()
    FakeNativeAgent.events().collect { event ->
        when (event) {
            is NativeEvent.Spawned -> {
                val id = relayedId(event.worker)
                val parentHistory = event.parent?.let { relayed.getValue(it).history } ?: rootHistory
                val session = NativeRelayedSubagentSession(parentHistory.child(id))

                // The announcement is the first item of the events this time: `announce` waits until it is sent.
                session.feed(
                    SessionUpdate.SubagentUpdate(
                        id,
                        title = MaybeUndefined.Value(event.title),
                        state = MaybeUndefined.Value(StateUpdate.Running()),
                    )
                )
                val parent = event.parent?.let(::relayedId)
                relayed[event.worker] = subagents.announce(id, AgentSubagentFactory { session }, parent)
                transcript.agentLine(id, "relays native worker ${event.worker} under ${event.parent ?: "the root"}")
            }

            is NativeEvent.Output -> relayed.getValue(event.worker).feed(message(event.text))

            is NativeEvent.Finished -> relayed.getValue(event.worker).run {
                feed(subagentState(relayedId(event.worker), StateUpdate.Idle(IdleStopReason.EndTurn)))
                end()
            }
        }
    }

    // The turn's response then follows every relayed update.
    relayed.values.forEach { it.drained.await() }
    say(message("Relayed ${relayed.size} workers of the native agent"))
}

private fun relayedId(worker: String) = SessionId("relay-$worker")
