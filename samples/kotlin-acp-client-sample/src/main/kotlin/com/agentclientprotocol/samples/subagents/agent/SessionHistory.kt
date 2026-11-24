@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.update
import com.agentclientprotocol.samples.subagents.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The recorded stream of one session, the root or a subagent, for `session/load`: every update that went on that
 * stream, in the order it went out.
 *
 * A child's own `subagent_update`s, its announcement included, go on its parent's stream, so they are in the parent's
 * history, tagged with the child. Everything else the child sends is in its own history. The tree of histories is the
 * tree of sessions.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 11. recording each stream in its live order;
 * - 12. replaying it in the same order, children and grandchildren included;
 * - 14. reporting each replayed child's current state after the load.
 *
 * @property parent the history of the parent's stream, `null` for a root
 */
internal class SessionHistory(val sessionId: SessionId, private val parent: SessionHistory? = null) {
    private sealed interface Entry {
        /**
         * An update this session sent on its own stream.
         */
        class Update(val update: SessionUpdate) : Entry

        /**
         * The announcement of [child], the first `subagent_update` for it on this stream.
         */
        class Announced(val child: SessionHistory, val announcement: SessionUpdate.SubagentUpdate) : Entry

        /**
         * A later `subagent_update` of [child] on this stream.
         */
        class Patched(val child: SessionHistory, val patch: SessionUpdate.SubagentUpdate) : Entry
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    /*
     * Held while an update is recorded and sent, so that the order of this history is the order of the stream, even
     * when the root and several children send on it at once.
     */
    private val streamLock = Mutex()

    /**
     * The history of the child [id] that this session announces.
     */
    fun child(id: SessionId): SessionHistory = SessionHistory(id, parent = this)

    /*
     * 11. Recording the history
     *
     * Every update the sample sends goes through here: the root's prompt flow, each factory's announcement, and each
     * child's `events`. The update is recorded in the history of the stream it goes on, and sent while that stream's
     * lock is held, so the history keeps the stream's live order. Permission requests aren't recorded: a replay must
     * not send a request again.
     *
     * What the SDK sends on its own, like the `unknown` for a feed that ends while active, isn't in the record. After
     * a load the client treats every replayed state as unconfirmed anyway.
     */
    suspend fun send(update: SessionUpdate, sendIt: suspend (SessionUpdate) -> Unit) {
        val ownPatch = update is SessionUpdate.SubagentUpdate && update.sessionId == sessionId
        val stream = if (ownPatch) checkNotNull(parent) { "A root sends no subagent_update for itself" } else this

        stream.streamLock.withLock {
            stream.entries += when {
                !ownPatch -> Entry.Update(update)
                stream.entries.none { it is Entry.Announced && it.child === this } ->
                    Entry.Announced(this, update as SessionUpdate.SubagentUpdate)

                else -> Entry.Patched(this, update as SessionUpdate.SubagentUpdate)
            }
            sendIt(update)
        }
    }

    /**
     * Sends every item of this flow through [send].
     */
    fun recording(updates: Flow<SessionUpdate>): Flow<SessionUpdate> = flow {
        updates.collect { update -> send(update) { emit(it) } }
    }

    /*
     * 12. Replaying the history
     *
     * Called from `replayHistory`, while the root is replaying: everything sent now is history, so a cancel grant in
     * it is never in effect and a state in it doesn't count as active. The loop sends this stream's entries in their
     * recorded order:
     * - its own updates through its own `client`;
     * - an announcement through `announce`, with the same id under the same parent. The factory sends the announcement
     *   and the child's own stream, its own children included, then returns and leaves the child's context to this
     *   loop;
     * - a child's later patch through that child's context, at its recorded place on this stream. The root's `client`
     *   rejects a `subagent_update`, and the child's routes it to this stream.
     *
     * So each stream is replayed in its live order. Only the interleaving between streams can differ, which the spec
     * allows.
     *
     * [sessionAfterLoad] returns the session a child continues with after the load. `null` gives a session that only
     * reports the child's current state (section 14).
     */
    suspend fun replay(
        client: ClientSessionOperations,
        subagents: AgentSubagents?,
        transcript: Transcript,
        sessionAfterLoad: (SessionHistory, AgentSubagentContext) -> AgentSubagentSession?,
    ) {
        val contexts = mutableMapOf<SessionHistory, AgentSubagentContext>()
        val skippedToolCalls = mutableSetOf<ToolCallId>()

        for (entry in entries.toList()) {
            when (entry) {
                is Entry.Update -> {
                    val update = entry.update
                    // Without the `subagents` capability, the client gets the root's history without its children.
                    if (subagents == null && update.namesSubagents(skippedToolCalls)) continue
                    client.notify(update)
                }

                is Entry.Announced -> {
                    val child = entry.child
                    subagents?.announce(child.sessionId, AgentSubagentFactory { context ->
                        context.client.notify(entry.announcement)
                        child.replay(context.client, context.subagents, transcript, sessionAfterLoad)
                        transcript.agentLine(child.sessionId, "replayed ${child.entries.size} recorded entries")
                        contexts[child] = context
                        sessionAfterLoad(child, context) ?: CurrentStateSubagentSession(child, recordedState(child))
                    })
                }

                is Entry.Patched -> contexts[entry.child]?.client?.notify(entry.patch)
            }
        }
    }

    /**
     * The last state recorded on this stream for [child].
     */
    private fun recordedState(child: SessionHistory): StateUpdate? = entries.fold(null as StateUpdate?) { state, entry ->
        when {
            entry is Entry.Announced && entry.child === child -> entry.announcement.state.update(state)
            entry is Entry.Patched && entry.child === child -> entry.patch.state.update(state)
            else -> state
        }
    }

    /**
     * Whether a client without the `subagents` capability must not get [this]: it names a subagent, or it updates a
     * tool call that was skipped for that reason.
     */
    private fun SessionUpdate.namesSubagents(skippedToolCalls: MutableSet<ToolCallId>): Boolean = when (this) {
        is SessionUpdate.SessionMessage, is SessionUpdate.SessionMessageChunk -> true
        is SessionUpdate.ToolCall ->
            content.any { it is ToolCallContent.Session }.also { skipped -> if (skipped) skippedToolCalls += toolCallId }

        is SessionUpdate.ToolCallUpdate ->
            toolCallId in skippedToolCalls || content.orEmpty().any { it is ToolCallContent.Session }

        else -> false
    }
}

/**
 * A replayed child that does no more work after the load.
 *
 * ### 14. The current state after the load
 *
 * After a load the client treats every replayed state as unconfirmed, and the agent should report each child's
 * current state when it knows it. This child's work had ended when it was recorded `idle`, so that is still its
 * state: its events report it once, as a live patch. A child whose last recorded state is active, like the probe whose
 * runner went away, reports nothing: the agent can't tell what it does now, and doesn't invent a state.
 */
private class CurrentStateSubagentSession(history: SessionHistory, recordedState: StateUpdate?) : AgentSubagentSession {
    override val events: Flow<SessionUpdate> = history.recording(
        if (recordedState is StateUpdate.Idle) flowOf(subagentState(history.sessionId, recordedState)) else flowOf()
    )
}
