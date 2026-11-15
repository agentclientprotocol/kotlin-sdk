package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LoadSessionResponse
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * When the last session initialization ends, `Client` reaps speculative [ClientSessionHolder] holders that no initialization claimed.
 * The `session/update` handler may have looked such a holder up just before the reap and only queue into it afterwards.
 * Both steps are non-suspending, so this interleaving needs real parallelism through `Client`;
 * these tests put the holder into the same states directly.
 *
 * The early delivery tests cover the order, the failure and the owner of a holder that `Client.loadSession` with a
 * [ClientSessionOperations] argument opens, and where the marker of the opening call's answer goes.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class ClientSessionHolderTest {
    private val queuedUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text("queued"))
    private val lateUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text("late"))
    private val response = LoadSessionResponse()

    @Test
    fun `update for a reaped holder is reported as undelivered instead of failing`() = runTest {
        val holder = ClientSessionHolder()
        holder.failAndDrainQueue(AcpExpectedError("Session session-9 not found")) { _, _ -> }

        assertFalse(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))
    }

    @Test
    fun `update arriving during the reap is reported after the queued updates are drained`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))

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
            holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)).also { events += "late update delivered: $it" }
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
        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))
        assertTrue(holder.claimForOpening(replayRequested = true))

        val operations = RecordingOperations()
        val delivery = async { holder.deliverEarly(operations) }
        runCurrent()
        assertEquals(listOf<SessionUpdate>(queuedUpdate), operations.updates)

        assertTrue(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))
        assertTrue(holder.markOpened(response))
        assertSame(response, delivery.await()?.response)
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `early delivery hands one update at a time to the operations`() = runTest {
        val holder = ClientSessionHolder()
        val releaseFirst = CompletableDeferred<Unit>()
        val operations = RecordingOperations(beforeEach = { update -> if (update == queuedUpdate) releaseFirst.await() })
        val delivery = launch { holder.deliverEarly(operations) }

        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))
        assertTrue(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))
        holder.markOpened(response)
        runCurrent()
        assertTrue(operations.updates.isEmpty(), "the second update must wait until the operations return from the first")

        releaseFirst.complete(Unit)
        delivery.join()
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), operations.updates)
    }

    @Test
    fun `early delivery stops at the marker without delivering it and leaves the later updates queued`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.claimForOpening(replayRequested = false))
        val operations = RecordingOperations()

        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))
        assertTrue(holder.markOpened(response))
        assertTrue(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))

        assertSame(response, holder.deliverEarly(operations)?.response)
        assertEquals(listOf<SessionUpdate>(queuedUpdate), operations.updates)
        assertEquals(0, operations.openedCount, "only the opening call delivers the marker, once it has succeeded")

        val unconnected = mutableListOf<SessionUpdate>()
        holder.failAndDrainQueue(IllegalStateException("load cancelled")) { update, _ -> unconnected += update }
        assertEquals(listOf<SessionUpdate>(lateUpdate), unconnected)
    }

    @Test
    fun `updates left by a failed early delivery go to the unconnected handler and later updates are undelivered`() = runTest {
        val holder = ClientSessionHolder()
        val failure = IllegalStateException("notify failed")
        val delivery = async { runCatching { holder.deliverEarly(RecordingOperations(beforeEach = { throw failure })) } }
        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))
        assertTrue(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))
        assertEquals(failure, delivery.await().exceptionOrNull())

        val unconnected = mutableListOf<SessionUpdate>()
        holder.failAndDrainQueue(IllegalStateException("load failed")) { update, _ -> unconnected += update }
        assertEquals(listOf<SessionUpdate>(lateUpdate), unconnected)
        assertFalse(holder.handleOrQueue(QueuedItem.Update(SessionUpdate.AgentMessageChunk(ContentBlock.Text("after")), null)))
    }

    @Test
    fun `a failed opening drops its marker`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.claimForOpening(replayRequested = true))
        assertTrue(holder.handleOrQueue(QueuedItem.Update(queuedUpdate, null)))
        assertTrue(holder.markOpened(response))
        assertTrue(holder.handleOrQueue(QueuedItem.Update(lateUpdate, null)))

        val unconnected = mutableListOf<SessionUpdate>()
        holder.failAndDrainQueue(IllegalStateException("load cancelled")) { update, _ -> unconnected += update }
        assertEquals(listOf<SessionUpdate>(queuedUpdate, lateUpdate), unconnected)
        assertTrue(holder.isFailed)
    }

    @Test
    fun `the marker goes only into an open queue`() = runTest {
        val holder = ClientSessionHolder()
        assertTrue(holder.claimForOpening(replayRequested = true))
        holder.failAndDrainQueue(IllegalStateException("load failed")) { _, _ -> }

        assertFalse(holder.markOpened(response))
        assertFalse(holder.isAnswered)
    }

    @Test
    fun `marking a holder opened records the answer`() {
        val holder = ClientSessionHolder()
        assertTrue(holder.claimForOpening(replayRequested = false))
        assertFalse(holder.isAnswered)

        assertTrue(holder.markOpened(response))
        assertTrue(holder.isAnswered)
    }

    @Test
    fun `one opening call owns a holder`() = runTest {
        val load = ClientSessionHolder()
        assertNull(load.replayRequested)
        assertTrue(load.claimForOpening(replayRequested = true))
        assertEquals(true, load.replayRequested)
        assertFalse(load.claimForOpening(replayRequested = true), "a second load")
        assertFalse(load.claimForOpening(replayRequested = false), "a resume of a holder that a load owns")
        assertEquals(true, load.replayRequested, "a refused claim leaves the holder unchanged")

        val resume = ClientSessionHolder()
        assertTrue(resume.claimForOpening(replayRequested = false))
        assertEquals(false, resume.replayRequested)
        assertFalse(resume.claimForOpening(replayRequested = false), "a second resume")
        assertFalse(resume.claimForOpening(replayRequested = true), "a load of a holder that a resume owns")

        val failed = ClientSessionHolder()
        failed.failAndDrainQueue(IllegalStateException("load failed")) { _, _ -> }
        assertTrue(failed.isFailed)
        assertFalse(failed.claimForOpening(replayRequested = true), "an opening of a failed holder")
        assertNull(failed.replayRequested)
    }

    private class RecordingOperations(
        private val beforeEach: suspend (SessionUpdate) -> Unit = {},
    ) : ClientSessionOperations {
        val updates = mutableListOf<SessionUpdate>()
        var openedCount = 0

        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = error("not expected in this test")

        override suspend fun notify(event: SessionEvent) {
            when (event) {
                is SessionEvent.Update -> {
                    beforeEach(event.update)
                    updates += event.update
                }
                is SessionEvent.Opened -> openedCount++
            }
        }
    }
}
