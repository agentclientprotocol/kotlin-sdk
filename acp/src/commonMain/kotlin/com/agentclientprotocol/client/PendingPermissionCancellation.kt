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
 * The signal stays completed until the next turn calls [startTurn], so a request that arrives in between is
 * answered `cancelled` without reaching the app. After [close] the signal is never replaced.
 */
internal class PendingPermissionCancellation {
    private val signal = atomic(CompletableDeferred<Unit>())

    /**
     * Gives a new turn a fresh signal, since a cancel belongs to the turn it interrupted.
     */
    fun startTurn() {
        signal.update { current -> if (current === CLOSED) current else CompletableDeferred() }
    }

    /**
     * Answers every pending and every later request of the current turn with `cancelled`.
     */
    fun cancelPending() {
        signal.value.complete(Unit)
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
         * replaced by the next turn. It is already completed, so it answers later requests right away, and
         * sharing it between sessions is safe.
         */
        val CLOSED = CompletableDeferred(Unit)
    }
}
