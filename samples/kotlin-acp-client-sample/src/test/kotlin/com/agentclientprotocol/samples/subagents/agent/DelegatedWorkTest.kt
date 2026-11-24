package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.model.IdleStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [runDelegatedWork] maps the end of a task's work to the `idle` stop reason the child reports.
 */
class DelegatedWorkTest {
    private val cancelRequested = CompletableDeferred<Unit>()

    @Test
    fun `work that returns ends the turn`() {
        runBlocking {
            assertEquals(IdleStopReason.EndTurn, runDelegatedWork(cancelRequested) {})
        }
    }

    @Test
    fun `work that throws is an error with its message`() {
        runBlocking {
            val stopReason = runDelegatedWork(cancelRequested) { error("The runner went away") }
            assertEquals("The runner went away", assertIs<IdleStopReason.Error>(stopReason).error?.message)
        }
    }

    @Test
    fun `a timeout inside the work is an error, not a cancel`() {
        runBlocking {
            val stopReason = runDelegatedWork(cancelRequested) { withTimeout(1.milliseconds) { awaitCancellation() } }
            assertIs<IdleStopReason.Error>(stopReason)
        }
    }

    @Test
    fun `a cancel request stops running work`() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val result = async {
                runDelegatedWork(cancelRequested) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            cancelRequested.complete(Unit)
            assertEquals(IdleStopReason.Cancelled, withTimeout(5.seconds) { result.await() })
        }
    }

    @Test
    fun `a permission answered cancelled stops the work as cancelled`() {
        runBlocking {
            assertEquals(IdleStopReason.Cancelled, runDelegatedWork(cancelRequested) { throw PermissionCancelledException() })
        }
    }

    @Test
    fun `a cancel request after the work ended changes nothing`() {
        runBlocking {
            cancelRequested.complete(Unit)
            assertEquals(IdleStopReason.EndTurn, runDelegatedWork(CompletableDeferred()) {})
            assertEquals(IdleStopReason.EndTurn, runDelegatedWork(cancelRequested) {}, "work that ends without suspending")
        }
    }

    @Test
    fun `the caller's cancellation propagates instead of becoming an outcome`() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val result = async {
                runDelegatedWork(cancelRequested) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            result.cancel()
            assertFailsWith<CancellationException> { result.await() }
        }
    }
}
