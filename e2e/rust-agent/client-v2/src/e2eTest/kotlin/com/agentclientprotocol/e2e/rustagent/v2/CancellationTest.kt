@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.e2e.rustagent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StopReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CancellationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(2)
    }

    @TempDir
    lateinit var cwd: Path

    /*
     * Testy ignores a cancel for a session without foreground work. The running update comes after the
     * prompt has started that work, so cancelling on it reaches the waiting turn.
     */
    @Test
    fun cancelEndsWaitForCancelTurn() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                val updates = session.promptAndCollect(scenario("wait_for_cancel"), onRunning = { session.cancel() })

                assertEquals(StopReason.Cancelled, updates.stopReason)
                assertTrue(updates.none { it is SessionUpdate.AgentMessageChunk }, "updates: $updates")
            }
        }
    }

    @Test
    fun cancelStatusReportsNotCancelled() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val updates = client.newSession(cwd).promptAndCollect(scenario("cancel_status"))

                assertContains(updates.reply, "cancel_status: not_cancelled")
                assertEquals(StopReason.EndTurn, updates.stopReason)
            }
        }
    }
}
