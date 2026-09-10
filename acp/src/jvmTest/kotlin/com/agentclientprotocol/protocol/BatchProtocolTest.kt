package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class BatchProtocolTest {
    private val method = AcpMethod.AgentMethods.V1.Initialize
    private val notification = AcpMethod.ClientMethods.V1.SessionUpdate

    private class FrameTransport : BaseTransport() {
        val sent = Channel<TransportFrame>(Channel.UNLIMITED)
        var failSend = false
        var onSend: ((TransportFrame) -> Unit)? = null
        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun send(frame: TransportFrame) {
            check(!failSend) { "Writer queue failed" }
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

    private fun test(block: suspend CoroutineScope.(Protocol, FrameTransport) -> Unit): Unit = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = FrameTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            try {
                block(protocol, transport)
            } finally {
                protocol.close(); scope.cancel()
            }
        }
    }

    private fun request(id: Int) = TransportFrame.Single(JsonRpcRequest(RequestId.create(id), method.methodName))
    private fun TransportFrame.replies(): List<JsonRpcResponse> = when (this) {
        is TransportFrame.Single -> listOf(assertIs<JsonRpcResponse>(message))
        is TransportFrame.Batch -> entries.map { assertIs<JsonRpcResponse>(assertIs<TransportFrame.Single>(it).message) }
        else -> error("Unexpected frame: $this")
    }

    @Test
    fun mixedBatchKeepsInvalidSiblingsAndDuplicateIds() = test { protocol, transport ->
        protocol.setRequestHandlerRaw(method) { JsonPrimitive("ok") }
        transport.receive(
            TransportFrame.Batch(
                listOf(
                    request(1),
                    TransportFrame.Single(JsonRpcNotification(notification.methodName)),
                    assertIs<TransportFrame.Entry>(parseTransportFrame("42")),
                    request(1),
                )
            )
        )
        val frame = assertIs<TransportFrame.Batch>(transport.sent.receive())
        assertEquals(listOf(RequestId.create(1), RequestId.Null, RequestId.create(1)), frame.replies().map { it.id })
        assertEquals(-32600, assertIs<JsonRpcErrorResponse>(frame.replies()[1]).error.code)
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test
    fun standaloneErrorsAndSingletonBatchKeepTheirShape() = test { protocol, transport ->
        for ((raw, code) in listOf("not json" to -32700, "[]" to -32600)) {
            transport.receive(parseTransportFrame(raw))
            val reply = assertIs<TransportFrame.Single>(transport.sent.receive()).replies().single()
            assertEquals(RequestId.Null, reply.id)
            assertEquals(code, assertIs<JsonRpcErrorResponse>(reply).error.code)
        }
        protocol.setRequestHandlerRaw(method) { null }
        transport.receive(TransportFrame.Batch(listOf(request(2))))
        assertIs<TransportFrame.Batch>(transport.sent.receive())
        transport.receive(request(3))
        assertIs<TransportFrame.Single>(transport.sent.receive())
    }

    @Test
    fun notificationsAndMalformedResponsesDoNotReply() = test { protocol, transport ->
        val notified = CompletableDeferred<Unit>()
        protocol.setNotificationHandlerRaw(notification) { notified.complete(Unit); error("notification failed") }
        transport.receive(
            TransportFrame.Batch(
                listOf(
                    TransportFrame.Single(JsonRpcNotification(notification.methodName)),
                    assertIs<TransportFrame.Entry>(parseTransportFrame("""{"result":null}""")),
                    TransportFrame.Single(JsonRpcErrorResponse(RequestId.Null, error = JsonRpcError(-1, "uncorrelated"))),
                )
            )
        )
        notified.await()
        protocol.setRequestHandlerRaw(method) { JsonNull }
        transport.receive(request(1)) // Reader barrier; no empty array or response-to-response precedes this.
        assertIs<TransportFrame.Single>(transport.sent.receive())
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test
    fun collectingBatchDoesNotBlockOtherFramesAndFollowUpsWaitForWholeFrame() = test { protocol, transport ->
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val followUps = Channel<Int>(Channel.UNLIMITED)
        val cleaned = Channel<Int>(Channel.UNLIMITED)
        protocol.setRequestOutcomeHandlerRaw(method) { request ->
            val id = request.id.value as Int
            if (id == 2) release.await()
            RequestOutcome(JsonPrimitive(id), afterResponse = {
                followUps.send(id)
                if (id == 1) awaitCancellation()
            }, onCompletion = { cleaned.trySend(id) }).also { if (id == 1) prepared.complete(Unit) }
        }
        transport.receive(TransportFrame.Batch(listOf(request(1), request(2))))
        prepared.await()
        transport.receive(request(3))
        assertEquals(3, transport.sent.receive().replies().single().id.value)
        assertEquals(3, followUps.receive())
        assertTrue(followUps.tryReceive().isFailure)
        release.complete(Unit)
        assertIs<TransportFrame.Batch>(transport.sent.receive())
        assertEquals(setOf(1, 2), setOf(followUps.receive(), followUps.receive()))
        protocol.cancelPendingIncomingRequest(RequestId.create(1))
        assertEquals(setOf(1, 2, 3), setOf(cleaned.receive(), cleaned.receive(), cleaned.receive()))
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test
    fun cancelledPreparedOutcomeIsCleanedWithoutStartingFollowUp() = test { protocol, transport ->
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val starts = atomic(0)
        val cleanups = atomic(0)
        protocol.setRequestOutcomeHandlerRaw(method) { request ->
            if (request.id.value == 2) release.await()
            RequestOutcome<JsonElement?>(JsonNull, afterResponse = { starts.incrementAndGet() }, onCompletion = {
                if (request.id.value == 1) {
                    cleanups.incrementAndGet(); cleaned.complete(Unit)
                }
            }).also { if (request.id.value == 1) prepared.complete(Unit) }
        }
        transport.receive(TransportFrame.Batch(listOf(request(1), request(2))))
        prepared.await()
        protocol.cancelPendingIncomingRequest(RequestId.create(1))
        cleaned.await()
        assertEquals(0, starts.value)
        release.complete(Unit)
        assertIs<TransportFrame.Batch>(transport.sent.receive())
        assertEquals(1, cleanups.value)
    }

    @Test
    fun peerCancellationTerminatesSlotWithoutReply() = test { protocol, transport ->
        val started = CompletableDeferred<Unit>()
        protocol.setRequestHandlerRaw(method) { request ->
            if (request.id.value == 1) {
                started.complete(Unit); awaitCancellation()
            }
            JsonNull
        }
        transport.receive(TransportFrame.Batch(listOf(request(1), request(2))))
        started.await()
        val cancel = AcpMethod.MetaMethods.CancelRequest
        transport.receive(
            TransportFrame.Single(
                JsonRpcNotification(
                    cancel.methodName,
                    ACPJson.encodeToJsonElement(cancel.serializer, CancelRequestNotification(RequestId.create(1), null))
                )
            )
        )
        assertEquals(
            listOf(RequestId.create(2)),
            assertIs<TransportFrame.Batch>(transport.sent.receive()).replies().map { it.id })
    }

    @Test
    fun mappingFailureCleansOutcomeAndContinuationFailureCannotSendSecondResponse() = test { protocol, transport ->
        val cleaned = Channel<Unit>(Channel.UNLIMITED)
        protocol.setRequestOutcomeHandlerRaw(method) { request ->
            val outcome = RequestOutcome<JsonElement?>(
                JsonNull,
                afterResponse = { error("stream failed") }, onCompletion = { cleaned.trySend(Unit) })
            if (request.id.value == 1) outcome.mapResponse { throw SerializationException("mapping failed") } else outcome
        }
        transport.receive(TransportFrame.Batch(listOf(request(1), request(2))))
        val replies = transport.sent.receive().replies()
        assertEquals(-32700, assertIs<JsonRpcErrorResponse>(replies[0]).error.code)
        assertIs<JsonRpcSuccessResponse>(replies[1])
        repeat(2) { cleaned.receive() }
        assertTrue(cleaned.tryReceive().isFailure)
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test
    fun enqueueFailureCleansOutcomeAndClosesProtocol() = test { protocol, transport ->
        val cleaned = CompletableDeferred<Unit>()
        val starts = atomic(0)
        protocol.setRequestOutcomeHandlerRaw(method) {
            RequestOutcome(
                JsonNull,
                afterResponse = { starts.incrementAndGet() },
                onCompletion = { cleaned.complete(Unit) })
        }
        transport.failSend = true
        transport.receive(request(1))
        cleaned.await()
        assertEquals(0, starts.value)
        assertEquals(Transport.State.CLOSED, transport.state.value)
    }

    @Test
    fun closeReleasesPreparedOutcomesAndOutgoingWaiters() = test { protocol, transport ->
        val prepared = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        protocol.setRequestOutcomeHandlerRaw(method) { request ->
            if (request.id.value == 2) awaitCancellation()
            RequestOutcome(JsonNull, onCompletion = { cleaned.complete(Unit) }).also { prepared.complete(Unit) }
        }
        val outgoing = async { runCatching { protocol.sendBatchRequestRaw(listOf(JsonRpcCall.Request(method.methodName))) } }
        transport.sent.receive()
        transport.receive(TransportFrame.Batch(listOf(request(1), request(2))))
        prepared.await()
        transport.close()
        cleaned.await()
        assertIs<CancellationException>(outgoing.await().exceptionOrNull())
    }

    @Test
    fun outgoingBatchRegistersBeforeSendAndCorrelatesReversedResponses() = test { protocol, transport ->
        val session = SessionId("test")
        val calls = listOf(
            JsonRpcCall.Request(method.methodName, buildJsonObject { put("value", "first") }),
            JsonRpcCall.Notification(notification.methodName, buildJsonObject { put("event", "notify") }),
            JsonRpcCall.Request(method.methodName, buildJsonObject { put("value", "last") }),
        )
        var sentRequests = emptyList<JsonRpcRequest>()
        transport.onSend = { frame ->
            val messages = assertIs<TransportFrame.Batch>(frame).entries.map { assertIs<TransportFrame.Single>(it).message }
            assertEquals(3, messages.size)
            val requests = listOf(assertIs<JsonRpcRequest>(messages[0]), assertIs<JsonRpcRequest>(messages[2]))
            assertEquals(listOf(calls[0].params, calls[2].params), requests.map { it.params })
            assertEquals(listOf(method.methodName, method.methodName), requests.map { it.method })
            assertEquals(JsonRpcNotification(calls[1].method, calls[1].params), messages[1])
            assertEquals(listOf(RequestId.create(1), RequestId.create(2)), requests.map { it.id })
            sentRequests = requests
            requests.forEach { assertEquals(session, protocol.getOutgoingRequestSessionId(it.id)) }
            transport.receive(TransportFrame.Batch(requests.reversed().map { request ->
                TransportFrame.Single(JsonRpcSuccessResponse(request.id, result = request.params ?: JsonNull))
            }))
        }
        val results = protocol.sendBatchRequestRaw(calls, sessionId = session)
        assertEquals(listOf(calls[0].params, calls[2].params), results.map { it.getOrThrow() })
        sentRequests.forEach { assertNull(protocol.getOutgoingRequestSessionId(it.id)) }
    }

    @Test
    fun outgoingIndependentErrorsIncludingRemoteCancellation() = test { protocol, transport ->
        val result = async { protocol.sendBatchRequestRaw(List(3) { JsonRpcCall.Request(method.methodName) }) }
        val requests =
            assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.map { (it as TransportFrame.Single).message as JsonRpcRequest }
        transport.receive(TransportFrame.Single(JsonRpcSuccessResponse(requests[2].id, result = JsonPrimitive("ok"))))
        transport.receive(
            TransportFrame.Batch(
                listOf(
                    TransportFrame.Single(
                        JsonRpcErrorResponse(
                            requests[1].id,
                            error = JsonRpcError(-32800, "remote cancellation")
                        )
                    ),
                    TransportFrame.Single(JsonRpcErrorResponse(requests[0].id, error = JsonRpcError(-32601, "missing"))),
                )
            )
        )
        val results = result.await()
        assertIs<JsonRpcException>(results[0].exceptionOrNull())
        assertIs<CancellationException>(results[1].exceptionOrNull())
        assertEquals(JsonPrimitive("ok"), results[2].getOrThrow())
    }

    @Test
    fun outgoingLocalCancellationCleansEveryIdAndNotifiesPeer() = test { protocol, transport ->
        val result = async {
            protocol.sendBatchRequestRaw(
                List(2) { JsonRpcCall.Request(method.methodName) },
                sessionId = SessionId("s"),
            )
        }
        val requests =
            assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.map { (it as TransportFrame.Single).message as JsonRpcRequest }
        result.cancelAndJoin()
        assertFailsWith<CancellationException> { result.await() }
        val cancelledIds = List(2) {
            val message = assertIs<JsonRpcNotification>(assertIs<TransportFrame.Single>(transport.sent.receive()).message)
            assertEquals(AcpMethod.MetaMethods.CancelRequest.methodName, message.method)
            ACPJson.decodeFromJsonElement(AcpMethod.MetaMethods.CancelRequest.serializer, message.params!!).requestId
        }
        assertEquals(requests.map { it.id }, cancelledIds)
        requests.forEach { assertNull(protocol.getOutgoingRequestSessionId(it.id)) }
    }

    @Test
    fun reusedIdCompletionDoesNotUnregisterTheNewerJob() = test { protocol, transport ->
        val count = atomic(0)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstCleaned = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        protocol.setNotificationHandlerRaw(notification) { barrier.complete(Unit) }
        protocol.setRequestOutcomeHandlerRaw(method) {
            if (count.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                RequestOutcome(JsonNull, onCompletion = { firstCleaned.complete(Unit) })
            } else {
                secondStarted.complete(Unit)
                awaitCancellation()
            }
        }
        transport.receive(request(1))
        firstStarted.await()
        transport.receive(request(1))
        secondStarted.await()
        releaseFirst.complete(Unit)
        assertIs<JsonRpcSuccessResponse>(transport.sent.receive().replies().single())
        firstCleaned.await()
        transport.receive(TransportFrame.Single(JsonRpcNotification(notification.methodName)))
        barrier.await()
        protocol.cancelPendingIncomingRequest(RequestId.create(1))
        assertEquals(-32800, assertIs<JsonRpcErrorResponse>(transport.sent.receive().replies().single()).error.code)
    }

    @Test
    fun cancellationBeforeHandlerEntryDoesNotStrandBatch() = test { protocol, transport ->
        val scheduled = Channel<Runnable>(Channel.UNLIMITED)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                scheduled.trySend(block)
            }
        }
        val calls = atomic(0)
        protocol.setRequestHandlerRaw(method, dispatcher) { calls.incrementAndGet(); JsonNull }
        transport.receive(TransportFrame.Batch(listOf(request(1))))
        val task = scheduled.receive()
        protocol.cancelPendingIncomingRequest(RequestId.create(1))
        task.run()
        assertEquals(-32800, assertIs<JsonRpcErrorResponse>(assertIs<TransportFrame.Batch>(transport.sent.receive()).replies().single()).error.code)
        assertEquals(0, calls.value)
    }

    @Test
    fun unknownDuplicateAndNullResponseIdsCannotResolveAnotherCall() = test { protocol, transport ->
        val operation = async { protocol.sendBatchRequestRaw(List(2) { JsonRpcCall.Request(method.methodName) }) }
        val requests =
            assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.map { (it as TransportFrame.Single).message as JsonRpcRequest }
        val barrier = CompletableDeferred<Unit>()
        protocol.setNotificationHandlerRaw(notification) { barrier.complete(Unit) }
        transport.receive(
            TransportFrame.Batch(
                listOf(
                    TransportFrame.Single(
                        JsonRpcErrorResponse(
                            RequestId.Null,
                            error = JsonRpcError(-32600, "uncorrelated")
                        )
                    ),
                    TransportFrame.Single(JsonRpcSuccessResponse(RequestId.create(999), result = JsonNull)),
                    TransportFrame.Single(JsonRpcSuccessResponse(requests[0].id, result = JsonPrimitive("first"))),
                    TransportFrame.Single(JsonRpcSuccessResponse(requests[0].id, result = JsonPrimitive("duplicate"))),
                    TransportFrame.Single(JsonRpcNotification(notification.methodName)),
                )
            )
        )
        barrier.await()
        assertFalse(operation.isCompleted)
        transport.receive(TransportFrame.Single(JsonRpcSuccessResponse(requests[1].id, result = JsonPrimitive("second"))))
        assertEquals(listOf(JsonPrimitive("first"), JsonPrimitive("second")), operation.await().map { it.getOrThrow() })
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test
    fun overlappingSinglesAndBatchesShareTheIdCounter() = test { protocol, transport ->
        val singleSession = SessionId("single")
        val batchSession = SessionId("batch")
        val firstSingle = async { protocol.sendRequestRaw(method.methodName, sessionId = singleSession) }
        val firstRequest = assertIs<JsonRpcRequest>(assertIs<TransportFrame.Single>(transport.sent.receive()).message)
        val call = JsonRpcCall.Request(method.methodName)
        val calls = listOf(call, JsonRpcCall.Notification(notification.methodName), call)
        val batch = async { protocol.sendBatchRequestRaw(calls, batchSession) }
        val batchRequests = assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.mapNotNull {
            assertIs<TransportFrame.Single>(it).message as? JsonRpcRequest
        }
        val secondSingle = async { protocol.sendRequestRaw(method.methodName, sessionId = singleSession) }
        val secondRequest = assertIs<JsonRpcRequest>(assertIs<TransportFrame.Single>(transport.sent.receive()).message)
        val secondBatch = async { protocol.sendBatchRequestRaw(listOf(call), batchSession) }
        val lastRequest = assertIs<JsonRpcRequest>(
            assertIs<TransportFrame.Single>(assertIs<TransportFrame.Batch>(transport.sent.receive()).entries.single()).message
        )
        val requests = listOf(firstRequest) + batchRequests + secondRequest + lastRequest
        assertEquals((1..5).map { RequestId.create(it) }, requests.map { it.id })
        listOf(firstRequest, secondRequest).forEach { assertEquals(singleSession, protocol.getOutgoingRequestSessionId(it.id)) }
        (batchRequests + lastRequest).forEach { assertEquals(batchSession, protocol.getOutgoingRequestSessionId(it.id)) }

        transport.receive(TransportFrame.Batch(requests.reversed().map {
            TransportFrame.Single(JsonRpcSuccessResponse(it.id, result = JsonPrimitive(it.id.toString())))
        }))
        assertEquals(JsonPrimitive("1"), firstSingle.await())
        assertEquals(listOf(JsonPrimitive("2"), JsonPrimitive("3")), batch.await().map { it.getOrThrow() })
        assertEquals(JsonPrimitive("4"), secondSingle.await())
        assertEquals(JsonPrimitive("5"), secondBatch.await().single().getOrThrow())
        requests.forEach { assertNull(protocol.getOutgoingRequestSessionId(it.id)) }
    }

    @Test
    fun outgoingNotificationOnlyAndInvalidBatches() = test { protocol, transport ->
        assertFailsWith<IllegalArgumentException> { protocol.sendBatchRequestRaw(emptyList()) }
        assertTrue(transport.sent.tryReceive().isFailure)
        assertEquals(emptyList(), protocol.sendBatchRequestRaw(listOf(JsonRpcCall.Notification(notification.methodName))))
        val notificationFrame = assertIs<TransportFrame.Batch>(transport.sent.receive())
        assertEquals(JsonRpcNotification(notification.methodName), assertIs<TransportFrame.Single>(notificationFrame.entries.single()).message)
        transport.failSend = true
        assertFailsWith<IllegalStateException> {
            protocol.sendBatchRequestRaw(
                listOf(
                    JsonRpcCall.Request(method.methodName),
                    JsonRpcCall.Request(method.methodName),
                ),
                sessionId = SessionId("s"),
            )
        }
        assertNull(protocol.getOutgoingRequestSessionId(RequestId.create(1)))
        assertNull(protocol.getOutgoingRequestSessionId(RequestId.create(2)))
    }
}
