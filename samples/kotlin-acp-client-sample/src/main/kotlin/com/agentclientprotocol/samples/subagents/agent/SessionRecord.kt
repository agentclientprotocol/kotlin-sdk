@file:OptIn(ExperimentalAtomicApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionUpdate
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/*
 * What the agent remembers of a session, so that `session/load` can tell it again (section 11, in
 * `DelegatingAgentSession`). A real agent keeps its conversation in its own terms and rebuilds the updates from it;
 * these plain records are the sample's version of that.
 *
 * Each turn and subagent record has one writer at a time, and `session/load` reads it from another coroutine,
 * possibly while the session is still open, which the SDK accepts. So each property is a `@Volatile` immutable value
 * that its writer replaces, and a reader gets a snapshot. The session's list of turns is the exception, see
 * `SessionRecord`.
 */

/**
 * What a root session did, kept across a close so that the session can be loaded again.
 */
internal class SessionRecord {
    /**
     * Atomic, unlike the other records: a load of the still-open session gives the id a second root session object,
     * and a turn of the old one may still be starting, so two turns may append at once.
     */
    private val _turns = AtomicReference(emptyList<TurnRecord>())

    val turns: List<TurnRecord> get() = _turns.load()

    fun started(turn: TurnRecord) = _turns.update { it + turn }
}

/**
 * What one turn of the root did. Only the turn's own prompt flow writes it.
 *
 * @property request the user's prompt
 */
internal class TurnRecord(val request: String) {
    /**
     * The subagents the turn started directly under the root, in order: the delegated tasks or the top-level workers of
     * the native agent. Empty when the turn worked inline.
     */
    @Volatile
    var subagents: List<SubagentRecord> = emptyList()
        private set

    /**
     * The tasks the turn delegated, each with how it ended: the root links each one from its stream and reports its
     * result there.
     */
    @Volatile
    var delegated: List<Pair<DelegatedTask, IdleStopReason>> = emptyList()
        private set

    /**
     * The root's own messages and tool calls: the inline work, or the closing summary.
     */
    @Volatile
    var said: List<SessionUpdate> = emptyList()
        private set

    fun started(child: SubagentRecord) {
        subagents += child
    }

    fun delegationsEnded(outcomes: List<Pair<DelegatedTask, IdleStopReason>>) {
        delegated += outcomes
    }

    fun said(update: SessionUpdate) {
        said += update
    }
}

/**
 * What one subagent did.
 *
 * Only the subagent's own work writes it, one coroutine at a time: the `events` collection of a delegated child or of
 * the probe, or the relay loop for a relayed worker, through its calls on the worker's session. A delegated child that
 * continues after a load writes only once its previous task has ended. `session/load` reads it from another coroutine.
 *
 * @param announcement the announcement the subagent was announced with
 */
internal class SubagentRecord(announcement: SessionUpdate.SubagentUpdate) {
    sealed interface Step {
        /**
         * An update the subagent sent on its own stream.
         */
        class Said(val update: SessionUpdate) : Step

        /**
         * A subagent it started, announced on its stream.
         */
        class Started(val child: SubagentRecord) : Step
    }

    /**
     * The subagent's announcement, with its latest title.
     */
    @Volatile
    var announcement: SessionUpdate.SubagentUpdate = announcement
        private set

    @Volatile
    var steps: List<Step> = emptyList()
        private set

    /**
     * How the subagent's work ended, `null` while it runs or when the agent lost sight of it.
     */
    @Volatile
    var outcome: IdleStopReason? = null
        private set

    val id get() = announcement.sessionId

    fun retitled(title: MaybeUndefined<String>) {
        announcement = announcement.copy(title = title)
    }

    fun said(update: SessionUpdate) {
        steps += Step.Said(update)
    }

    fun started(child: SubagentRecord) {
        steps += Step.Started(child)
    }

    fun ended(outcome: IdleStopReason) {
        this.outcome = outcome
    }
}
