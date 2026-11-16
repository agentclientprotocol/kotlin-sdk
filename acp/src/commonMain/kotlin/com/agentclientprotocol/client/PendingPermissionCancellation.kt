package com.agentclientprotocol.client

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Answers a session's pending permission requests with `cancelled` when the client cancels or closes the session.
 *
 * Every request races the app's handler against one per-session signal, so a single [cancelPending] answers all of
 * them. The race is the only answering path, which keeps exactly one response per request.
 *
 * The signal stays completed until [clearCancellation] is called when new work starts, so a request that arrives in
 * between is answered `cancelled` without reaching the app. After [close] the signal is never replaced.
 */
internal class PendingPermissionCancellation {
    private val signal = atomic(CompletableDeferred<Unit>())

    /**
     * Lets later requests reach the app again after a cancel. A signal that no cancel has completed is kept, so
     * requests already waiting on it are still answered by the next cancel.
     */
    fun clearCancellation() {
        signal.update { current -> if (current.isCompleted && current !== CLOSED) CompletableDeferred() else current }
    }

    /**
     * Answers every pending request, and every later one until [clearCancellation], with `cancelled`.
     */
    fun cancelPending() {
        signal.value.complete(Unit)
    }

    /**
     * Sets the requests pending now aside: completing the returned signal answers them `cancelled`, and not the
     * requests that arrive from now on. Until then they stay pending as before, so the next [cancelPending] or [close]
     * answers them too.
     *
     * @return the signal of the requests set aside, or `null` if the signal is completed, so no request waits on it
     */
    fun setPendingAside(): CompletableDeferred<Unit>? {
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

    /**
     * Answers every pending and every later request with `cancelled`, permanently.
     */
    fun close() {
        signal.getAndSet(CLOSED).complete(Unit)
    }

    /**
     * Runs [answer], unless the session is cancelled first: then [answer] is cancelled, and the result is
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

    private companion object {
        /*
         * Marks a closed session and is never replaced. Compare by identity: a signal completed by a cancel is
         * replaced by [clearCancellation]. It is already completed, so it answers later requests right away, and
         * sharing it between sessions is safe.
         */
        val CLOSED = CompletableDeferred(Unit)
    }
}
