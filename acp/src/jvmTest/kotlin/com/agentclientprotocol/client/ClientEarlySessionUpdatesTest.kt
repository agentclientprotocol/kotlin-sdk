@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.ResumeSessionResponse
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionModeState
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `Client.loadSession` and `Client.resumeSession` with a [ClientSessionOperations] argument deliver each
 * `session/update` of the session before the agent answers. The tests play the agent on a scripted transport, so
 * they control the exact wire order of the updates and the answer.
 */
class ClientEarlySessionUpdatesTest {
    private val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())

    @Test
    fun `load delivers replayed updates before the agent answers`() = withClient { client, agent ->
        val sessionId = SessionId("early-load")
        val operations = RecordingOperations()
        val load = async { client.loadSession(sessionId, parameters, operations) }

        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        val replay = (1..3).map { text("replay-$it") }
        replay.forEach { agent.sendUpdate(sessionId, it) }

        operations.awaitCount(replay.size)
        assertFalse(load.isCompleted, "the replay must reach the operations before the answer")

        agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        val session = load.await()
        assertSame(operations, session.operations)

        val later = text("after-answer")
        agent.sendUpdate(sessionId, later)
        operations.awaitUpdate(later)
        assertEquals(replay + later, operations.updates.value, "each update reaches the operations exactly once")
    }

    @Test
    fun `resume delivers updates before the agent answers`() = withClient { client, agent ->
        val sessionId = SessionId("early-resume")
        val operations = RecordingOperations()
        val resume = async { client.resumeSession(sessionId, parameters, operations) }

        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionResume.methodName)
        val early = text("before-resume-answer")
        agent.sendUpdate(sessionId, early)

        operations.awaitUpdate(early)
        assertFalse(resume.isCompleted)

        agent.answer(request, AcpMethod.AgentMethods.V1.SessionResume.responseSerializer, ResumeSessionResponse())
        assertSame(operations, resume.await().operations)
        assertEquals(listOf<SessionUpdate>(early), operations.updates.value)
    }

    @Test
    fun `updates racing the answer keep their order and arrive once`() = withClient { client, agent ->
        /*
         * The answer and the updates after it are put on the wire back to back, so the client switches from
         * early delivery to the session while those updates are handled. Each round can take either side.
         */
        repeat(RACE_ROUNDS) { round ->
            val sessionId = SessionId("race-$round")
            val operations = RecordingOperations()
            val load = async { client.loadSession(sessionId, parameters, operations) }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)

            val beforeAnswer = listOf(text("$round-1"), SessionUpdate.CurrentModeUpdate(PLAN), text("$round-2"))
            val afterAnswer = listOf(text("$round-3"), SessionUpdate.CurrentModeUpdate(REVIEW), text("$round-4"))
            beforeAnswer.forEach { agent.sendUpdate(sessionId, it) }
            agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse(modes = modes()))
            afterAnswer.forEach { agent.sendUpdate(sessionId, it) }

            val session = load.await()
            operations.awaitCount(beforeAnswer.size + afterAnswer.size)
            assertEquals(beforeAnswer + afterAnswer, operations.updates.value, "round $round")
            assertEquals(REVIEW, session.currentMode.value, "round $round")
        }
    }

    @Test
    fun `the answer waits for an early update that the operations still handle`() = withClient { client, agent ->
        val sessionId = SessionId("slow-early-update")
        val releaseModeUpdate = CompletableDeferred<Unit>()
        val operations = RecordingOperations(beforeEach = { update ->
            if (update is SessionUpdate.CurrentModeUpdate) releaseModeUpdate.await()
        })
        val load = async { client.loadSession(sessionId, parameters, operations) }
        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)

        val modeUpdate = SessionUpdate.CurrentModeUpdate(PLAN)
        val afterAnswer = text("after-answer")
        agent.sendUpdate(sessionId, modeUpdate)
        agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse(modes = modes()))
        agent.sendUpdate(sessionId, afterAnswer)
        agent.awaitHandled()

        assertNull(
            withTimeoutOrNull(200.milliseconds) { load.await() },
            "the session must not take over while an early update is still in the operations",
        )

        releaseModeUpdate.complete(Unit)
        val session = load.await()
        operations.awaitUpdate(afterAnswer)
        assertEquals(listOf(modeUpdate, afterAnswer), operations.updates.value)
        assertEquals(PLAN, session.currentMode.value, "the session applies the mode of the early update")
    }

    @Test
    fun `mode and config options after an early load equal those after a buffered load`() = withClient { client, agent ->
        val replay = listOf(
            SessionUpdate.CurrentModeUpdate(PLAN),
            SessionUpdate.ConfigOptionUpdate(listOf(SessionConfigOption.boolean("thinking", "Thinking", currentValue = true))),
            text("history"),
        )
        val response = LoadSessionResponse(
            modes = modes(),
            configOptions = listOf(SessionConfigOption.boolean("thinking", "Thinking", currentValue = false)),
        )

        suspend fun load(sessionId: SessionId, open: suspend (ClientSessionOperations) -> ClientSession): ClientSession {
            val operations = RecordingOperations()
            val load = async { open(operations) }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            replay.forEach { agent.sendUpdate(sessionId, it) }
            agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, response)
            return load.await().also { operations.awaitCount(replay.size) }
        }

        val buffered = load(SessionId("buffered")) { operations ->
            client.loadSession(SessionId("buffered"), parameters) { _, _ -> operations }
        }
        val early = load(SessionId("early")) { operations ->
            client.loadSession(SessionId("early"), parameters, operations)
        }

        assertEquals(PLAN, buffered.currentMode.value)
        assertEquals(buffered.currentMode.value, early.currentMode.value)
        assertEquals(buffered.configOptions.value, early.configOptions.value)
        assertEquals(replay[1].let { (it as SessionUpdate.ConfigOptionUpdate).configOptions }, early.configOptions.value)
    }

    @Test
    fun `failed load releases the session id`() {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            val sessionId = SessionId("failing-load")
            val failedOperations = RecordingOperations()
            val failedLoad = async { runCatching { client.loadSession(sessionId, parameters, failedOperations) } }

            val failedRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            val replay = text("replay-of-failed-load")
            agent.sendUpdate(sessionId, replay)
            failedOperations.awaitUpdate(replay)
            agent.fail(failedRequest)
            assertTrue(failedLoad.await().isFailure, "the load must fail")

            val afterFailure = text("after-failure")
            agent.sendUpdate(sessionId, afterFailure)
            unconnected.awaitUpdate(afterFailure)
            assertEquals(listOf<SessionUpdate>(replay), failedOperations.updates.value)

            val operations = RecordingOperations()
            val retry = async { client.loadSession(sessionId, parameters, operations) }
            val retryRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            val retryReplay = text("replay-of-retry")
            agent.sendUpdate(sessionId, retryReplay)
            agent.answer(retryRequest, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
            retry.await()

            operations.awaitUpdate(retryReplay)
            assertEquals(listOf<SessionUpdate>(retryReplay), operations.updates.value)
            assertEquals(listOf<SessionUpdate>(replay), failedOperations.updates.value)
        }
    }

    @Test
    fun `early load takes over updates buffered for its session id`() = withClient { client, agent ->
        val sessionId = SessionId("buffered-before-load")
        val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
        val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

        // While session/new runs, the client buffers an update for an unknown id in case the id is the new one.
        val buffered = text("buffered")
        agent.sendUpdate(sessionId, buffered)
        agent.awaitHandled()

        val operations = RecordingOperations()
        val load = async { client.loadSession(sessionId, parameters, operations) }
        val loadRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        operations.awaitUpdate(buffered)

        val replay = text("replay")
        agent.sendUpdate(sessionId, replay)
        operations.awaitUpdate(replay)
        agent.answer(loadRequest, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        load.await()
        assertEquals(listOf<SessionUpdate>(buffered, replay), operations.updates.value)

        agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new")))
        newSession.await()
    }

    @Test
    fun `factory load still buffers updates until the agent answers`() = withClient { client, agent ->
        val sessionId = SessionId("factory-load")
        val operations = RecordingOperations()
        val factoryCalls = atomic(0)
        val load = async {
            client.loadSession(sessionId, parameters) { _, _ ->
                factoryCalls.incrementAndGet()
                operations
            }
        }

        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        val replay = text("replay")
        agent.sendUpdate(sessionId, replay)
        agent.awaitHandled()
        assertTrue(operations.updates.value.isEmpty(), "the factory overload must not deliver before the answer")
        assertEquals(0, factoryCalls.value)

        agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        load.await()
        operations.awaitUpdate(replay)
        assertEquals(listOf<SessionUpdate>(replay), operations.updates.value)
        assertEquals(1, factoryCalls.value)
    }

    @Test
    fun `a failing notify fails the load and sends the later updates to the unconnected handler`() {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            val sessionId = SessionId("failing-notify")
            // A pending session/new keeps the holder of the failed load registered, so later updates still find it.
            val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
            val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)
            val buffered = text("buffered")
            agent.sendUpdate(sessionId, buffered)
            agent.awaitHandled()

            val failure = IllegalStateException("notify failed")
            val operations = RecordingOperations(beforeEach = { throw failure })
            // The failure can cancel the request before it goes out, so the test does not wait for the request.
            val load = async { runCatching { client.loadSession(sessionId, parameters, operations) } }
            // The coroutines debug agent may rethrow a copy of the failure, so compare the message.
            assertEquals(failure.message, load.await().exceptionOrNull()?.message)

            val afterFailure = text("after-failure")
            agent.sendUpdate(sessionId, afterFailure)
            unconnected.awaitUpdate(afterFailure)
            assertTrue(operations.updates.value.isEmpty(), "the operations of the failed load get no update")

            agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new")))
            newSession.await()
        }
    }

    @Test
    fun `a load cancelled during the early delivery sends the later updates to the unconnected handler`() {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            val sessionId = SessionId("cancelled-load")
            val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
            val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

            val entered = CompletableDeferred<Unit>()
            val operations = RecordingOperations(beforeEach = {
                entered.complete(Unit)
                awaitCancellation()
            })
            val load = async { client.loadSession(sessionId, parameters, operations) }
            agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            agent.sendUpdate(sessionId, text("replay"))
            entered.await()
            val queued = text("queued")
            agent.sendUpdate(sessionId, queued)
            agent.awaitHandled()

            load.cancelAndJoin()
            unconnected.awaitUpdate(queued)
            val afterCancel = text("after-cancel")
            agent.sendUpdate(sessionId, afterCancel)
            unconnected.awaitUpdate(afterCancel)
            assertTrue(operations.updates.value.isEmpty(), "the operations of the cancelled load get no update")

            agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new")))
            newSession.await()
        }
    }

    @Test
    fun `a notify that times out fails the load and sends the later updates to the unconnected handler`() {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            val sessionId = SessionId("timed-out-notify")
            val timedOut = CompletableDeferred<Unit>()
            // A cancellation that is not the caller's own, as from a timeout inside a UI callback.
            val operations = RecordingOperations(beforeEach = {
                try {
                    withTimeout(10.milliseconds) { awaitCancellation() }
                } finally {
                    timedOut.complete(Unit)
                }
            })
            val load = async { runCatching { client.loadSession(sessionId, parameters, operations) } }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            agent.sendUpdate(sessionId, text("replay"))
            timedOut.await()

            val afterTimeout = listOf(text("after-timeout-1"), text("after-timeout-2"))
            afterTimeout.forEach { agent.sendUpdate(sessionId, it) }
            agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())

            assertTrue(load.await().exceptionOrNull() is IllegalStateException, "the load must fail")
            unconnected.awaitCount(afterTimeout.size)
            assertEquals(afterTimeout, unconnected.updates.value)
            assertTrue(operations.updates.value.isEmpty(), "the operations of the failed load get no update")
        }
    }

    @Test
    fun `a throwing unconnected handler neither replaces the load failure nor stops the drain`() {
        val unconnected = RecordingOperations()
        val queued = listOf(text("queued-1"), text("queued-2"))
        val handler = GlobalSessionUpdateHandler { _, update, meta ->
            unconnected.notify(update, meta)
            if (update == queued.first()) error("global boom")
        }
        withClient(globalSessionUpdateHandler = handler) { client, agent ->
            val sessionId = SessionId("throwing-global-handler")
            val entered = CompletableDeferred<Unit>()
            val operations = RecordingOperations(beforeEach = {
                entered.complete(Unit)
                awaitCancellation()
            })
            val load = async { runCatching { client.loadSession(sessionId, parameters, operations) } }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            agent.sendUpdate(sessionId, text("replay"))
            entered.await()
            queued.forEach { agent.sendUpdate(sessionId, it) }
            agent.awaitHandled()

            agent.fail(request)
            assertEquals("load failed", load.await().exceptionOrNull()?.message)
            unconnected.awaitCount(queued.size)
            assertEquals(queued, unconnected.updates.value)
        }
    }

    @Test
    fun `a failed factory hands the queued updates to the unconnected handler at once`() {
        val unconnected = RecordingOperations()
        withClient(globalSessionUpdateHandler = { _, update, meta -> unconnected.notify(update, meta) }) { client, agent ->
            val sessionId = SessionId("failing-factory")
            // A pending session/new keeps the failed holder registered, so the reap does not drain it.
            val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
            val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

            val load = async { runCatching { client.loadSession(sessionId, parameters) { _, _ -> error("factory failed") } } }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            val replay = text("replay")
            agent.sendUpdate(sessionId, replay)
            agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
            assertTrue(load.await().isFailure)
            unconnected.awaitUpdate(replay)

            agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new")))
            newSession.await()
        }
    }

    @Test
    fun `a second load of an open session fails before its request`() = withClient { client, agent ->
        val sessionId = SessionId("open-twice")
        val first = RecordingOperations()
        val load = async { client.loadSession(sessionId, parameters, first) }
        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        val session = load.await()

        val second = runCatching { client.loadSession(sessionId, parameters, RecordingOperations()) }
        assertTrue(second.exceptionOrNull() is IllegalStateException)
        val factory = runCatching { client.loadSession(sessionId, parameters) { _, _ -> RecordingOperations() } }
        assertTrue(factory.exceptionOrNull() is IllegalStateException)
        agent.assertNoRequest()
        assertSame(session, client.getSession(sessionId))

        val update = text("update")
        agent.sendUpdate(sessionId, update)
        first.awaitUpdate(update)
    }

    @Test
    fun `a load with operations fails before its request while a factory load is in flight`() = withClient { client, agent ->
        val sessionId = SessionId("factory-and-early")
        val factoryOperations = RecordingOperations()
        val factoryLoad = async { client.loadSession(sessionId, parameters) { _, _ -> factoryOperations } }
        val factoryRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)

        val operations = RecordingOperations()
        val earlyLoad = runCatching { client.loadSession(sessionId, parameters, operations) }
        assertTrue(earlyLoad.exceptionOrNull() is IllegalStateException)
        agent.assertNoRequest()

        val replay = text("replay")
        agent.sendUpdate(sessionId, replay)
        agent.answer(factoryRequest, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        assertSame(factoryLoad.await(), client.getSession(sessionId))
        factoryOperations.awaitUpdate(replay)
        assertTrue(operations.updates.value.isEmpty(), "the refused load gets no update")
    }

    @Test
    fun `a load with operations after a failed factory load gets only its own updates`() = withClient { client, agent ->
        val sessionId = SessionId("early-after-failed-factory")
        // A pending session/new keeps the holder of the failed load registered.
        val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
        val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

        val failedLoad = async { runCatching { client.loadSession(sessionId, parameters) { _, _ -> RecordingOperations() } } }
        val failedRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        agent.sendUpdate(sessionId, text("replay-of-failed-load"))
        agent.fail(failedRequest)
        assertTrue(failedLoad.await().isFailure)

        val operations = RecordingOperations()
        val retry = async { client.loadSession(sessionId, parameters, operations) }
        val retryRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        val replay = text("replay-of-retry")
        agent.sendUpdate(sessionId, replay)
        agent.answer(retryRequest, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
        assertSame(retry.await(), client.getSession(sessionId))
        operations.awaitUpdate(replay)
        assertEquals(listOf<SessionUpdate>(replay), operations.updates.value)

        agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new")))
        newSession.await()
    }

    @Test
    fun `a retry after a failed load gets the updates while another session initializes`() = withClient { client, agent ->
        suspend fun retryAfterFailure(sessionId: SessionId, load: suspend (ClientSessionOperations) -> ClientSession) {
            val newSession = async { client.newSession(parameters) { _, _ -> RecordingOperations() } }
            val newRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)

            val failedLoad = async { runCatching { load(RecordingOperations()) } }
            agent.fail(agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName))
            assertTrue(failedLoad.await().isFailure)

            val operations = RecordingOperations()
            val retry = async { load(operations) }
            val retryRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
            val replay = text("replay-of-retry")
            agent.sendUpdate(sessionId, replay)
            agent.answer(retryRequest, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse())
            assertSame(retry.await(), client.getSession(sessionId))
            operations.awaitUpdate(replay)

            agent.answer(newRequest, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("new-${sessionId.value}")))
            newSession.await()
        }

        val early = SessionId("retry-early")
        retryAfterFailure(early) { client.loadSession(early, parameters, it) }
        val factory = SessionId("retry-factory")
        retryAfterFailure(factory) { operations -> client.loadSession(factory, parameters) { _, _ -> operations } }
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
                // Real parallelism between the protocol read loop and the load call is part of what is tested.
                withContext(Dispatchers.Default) {
                    withTimeout(60.seconds) { block(client, transport) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

    private fun modes() = SessionModeState(
        currentModeId = SessionModeId("default"),
        availableModes = listOf(SessionMode(SessionModeId("default"), "Default"), SessionMode(PLAN, "Plan"), SessionMode(REVIEW, "Review")),
    )

    private companion object {
        private const val RACE_ROUNDS = 500
        private val PLAN = SessionModeId("plan")
        private val REVIEW = SessionModeId("review")
    }
}

private class RecordingOperations(
    private val beforeEach: suspend (SessionUpdate) -> Unit = {},
) : ClientSessionOperations {
    val updates = MutableStateFlow<List<SessionUpdate>>(emptyList())

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = error("not expected in this test")

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        beforeEach(notification)
        updates.update { it + notification }
    }

    suspend fun awaitCount(count: Int) {
        withTimeout(TIMEOUT) { updates.first { it.size >= count } }
    }

    suspend fun awaitUpdate(update: SessionUpdate) {
        withTimeout(TIMEOUT) { updates.first { update in it } }
    }

    private companion object {
        private val TIMEOUT = 5.seconds
    }
}

/**
 * The agent side of a connection, driven by the test: it records the requests of the client, and sends the updates
 * and the answers the test asks for, in exactly that order.
 */
private class ScriptedAgentTransport : BaseTransport() {
    private val requests = Channel<JsonRpcRequest>(capacity = Channel.UNLIMITED)
    private val responses = Channel<JsonRpcResponse>(capacity = Channel.UNLIMITED)
    private val barrierIds = atomic(0)

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    override fun send(frame: TransportFrame) {
        when (val message = (frame as TransportFrame.Single).message) {
            is JsonRpcRequest -> requests.trySend(message)
            is JsonRpcResponse -> responses.trySend(message)
            else -> {}
        }
    }

    suspend fun receiveRequest(method: MethodName): JsonRpcRequest {
        val request = withTimeout(5.seconds) { requests.receive() }
        assertEquals(method, request.method)
        return request
    }

    fun assertNoRequest() {
        assertNull(requests.tryReceive().getOrNull(), "the client must not send a request")
    }

    fun sendUpdate(sessionId: SessionId, update: SessionUpdate) {
        fireFrame(
            TransportFrame.Single(
                JsonRpcNotification(
                    method = AcpMethod.ClientMethods.V1.SessionUpdate.methodName,
                    params = ACPJson.encodeToJsonElement(
                        AcpMethod.ClientMethods.V1.SessionUpdate.serializer,
                        SessionNotification(sessionId, update),
                    ),
                )
            )
        )
    }

    fun <T : AcpCreatedSessionResponse> answer(request: JsonRpcRequest, serializer: KSerializer<T>, response: T) {
        fireFrame(TransportFrame.Single(JsonRpcSuccessResponse(request.id, ACPJson.encodeToJsonElement(serializer, response))))
    }

    fun fail(request: JsonRpcRequest) {
        val error = JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, "load failed")
        fireFrame(TransportFrame.Single(JsonRpcErrorResponse(request.id, error)))
    }

    /**
     * Returns after the client has handled every message sent before this call.
     *
     * The client reads messages in wire order and lets each notification handler run before it reads on, so its
     * answer to a later request proves that the earlier notifications have been handled.
     */
    suspend fun awaitHandled() {
        val id = RequestId.create("barrier-${barrierIds.incrementAndGet()}")
        fireFrame(TransportFrame.Single(JsonRpcRequest(id, MethodName("test/barrier"), null)))
        withTimeout(5.seconds) {
            while (responses.receive().id != id) {
                // An answer to an earlier barrier.
            }
        }
    }
}

