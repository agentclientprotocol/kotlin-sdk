package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the last session initialization ends, `Client` reaps speculative [ClientSessionHolder] holders that no initialization claimed.
 * The `session/update` handler may have looked such a holder up just before the reap and only queue into it afterwards.
 * Both steps are non-suspending, so this interleaving needs real parallelism through `Client`;
 * these tests put the holder into the same states directly.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class ClientSessionHolderTest {
    private val queuedUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text("queued"))
    private val lateUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text("late"))

    @Test
    fun `update for a reaped holder is reported as undelivered instead of failing`() = runTest {
        val holder = ClientSessionHolder()
        holder.failAndDrainQueue(AcpExpectedError("Session session-9 not found")) { _, _ -> }

        assertFalse(holder.handleOrQueue(lateUpdate, null))
    }

    @Test
    fun `update arriving during the reap is reported after the queued updates are drained`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.handleOrQueue(queuedUpdate, null))

        val events = mutableListOf<String>()
        val releaseDrain = CompletableDeferred<Unit>()
        launch {
            holder.failAndDrainQueue(AcpExpectedError("Session session-9 not found")) { update, _ ->
                releaseDrain.await()
                events += "drained $update"
            }
        }
        // The reap has closed the channel and is suspended while handing the queued update over.
        runCurrent()

        val late = async {
            holder.handleOrQueue(lateUpdate, null).also { events += "late update delivered: $it" }
        }
        runCurrent()
        assertFalse(late.isCompleted, "the late update must wait until the queued updates are drained")

        releaseDrain.complete(Unit)
        assertFalse(late.await())
        assertEquals(listOf("drained $queuedUpdate", "late update delivered: false"), events)
    }
}
