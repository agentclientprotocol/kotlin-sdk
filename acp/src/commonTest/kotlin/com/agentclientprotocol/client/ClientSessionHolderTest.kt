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
 * The early delivery tests cover the order, the failure and the owner of a holder that `Client.loadSession` with a
 * [ClientSessionOperations] argument opens.
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
    fun `early delivery hands the queued updates and the arriving updates to the operations in arrival order`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.handleOrQueue(queuedUpdate, null))
        assertTrue(holder.claim(ClientSessionHolder.Opener.OPERATIONS))

        val operations = RecordingOperations()
        val delivery = launch { holder.deliverEarly(operations) }
        runCurrent()
        assertEquals(listOf<SessionUpdate>(queuedUpdate), operations.updates)

        assertTrue(holder.handleOrQueue(lateUpdate, null))
        holder.endEarlyDelivery()
        delivery.join()
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `early delivery hands one update at a time to the operations`() = runTest {
        val holder = ClientSessionHolder()
        val releaseFirst = CompletableDeferred<Unit>()
        val operations = RecordingOperations(beforeEach = { update -> if (update == queuedUpdate) releaseFirst.await() })
        val delivery = launch { holder.deliverEarly(operations) }

        assertTrue(holder.handleOrQueue(queuedUpdate, null))
        assertTrue(holder.handleOrQueue(lateUpdate, null))
        holder.endEarlyDelivery()
        runCurrent()
        assertTrue(operations.updates.isEmpty(), "the second update must wait until the operations return from the first")

        releaseFirst.complete(Unit)
        delivery.join()
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `updates left by a failed early delivery go to the unconnected handler and later updates are undelivered`() = runTest {
        val holder = ClientSessionHolder()
        val failure = IllegalStateException("notify failed")
        val delivery = async { runCatching { holder.deliverEarly(RecordingOperations(beforeEach = { throw failure })) } }
        assertTrue(holder.handleOrQueue(queuedUpdate, null))
        assertTrue(holder.handleOrQueue(lateUpdate, null))
        assertEquals(failure, delivery.await().exceptionOrNull())

        val unconnected = mutableListOf<SessionUpdate>()
        holder.failAndDrainQueue(IllegalStateException("load failed")) { update, _ -> unconnected += update }
        assertEquals(listOf<SessionUpdate>(lateUpdate), unconnected)
        assertFalse(holder.handleOrQueue(SessionUpdate.AgentMessageChunk(ContentBlock.Text("after")), null))
    }

    @Test
    fun `one kind of opener owns a holder`() = runTest {
        val early = ClientSessionHolder()
        assertTrue(early.claim(ClientSessionHolder.Opener.OPERATIONS))
        assertFalse(early.claim(ClientSessionHolder.Opener.OPERATIONS), "a second early opening")
        assertFalse(early.claim(ClientSessionHolder.Opener.FACTORY), "a factory opening of an early holder")

        val factory = ClientSessionHolder()
        assertTrue(factory.claim(ClientSessionHolder.Opener.FACTORY))
        assertTrue(factory.claim(ClientSessionHolder.Opener.FACTORY), "a second factory opening keeps its old behavior")
        assertFalse(factory.claim(ClientSessionHolder.Opener.OPERATIONS), "an early opening of a factory holder")

        val failed = ClientSessionHolder()
        failed.failAndDrainQueue(IllegalStateException("load failed")) { _, _ -> }
        assertTrue(failed.isFailed)
        assertFalse(failed.claim(ClientSessionHolder.Opener.OPERATIONS), "an early opening of a failed holder")

        val failedEarly = ClientSessionHolder()
        assertTrue(failedEarly.claim(ClientSessionHolder.Opener.OPERATIONS))
        failedEarly.failAndDrainQueue(IllegalStateException("load failed")) { _, _ -> }
        assertFalse(failedEarly.claim(ClientSessionHolder.Opener.FACTORY), "a factory opening of a failed early holder")
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
