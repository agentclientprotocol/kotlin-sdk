package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the last session initialization ends, `Client` reaps speculative [ClientSessionHolder] holders that no initialization claimed.
 * The `session/update` handler may have looked such a holder up just before the reap and only queue into it afterwards.
 * Both steps are non-suspending, so this interleaving needs real parallelism through `Client`;
 * these tests put the holder into the same states directly.
 *
 * The early delivery tests cover the order and the failure of a holder that `Client.loadSession` with a
 * [ClientSessionOperations] argument switches to early delivery.
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

    @Test
    fun `early delivery hands the buffered updates to the operations before each new update`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.handleOrQueue(queuedUpdate, null))

        val operations = RecordingOperations()
        assertTrue(holder.startEarlyDelivery(operations))
        assertEquals(listOf<SessionUpdate>(queuedUpdate), operations.updates)

        assertTrue(holder.handleOrQueue(lateUpdate, null))
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `early delivery waits for an update that the operations still handle`() = runTest {
        val holder = ClientSessionHolder()
        val releaseFirst = CompletableDeferred<Unit>()
        val operations = RecordingOperations(beforeEach = { update -> if (update == queuedUpdate) releaseFirst.await() })
        assertTrue(holder.startEarlyDelivery(operations))

        val first = async { holder.handleOrQueue(queuedUpdate, null) }
        runCurrent()
        val second = async { holder.handleOrQueue(lateUpdate, null) }
        runCurrent()
        assertFalse(second.isCompleted, "the second update must wait until the operations return from the first")

        releaseFirst.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `failed early holder reports later updates as undelivered`() = runTest {
        val holder = ClientSessionHolder()
        val operations = RecordingOperations()
        assertTrue(holder.startEarlyDelivery(operations))
        holder.completeExceptionally(IllegalStateException("load failed"))

        assertFalse(holder.handleOrQueue(lateUpdate, null))
        assertTrue(operations.updates.isEmpty())
    }

    @Test
    fun `early delivery cannot start on a failed holder or twice`() = runTest {
        val twice = ClientSessionHolder()
        assertTrue(twice.startEarlyDelivery(RecordingOperations()))
        assertFalse(twice.startEarlyDelivery(RecordingOperations()))

        val failed = ClientSessionHolder()
        failed.completeExceptionally(IllegalStateException("load failed"))
        assertFalse(failed.startEarlyDelivery(RecordingOperations()))
    }

    private class RecordingOperations(
        private val beforeEach: suspend (SessionUpdate) -> Unit = {},
    ) : ClientSessionOperations {
        val updates = mutableListOf<SessionUpdate>()

        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = error("not expected in this test")

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            beforeEach(notification)
            updates += notification
        }
    }
}
