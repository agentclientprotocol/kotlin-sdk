package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SetSessionModeRequest
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * What runs in the read loop's order: the internal response hook of [Protocol.sendRequestRaw] and its typed
 * [Protocol.sendRequest] overload, and the ordering guarantee of notification handlers.
 */
class ReadLoopOrderingTest {
    private val method = AcpMethod.AgentMethods.V1.Initialize
    private val typedMethod = AcpMethod.AgentMethods.V1.SessionSetMode
    private val rawNotification = AcpMethod.ClientMethods.V1.SessionUpdate
    private val typedNotification = AcpMethod.AgentMethods.V1.SessionCancel

    private class FrameTransport : BaseTransport() {
        val sent = Channel<TransportFrame>(Channel.UNLIMITED)
        var onSend: ((TransportFrame) -> Unit)? = null

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun send(frame: TransportFrame) {
            sent.trySend(frame).getOrThrow()
            onSend?.invoke(frame)
        }

        fun receive(frame: TransportFrame) = fireFrame(frame)

        override fun close() {
            if (sent.close()) {
                _state.value = Transport.State.CLOSED
                fireClose()
            }
        }
    }

    /**
     * Holds dispatched tasks until the test runs them, so a handler entered through it cannot start on its own.
     */
    private class HeldDispatcher : CoroutineDispatcher() {
        val tasks = Channel<Runnable>(Channel.UNLIMITED)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.trySend(block).getOrThrow()
        }
    }

    private fun test(
        options: ProtocolOptions = ProtocolOptions(),
        block: suspend CoroutineScope.(Protocol, FrameTransport) -> Unit,
    ): Unit = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = FrameTransport()
            val protocol = Protocol(scope, transport, options)
            protocol.start()
            try {
                block(protocol, transport)
            } finally {
                protocol.close(); scope.cancel()
            }
        }
    }

    private fun TransportFrame.request(): JsonRpcRequest = assertIs<JsonRpcRequest>(assertIs<TransportFrame.Single>(this).message)

    private fun success(id: RequestId, result: JsonElement = JsonPrimitive("ok")) =
        TransportFrame.Single(JsonRpcSuccessResponse(id, result))

    private fun rawNotification(name: String) =
        TransportFrame.Single(JsonRpcNotification(rawNotification.methodName, JsonPrimitive(name)))

    private fun typedNotification() = TransportFrame.Single(
        JsonRpcNotification(
            typedNotification.methodName,
            ACPJson.encodeToJsonElement(typedNotification.serializer, CancelNotification(SessionId("s"))),
        )
    )

    @Test
    fun hookRunsBeforeTheCallerResumesAndBeforeAFollowingNotification() = test { protocol, transport ->
        val events = ConcurrentLinkedQueue<String>()
        val notified = CompletableDeferred<Unit>()
        protocol.setNotificationHandlerRaw(rawNotification) {
            events += "notification"
            notified.complete(Unit)
        }
        // Answering from inside send proves the hook is registered before the request reaches the transport.
        transport.onSend = { frame ->
            transport.receive(success(frame.request().id, JsonPrimitive("answer")))
            transport.receive(rawNotification("after"))
        }

        val result = protocol.sendRequestRaw(method.methodName, null, null) { events += "hook:$it" }
        events += "caller"
        notified.await()

        assertEquals(JsonPrimitive("answer"), result)
        assertEquals("hook:\"answer\"", events.first())
        assertEquals(setOf("hook:\"answer\"", "caller", "notification"), events.toSet())
        assertEquals(3, events.size)
    }

    @Test
    fun hookDoesNotRunForAnErrorResponse() = test { protocol, transport ->
        val hookCalls = atomic(0)
        val call = async { runCatching { protocol.sendRequestRaw(method.methodName, null, null) { hookCalls.incrementAndGet() } } }
        val request = transport.sent.receive().request()

        transport.receive(TransportFrame.Single(JsonRpcErrorResponse(request.id, JsonRpcError(-32603, "failed"))))

        val error = assertIs<JsonRpcException>(call.await().exceptionOrNull())
        assertEquals("failed", error.message)
        assertEquals(0, hookCalls.value)
    }

    @Test
    fun hookDoesNotRunForACancelledCallEvenIfItsResponseArrivesDuringTheGracefulWait() = test(
        // Long enough that only the response can end the graceful wait before the test's own timeout.
        ProtocolOptions(gracefulRequestCancellationTimeout = 1.minutes)
    ) { protocol, transport ->
        val hookCalls = atomic(0)
        val call = async { protocol.sendRequestRaw(method.methodName, null, null) { hookCalls.incrementAndGet() } }
        val request = transport.sent.receive().request()

        call.cancel()
        // The cancelled call has sent `$/cancel_request` and is still waiting for a response to its request.
        val cancel = assertIs<JsonRpcNotification>(assertIs<TransportFrame.Single>(transport.sent.receive()).message)
        assertEquals(AcpMethod.MetaMethods.CancelRequest.methodName, cancel.method)
        transport.receive(success(request.id))
        call.join()

        assertTrue(call.isCancelled)
        assertEquals(0, hookCalls.value)
    }

    @Test
    fun hookDoesNotRunAfterTheProtocolCloses() = test { protocol, transport ->
        val hookCalls = atomic(0)
        val call = async { runCatching { protocol.sendRequestRaw(method.methodName, null, null) { hookCalls.incrementAndGet() } } }
        val request = transport.sent.receive().request()

        protocol.close()
        transport.receive(success(request.id))

        assertIs<CancellationException>(call.await().exceptionOrNull())
        assertEquals(0, hookCalls.value)
    }

    @Test
    fun throwingHookIsIsolatedFromTheCallerAndTheReadLoop() = test { protocol, transport ->
        val failing = async { protocol.sendRequestRaw(method.methodName, null, null) { error("hook failure") } }
        transport.receive(success(transport.sent.receive().request().id, JsonPrimitive("first")))
        assertEquals(JsonPrimitive("first"), failing.await())

        // The read loop keeps going: a later call still gets its response and its hook.
        val observed = CompletableDeferred<JsonElement>()
        val next = async { protocol.sendRequestRaw(method.methodName, null, null) { observed.complete(it) } }
        transport.receive(success(transport.sent.receive().request().id, JsonPrimitive("second")))
        assertEquals(JsonPrimitive("second"), next.await())
        assertEquals(JsonPrimitive("second"), observed.await())
    }

    @Test
    fun typedOverloadPassesTheDecodedResponseAndReturnsTheSameValue() = test { protocol, transport ->
        val request = SetSessionModeRequest(SessionId("s"), SessionModeId("plan"))
        val observed = atomic<SetSessionModeResponse?>(null)
        val call = async { protocol.sendRequest(typedMethod, request) { observed.value = it } }

        val sent = transport.sent.receive().request()
        assertEquals(typedMethod.methodName, sent.method)
        assertEquals(ACPJson.encodeToJsonElement(typedMethod.requestSerializer, request), sent.params)
        assertEquals(SessionId("s"), protocol.getOutgoingRequestSessionId(sent.id))
        val meta = buildJsonObject { put("k", "v") }
        transport.receive(success(sent.id, buildJsonObject { put("_meta", meta) }))

        val returned = call.await()
        assertEquals(SetSessionModeResponse(_meta = meta), returned)
        assertSame(returned, observed.value)
    }

    @Test
    fun bothTypedOverloadsReadANullResultAsAnEmptyObject() = test { protocol, transport ->
        val request = SetSessionModeRequest(SessionId("s"), SessionModeId("plan"))
        val observed = atomic<SetSessionModeResponse?>(null)

        val hooked = async { protocol.sendRequest(typedMethod, request) { observed.value = it } }
        transport.receive(success(transport.sent.receive().request().id, JsonNull))
        val plain = async { protocol.sendRequest(typedMethod, request) }
        transport.receive(success(transport.sent.receive().request().id, JsonNull))

        assertEquals(SetSessionModeResponse(), hooked.await())
        assertEquals(SetSessionModeResponse(), observed.value)
        assertEquals(SetSessionModeResponse(), plain.await())
    }

    @Test
    fun typedOverloadSkipsTheHookOnADecodeFailureAndFailsLikeThePlainOverload() = test { protocol, transport ->
        val request = SetSessionModeRequest(SessionId("s"), SessionModeId("plan"))
        val undecodable = JsonPrimitive("not an object")
        val hookCalls = atomic(0)

        val hooked = async { runCatching { protocol.sendRequest(typedMethod, request) { hookCalls.incrementAndGet() } } }
        transport.receive(success(transport.sent.receive().request().id, undecodable))
        val plain = async { runCatching { protocol.sendRequest(typedMethod, request) } }
        transport.receive(success(transport.sent.receive().request().id, undecodable))

        val hookedError = assertNotNull(hooked.await().exceptionOrNull())
        val plainError = assertNotNull(plain.await().exceptionOrNull())
        assertIs<IllegalArgumentException>(plainError)
        assertEquals(plainError::class, hookedError::class)
        assertEquals(plainError.message, hookedError.message)
        assertEquals(0, hookCalls.value)
    }

    @Test
    fun batchCallsAreUnaffectedByAHookedSingleCall() = test { protocol, transport ->
        val observed = ConcurrentLinkedQueue<JsonElement>()
        val single = async { protocol.sendRequestRaw(method.methodName, null, null) { observed += it } }
        val singleRequest = transport.sent.receive().request()
        val batch = async { protocol.sendBatchRequestRaw(List(2) { JsonRpcCall.Request(method.methodName) }) }
        val batchRequests = assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.map { it.request() }

        transport.receive(
            TransportFrame.Batch(
                listOf(
                    success(batchRequests[1].id, JsonPrimitive("b1")),
                    success(singleRequest.id, JsonPrimitive("single")),
                    success(batchRequests[0].id, JsonPrimitive("b0")),
                )
            )
        )

        assertEquals(listOf(JsonPrimitive("b0"), JsonPrimitive("b1")), batch.await().map { it.getOrThrow() })
        assertEquals(JsonPrimitive("single"), single.await())
        assertEquals(listOf<JsonElement>(JsonPrimitive("single")), observed.toList())
    }

    @Test
    fun notificationHandlersRunTheirSynchronousPartsInArrivalOrderWithoutWaitingForEachOther() = test { protocol, transport ->
        val started = Channel<String>(Channel.UNLIMITED)
        val finished = Channel<String>(Channel.UNLIMITED)
        val gate = CompletableDeferred<Unit>()
        val firstSuspended = atomic(false)
        val secondSawFirstSuspended = CompletableDeferred<Boolean>()
        protocol.setNotificationHandlerRaw(rawNotification) { notification ->
            val name = notification.params!!.jsonPrimitive.content
            if (name == "second") secondSawFirstSuspended.complete(firstSuspended.value)
            started.trySend(name)
            if (name == "first") firstSuspended.value = true
            gate.await()
            finished.trySend(name)
        }

        transport.receive(rawNotification("first"))
        transport.receive(rawNotification("second"))

        assertEquals("first", started.receive())
        assertEquals("second", started.receive())
        assertTrue(secondSawFirstSuspended.await())
        // Neither handler has finished: the second started while the first was suspended.
        assertTrue(finished.tryReceive().isFailure)
        gate.complete(Unit)
        assertEquals(setOf("first", "second"), setOf(finished.receive(), finished.receive()))
    }

    @Test
    fun notificationHandlerRunsUntilItSuspendsBeforeAFollowingResponseIsHandled() = test { protocol, transport ->
        val events = ConcurrentLinkedQueue<String>()
        val gate = CompletableDeferred<Unit>()
        protocol.setNotificationHandler(typedNotification) {
            events += "notification"
            gate.await()
            events += "notification resumed"
        }
        val call = async { protocol.sendRequestRaw(method.methodName, null, null) { events += "hook" } }
        val request = transport.sent.receive().request()

        transport.receive(typedNotification())
        transport.receive(success(request.id))
        call.await()

        assertEquals(listOf("notification", "hook"), events.toList())
        gate.complete(Unit)
    }

    @Test
    fun requestHandlerRunsUntilItSuspendsBeforeAFollowingNotificationIsHandled() = test { protocol, transport ->
        val events = ConcurrentLinkedQueue<String>()
        val gate = CompletableDeferred<Unit>()
        val notified = CompletableDeferred<Unit>()
        protocol.setRequestHandlerRaw(method) {
            events += "request"
            gate.await()
            events += "request resumed"
            JsonNull
        }
        protocol.setNotificationHandlerRaw(rawNotification) {
            events += "notification"
            notified.complete(Unit)
        }

        transport.receive(TransportFrame.Single(JsonRpcRequest(RequestId.create(1), method.methodName)))
        transport.receive(rawNotification("after"))
        notified.await()

        assertEquals(listOf("request", "notification"), events.toList())
        gate.complete(Unit)
        transport.sent.receive()
        assertEquals(listOf("request", "notification", "request resumed"), events.toList())
    }

    @Test
    fun aDispatcherInTheAdditionalContextLetsAFollowingResponseOvertakeTheHandler() = test { protocol, transport ->
        val events = ConcurrentLinkedQueue<String>()
        val held = HeldDispatcher()
        val notified = CompletableDeferred<Unit>()
        protocol.setNotificationHandler(typedNotification, additionalContext = held) {
            events += "notification"
            notified.complete(Unit)
        }
        val call = async { protocol.sendRequestRaw(method.methodName, null, null) { events += "hook" } }
        val request = transport.sent.receive().request()

        transport.receive(typedNotification())
        transport.receive(success(request.id))
        call.await()

        // The handler is still waiting for its dispatcher, so the response was handled first.
        assertEquals(listOf("hook"), events.toList())
        held.tasks.receive().run()
        notified.await()
        assertEquals(listOf("hook", "notification"), events.toList())
    }
}
