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
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class WebSocketTransportTest {
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
    fun malformedStandaloneOutputFailsTheWriter() = rejectsMalformedOutput(parseTransportFrame("["))

    @Test
    fun malformedBatchOutputCannotEmitPartialJson() = rejectsMalformedOutput(
        parseTransportFrame("""[{"jsonrpc":"2.0","method":"notify"},42]""")
    )

    private fun rejectsMalformedOutput(frame: TransportFrame) = testTransport { transport ->
        val error = CompletableDeferred<Throwable>()
        transport.onError { error.complete(it) }
        transport.start()
        transport.send(frame)
        assertIs<SerializationException>(error.await())
        transport.state.first { it == Transport.State.CLOSED }
        // The peer may see a close frame or EOF, but never JSON from the rejected frame.
        assertFalse(incoming.receiveCatching().getOrNull() is Frame.Text)
    }
}
