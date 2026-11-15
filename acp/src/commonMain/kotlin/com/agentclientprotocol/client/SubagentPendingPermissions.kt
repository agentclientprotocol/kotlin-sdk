@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.StateUpdate
import kotlinx.atomicfu.atomic

/**
 * Answers a subagent's pending permission requests with `cancelled` when its work is cancelled, by the client or by
 * anyone, or when it is closed.
 *
 * A subagent has no prompt, so the work it does is known only from the `state` its parent's stream reports. The RFD
 * requires the client to answer a session's pending requests when it cancels the session's work, and to clean up a
 * subagent's requests once it reports `idle` with `cancelled`. Until then the user can still answer them, so the
 * client never denies a tool use of work that continues.
 *
 * The state is followed at arrival, in wire order, not at delivery to the app: the parent's consumer may lag behind
 * the requests. A cancel by the client blocks later requests only while the last live state confirmed foreground
 * work, `running` or `requires_action`, and only until the next live state. After `idle` a cancel raced with work that
 * already ended, and no new `idle` will come, so blocking would deny background requests. With an unconfirmed or
 * `unknown` state the client can't tell, so it doesn't deny work that may continue.
 *
 * As with [PendingPermissionCancellation], the race in [answerUnlessCancelled] is the only answering path, so each
 * request gets exactly one response.
 */
internal class SubagentPendingPermissions {
    private val cancellation = PendingPermissionCancellation()
    private val activeWork = atomic(false)

    /*
     * Whether the latest state that arrived before the parent's answer confirmed foreground work. Only the arrival
     * path writes it, in [onState] and [onOpened].
     */
    private val heldActiveWork = atomic(false)

    /**
     * Follows a `state` patch of this subagent, at its arrival in the parent's stream.
     *
     * The caller passes the `state` field of a `subagent_update` patch only if the patch has one: a patch without
     * `state` must not call it, and a patch with `state: null` must call it with `null`. That is
     * `if (update.state != MaybeUndefined.Undefined) onState(update.state.valueOrNull(), live)`.
     *
     * A live `idle` with stop reason `cancelled` answers the pending requests, whoever cancelled the work. Then every
     * live patch lets later requests reach the app again, and records whether the state confirms foreground work.
     * A patch that isn't live is history, or current but not yet in effect: it only holds that value back for
     * [onOpened].
     *
     * @param state the patch's `state`, `null` for an unconfirmed state
     * @param live whether the parent's answer had arrived before this patch, so the state is current
     */
    fun onState(state: StateUpdate?, live: Boolean) {
        val confirmsWork = state is StateUpdate.Running || state is StateUpdate.RequiresAction
        if (!live) {
            heldActiveWork.value = confirmsWork
            return
        }

        if (state is StateUpdate.Idle && state.stopReason == IdleStopReason.Cancelled) cancellation.cancelPending()
        /*
         * Record the work before clearing, so a concurrent [cancelByClient] that sees the old value has its block
         * cleared by this patch, and one that sees the new value runs after the clear. Either way the outcome is
         * that of one of the two orders.
         */
        activeWork.value = confirmsWork
        cancellation.clearCancellation()
    }

    /**
     * Resolves the value that [onState] held back, when the parent's answer arrives.
     *
     * No request is pending then, because requests on a subagent wait until the opening completes.
     *
     * @param promote `true` after a call that replayed no history: the held value was current, so it takes effect.
     * `false` after a load: it was history, so no foreground work is confirmed.
     */
    fun onOpened(promote: Boolean) {
        val held = heldActiveWork.getAndSet(false)
        activeWork.value = promote && held
    }

    /**
     * Answers the pending requests after the client sent `session/cancel` for this subagent. Later requests are
     * answered `cancelled` too while the last live state confirms foreground work, until the next live state.
     */
    fun cancelByClient() {
        cancellation.cancelPending()
        if (!activeWork.value) cancellation.clearCancellation()
    }

    /**
     * Runs [answer], unless this subagent's requests are cancelled first: then [answer] is cancelled, and the result
     * is [cancelledAnswer] once it has finished.
     *
     * [answer] runs in the caller's context, so session context elements stay visible to the app's handler.
     */
    suspend fun <T> answerUnlessCancelled(cancelledAnswer: () -> T, answer: suspend () -> T): T =
        cancellation.answerUnlessCancelled(cancelledAnswer, answer)

    /**
     * Answers every pending and every later request with `cancelled`, permanently.
     */
    fun close() {
        cancellation.close()
    }
}
