@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

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
 * them. The race is the only answering path, which keeps exactly one response per request.
 *
 * The signal stays completed until [clearCancellation] is called when new work starts, so a request that arrives in
 * between is answered `cancelled` without reaching the app. After [close] the signal is never replaced.
 *
 * The subclasses decide when to cancel and when to clear: [RootPendingPermissions] on the client's commands, and
 * [SubagentPendingPermissions] on the events of a subagent.
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
 * A subagent has no prompt, so its work is known only from the `state` its parent's stream reports. The spec
 * requires the client to answer a session's pending requests when it cancels the session's work, and to clean up a
 * subagent's requests once it reports `idle` with `cancelled`. Until then the user can still answer them, so the
 * client never denies a tool use of work that continues.
 *
 * ### Arrival order
 *
 * States and requests are followed when they arrive, in wire order, not at delivery: the parent's stream can lag
 * behind the requests. See [onState] and [answerPermissionRequest].
 *
 * ### What answers the pending requests
 *
 * - [onClientCancel]: the client sent `session/cancel` for this subagent;
 * - [onState]: a live `idle` with `cancelled`, whoever cancelled the work;
 * - [close]: the client started closing the subagent's root, or removed the root's tree after the close.
 *
 * ### Later requests after a client cancel
 *
 * Whether requests that arrive after [onClientCancel] are answered `cancelled` too depends on the last live state:
 * - `running` or `requires_action`: yes, until the next live state, because the cancelled work is still running;
 * - `idle`: no. The cancel raced with work that had already ended, and no new `idle` will come, so blocking would
 *   deny background requests;
 * - unconfirmed or `unknown`: no. The client can't tell, so it doesn't deny work that may continue.
 *
 * ### History
 *
 * A state that arrives before the root's answer is held back until [onOpened]: it takes effect after a call that
 * replayed no history, and is dropped after a load.
 *
 * As in [PendingPermissions], the race in [answerUnlessCancelled] is the only answering path, so each request gets
 * exactly one response.
 */
internal class SubagentPendingPermissions : PendingPermissions() {
    private sealed class WorkState {
        /*
         * Before the root's answer: whether the latest held state confirmed foreground work, and the requests set
         * aside by the latest held `idle` with `cancelled`.
         */
        class Held(val activeWork: Boolean, val setAside: CompletableDeferred<Unit>?) : WorkState()

        // After the root's answer: whether the last live state confirmed foreground work.
        class Live(val activeWork: Boolean) : WorkState()
    }

    // Only the arrival path writes it, in [onState] and [onOpened]; [onClientCancel] reads it from other coroutines.
    private val workState = atomic<WorkState>(WorkState.Held(activeWork = false, setAside = null))

    /**
     * Follows a `state` patch of this subagent, at its arrival in the parent's stream.
     *
     * The caller passes the `state` field of a `subagent_update` patch only if the patch has one: a patch without
     * `state` must not call it, and a patch with `state: null` must call it with `null`. That is
     * `if (update.state != MaybeUndefined.Undefined) onState(update.state.valueOrNull(), live)`.
     *
     * A live `idle` with stop reason `cancelled` answers the pending requests, whoever cancelled the work. Then every
     * live patch lets later requests reach the app again, and records whether the state confirms foreground work.
     * A patch that isn't live is history, or current but not yet in effect: it only holds its effect back for
     * [onOpened]. For an `idle` with `cancelled` that effect is the answer to the requests pending at its arrival,
     * so they are set aside from the requests that arrive later.
     *
     * @param state the patch's `state`, `null` for an unconfirmed state
     * @param live whether the root's answer had arrived before this patch, so the state is current
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
         * Record the work before clearing, so a concurrent [onClientCancel] that sees the old value has its block
         * cleared by this patch, and one that sees the new value runs after the clear. Either way the outcome is
         * that of one of the two orders.
         */
        workState.value = WorkState.Live(confirmsWork)
        clearCancellation()
    }

    /**
     * Resolves what [onState] held back, when the root's answer arrives, and makes later states live.
     *
     * Requests may be pending at this point: a request joins the race when it arrives, also while it waits for its
     * holder, which completes only after the answer.
     *
     * ### After a call that replayed no history
     *
     * The held states were current, so their effect applies now:
     * - the requests set aside by a held `idle` with `cancelled` are answered `cancelled`, as that `idle` would have
     *   answered them live;
     * - the latest held state decides whether foreground work is confirmed.
     *
     * ### After a load
     *
     * The held states were history, so nothing applies:
     * - nothing is answered: the requests set aside stay pending like the others, and a later cancel or close still
     *   answers them;
     * - no foreground work is confirmed.
     *
     * @param promote `true` after a call that replayed no history, `false` after a load
     */
    fun onOpened(promote: Boolean) {
        val held = workState.value as? WorkState.Held ?: return
        workState.value = WorkState.Live(promote && held.activeWork)
        if (promote) held.setAside?.complete(Unit)
    }

    /**
     * Answers the pending requests after the client sent `session/cancel` for this subagent. Later requests are
     * answered `cancelled` too while the last live state confirms foreground work, until the next live state.
     */
    fun onClientCancel() {
        cancelPending()
        val current = workState.value
        val workContinues = current is WorkState.Live && current.activeWork
        if (!workContinues) clearCancellation()
    }

    /**
     * Sets the requests pending now aside: completing the returned signal answers them `cancelled`, and not the
     * requests that arrive from now on. Until then they stay pending as before, so the next [cancelPending] or [close]
     * answers them too.
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
