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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A relayed worker of the native agent: its events are the ones the relay feeds into the channel.
 *
 * Scenario in this file: 10. relay, an agent that wraps a native agent with a flat event stream announces nested
 * children with `parent`.
 */
internal class NativeRelayedSubagentSession(feed: ReceiveChannel<SessionUpdate>) : AgentSubagentSession {
    /**
     * Completes once the SDK has sent the last of the events.
     */
    val drained = CompletableDeferred<Unit>()

    override val events: Flow<SessionUpdate> = feed.receiveAsFlow().onCompletion { drained.complete(Unit) }
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
internal suspend fun FlowCollector<Event>.relayNativeAgent(subagents: AgentSubagents, transcript: Transcript) {
    val feeds = mutableMapOf<String, Channel<SessionUpdate>>()
    val relayed = mutableListOf<NativeRelayedSubagentSession>()
    fakeNativeAgentEvents().collect { event ->
        when (event) {
            is NativeEvent.Spawned -> {
                val id = relayedId(event.worker)
                val feed = Channel<SessionUpdate>(Channel.UNLIMITED).also { feeds[event.worker] = it }

                // The announcement is the first item of the events this time: `announce` waits until it is sent.
                feed.send(
                    SessionUpdate.SubagentUpdate(
                        id,
                        title = MaybeUndefined.Value(event.title),
                        state = MaybeUndefined.Value(StateUpdate.Running()),
                    )
                )
                val parent = event.parent?.let(::relayedId)
                relayed += subagents.announce(id, AgentSubagentFactory { NativeRelayedSubagentSession(feed) }, parent)
                transcript.agentLine(id, "relays native worker ${event.worker} under ${event.parent ?: "the root"}")
            }

            is NativeEvent.Output -> feeds.getValue(event.worker).send(message(event.text))

            is NativeEvent.Finished -> feeds.remove(event.worker)?.run {
                send(subagentState(relayedId(event.worker), StateUpdate.Idle(IdleStopReason.EndTurn)))
                close()
            }
        }
    }

    // The turn's response then follows every relayed update.
    relayed.forEach { it.drained.await() }
    say(message("Relayed ${relayed.size} workers of the native agent"))
}

private fun relayedId(worker: String) = SessionId("relay-$worker")
