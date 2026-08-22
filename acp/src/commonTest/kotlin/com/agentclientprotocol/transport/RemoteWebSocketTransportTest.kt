package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RemoteWebSocketTransportTest {
    @Test
    fun `send writes encoded text frame`(): TestResult = runTest {
        val connection = FakeAcpWebSocketConnection()
        val transport = RemoteWebSocketTransport(backgroundScope, connection)
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.send(JsonRpcRequest(RequestId.create(7), MethodName("ping")))

        val text = withTimeout(1.seconds) { connection.sentTextFrames.receive() }
        assertTrue(text.contains("\"method\":\"ping\""), "encoded frame should carry the method, was: $text")
        assertTrue(text.contains("\"id\":7"), "encoded frame should carry the id, was: $text")
        transport.close()
    }

    @Test
    fun `incoming text frame fires onMessage`(): TestResult = runTest {
        val connection = FakeAcpWebSocketConnection()
        val transport = RemoteWebSocketTransport(backgroundScope, connection)
        val received = transport.asMessageChannel()
        transport.start()
        transport.expectState(Transport.State.STARTED)

        connection.inboundTextFrames.send("""{"jsonrpc":"2.0","method":"hello"}""")

        val message = withTimeout(1.seconds) { received.receive() }
        assertTrue(message is JsonRpcNotification)
        assertEquals(MethodName("hello"), message.method)
        transport.close()
    }

    @Test
    fun `invalid incoming frame reports error and continues`(): TestResult = runTest {
        val errors = mutableListOf<Throwable>()
        val connection = FakeAcpWebSocketConnection()
        val transport = RemoteWebSocketTransport(backgroundScope, connection).apply {
            onError { errors.add(it) }
        }
        val received = Channel<JsonRpcMessage>(Channel.UNLIMITED)
        transport.onMessage { received.trySend(it) }
        transport.start()
        transport.expectState(Transport.State.STARTED)

        connection.inboundTextFrames.send("not json")
        connection.inboundTextFrames.send("""{"jsonrpc":"2.0","method":"after-invalid"}""")

        val message = withTimeout(1.seconds) { received.receive() }
        assertTrue(errors.isNotEmpty(), "invalid JSON should be reported through onError")
        assertTrue(message is JsonRpcNotification)
        assertEquals(MethodName("after-invalid"), message.method)
        transport.close()
    }

    @Test
    fun `close closes connection and reaches closed state`(): TestResult = runTest {
        val connection = FakeAcpWebSocketConnection()
        val transport = RemoteWebSocketTransport(backgroundScope, connection)
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.close()

        transport.expectState(Transport.State.CLOSED)
        assertTrue(connection.closed, "close should close the underlying WebSocket connection")
    }

    private class FakeAcpWebSocketConnection : AcpWebSocketConnection {
        val inboundTextFrames = Channel<String>(Channel.UNLIMITED)
        val sentTextFrames = Channel<String>(Channel.UNLIMITED)
        var closed: Boolean = false
            private set

        override val incomingTextFrames = inboundTextFrames.receiveAsFlow()

        override suspend fun sendText(text: String) {
            sentTextFrames.send(text)
        }

        override fun close() {
            closed = true
            inboundTextFrames.close()
        }
    }

    private suspend fun Transport.expectState(state: Transport.State, timeout: Duration = 1.seconds) {
        val observed = mutableListOf<Transport.State>()
        try {
            withTimeout(timeout) {
                this@expectState.state
                    .onEach { observed.add(it) }
                    .first { it == state }
            }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting for state $state after $timeout, observed: ${observed.joinToString { it.name }}")
        }
    }
}
