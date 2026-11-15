@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ForkSessionResponse
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.ResumeSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Every operations object of a root session gets exactly one [com.agentclientprotocol.common.SessionEvent.Opened]
 * with the answer of the call that opened the session, at the place where the answer fell among the session's
 * updates on the wire. A failed opening delivers none. The tests play the agent on a scripted transport, so they
 * control the exact wire order of the updates and the answer.
 */
class ClientSessionOpenedTest {
    private val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())

    /**
     * One way to open a session: the call, its method, and the answer the scripted agent gives.
     *
     * @property answeredId whether the session id first appears in the answer, as for `session/new` and
     *   `session/fork`
     * @property factory whether the call takes a [ClientOperationsFactory]
     */
    private class Opening(
        val name: String,
        val method: MethodName,
        val answeredId: Boolean,
        val factory: Boolean,
        val open: suspend (Client, SessionId, SessionCreationParameters, RecordingOperations) -> ClientSession,
        val answer: (ScriptedAgentTransport, JsonRpcRequest, SessionId, String) -> AcpCreatedSessionResponse,
    ) {
        override fun toString() = name
    }

    private val loadWithOperations = Opening(
        "load with operations", AcpMethod.AgentMethods.V1.SessionLoad.methodName, answeredId = false, factory = false,
        open = { client, id, parameters, operations -> client.loadSession(id, parameters, operations) },
        answer = { agent, request, _, tag -> loadResponse(agent, request, tag) },
    )
    private val resumeWithOperations = Opening(
        "resume with operations", AcpMethod.AgentMethods.V1.SessionResume.methodName, answeredId = false, factory = false,
        open = { client, id, parameters, operations -> client.resumeSession(id, parameters, operations) },
        answer = { agent, request, _, tag -> resumeResponse(agent, request, tag) },
    )
    private val loadWithFactory = Opening(
        "load with a factory", AcpMethod.AgentMethods.V1.SessionLoad.methodName, answeredId = false, factory = true,
        open = { client, id, parameters, operations -> client.loadSession(id, parameters) { _, _ -> operations } },
        answer = { agent, request, _, tag -> loadResponse(agent, request, tag) },
    )
    private val resumeWithFactory = Opening(
        "resume with a factory", AcpMethod.AgentMethods.V1.SessionResume.methodName, answeredId = false, factory = true,
        open = { client, id, parameters, operations -> client.resumeSession(id, parameters) { _, _ -> operations } },
        answer = { agent, request, _, tag -> resumeResponse(agent, request, tag) },
    )
    private val newWithFactory = Opening(
        "new", AcpMethod.AgentMethods.V1.SessionNew.methodName, answeredId = true, factory = true,
        open = { client, _, parameters, operations -> client.newSession(parameters) { _, _ -> operations } },
        answer = { agent, request, id, tag ->
            NewSessionResponse(id, _meta = JsonPrimitive(tag))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, it) }
        },
    )
    private val forkWithFactory = Opening(
        "fork", AcpMethod.AgentMethods.V1.SessionFork.methodName, answeredId = true, factory = true,
        open = { client, _, parameters, operations -> client.forkSession(SessionId("fork-source"), parameters) { _, _ -> operations } },
        answer = { agent, request, id, tag ->
            ForkSessionResponse(id, _meta = JsonPrimitive(tag))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionFork.responseSerializer, it) }
        },
    )

    private val allOpenings = listOf(loadWithOperations, resumeWithOperations, loadWithFactory, resumeWithFactory, newWithFactory, forkWithFactory)

    /**
     * The openings whose session id is known before the request, so the one-opening rule refuses them before it.
     */
    private val knownIdOpenings = listOf(loadWithOperations, resumeWithOperations, loadWithFactory, resumeWithFactory)

    @Test
    fun `the marker falls between the updates sent before and after the answer`() = withClient { client, agent ->
        /*
         * The updates after the answer are put on the wire right behind it, so the switch to the session races with
         * them. Each round can take either side.
         */
        for (opening in allOpenings) {
            repeat(ROUNDS) { round ->
                val sessionId = SessionId("$opening-$round")
                val operations = RecordingOperations()
                val open = async { opening.open(client, sessionId, parameters, operations) }
                val request = agent.receiveRequest(opening.method)

                val beforeAnswer = listOf(text("$round-1"), text("$round-2"))
                val afterAnswer = listOf(text("$round-3"), text("$round-4"))
                beforeAnswer.forEach { agent.sendUpdate(sessionId, it) }
                val response = opening.answer(agent, request, sessionId, "$opening-$round")
                afterAnswer.forEach { agent.sendUpdate(sessionId, it) }

                assertEquals(sessionId, open.await().sessionId)
                operations.awaitEvents(beforeAnswer.size + 1 + afterAnswer.size)
                assertEquals(beforeAnswer + RecordingOperations.OPENED + afterAnswer, operations.events.value, "$opening, round $round")
                assertEquals(listOf<AcpCreatedSessionResponse?>(response), operations.openedResponses.value, "$opening, round $round")
            }
        }
    }

    @Test
    fun `the marker comes first when no update came before the answer`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val sessionId = SessionId("no-history-$opening")
            val operations = RecordingOperations()
            val open = async { opening.open(client, sessionId, parameters, operations) }
            val response = opening.answer(agent, agent.receiveRequest(opening.method), sessionId, "$opening")
            open.await()

            val live = text("live")
            agent.sendUpdate(sessionId, live)
            operations.awaitEvents(2)
            assertEquals(listOf(RecordingOperations.OPENED, live), operations.events.value, "$opening")
            assertEquals(listOf<AcpCreatedSessionResponse?>(response), operations.openedResponses.value, "$opening")
        }
    }

    @Test
    fun `an error answer delivers no marker and fails the holder`() = withUnconnectedRecorder { client, agent, unconnected ->
        for (opening in allOpenings) {
            val sessionId = SessionId("error-$opening")
            val operations = RecordingOperations()
            val open = async { runCatching { opening.open(client, sessionId, parameters, operations) } }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(sessionId, text("before-error-$opening"))
            agent.fail(request)
            assertTrue(open.await().isFailure, "$opening must fail")

            assertNoMarkerAndHolderFailed(opening, client, agent, unconnected, sessionId, operations)
        }
    }

    @Test
    fun `an answer that does not decode delivers no marker and fails the holder`() = withUnconnectedRecorder { client, agent, unconnected ->
        for (opening in allOpenings) {
            val sessionId = SessionId("undecodable-$opening")
            val operations = RecordingOperations()
            val open = async { runCatching { opening.open(client, sessionId, parameters, operations) } }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(sessionId, text("before-answer-$opening"))
            agent.answerRaw(request, JsonPrimitive("not a response"))
            assertTrue(open.await().isFailure, "$opening must fail")

            assertNoMarkerAndHolderFailed(opening, client, agent, unconnected, sessionId, operations)
        }
    }

    @Test
    fun `a call cancelled before the answer delivers no marker and fails the holder`() = withUnconnectedRecorder { client, agent, unconnected ->
        for (opening in allOpenings) {
            val sessionId = SessionId("cancelled-$opening")
            val operations = RecordingOperations()
            val open = async { opening.open(client, sessionId, parameters, operations) }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(sessionId, text("before-cancel-$opening"))
            agent.awaitHandled()

            open.cancel()
            // The client asks the agent to cancel only after it has dropped the response hook.
            agent.receiveNotification(AcpMethod.MetaMethods.CancelRequest.methodName)
            opening.answer(agent, request, sessionId, "$opening")
            open.join()
            assertTrue(open.isCancelled, "$opening must end cancelled")

            assertNoMarkerAndHolderFailed(opening, client, agent, unconnected, sessionId, operations)
        }
    }

    @Test
    fun `a call cancelled after its response hook ran delivers no marker and fails the holder`() {
        val callerExecutor = Executors.newSingleThreadExecutor()
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        try {
            withUnconnectedRecorder { client, agent, unconnected ->
                for (opening in allOpenings) {
                    val sessionId = SessionId("cancelled-after-hook-$opening")
                    val operations = RecordingOperations()
                    val open = async(callerDispatcher) { opening.open(client, sessionId, parameters, operations) }
                    val request = agent.receiveRequest(opening.method)
                    agent.sendUpdate(sessionId, text("before-answer-$opening"))
                    agent.awaitHandled()

                    // Occupy the caller's thread, so the call can't resume when the answer completes it.
                    val occupied = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    callerExecutor.execute {
                        occupied.countDown()
                        release.await()
                    }
                    occupied.await()

                    opening.answer(agent, request, sessionId, "$opening")
                    // The read loop runs the hook before it reads on, so the barrier proves that the hook has run.
                    agent.awaitHandled()
                    open.cancel()
                    release.countDown()
                    open.join()
                    assertTrue(open.isCancelled, "$opening must end cancelled")

                    assertNoMarkerAndHolderFailed(opening, client, agent, unconnected, sessionId, operations)
                }
            }
        } finally {
            callerDispatcher.close()
        }
    }

    @Test
    fun `a new session cancelled after its hook claimed the holder releases the session id`() {
        val callerExecutor = Executors.newSingleThreadExecutor()
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        try {
            withClient { client, agent ->
                // A pending opening keeps the holders from being reaped, so only the failure itself can release the id.
                val pending = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
                val pendingRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

                val sessionId = SessionId("answered-then-cancelled")
                val open = async(callerDispatcher) { client.newSession(parameters) { _, _ -> RecordingOperations() } }
                val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)
                val occupied = CountDownLatch(1)
                val release = CountDownLatch(1)
                callerExecutor.execute {
                    occupied.countDown()
                    release.await()
                }
                occupied.await()
                newWithFactory.answer(agent, request, sessionId, "cancelled")
                agent.awaitHandled()
                open.cancel()
                release.countDown()
                open.join()

                val operations = RecordingOperations()
                val load = async { client.loadSession(sessionId, parameters, operations) }
                loadResponse(agent, agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName), "load")
                assertSame(load.await(), client.getSession(sessionId))

                newWithFactory.answer(agent, pendingRequest, SessionId("pending"), "pending")
                pending.await()
            }
        } finally {
            callerDispatcher.close()
        }
    }

    @Test
    fun `an opening of an open session fails before its request`() = withClient { client, agent ->
        for (first in knownIdOpenings) {
            for (second in knownIdOpenings) {
                val sessionId = SessionId("open-$first-then-$second")
                val operations = RecordingOperations()
                val open = async { first.open(client, sessionId, parameters, operations) }
                first.answer(agent, agent.receiveRequest(first.method), sessionId, "first")
                val session = open.await()

                val refused = RecordingOperations()
                val failure = assertFailsWith<IllegalStateException>("$first then $second") {
                    second.open(client, sessionId, parameters, refused)
                }
                assertEquals("Session $sessionId is already open or being opened", failure.message)
                agent.assertNoRequest()
                assertSame(session, client.getSession(sessionId))

                val live = text("live")
                agent.sendUpdate(sessionId, live)
                operations.awaitUpdate(live)
                assertEquals(listOf(RecordingOperations.OPENED, live), operations.events.value, "$first then $second")
                assertTrue(refused.events.value.isEmpty(), "the refused opening gets no event")
            }
        }
    }

    @Test
    fun `an opening of a session being opened fails before its request`() = withClient { client, agent ->
        for (first in knownIdOpenings) {
            for (second in knownIdOpenings) {
                val sessionId = SessionId("opening-$first-then-$second")
                val operations = RecordingOperations()
                val open = async { first.open(client, sessionId, parameters, operations) }
                val request = agent.receiveRequest(first.method)

                val refused = RecordingOperations()
                val failure = assertFailsWith<IllegalStateException>("$first then $second") {
                    second.open(client, sessionId, parameters, refused)
                }
                assertEquals("Session $sessionId is already open or being opened", failure.message)
                agent.assertNoRequest()

                val history = text("history")
                agent.sendUpdate(sessionId, history)
                first.answer(agent, request, sessionId, "first")
                assertSame(open.await(), client.getSession(sessionId))
                operations.awaitEvents(2)
                assertEquals(listOf(history, RecordingOperations.OPENED), operations.events.value, "$first then $second")
                assertTrue(refused.events.value.isEmpty(), "the refused opening gets no event")
            }
        }
    }

    @Test
    fun `a session can be loaded again after it is closed`() = withClient { client, agent ->
        for (first in knownIdOpenings) {
            for (second in knownIdOpenings) {
                val sessionId = SessionId("reopen-$first-then-$second")
                val open = async { first.open(client, sessionId, parameters, RecordingOperations()) }
                first.answer(agent, agent.receiveRequest(first.method), sessionId, "first")
                val session = open.await()

                val close = async { session.close() }
                val closeRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionClose.methodName)
                agent.answer(closeRequest, AcpMethod.AgentMethods.V1.SessionClose.responseSerializer, CloseSessionResponse())
                close.await()

                val operations = RecordingOperations()
                val reopen = async { second.open(client, sessionId, parameters, operations) }
                val request = agent.receiveRequest(second.method)
                val history = text("history")
                agent.sendUpdate(sessionId, history)
                val response = second.answer(agent, request, sessionId, "second")
                assertSame(reopen.await(), client.getSession(sessionId))
                operations.awaitEvents(2)
                assertEquals(listOf(history, RecordingOperations.OPENED), operations.events.value, "$first then $second")
                assertEquals(listOf<AcpCreatedSessionResponse?>(response), operations.openedResponses.value)
            }
        }
    }

    @Test
    fun `a session that answers with the id of an open session fails`() = withClient { client, agent ->
        for (opening in listOf(newWithFactory, forkWithFactory)) {
            val sessionId = SessionId("taken-by-$opening")
            val operations = RecordingOperations()
            val load = async { client.loadSession(sessionId, parameters, operations) }
            loadResponse(agent, agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName), "load")
            val session = load.await()

            val refused = RecordingOperations()
            val open = async { runCatching { opening.open(client, sessionId, parameters, refused) } }
            opening.answer(agent, agent.receiveRequest(opening.method), sessionId, "$opening")
            val failure = open.await().exceptionOrNull()
            assertTrue(failure is IllegalStateException, "$opening must fail, got $failure")
            assertEquals("Session $sessionId is already open or being opened", failure.message)
            assertSame(session, client.getSession(sessionId))

            val live = text("live")
            agent.sendUpdate(sessionId, live)
            operations.awaitUpdate(live)
            assertEquals(listOf(RecordingOperations.OPENED, live), operations.events.value, "$opening")
            assertTrue(refused.events.value.isEmpty(), "the refused opening gets no event")
        }
    }

    @Test
    fun `updates that miss the closed queue wait for the updates queued after the marker`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val sessionId = SessionId("late-$opening")
            val queuedAfterAnswer = text("queued-after-answer")
            val releaseOpened = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val operations = RecordingOperations(
                beforeOpened = { releaseOpened.await() },
                beforeEach = { update ->
                    if (update == queuedAfterAnswer) {
                        entered.complete(Unit)
                        release.await()
                    }
                },
            )

            val open = async { opening.open(client, sessionId, parameters, operations) }
            val request = agent.receiveRequest(opening.method)
            opening.answer(agent, request, sessionId, "$opening")
            // The queue stays open while the marker is delivered, so this update goes into it behind the marker.
            agent.sendUpdate(sessionId, queuedAfterAnswer)
            agent.awaitHandled()
            releaseOpened.complete(Unit)
            entered.await()

            // The queue is closed now, so these take the late path and wait for the update in the operations.
            val late = listOf(text("late-1"), text("late-2"))
            late.forEach { agent.sendUpdate(sessionId, it) }
            agent.awaitHandled()
            assertEquals(listOf(RecordingOperations.OPENED), operations.events.value, "$opening")

            release.complete(Unit)
            open.await()
            operations.awaitEvents(4)
            assertEquals(listOf(RecordingOperations.OPENED, queuedAfterAnswer) + late, operations.events.value, "$opening")
        }
    }

    @Test
    fun `a call cancelled while the marker is delivered still opens the session`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val sessionId = SessionId("cancel-at-marker-$opening")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val operations = RecordingOperations(beforeOpened = {
                entered.complete(Unit)
                release.await()
            })
            val returned = CompletableDeferred<ClientSession>()
            val open = async { returned.complete(opening.open(client, sessionId, parameters, operations)) }
            val request = agent.receiveRequest(opening.method)
            opening.answer(agent, request, sessionId, "$opening")
            entered.await()

            open.cancel()
            release.complete(Unit)
            open.join()
            val session = withTimeout(5.seconds) { returned.await() }
            assertSame(session, client.getSession(sessionId), "$opening")

            val live = text("live")
            agent.sendUpdate(sessionId, live)
            operations.awaitUpdate(live)
            assertEquals(listOf(RecordingOperations.OPENED, live), operations.events.value, "$opening")
        }
    }

    @Test
    fun `a notify that fails for the marker leaves the session open`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val sessionId = SessionId("failing-marker-$opening")
            val operations = RecordingOperations(beforeOpened = { error("notify failed for the marker") })
            val open = async { opening.open(client, sessionId, parameters, operations) }
            opening.answer(agent, agent.receiveRequest(opening.method), sessionId, "$opening")
            assertSame(open.await(), client.getSession(sessionId), "$opening")

            val live = text("live")
            agent.sendUpdate(sessionId, live)
            operations.awaitUpdate(live)
            assertEquals(listOf<SessionUpdate>(live), operations.updates.value, "$opening")
        }
    }

    /**
     * Asserts that the failed opening of [sessionId] delivered no marker, and that its holder failed: a later update
     * goes to the unconnected handler, and a new load of the id works.
     */
    private suspend fun CoroutineScope.assertNoMarkerAndHolderFailed(
        opening: Opening,
        client: Client,
        agent: ScriptedAgentTransport,
        unconnected: RecordingOperations,
        sessionId: SessionId,
        operations: RecordingOperations,
    ) {
        assertTrue(operations.openedResponses.value.isEmpty(), "$opening: a failed opening delivers no marker")
        if (opening.factory) {
            assertTrue(operations.events.value.isEmpty(), "$opening: the factory of a failed opening is not called")
        }

        val afterFailure = text("after-failure-$opening")
        agent.sendUpdate(sessionId, afterFailure)
        unconnected.awaitUpdate(afterFailure)

        val retried = RecordingOperations()
        val retry = async { client.loadSession(sessionId, parameters, retried) }
        val response = loadResponse(agent, agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName), "retry")
        assertSame(retry.await(), client.getSession(sessionId))
        retried.awaitEvents(1)
        assertEquals(listOf<AcpCreatedSessionResponse?>(response), retried.openedResponses.value)
    }

    private fun withUnconnectedRecorder(
        block: suspend CoroutineScope.(Client, ScriptedAgentTransport, RecordingOperations) -> Unit,
    ) {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            block(client, agent, unconnected)
        }
    }

    private fun withClient(
        globalSessionUpdateHandler: GlobalSessionUpdateHandler? = null,
        block: suspend CoroutineScope.(Client, ScriptedAgentTransport) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = ScriptedAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val client = Client(protocol, globalSessionUpdateHandler = globalSessionUpdateHandler)
            runBlocking {
                // Real parallelism between the protocol read loop and the opening call is part of what is tested.
                withContext(Dispatchers.Default) {
                    withTimeout(60.seconds) { block(client, transport) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        private const val ROUNDS = 100
        private val counter = AtomicInteger()

        private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        private fun loadResponse(agent: ScriptedAgentTransport, request: JsonRpcRequest, tag: String): LoadSessionResponse =
            LoadSessionResponse(_meta = JsonPrimitive("$tag-${counter.incrementAndGet()}"))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, it) }

        private fun resumeResponse(agent: ScriptedAgentTransport, request: JsonRpcRequest, tag: String): ResumeSessionResponse =
            ResumeSessionResponse(_meta = JsonPrimitive("$tag-${counter.incrementAndGet()}"))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionResume.responseSerializer, it) }
    }
}
