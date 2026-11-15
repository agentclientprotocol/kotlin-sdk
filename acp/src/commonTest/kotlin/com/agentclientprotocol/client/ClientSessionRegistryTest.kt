package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class SilentTransport : BaseTransport() {
    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSED
    }

    override fun send(frame: TransportFrame) {}
}

/**
 * The registration of subagents in [ClientSessionRegistry], driven directly without a wire: the tree of holders, the
 * markers of the root's answer, the blocking rule and the failure cascade.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class ClientSessionRegistryTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val protocol = Protocol(scope, SilentTransport())
    private val unconnected = mutableListOf<Pair<SessionId, SessionUpdate>>()
    private val registry = ClientSessionRegistry({ id, update, _ -> unconnected += id to update }, protocol, scope)
    private val parameters = SessionCreationParameters(cwd = "/root", mcpServers = emptyList())

    private val root = SessionId("root")
    private val child = SessionId("child")
    private val grandchild = SessionId("grandchild")

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a subagent registered before the answer gets its marker at the answer and one registered after gets it first`() = runTest {
        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = false, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, text("child-history"), null)
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))

            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            val late = SessionId("late")
            registry.deliverSessionUpdate(root, announce(late), null)
            val lateHolder = assertNotNull(rootHolder.childOrNull(late))
            registry.deliverSessionUpdate(late, text("late-live"), null)

            assertNull(assertNotNull(grandchildHolder.deliverEarly(Recorder())).response, "a grandchild gets its marker too")
            val childOperations = Recorder()
            assertNull(assertNotNull(childHolder.deliverEarly(childOperations)).response)
            assertEquals(listOf<SessionUpdate>(text("child-history"), announce(grandchild)), childOperations.updates)
            val lateOperations = Recorder()
            assertNotNull(lateHolder.deliverEarly(lateOperations), "a subagent registered after the answer has its marker first")
            assertTrue(lateOperations.updates.isEmpty())

            assertSame(rootHolder, grandchildHolder.root)
        }
    }

    @Test
    fun `a failed subagent keeps its id while its parent lives and its subtree fails with it`() = runTest {
        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))
            registry.deliverSessionUpdate(grandchild, text("grandchild-queued"), null)

            registry.failSessionHolder(childHolder, IllegalStateException("setup failed"))
            assertTrue(childHolder.isFailed)
            assertTrue(grandchildHolder.isFailed, "the cascade fails the subtree")
            assertFalse(rootHolder.isFailed)
            assertEquals(listOf<Pair<SessionId, SessionUpdate>>(child to announce(grandchild), grandchild to text("grandchild-queued")), unconnected, "parent first")

            assertTrue(childHolder.blocksId, "a failed subagent of a live parent blocks its id")
            assertFalse(grandchildHolder.blocksId, "a failed subagent of a failed parent does not")

            // A later patch of the failed subagent stays a patch, and nothing registers under it.
            registry.deliverSessionUpdate(root, announce(child), null)
            assertSame(childHolder, rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, text("after"), null)
            registry.deliverSessionUpdate(child, announce(SessionId("under-failed")), null)
            assertEquals(listOf<Pair<SessionId, SessionUpdate>>(child to text("after"), child to announce(SessionId("under-failed"))), unconnected.takeLast(2))
            assertNull(childHolder.childOrNull(SessionId("under-failed")))

            val delivered = Recorder()
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            assertNotNull(rootHolder.deliverEarly(delivered))
            assertEquals(listOf<SessionUpdate>(announce(child), announce(child)), delivered.updates)
        }
    }

    @Test
    fun `an opening of a subagent's id is refused`() = runTest {
        registry.withInitializingSession {
            registry.claimSessionHolder(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)

            val refused = runCatching { registry.claimSessionHolder(child, replayRequested = true, parameters) }
            assertEquals("Session $child is already open or being opened", refused.exceptionOrNull()?.message)
            assertNull(registry.claimAnsweredSessionHolder(child, LoadSessionResponse(), parameters))
        }
    }

    @Test
    fun `a failing root stops its subagents' consumers so that none starts afterwards`() = runTest {
        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))

            registry.failSessionHolder(rootHolder, IllegalStateException("load failed"))
            var started = false
            childHolder.startConsumer(this@runTest) { started = true }
            runCurrent()
            assertFalse(started, "a setup that returns after the failure starts no consumer")

            // A failed parent takes no subagents any more.
            assertFalse(childHolder.addChild(ClientSessionHolder(grandchild, childHolder, SubagentSetup(protocol, scope) { _, _ -> })))
        }
    }

    @Test
    fun `removing a tree frees its ids and fails its unopened subagents and starts no consumer afterwards`() = runTest {
        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = false, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))
            registry.deliverSessionUpdate(grandchild, text("grandchild-queued"), null)

            assertEquals(setOf(root, child, grandchild), registry.removeSessionTree(root))
            assertEquals(emptySet(), registry.removeSessionTree(root), "a removed tree is removed once")

            for (holder in listOf(childHolder, grandchildHolder)) {
                assertEquals("Session ${holder.sessionId} closed", holder.session.getCompletionExceptionOrNull()?.message)
            }
            assertFalse(rootHolder.isFailed, "the root's opening is not the removal's to fail")
            assertEquals(
                listOf<Pair<SessionId, SessionUpdate>>(grandchild to text("grandchild-queued"), child to announce(grandchild)),
                unconnected,
                "each queue is handed on, bottom-up",
            )

            var started = false
            grandchildHolder.startConsumer(this@runTest) { started = true }
            runCurrent()
            assertFalse(started, "a setup that returns after the removal starts no consumer")
            assertFalse(rootHolder.addChild(ClientSessionHolder(SessionId("late"), rootHolder, SubagentSetup(protocol, scope) { _, _ -> })))
        }
    }

    @Test
    fun `a consumer starts once and stopping it returns the running one`() = runTest {
        val holder = ClientSessionHolder(child, ClientSessionHolder(root, null, SubagentSetup(protocol, scope) { _, _ -> }), SubagentSetup(protocol, scope) { _, _ -> })
        var runs = 0
        holder.startConsumer(this) {
            runs++
            awaitCancellation()
        }
        runCurrent()
        assertEquals(1, runs)

        val running = assertNotNull(holder.stopConsumer())
        assertTrue(running.isActive)
        running.cancel()
        assertNull(holder.stopConsumer(), "a stopped consumer is returned once")
    }

    @Test
    fun `an announcement whose id another holder owns is a plain update and registers nothing`() = runTest {
        val speculative = SessionId("speculative")
        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
            registry.claimSessionHolder(SessionId("other"), replayRequested = true, parameters)
            registry.deliverSessionUpdate(speculative, text("speculative"), null)

            for (id in listOf(root, SessionId("other"), speculative)) {
                registry.deliverSessionUpdate(root, announce(id), null)
                assertNull(rootHolder.childOrNull(id), "$id")
            }

            val delivered = Recorder()
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            assertNotNull(rootHolder.deliverEarly(delivered))
            assertEquals(listOf<SessionUpdate>(announce(root), announce(SessionId("other")), announce(speculative)), delivered.updates)
        }
        // The reaping fails the speculative holder; the announcement it rejected registered nothing that could stay.
        assertEquals(listOf<Pair<SessionId, SessionUpdate>>(speculative to text("speculative")), unconnected)
        registry.deliverSessionUpdate(speculative, text("after"), null)
        assertEquals<Pair<SessionId, SessionUpdate>>(speculative to text("after"), unconnected.last())
    }

    @Test
    fun `the subagents of a failed root leave the session map when the root is reaped`() = runTest {
        for (attempt in 1..3) {
            val attemptChild = SessionId("child-$attempt")
            val attemptGrandchild = SessionId("grandchild-$attempt")
            registry.withInitializingSession {
                val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
                registry.deliverSessionUpdate(root, announce(attemptChild), null)
                registry.deliverSessionUpdate(attemptChild, announce(attemptGrandchild), null)
                assertNotNull(rootHolder.childOrNull(attemptChild)?.childOrNull(attemptGrandchild))
                registry.failSessionHolder(rootHolder, IllegalStateException("load failed"))
            }
            for (id in listOf(root, attemptChild, attemptGrandchild)) {
                assertFalse(registry.isSessionCompleted(id), "$id is still in the map after attempt $attempt")
            }
        }

        registry.withInitializingSession {
            val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
            for (attempt in 1..3) {
                registry.deliverSessionUpdate(root, announce(SessionId("child-$attempt")), null)
                assertNotNull(rootHolder.childOrNull(SessionId("child-$attempt")), "a later load registers child-$attempt again")
            }
        }
    }

    @Test
    fun `the subagents of a failed root leave the session map when a new opening replaces the root`() = runTest {
        registry.withInitializingSession {
            // The outer opening keeps the failed root from being reaped.
            val failed = registry.withInitializingSession {
                val rootHolder = registry.claimSessionHolder(root, replayRequested = true, parameters)
                registry.deliverSessionUpdate(root, announce(child), null)
                registry.deliverSessionUpdate(child, announce(grandchild), null)
                registry.failSessionHolder(rootHolder, IllegalStateException("load failed"))
                rootHolder
            }
            assertTrue(registry.isSessionCompleted(child), "the failed subagent stays while its root isn't replaced")

            val retried = registry.claimSessionHolder(root, replayRequested = true, parameters)
            assertTrue(retried !== failed)
            for (id in listOf(child, grandchild)) assertFalse(registry.isSessionCompleted(id), "$id is still in the map")

            registry.deliverSessionUpdate(root, announce(child), null)
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            assertNotNull(retried.childOrNull(child)?.childOrNull(grandchild), "the retry registers the same ids again")
        }
    }

    @Test
    fun `a subagent consumer that the failure cascade stops takes no item once its notify returns`() = runTest {
        val handedOn = mutableListOf<SessionUpdate>()
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val drainingRegistry = ClientSessionRegistry({ _, update, _ ->
            handedOn += update
            if (update == text("second")) {
                drainEntered.complete(Unit)
                releaseDrain.await()
            }
        }, protocol, scope)

        val notifyEntered = CompletableDeferred<Job>()
        val releaseNotify = CompletableDeferred<Unit>()
        val childUpdates = MutableStateFlow<List<SessionUpdate>>(emptyList())
        val childOperations = object : ClientSessionOperations by Recorder() {
            override suspend fun notify(event: SessionEvent) {
                if (event !is SessionEvent.Update) return
                if (event.update == text("first")) {
                    notifyEntered.complete(currentCoroutineContext().job)
                    // Ignores the cancellation, like an app call that doesn't suspend.
                    withContext(NonCancellable) { releaseNotify.await() }
                }
                childUpdates.update { it + event.update }
            }
        }
        val rootOperations = object : ClientSessionOperations by Recorder(), SubagentOperations {
            override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations = childOperations
        }

        drainingRegistry.withInitializingSession {
            val rootHolder = drainingRegistry.claimSessionHolder(root, replayRequested = true, parameters)
            drainingRegistry.deliverSessionUpdate(root, announce(child), null)
            for (name in listOf("first", "second", "third")) drainingRegistry.deliverSessionUpdate(child, text(name), null)
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            assertNotNull(rootHolder.deliverEarly(rootOperations))
            val consumer = notifyEntered.await()

            val failing = launch { drainingRegistry.failSessionHolder(rootHolder, IllegalStateException("load failed")) }
            // The cascade has cancelled the consumer, and hands the queue on while the consumer is still in notify.
            drainEntered.await()
            releaseNotify.complete(Unit)
            consumer.join()
            releaseDrain.complete(Unit)
            failing.join()

            assertEquals(listOf<SessionUpdate>(text("first")), childUpdates.value)
            assertEquals(listOf<SessionUpdate>(text("second"), text("third")), handedOn)
        }
    }

    private class Recorder : ClientSessionOperations {
        val updates = mutableListOf<SessionUpdate>()

        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = error("not expected in this test")

        override suspend fun notify(event: SessionEvent) {
            if (event is SessionEvent.Update) updates += event.update
        }
    }

    private companion object {
        fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        fun announce(id: SessionId) = SessionUpdate.SubagentUpdate(id)
    }
}
