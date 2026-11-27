@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.StateUpdate
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Answers a session's pending permission requests with `cancelled` when the session's work is cancelled or the
 * session is closed.
 *
 * Every request races the app's handler against one per-session signal, so a single [cancelPending] answers all of
 * them, and the race is the only answering path. A completed signal also answers the requests that arrive before
 * [clearCancellation].
 */
internal sealed class PendingPermissions {
    protected val signal = atomic(CompletableDeferred<Unit>())

    /**
     * Runs [answer], unless the requests are cancelled first: then [answer] is cancelled, and the result is
     * [cancelledAnswer] once it has finished.
     *
     * [answer] runs in the caller's context, so session context elements stay visible to the app's handler.
     */
    suspend fun <T> answerUnlessCancelled(cancelledAnswer: () -> T, answer: suspend () -> T): T {
        val cancelled = signal.value
        if (cancelled.isCompleted) return cancelledAnswer()

        return coroutineScope {
            val pending = async { answer() }
            select {
                pending.onAwait { it }
                cancelled.onAwait {
                    pending.cancel()
                    cancelledAnswer()
                }
            }
        }
    }

    /**
     * Answers every pending and every later request with `cancelled`, permanently.
     */
    fun close() {
        signal.getAndSet(CLOSED).complete(Unit)
    }

    /**
     * Answers every pending request, and every later one until [clearCancellation], with `cancelled`.
     */
    protected open fun cancelPending() {
        signal.value.complete(Unit)
    }

    /**
     * Lets later requests reach the app again after a cancel. A signal that no cancel has completed is kept, so
     * requests already waiting on it are still answered by the next cancel.
     */
    protected open fun clearCancellation() {
        signal.update { current -> if (current.isCompleted && current !== CLOSED) CompletableDeferred() else current }
    }

    private companion object {
        /*
         * Marks a closed session and is never replaced. Compare by identity: a signal completed by a cancel is
         * replaced by [clearCancellation]. It is already completed, so it answers later requests right away, and
         * sharing it between sessions is safe.
         */
        val CLOSED = CompletableDeferred(Unit)
    }
}

/**
 * The pending permission requests of a root session: the session cancels them when the client cancels its turn, and
 * clears the cancellation when a new prompt starts.
 */
internal class RootPendingPermissions : PendingPermissions() {
    public override fun cancelPending(): Unit = super.cancelPending()

    public override fun clearCancellation(): Unit = super.clearCancellation()
}

/**
 * Answers a subagent's pending permission requests with `cancelled` when its work is cancelled, by the client or by
 * the agent, or when it is closed.
 *
 * A subagent has no prompt, so its work is known only from the `state` its parent's stream reports, and ACP asks the
 * client to answer its requests once it reports `idle` with `cancelled`. States are followed at arrival, because the
 * parent's stream can lag behind the requests, and a state that arrives before the root's answer is held back until
 * [onOpened].
 */
internal class SubagentPendingPermissions : PendingPermissions() {
    private sealed class WorkState {
        // Before the root's answer: the latest held state's work, and the requests set aside by an `idle(cancelled)`.
        class Held(val activeWork: Boolean, val setAside: CompletableDeferred<Unit>?) : WorkState()

        // After the root's answer: whether the last live state confirmed foreground work.
        class Live(val activeWork: Boolean) : WorkState()
    }

    // Only the arrival path writes it, in [onState] and [onOpened]; [onClientCancel] reads it from other coroutines.
    private val workState = atomic<WorkState>(WorkState.Held(activeWork = false, setAside = null))

    /**
     * Follows the `state` of a patch of this subagent when it arrives. A patch without `state` doesn't call it.
     *
     * A live `idle` with `cancelled` answers the pending requests, and every live state then lets later requests reach
     * the app again. A state that isn't live only holds its effect back for [onOpened].
     *
     * @param state the patch's `state`, `null` for an unconfirmed state
     * @param live whether the root's answer had arrived before this patch
     */
    fun onState(state: StateUpdate?, live: Boolean) {
        val confirmsWork = state is StateUpdate.Running || state is StateUpdate.RequiresAction
        val cancelsWork = state is StateUpdate.Idle && state.stopReason == IdleStopReason.Cancelled
        if (!live) {
            // The root's answer never goes back, so a state that isn't live arrives before [onOpened].
            val held = workState.value as? WorkState.Held ?: return
            // Completing the signal set aside here also completes any set aside before it, so only the latest is kept.
            val setAside = if (cancelsWork) setPendingAside() ?: held.setAside else held.setAside
            workState.value = WorkState.Held(confirmsWork, setAside)
            return
        }

        if (cancelsWork) cancelPending()
        /*
         * Record the work before clearing: a concurrent [onClientCancel] that sees the old value has its block cleared
         * here, and one that sees the new value runs after the clear.
         */
        workState.value = WorkState.Live(confirmsWork)
        clearCancellation()
    }

    /**
     * Resolves what [onState] held back, when the root's answer arrives, and makes later states live. Requests may
     * already be pending, because a request joins the race when it arrives.
     *
     * @param applyHeld `true` after a call that replayed no history, so the held states were current and take effect;
     * `false` after a load, so they were history and nothing applies
     */
    fun onOpened(applyHeld: Boolean) {
        val held = workState.value as? WorkState.Held ?: return
        workState.value = WorkState.Live(applyHeld && held.activeWork)
        if (applyHeld) held.setAside?.complete(Unit)
    }

    /**
     * Answers the pending requests after the client sent `session/cancel` for this subagent.
     *
     * Later requests are answered `cancelled` too only while the last live state is `running` or `requires_action`,
     * until the next live state. After `idle` no later state may come to lift the block, and an unconfirmed state
     * says nothing, so the client doesn't deny work that may go on.
     */
    fun onClientCancel() {
        cancelPending()
        val current = workState.value
        val workContinues = current is WorkState.Live && current.activeWork
        if (!workContinues) clearCancellation()
    }

    /**
     * Sets the requests pending now aside: completing the returned signal answers them, and not the later ones. The
     * next [cancelPending] or [close] still answers them too.
     *
     * @return the signal of the requests set aside, or `null` if the signal is completed, so no request waits on it
     */
    private fun setPendingAside(): CompletableDeferred<Unit>? {
        while (true) {
            val pending = signal.value
            if (pending.isCompleted) return null
            val next = CompletableDeferred<Unit>()
            if (signal.compareAndSet(pending, next)) {
                next.invokeOnCompletion { pending.complete(Unit) }
                return pending
            }
        }
    }
}
