package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CancellationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @TempDir
    lateinit var cwd: Path

    @Test
    fun cancelEndsWaitForCancelTurn() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                val turn = async { session.promptTurn(scenario("wait_for_cancel")) }
                /*
                 * The client gives no signal that the prompt request reached the agent, and a cancel that arrives
                 * before it does not end the later turn, so the cancel is repeated until the turn ends.
                 */
                while (!turn.isCompleted) {
                    session.cancel()
                    delay(100.milliseconds)
                }

                assertEquals(StopReason.CANCELLED, turn.await().stopReason)
            }
        }
    }

    @Test
    fun cancelStatusReportsNotCancelled() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(scenario("cancel_status"))

                assertContains(turn.reply, "cancel_status: not_cancelled")
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }
}
