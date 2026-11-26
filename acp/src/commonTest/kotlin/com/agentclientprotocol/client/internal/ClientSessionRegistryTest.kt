package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.SubagentOperations
import com.agentclientprotocol.client.internal.SubagentSessionHolder.OpeningState
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * markers of the root's answer, the ownership of ids and the failure cascade.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class ClientSessionRegistryTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val protocol = Protocol(scope, SilentTransport())

    /*
     * The subagents' openings of [registry] run only when a test runs [openingScheduler], so a test can tell whether
     * one was started.
     */
    private val openingScheduler = TestCoroutineScheduler()
    private val openingScope = CoroutineScope(StandardTestDispatcher(openingScheduler) + SupervisorJob())
    private val unconnected = mutableListOf<Pair<SessionId, SessionUpdate>>()
    private val registry = ClientSessionRegistry({ id, update, _ -> unconnected += id to update }, protocol, openingScope)
    private val parameters = SessionCreationParameters(cwd = "/root", mcpServers = emptyList())

    private val root = SessionId("root")
    private val child = SessionId("child")
    private val grandchild = SessionId("grandchild")

    @AfterTest
    fun tearDown() {
        openingScope.cancel()
        scope.cancel()
    }

    @Test
    fun `a subagent registered before the answer gets its marker at the answer and one registered after gets it first`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = false, parameters)
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

            assertEquals(1, consumeUntilOpened(grandchildHolder).openedCount, "a grandchild gets its marker too")
            val childOperations = consumeUntilOpened(childHolder)
            assertEquals(1, childOperations.openedCount)
            assertEquals(listOf<SessionUpdate>(text("child-history"), announce(grandchild)), childOperations.updates)
            val lateOperations = consumeUntilOpened(lateHolder)
            assertEquals(1, lateOperations.openedCount, "a subagent registered after the answer has its marker first")
            assertTrue(lateOperations.updates.isEmpty())

            assertSame(rootHolder, grandchildHolder.root)
        }
    }

    @Test
    fun `a subagent registered after the close started has its permission requests answered cancelled`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = false, parameters)
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            assertEquals("answered", answerOf(childHolder), "before the close, a request reaches the app")

            rootHolder.startClosing()
            assertEquals("cancelled", answerOf(childHolder), "the walk closes the subagents it finds")

            val late = SessionId("late")
            registry.deliverSessionUpdate(root, announce(late), null)
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            for (holder in listOf(assertNotNull(rootHolder.childOrNull(late)), assertNotNull(childHolder.childOrNull(grandchild)))) {
                assertEquals("cancelled", answerOf(holder), "${holder.sessionId}: its registration closes its permissions")
            }
        }
    }

    @Test
    fun `a failed subagent keeps its id while its root lives and its subtree fails with it`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))
            registry.deliverSessionUpdate(grandchild, text("grandchild-queued"), null)

            assertTrue(registry.failSubagentTree(childHolder, IllegalStateException("setup failed")))
            assertTrue(childHolder.isFailed)
            assertTrue(grandchildHolder.isFailed, "the cascade fails the subtree")
            assertFalse(rootHolder.isFailed)
            assertEquals(listOf<Pair<SessionId, SessionUpdate>>(child to announce(grandchild), grandchild to text("grandchild-queued")), unconnected, "parent first")

            /*
             * The grandchild keeps its id under its failed parent too; before the failed root's tree left the map at
             * its failure, a failed subagent of a failed parent freed its id.
             */
            for (holder in listOf(childHolder, grandchildHolder)) {
                assertSame(holder, registry.holderForRequest(holder.sessionId), "${holder.sessionId} keeps its id")
                assertNull(registry.claimOnAnswer(holder.sessionId, parameters), "${holder.sessionId}: an opening of the id is refused")
            }

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
        registry.withOpeningCall {
            registry.claimBeforeRequest(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)

            val refused = runCatching { registry.claimBeforeRequest(child, replayRequested = true, parameters) }
            assertEquals("Session $child is already open or being opened", refused.exceptionOrNull()?.message)
            assertNull(registry.claimOnAnswer(child, parameters))
        }
    }

    @Test
    fun `a failing root stops its subagents' openings so that none starts afterwards`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))

            registry.failRootTree(rootHolder, IllegalStateException("load failed"))
            val session = ClientSubagentSession(childHolder, Recorder(), protocol)
            childHolder.startOpening(session)
            assertTrue(openingScope.coroutineContext.job.children.none(), "a setup that returns after the failure starts no opening")
            assertClosed(session)

            // A failed parent takes no subagents any more.
            assertFalse(childHolder.addChild(SubagentSessionHolder(grandchild, childHolder)))
        }
    }

    @Test
    fun `removing a tree frees its ids and fails its unopened subagents and starts no opening afterwards`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = false, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))
            registry.deliverSessionUpdate(grandchild, text("grandchild-queued"), null)

            assertEquals(setOf(root, child, grandchild), registry.removeSessionTree(rootHolder))
            assertEquals(emptySet(), registry.removeSessionTree(rootHolder), "a removed tree is removed once")

            for (holder in listOf(childHolder, grandchildHolder)) {
                assertEquals("Session ${holder.sessionId} closed", holder.session.getCompletionExceptionOrNull()?.message)
            }
            assertFalse(rootHolder.isFailed, "the root's opening is not the removal's to fail")
            assertEquals(
                listOf<Pair<SessionId, SessionUpdate>>(grandchild to text("grandchild-queued"), child to announce(grandchild)),
                unconnected,
                "each queue is handed on, bottom-up",
            )

            val session = ClientSubagentSession(grandchildHolder, Recorder(), protocol)
            grandchildHolder.startOpening(session)
            assertTrue(openingScope.coroutineContext.job.children.none(), "a setup that returns after the removal starts no opening")
            assertClosed(session)
            assertFalse(rootHolder.addChild(SubagentSessionHolder(SessionId("late"), rootHolder)))
        }
    }

    @Test
    fun `an opening starts once and stopping it returns the running one`() = runTest {
        val holder = SubagentSessionHolder(child, RootSessionHolder(root, protocol, this) { _, _ -> true })
        val session = ClientSubagentSession(holder, Recorder(), protocol)
        holder.startOpening(session)
        runCurrent()

        val running = assertIs<OpeningState.Running>(holder.markOpeningStopped()).job
        assertTrue(running.isActive, "the opening started and waits for the opened marker, and marking it stopped doesn't cancel it")
        running.cancel()
        assertSame(OpeningState.Stopped, holder.markOpeningStopped(), "a stopped opening is returned once")
    }

    @Test
    fun `an opening stopped after its last cancellation check does not complete its holder`() = runTest {
        val liveEntered = CompletableDeferred<Unit>()
        val releaseLive = CompletableDeferred<Unit>()
        val childOperations = object : ClientSessionOperations by Recorder() {
            override suspend fun notify(event: SessionEvent) {
                if (event is SessionEvent.Update && event.update == text("live")) {
                    liveEntered.complete(Unit)
                    releaseLive.await()
                }
            }
        }

        registry.withOpeningCall {
            val (rootHolder, childHolder) = openRootWithChild(childOperations, liveUpdate = text("live"))
            val request = async { runCatching { registry.awaitSession(child) } }
            runCurrent()
            openingScheduler.runCurrent()
            assertTrue(liveEntered.isCompleted, "the opening delivers the last live update")

            /*
             * The opening took the last update before the close stopped it, so it passed its last cancellation check:
             * stopping it without the cancellation lets it run on, as an opening on another thread can, into the
             * empty queue and up to the completion of its holder.
             */
            assertIs<OpeningState.Running>(childHolder.markOpeningStopped())
            releaseLive.complete(Unit)
            openingScheduler.runCurrent()
            assertFalse(childHolder.session.isCompleted, "a stopped opening doesn't complete its holder")

            // The stop's caller settles the holder, so neither a later removal nor a later failure takes it over.
            assertEquals(setOf(root, child), registry.removeSessionTree(rootHolder))
            assertFalse(registry.failSubagentTree(childHolder, IllegalStateException("late failure")))
            assertFalse(childHolder.session.isCompleted)
            request.cancel()
        }
    }

    @Test
    fun `an opening whose notify fails after the close cancelled it leaves its holder to the close`() = runTest {
        val handedOn = mutableListOf<String>()
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val drainingRegistry = ClientSessionRegistry({ _, update, _ ->
            handedOn += "enter $update"
            if (update == text("second")) {
                drainEntered.complete(Unit)
                releaseDrain.await()
            }
            handedOn += "exit $update"
        }, protocol, openingScope)

        val notifyEntered = CompletableDeferred<Unit>()
        val childOperations = object : ClientSessionOperations by Recorder() {
            override suspend fun notify(event: SessionEvent) {
                if (event is SessionEvent.Update && event.update == text("first")) {
                    notifyEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (ce: CancellationException) {
                        throw IllegalStateException("notify turned its cancellation into a failure", ce)
                    }
                }
            }
        }

        drainingRegistry.withOpeningCall {
            val (rootHolder, _) = openRootWithChild(
                childOperations,
                history = listOf(text("first"), text("second"), text("third")),
                registry = drainingRegistry,
            )
            val request = async { runCatching { drainingRegistry.awaitSession(child) } }
            runCurrent()
            openingScheduler.runCurrent()
            assertTrue(notifyEntered.isCompleted, "the opening is in notify before the opened marker")

            /*
             * The close cancels the opening and fails the holder, and its drain waits in the handler. The opening then
             * runs, on its own scheduler, into the failure of its notify, while the close's drain is not done.
             */
            val closing = launch { drainingRegistry.removeSessionTree(rootHolder) }
            runCurrent()
            assertTrue(drainEntered.isCompleted)
            openingScheduler.runCurrent()
            releaseDrain.complete(Unit)
            closing.join()

            assertEquals("Session $child closed", request.await().exceptionOrNull()?.message)
            assertEquals(
                listOf("enter ${text("second")}", "exit ${text("second")}", "enter ${text("third")}", "exit ${text("third")}"),
                handedOn,
                "one drain hands the queue on, in order",
            )
        }
    }

    @Test
    fun `a setup that fails after the failure cascade cancelled it leaves the subagent to the cascade`() = runTest {
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val drainingRegistry = ClientSessionRegistry({ _, update, _ ->
            if (update == text("child-queued")) {
                drainEntered.complete(Unit)
                releaseDrain.await()
            }
        }, protocol, openingScope)

        val factoryEntered = CompletableDeferred<Unit>()
        val childOperations = object : ClientSessionOperations by Recorder(), SubagentOperations {
            override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations {
                factoryEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (ce: CancellationException) {
                    throw IllegalStateException("the factory turned its cancellation into a failure", ce)
                }
            }
        }
        val rootOperations = object : ClientSessionOperations by Recorder(), SubagentOperations {
            override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations = childOperations
        }

        drainingRegistry.withOpeningCall {
            val rootHolder = drainingRegistry.claimBeforeRequest(root, replayRequested = true, parameters)
            drainingRegistry.deliverSessionUpdate(root, announce(child), null)
            val childHolder = assertNotNull(rootHolder.childOrNull(child))
            drainingRegistry.deliverSessionUpdate(child, announce(grandchild), null)
            val grandchildHolder = assertNotNull(childHolder.childOrNull(grandchild))
            drainingRegistry.deliverSessionUpdate(child, text("child-queued"), null)
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            assertNotNull(rootHolder.deliverEarly(rootOperations))
            openingScheduler.runCurrent()
            assertTrue(factoryEntered.isCompleted, "the child's opening sets the grandchild up")

            /*
             * The cascade cancels the child's opening and waits in the drain of the child's queue, before it reaches
             * the grandchild. The opening then runs into the failure of the grandchild's setup.
             */
            val failing = launch { drainingRegistry.failRootTree(rootHolder, IllegalStateException("load failed")) }
            runCurrent()
            assertTrue(drainEntered.isCompleted)
            openingScheduler.runCurrent()
            releaseDrain.complete(Unit)
            failing.join()

            assertEquals(
                "Session $child, the parent of subagent session $grandchild, failed",
                grandchildHolder.session.getCompletionExceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun `a subagent whose opening finished counts as opened and closed after the removal`() = runTest {
        registry.withOpeningCall {
            val (rootHolder, childHolder) = openRootWithChild(Recorder())
            openingScheduler.runCurrent()
            val session = assertIs<ClientSubagentSession>(childHolder.session.await())

            assertEquals(setOf(root, child), registry.removeSessionTree(rootHolder))
            assertSame(session, childHolder.session.getCompleted(), "the removal doesn't fail an opened subagent")
            assertClosed(session)
            assertSame(OpeningState.Stopped, childHolder.markOpeningStopped())
        }
    }

    @Test
    fun `an announcement whose id another holder owns is a plain update and registers nothing`() = runTest {
        val speculative = SessionId("speculative")
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            registry.claimBeforeRequest(SessionId("other"), replayRequested = true, parameters)
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
    fun `only a session whose opening succeeded counts as completed`() = runTest {
        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            registry.deliverSessionUpdate(root, announce(child), null)
            assertNull(registry.completedSessionOrNull(root), "an opening root is not completed")
            assertNull(registry.completedSessionOrNull(child), "an opening subagent is not completed")

            assertTrue(registry.failSubagentTree(assertNotNull(rootHolder.childOrNull(child)), IllegalStateException("setup failed")))
            assertTrue(registry.isSessionCompleted(child), "the failed subagent stays in the map")
            assertNull(registry.completedSessionOrNull(child), "a failed subagent is not completed")
        }
    }

    @Test
    fun `a failed root's tree leaves the session map with it and a later load registers the same ids again`() = runTest {
        for (attempt in 1..3) {
            val attemptChild = SessionId("child-$attempt")
            val attemptGrandchild = SessionId("grandchild-$attempt")
            registry.withOpeningCall {
                val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
                registry.deliverSessionUpdate(root, announce(attemptChild), null)
                registry.deliverSessionUpdate(attemptChild, announce(attemptGrandchild), null)
                assertNotNull(rootHolder.childOrNull(attemptChild)?.childOrNull(attemptGrandchild))
                registry.failRootTree(rootHolder, IllegalStateException("load failed"))
            }
            for (id in listOf(root, attemptChild, attemptGrandchild)) {
                assertFalse(registry.isSessionCompleted(id), "$id is still in the map after attempt $attempt")
            }
        }

        registry.withOpeningCall {
            val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            for (attempt in 1..3) {
                registry.deliverSessionUpdate(root, announce(SessionId("child-$attempt")), null)
                assertNotNull(rootHolder.childOrNull(SessionId("child-$attempt")), "a later load registers child-$attempt again")
            }
        }
    }

    @Test
    fun `a failed root's tree is out of the session map when its failure returns even while another opening runs`() = runTest {
        // The outer opening keeps the end of the last opening from removing anything.
        registry.withOpeningCall {
            val failed = registry.withOpeningCall {
                val rootHolder = registry.claimBeforeRequest(root, replayRequested = true, parameters)
                registry.deliverSessionUpdate(root, announce(child), null)
                registry.deliverSessionUpdate(child, announce(grandchild), null)
                registry.failRootTree(rootHolder, IllegalStateException("load failed"))
                for (id in listOf(root, child, grandchild)) assertFalse(registry.isSessionCompleted(id), "$id is still in the map")
                rootHolder
            }
            assertFailsWith<JsonRpcException> { registry.holderForRequest(child) }

            val retried = registry.claimBeforeRequest(root, replayRequested = true, parameters)
            assertTrue(retried !== failed)

            registry.deliverSessionUpdate(root, announce(child), null)
            registry.deliverSessionUpdate(child, announce(grandchild), null)
            assertNotNull(retried.childOrNull(child)?.childOrNull(grandchild), "the retry registers the same ids again")
        }
    }

    @Test
    fun `a failing root keeps its id until its cascade ends and an opening of the id meanwhile is refused`() = runTest {
        val handedOn = mutableListOf<Pair<SessionId, SessionUpdate>>()
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val drainingRegistry = ClientSessionRegistry({ id, update, _ ->
            handedOn += id to update
            if (update == text("child-queued")) {
                drainEntered.complete(Unit)
                releaseDrain.await()
            }
        }, protocol, openingScope)

        drainingRegistry.withOpeningCall {
            val rootHolder = drainingRegistry.claimBeforeRequest(root, replayRequested = true, parameters)
            drainingRegistry.deliverSessionUpdate(root, announce(child), null)
            drainingRegistry.deliverSessionUpdate(child, text("child-queued"), null)

            // The cascade has failed the root and waits in the drain of the child's queue.
            val failing = launch { drainingRegistry.failRootTree(rootHolder, IllegalStateException("load failed")) }
            drainEntered.await()
            assertTrue(rootHolder.isFailed)

            val refused = runCatching { drainingRegistry.claimBeforeRequest(root, replayRequested = true, parameters) }
            assertEquals("Session $root is already open or being opened", refused.exceptionOrNull()?.message)
            drainingRegistry.deliverSessionUpdate(root, text("during"), null)
            assertEquals<Pair<SessionId, SessionUpdate>>(root to text("during"), handedOn.last(), "an update meanwhile is unconnected")

            releaseDrain.complete(Unit)
            failing.join()
            assertTrue(drainingRegistry.claimBeforeRequest(root, replayRequested = true, parameters) !== rootHolder)
        }
    }

    @Test
    fun `a subagent opening that the failure cascade stops takes no item once its notify returns`() = runTest {
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

        drainingRegistry.withOpeningCall {
            val rootHolder = drainingRegistry.claimBeforeRequest(root, replayRequested = true, parameters)
            drainingRegistry.deliverSessionUpdate(root, announce(child), null)
            for (name in listOf("first", "second", "third")) drainingRegistry.deliverSessionUpdate(child, text(name), null)
            assertTrue(rootHolder.markOpened(LoadSessionResponse()))
            assertNotNull(rootHolder.deliverEarly(rootOperations))
            val opening = notifyEntered.await()

            val failing = launch { drainingRegistry.failRootTree(rootHolder, IllegalStateException("load failed")) }
            // The cascade has cancelled the opening, and hands the queue on while the opening is still in notify.
            drainEntered.await()
            releaseNotify.complete(Unit)
            opening.join()
            releaseDrain.complete(Unit)
            failing.join()

            assertEquals(listOf<SessionUpdate>(text("first")), childUpdates.value)
            assertEquals(listOf<SessionUpdate>(text("second"), text("third")), handedOn)
        }
    }

    /**
     * Opens [root] in [registry] without a replay, with the subagent [child] announced before the answer and set up
     * with [childOperations], [history] queued for [child] before its opened marker and [liveUpdate], if any, after
     * it. Returns the holders of both; the root's has completed, the child's opening is started but hasn't run.
     */
    private suspend fun openRootWithChild(
        childOperations: ClientSessionOperations,
        liveUpdate: SessionUpdate? = null,
        history: List<SessionUpdate> = emptyList(),
        registry: ClientSessionRegistry = this.registry,
    ): Pair<RootSessionHolder, SubagentSessionHolder> {
        val rootOperations = object : ClientSessionOperations by Recorder(), SubagentOperations {
            override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations = childOperations
        }
        val rootHolder = registry.claimBeforeRequest(root, replayRequested = false, parameters)
        registry.deliverSessionUpdate(root, announce(child), null)
        val childHolder = assertNotNull(rootHolder.childOrNull(child))
        for (update in history) registry.deliverSessionUpdate(child, update, null)
        assertTrue(rootHolder.markOpened(LoadSessionResponse()))
        if (liveUpdate != null) registry.deliverSessionUpdate(child, liveUpdate, null)

        val rootSession = ClientSessionImpl(
            client = Client(protocol),
            sessionId = root,
            parameters = parameters,
            operations = rootOperations,
            createdResponse = LoadSessionResponse(),
            protocol = protocol,
            isOpen = { true },
            onClosed = {},
            holder = rootHolder,
        )
        rootHolder.completeOpening(rootSession, rootHolder.drainHistory(rootSession))
        return rootHolder to childHolder
    }

    /**
     * Returns `cancelled` if a permission request of [holder] would be answered `cancelled` without reaching the app.
     */
    private suspend fun answerOf(holder: SubagentSessionHolder): String =
        holder.permissions.answerUnlessCancelled({ "cancelled" }) { "answered" }

    /**
     * Runs the opening of [holder] until it has delivered the opened marker, and returns what its operations got. The
     * opening then waits for the parent's holder to complete, which these tests don't do, so it is cancelled.
     */
    private fun consumeUntilOpened(holder: SubagentSessionHolder): Recorder {
        val operations = Recorder()
        val session = ClientSubagentSession(holder, operations, protocol)
        holder.startOpening(session)
        openingScheduler.runCurrent()
        assertIs<OpeningState.Running>(holder.markOpeningStopped()).job.cancel()
        openingScheduler.runCurrent()
        return operations
    }

    private suspend fun assertClosed(session: ClientSubagentSession) {
        val failure = runCatching { session.cancel() }.exceptionOrNull()
        assertEquals("Subagent session ${session.sessionId} is closed", failure?.message)
    }

    private class Recorder : ClientSessionOperations {
        val updates = mutableListOf<SessionUpdate>()
        var openedCount = 0

        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = error("not expected in this test")

        override suspend fun notify(event: SessionEvent) {
            when (event) {
                is SessionEvent.Update -> updates += event.update
                is SessionEvent.Opened -> openedCount++
            }
        }
    }

    private companion object {
        fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        fun announce(id: SessionId) = SessionUpdate.SubagentUpdate(id)
    }
}
