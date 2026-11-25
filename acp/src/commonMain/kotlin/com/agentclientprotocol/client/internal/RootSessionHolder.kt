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
    failSessionTree: suspend (ClientSessionHolder, Throwable) -> Unit,
) : ClientSessionHolder(sessionId, protocol, openingScope, failSessionTree) {
    /**
     * The opening call that claimed a root's holder.
     *
     * @property replayRequested whether the call asked the agent to replay the history: `true` for `session/load`,
     * `false` for the other opening calls
     * @property parameters the parameters of the call, which the effective working directories of the root's
     * subagents come from; known from the claim on, so subagents can read them before the root's [ClientSession]
     * exists
     */
    class Opening(val replayRequested: Boolean, val parameters: SessionCreationParameters)

    /**
     * The stages of a root's lifecycle, in order: [claimForOpening] leads to [CLAIMED], [markOpened] to [ANSWERED],
     * and [startClosing] to [CLOSING].
     */
    enum class Stage { UNCLAIMED, CLAIMED, ANSWERED, CLOSING }

    /**
     * One snapshot of a root's lifecycle. [opening] is `null` only before the claim.
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
         * The phase once the close has started, or `null` if the root isn't answered yet. A closing root stays as it
         * is, for a close that follows a failed one or runs beside another.
         */
        fun closing(): RootPhase? = when (stage) {
            Stage.UNCLAIMED, Stage.CLAIMED -> null
            Stage.ANSWERED -> RootPhase(Stage.CLOSING, opening)
            Stage.CLOSING -> this
        }

        companion object {
            /**
             * The phase of a new holder.
             */
            val UNCLAIMED: RootPhase = RootPhase(Stage.UNCLAIMED, opening = null)
        }
    }

    private val _phase = atomic(RootPhase.UNCLAIMED)

    /*
     * The last session state that the updates of an early delivery carried. Only the consumer in [deliverEarly]
     * writes them, and [applyEarlyState] reads them after that consumer has finished. Unlike the other fields,
     * they need no atomic: the join of the consumer orders its writes before the read, and nothing reads them
     * concurrently.
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
     * Starts the close of this root: moves it to [Stage.CLOSING], and then answers the pending and the later permission
     * requests of every subagent below it with `cancelled`. Each close only completes a signal, so the order of the
     * walk doesn't matter.
     *
     * The stage comes first, so a subagent that this walk misses has its permissions closed by its own registration,
     * see [addChild]. The root stays closing if the close fails, as the closed permissions do, and a later close leaves
     * it so.
     *
     * Only the close of this root's own [ClientSession] calls this, and that session exists only after the answer.
     *
     * @throws IllegalStateException if the root isn't answered yet
     */
    fun startClosing() {
        _phase.update { current -> checkNotNull(current.closing()) { "Root session $sessionId is closed before its answer" } }
        forEachDescendant { it.permissions.close() }
    }

    /**
     * Makes the opening call that asked for [replayRequested] with [parameters] the owner of this holder.
     *
     * Only a root's holder that has no owner and hasn't completed can be claimed: the response hook can put the
     * opened marker only into an open queue, and two openings of one id would race for it.
     *
     * @return `false` if the holder already has an owner or has completed. The holder is then unchanged.
     */
    fun claimForOpening(replayRequested: Boolean, parameters: SessionCreationParameters): Boolean {
        if (_session.isCompleted) return false
        val unclaimed = phase
        val claimed = unclaimed.claimedBy(Opening(replayRequested, parameters)) ?: return false
        return _phase.compareAndSet(unclaimed, claimed)
    }

    /**
     * Puts the [QueuedItem.Opened] marker with [response] into the queue, after every update that has reached the
     * holder, records that the opening call was answered, and puts an opened marker without a response into the queue
     * of every subagent below, which resolves their held-back permission state too.
     *
     * The response hook of the opening call calls this in the read loop, so it must not suspend or block.
     *
     * Each subagent gets its opened marker exactly once:
     * - a subagent registered before the answer is in the tree, and gets it from the walk here;
     * - a subagent registered after the answer gets it in its holder's `init`.
     *
     * Both run in read-loop order, this in the response hook and the registration in the `session/update` handler
     * before it suspends, so they never interleave.
     *
     * @return `false` if the queue is closed because the holder failed. The opened marker is then dropped, and the
     * holder stays claimed.
     * @throws IllegalStateException if no call has claimed the holder, or it was already answered
     */
    fun markOpened(response: AcpCreatedSessionResponse): Boolean {
        val claimed = phase
        val answered = checkNotNull(claimed.answered()) { "Root session $sessionId is answered without a claim, or twice" }
        if (queue.trySend(QueuedItem.Opened(response)).isFailure) return false
        check(_phase.compareAndSet(claimed, answered)) { "The phase of root session $sessionId changed during its answer" }

        /*
         * Every subagent's queue is still open here: a subagent's opening closes it only after it has delivered
         * this opened marker, and a failed one's is closed, so it is skipped.
         */
        val promote = answered.opening?.replayRequested != true
        forEachDescendant { it.markOpened(promote) }
        return true
    }

    /**
     * Hands the updates queued before the opened marker to [session], in queue order, and returns the marker without
     * delivering it. Pass the marker to [completeOpening].
     *
     * Must run after the opening call was answered, so the opened marker is in the queue. A failure of the session's
     * operations fails this call, and the remaining items stay in the queue for [failAndDrainQueue]. As in
     * [completeOpening], a cancellation that the operations throw while the caller is active becomes an
     * [IllegalStateException].
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
     * Hands each queued update and each arriving update to [operations], one at a time and in arrival order, until
     * it takes the opened marker, and returns that marker without delivering it: only the opening call knows whether
     * it succeeded. Pass the marker to [completeOpening] once it has.
     *
     * The holder must be claimed. If [operations] throws, the remaining items stay in the queue for
     * [failAndDrainQueue].
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
     * Applies the session state that the updates of [deliverEarly] carried to [session]. Must run after
     * [deliverEarly] has returned.
     */
    fun applyEarlyState(session: ClientSessionImpl) {
        // TODO remove together with the deprecated ClientSession.currentMode.
        earlyMode?.let(session::trackState)
        // TODO remove together with the deprecated ClientSession.configOptions.
        earlyConfigOptions?.let(session::trackState)
    }

    /**
     * Delivers [opened] and the updates queued after it to [session], then makes [session] the receiver of all
     * later updates. The subagents' held-back state is resolved just before [opened] is delivered.
     *
     * Must run after:
     * - the request of the opening call has returned;
     * - the consumer of the queue, [deliverEarly] or [drainHistory], has returned [opened].
     *
     * The operations run in the caller's coroutine, so this can be cancelled. This throws, and the opening fails,
     * when:
     * - the caller is cancelled;
     * - the operations throw. A cancellation that they throw while the caller is active becomes an
     *   [IllegalStateException], so the caller sees a failed opening rather than a cancellation of its own.
     *
     * The operations may already have the opened marker then. The caller must fail the holder with
     * [ClientSessionRegistry.failSessionTree], which hands the rest of the queue on.
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

        // A cancellation that came after the last delivery still fails the opening, before the session is published.
        currentCoroutineContext().ensureActive()
        check(_session.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    /**
     * Runs [block], a delivery to the application during the opening, with [session] in the context.
     *
     * Only the opening uses it, in [drainHistory] and [completeOpening]. A [CancellationException] that app code throws
     * while the caller is still active becomes an [IllegalStateException], so the opening fails rather than looks like
     * the caller's own cancellation. Live deliveries log such failures instead: a subagent's in
     * [SubagentSessionHolder.deliverLive], a root's in the protocol's notification handler.
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
