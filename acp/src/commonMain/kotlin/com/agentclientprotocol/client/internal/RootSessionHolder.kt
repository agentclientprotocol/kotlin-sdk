@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.internal.ClientSessionImpl
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The holder of a root session, which one opening call claims and answers, and whose close marks it closing.
 */
internal class RootSessionHolder(
    sessionId: SessionId,
    protocol: Protocol,
    openingScope: CoroutineScope,
    failSessionTree: suspend (ClientSessionHolder, Throwable) -> Boolean,
) : ClientSessionHolder(sessionId, protocol, openingScope, failSessionTree) {
    /**
     * The opening call that claimed a root's holder.
     *
     * @property replayRequested whether the call asked for a replay of the history, which only `session/load` does
     * @property parameters the call's parameters, which give the subagents their working directories before the root's
     * [ClientSession] exists
     */
    class Opening(val replayRequested: Boolean, val parameters: SessionCreationParameters)

    /**
     * The stages of a root's lifecycle, in order.
     */
    enum class Stage { UNCLAIMED, CLAIMED, ANSWERED, CLOSING }

    /**
     * One snapshot of a root's lifecycle; [opening] is `null` only before the claim.
     */
    class RootPhase private constructor(private val stage: Stage, val opening: Opening?) {
        /**
         * Whether the root has reached [stage]: a closing root has also been answered.
         */
        fun isAtLeast(stage: Stage): Boolean = this.stage >= stage

        /**
         * The phase after the claim by [opening], or `null` if a call has already claimed the root.
         */
        fun claimedBy(opening: Opening): RootPhase? =
            if (stage == Stage.UNCLAIMED) RootPhase(Stage.CLAIMED, opening) else null

        /**
         * The phase after the answer, or `null` unless the root is claimed and not answered yet.
         */
        fun answered(): RootPhase? = if (stage == Stage.CLAIMED) RootPhase(Stage.ANSWERED, opening) else null

        /**
         * The phase once the close has started, or `null` if the root isn't answered yet. A closing root stays closing.
         */
        fun closing(): RootPhase? = when (stage) {
            Stage.UNCLAIMED, Stage.CLAIMED -> null
            Stage.ANSWERED -> RootPhase(Stage.CLOSING, opening)
            Stage.CLOSING -> this
        }

        companion object {
            val UNCLAIMED: RootPhase = RootPhase(Stage.UNCLAIMED, opening = null)
        }
    }

    private val _phase = atomic(RootPhase.UNCLAIMED)

    /*
     * The last session state that the early updates carried. Only [deliverEarly] writes them, and [applyEarlyState]
     * reads them after it returned, so they need no atomic.
     */
    // TODO remove together with the deprecated ClientSession.currentMode.
    private var earlyMode: SessionUpdate.CurrentModeUpdate? = null
    // TODO remove together with the deprecated ClientSession.configOptions.
    @OptIn(UnstableApi::class)
    private var earlyConfigOptions: SessionUpdate.ConfigOptionUpdate? = null

    /**
     * The current snapshot of this root's lifecycle. Read it once for a decision that needs both its stage and its
     * opening.
     */
    val phase: RootPhase get() = _phase.value

    override val root: RootSessionHolder get() = this

    override val blocksId: Boolean get() = !isFailed

    override fun sessionCwd(): String = claimedParameters().cwd

    override fun sessionAdditionalDirectories(): List<String> = claimedParameters().additionalDirectories ?: emptyList()

    private fun claimedParameters(): SessionCreationParameters =
        checkNotNull(phase.opening) { "Session $sessionId is not claimed" }.parameters

    /**
     * Moves this root to [Stage.CLOSING], then answers the current and later permission requests of every subagent
     * below it with `cancelled`. The root stays closing if the close fails.
     *
     * @throws IllegalStateException if the root isn't answered yet
     */
    fun startClosing() {
        _phase.update { current -> checkNotNull(current.closing()) { "Root session $sessionId is closed before its answer" } }
        // After the stage, so a subagent that this walk misses is closed by [addChild].
        forEachDescendant { it.permissions.close() }
    }

    /**
     * Makes the opening call that asked for [replayRequested] with [parameters] the owner of this holder.
     *
     * @return `false` if the holder already has an owner or has completed
     */
    fun claimForOpening(replayRequested: Boolean, parameters: SessionCreationParameters): Boolean {
        // A completed holder's queue is closed, so it couldn't take the opened marker.
        if (_session.isCompleted) return false
        val unclaimed = phase
        val claimed = unclaimed.claimedBy(Opening(replayRequested, parameters)) ?: return false
        return _phase.compareAndSet(unclaimed, claimed)
    }

    /**
     * Puts the opened marker with [response] after every update queued so far, marks the root answered, and puts an
     * opened marker into the queue of every subagent below it.
     *
     * It runs in the opening call's response hook, so it must not suspend or block. A subagent registered later takes
     * its marker when its holder is created.
     *
     * @return `false` if the holder failed, so its queue is closed and the marker dropped
     * @throws IllegalStateException if no call has claimed the holder, or it was already answered
     */
    fun markOpened(response: AcpCreatedSessionResponse): Boolean {
        val claimed = phase
        val answered = checkNotNull(claimed.answered()) { "Root session $sessionId is answered without a claim, or twice" }
        if (queue.trySend(QueuedItem.Opened(response)).isFailure) return false
        check(_phase.compareAndSet(claimed, answered)) { "The phase of root session $sessionId changed during its answer" }

        // A subagent's opening closes its queue only after this marker; a failed one's is closed and skipped.
        val promote = answered.opening?.replayRequested != true
        forEachDescendant { it.markOpened(promote) }
        return true
    }

    /**
     * Hands the updates queued before the opened marker to [session] and returns the marker undelivered, for
     * [completeOpening]. It runs after the answer, which queued the marker.
     *
     * If the operations fail, the remaining items stay queued for [failAndDrainQueue].
     */
    suspend fun drainHistory(session: ClientSessionImpl): QueuedItem.Opened {
        while (true) {
            when (val item = queue.receive()) {
                is QueuedItem.Opened -> return item
                else -> deliverDuringOpening(session) { deliverItem(item, session, opened = false) }
            }
        }
    }

    /**
     * Hands the queued and arriving updates to [operations] until the opened marker, and returns the marker
     * undelivered, because only the opening call knows whether it succeeded.
     *
     * If [operations] throws, the remaining items stay queued for [failAndDrainQueue].
     *
     * @return `null` if the queue was closed before the opened marker
     */
    suspend fun deliverEarly(operations: ClientSessionOperations): QueuedItem.Opened? {
        while (true) {
            when (val item = queue.receiveCatching().getOrNull() ?: return null) {
                is QueuedItem.Opened -> return item
                else -> deliverItem(item, operations, opened = false) { update, meta ->
                    @OptIn(UnstableApi::class)
                    when (update) {
                        // TODO remove together with the deprecated ClientSession.currentMode.
                        is SessionUpdate.CurrentModeUpdate -> earlyMode = update
                        // TODO remove together with the deprecated ClientSession.configOptions.
                        is SessionUpdate.ConfigOptionUpdate -> earlyConfigOptions = update
                        else -> {}
                    }
                    operations.notify(update, meta)
                }
            }
        }
    }

    // TODO remove together with the deprecated ClientSession.currentMode and configOptions.
    /**
     * Applies the session state that the updates of [deliverEarly] carried to [session], after it returned.
     */
    fun applyEarlyState(session: ClientSessionImpl) {
        // TODO remove together with the deprecated ClientSession.currentMode.
        earlyMode?.let(session::trackState)
        // TODO remove together with the deprecated ClientSession.configOptions.
        earlyConfigOptions?.let(session::trackState)
    }

    /**
     * Delivers [opened] and the updates queued after it to [session], then makes [session] the receiver of later
     * updates. It runs after the opening call's request and the queue's consumer have returned.
     *
     * It runs app code in the caller's coroutine, so the caller's cancellation or a failure of the operations makes it
     * throw. The caller must then fail the holder, which hands the rest of the queue on.
     */
    suspend fun completeOpening(session: ClientSessionImpl, opened: QueuedItem.Opened) {
        val response = checkNotNull(opened.response) { "The opened marker of root session ${session.sessionId} has no answer" }
        deliverDuringOpening(session) {
            resolveHeldSubagentState()
            // No prompt can be active yet, so the opened marker goes to `notify`.
            session.operations.notify(SessionEvent.Opened(response))
        }
        queue.close()
        for (item in queue) {
            deliverDuringOpening(session) { deliverItem(item, session, opened = true) }
        }

        // A cancellation after the last delivery still fails the opening.
        currentCoroutineContext().ensureActive()
        check(_session.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    /**
     * Runs [block], a delivery to the app during the opening, with [session] in the context.
     *
     * A [CancellationException] from app code while the caller is active becomes an [IllegalStateException], so the
     * opening fails instead of looking like the caller's own cancellation.
     */
    private suspend fun deliverDuringOpening(session: BaseClientSession, block: suspend () -> Unit) {
        try {
            session.executeWithSession(block)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("ClientSessionOperations.notify was cancelled", ce)
        }
    }
}
