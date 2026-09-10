package com.agentclientprotocol

import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.WebSocketTransport
import com.agentclientprotocol.transport.asFrameChannel
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebSocketTransportTest {
    @Test
    fun closeDeliversQueuedFramesAndNormalCloseReason() = testTransport { transport ->
        val frames = List(32) { TransportFrame.Single(JsonRpcSuccessResponse(RequestId.IntId(it), JsonNull)) }
        frames.forEach(transport::send)
        transport.start()
        transport.close()

        for (frame in frames) {
            assertEquals(frame, parseTransportFrame(assertIs<Frame.Text>(incoming.receive()).readText()))
        }
        assertEquals(CloseReason.Codes.NORMAL.code, closeReason.await()?.code)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun readerEofDrainsQueuedFramesAndClosesOnce() = runTest {
        val session = TestWebSocketSession(coroutineContext)
        val transport = WebSocketTransport(this, session)
        var closeCount = 0
        transport.onClose { closeCount++ }
        transport.start()
        runCurrent()
        session.incoming.close()
        val frame = TransportFrame.Single(JsonRpcNotification(MethodName("finalUpdate")))
        transport.send(frame)
        runCurrent()

        assertEquals(frame, parseTransportFrame(assertIs<Frame.Text>(session.outgoing.receive()).readText()))
        assertEquals(CloseReason.Codes.NORMAL.code, assertIs<Frame.Close>(session.outgoing.receive()).readReason()?.code)
        assertFalse(session.coroutineContext[Job]!!.isCancelled)
        assertEquals(Transport.State.CLOSED, transport.state.value)
        transport.close()
        assertEquals(1, closeCount)
        session.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeBeforeStartDrainsAcceptedFrames() = runTest {
        val session = TestWebSocketSession(coroutineContext)
        val transport = WebSocketTransport(this, session)
        val frame = TransportFrame.Single(JsonRpcNotification(MethodName("queued")))
        transport.send(frame)
        transport.close()
        assertFailsWith<ClosedSendChannelException> { transport.send(frame) }
        assertFailsWith<IllegalStateException> { transport.start() }
        runCurrent()
        assertEquals(frame, parseTransportFrame(assertIs<Frame.Text>(session.outgoing.receive()).readText()))
        assertIs<Frame.Close>(session.outgoing.receive())
        assertEquals(Transport.State.CLOSED, transport.state.value)
        session.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeAbortsBlockedSendFlushOrHandshakeAfterTimeout() = runTest {
        for (blockedAt in listOf("send", "flush", "close")) {
            val session = TestWebSocketSession(coroutineContext, blockedAt)
            val transport = WebSocketTransport(this, session)
            var closeCount = 0
            transport.onClose { closeCount++ }
            transport.start()
            transport.send(TransportFrame.Single(JsonRpcNotification(MethodName("queued"))))
            transport.close()
            runCurrent()
            assertEquals(Transport.State.CLOSING, transport.state.value)
            assertFalse(session.coroutineContext[Job]!!.isCancelled)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertTrue(session.coroutineContext[Job]!!.isCancelled)
            assertEquals(Transport.State.CLOSED, transport.state.value)
            assertEquals(1, closeCount)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parentCancellationAbortsBlockedWriter() = runTest {
        val parent = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val session = TestWebSocketSession(coroutineContext, "send")
        val transport = WebSocketTransport(parent, session)
        transport.start()
        transport.send(TransportFrame.Single(JsonRpcNotification(MethodName("queued"))))
        runCurrent()
        parent.cancel()
        runCurrent()
        assertTrue(session.coroutineContext[Job]!!.isCancelled)
        assertEquals(Transport.State.CLOSED, transport.state.value)
    }

    private class TestWebSocketSession(context: CoroutineContext, private val blockedAt: String? = null) : WebSocketSession {
        override val coroutineContext = context + Job(context[Job])
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions = emptyList<WebSocketExtension<*>>()

        override suspend fun send(frame: Frame) {
            if (blockedAt == "send" || blockedAt == "close" && frame is Frame.Close) awaitCancellation()
            outgoing.send(frame)
        }

        override suspend fun flush() {
            if (blockedAt == "flush") awaitCancellation()
        }

        @Deprecated("Use cancel() instead.")
        override fun terminate() { cancel() }
    }

    private fun testTransport(block: suspend DefaultClientWebSocketSession.(WebSocketTransport) -> Unit) = testApplication {
        val ready = CompletableDeferred<WebSocketTransport>()
        install(WebSockets)
        routing {
            webSocket("/frames") {
                val transport = WebSocketTransport(this, this)
                ready.complete(transport)
                try { awaitCancellation() } finally { transport.close() }
            }
        }
        val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
        client.webSocket("/frames") {
            val transport = ready.await()
            try {
                withTimeout(5.seconds) { block(this@webSocket, transport) }
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun malformedInputDoesNotCloseTheConnectionOrDiscardBatchSiblings() = testTransport { transport ->
        val received = transport.asFrameChannel()
        transport.start()
        send(Frame.Text("["))
        send(Frame.Text("{} {}"))
        send(Frame.Text("""[{"jsonrpc":"2.0","method":"notify"},42,{"result":null}]"""))
        send(Frame.Text("""{"jsonrpc":"2.0","id":1,"result":null}"""))

        repeat(2) { assertEquals(-32700, assertIs<TransportFrame.Malformed>(received.receive()).error.code) }
        val batch = assertIs<TransportFrame.Batch>(received.receive())
        assertEquals(3, batch.entries.size)
        assertIs<TransportFrame.Single>(batch.entries[0])
        assertEquals(-32600, assertIs<TransportFrame.Malformed>(batch.entries[1]).error.code)
        assertTrue(assertIs<TransportFrame.Malformed>(batch.entries[2]).isResponse)
        assertIs<JsonRpcSuccessResponse>(assertIs<TransportFrame.Single>(received.receive()).message)
        assertEquals(Transport.State.STARTED, transport.state.value)
    }

    @Test
    fun singletonBatchUsesOneTextFrameAndKeepsNullResult() = testTransport { transport ->
        transport.start()
        val batch = TransportFrame.Batch(listOf(TransportFrame.Single(JsonRpcSuccessResponse(RequestId.Null, JsonNull))))
        transport.send(batch)
        val text = assertIs<Frame.Text>(incoming.receive()).readText()
        assertIs<JsonArray>(JsonRpcJson.parseToJsonElement(text))
        assertEquals(batch, parseTransportFrame(text))
        assertTrue(incoming.tryReceive().isFailure)
    }

    @Test
    fun malformedStandaloneOutputFailsSendAndLeavesTheWriterUsable() = rejectsMalformedOutput(parseTransportFrame("["))

    @Test
    fun malformedBatchOutputCannotEmitPartialJson() = rejectsMalformedOutput(
        parseTransportFrame("""[{"jsonrpc":"2.0","method":"notify"},42]""")
    )

    private fun rejectsMalformedOutput(frame: TransportFrame) = testTransport { transport ->
        val error = CompletableDeferred<Throwable>()
        transport.onError { error.complete(it) }
        transport.start()
        assertFailsWith<SerializationException> { transport.send(frame) }

        val valid = TransportFrame.Single(JsonRpcNotification(MethodName("afterFailure")))
        transport.send(valid)
        assertEquals(valid, parseTransportFrame(assertIs<Frame.Text>(incoming.receive()).readText()))
        assertTrue(incoming.tryReceive().isFailure)
        assertFalse(error.isCompleted)
        assertEquals(Transport.State.STARTED, transport.state.value)
    }
}
