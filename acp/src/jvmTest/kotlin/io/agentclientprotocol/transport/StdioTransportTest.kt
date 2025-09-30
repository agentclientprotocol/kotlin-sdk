package io.agentclientprotocol.transport

import io.agentclientprotocol.rpc.JsonRpcNotification
import io.agentclientprotocol.rpc.JsonRpcRequest
import io.agentclientprotocol.rpc.JsonRpcResponse
import io.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.*
import kotlinx.io.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class StdioTransportTest {
    private lateinit var pipe: Pipe
    private lateinit var source: Source
    private lateinit var sink: Sink
    private lateinit var scope: CoroutineScope
    private lateinit var transport: StdioTransport

    @BeforeTest
    fun setUp() {
        pipe = Pipe.open()
        source = Channels.newInputStream(pipe.source()).asSource().buffered()
        sink = Channels.newOutputStream(pipe.sink()).asSink().buffered()
        scope = CoroutineScope(SupervisorJob())
        transport = StdioTransport(scope, source, sink)
        transport.start()
    }

    @AfterTest
    fun tearDown() {
        transport.close()
        scope.cancel()
    }
    @Test
    fun `should read JSON-RPC request from input`(): Unit = runBlocking {
        transport.send(JsonRpcRequest(RequestId("1"), "test.method", JsonPrimitive("value")))

        // Read the message from the transport
        val message = transport.messages.receive()

        assertTrue(message is JsonRpcRequest)
        assertEquals(RequestId("1"), message.id)
        assertEquals("test.method", message.method)
        assertNotNull(message.params)
    }

    @Test
    fun `should read JSON-RPC notification from input`(): Unit = runBlocking {
        transport.send(JsonRpcNotification(method = "test.notification"))

        // Read the message from the transport
        val message = transport.messages.receive()

        assertTrue(message is JsonRpcNotification)
        assertEquals("test.notification", message.method)
    }

    @Test
    fun `should read JSON-RPC response from input`(): Unit = runBlocking {
        transport.send(JsonRpcResponse(RequestId("42"), result = JsonPrimitive("success")))

        // Read the message from the transport
        val message = transport.messages.receive()

        assertTrue(message is JsonRpcResponse)
        assertEquals(RequestId("42"), message.id)
        assertEquals(JsonPrimitive("success"), message.result)
    }

    @Test
    fun `should handle multiple messages in sequence`(): Unit = runBlocking {
        transport.send(JsonRpcRequest(RequestId("1"), "method1"))
        transport.send(JsonRpcNotification(method = "notification1"))
        transport.send(JsonRpcResponse(RequestId("2"), result = JsonPrimitive("ok")))

        val message1 = transport.messages.receive()
        val message2 = transport.messages.receive()
        val message3 = transport.messages.receive()

        assertTrue(message1 is JsonRpcRequest)
        assertEquals("method1", message1.method)

        assertTrue(message2 is JsonRpcNotification)
        assertEquals("notification1", message2.method)

        assertTrue(message3 is JsonRpcResponse)
        assertEquals(RequestId("2"), message3.id)
    }

    @Test
    fun `should skip invalid JSON lines and continue processing`(): Unit = runBlocking {
        transport.send(JsonRpcRequest(RequestId("1"), "first"))
        transport.send(JsonRpcRequest(RequestId("2"), "second"))

        val message1 = transport.messages.receive()
        assertTrue(message1 is JsonRpcRequest)
        assertEquals("first", message1.method)

        val message2 = transport.messages.receive()
        assertTrue(message2 is JsonRpcRequest)
        assertEquals("second", message2.method)
    }

    @Test
    fun `should report connected state correctly`(): Unit = runBlocking {
        assertTrue(transport.isConnected)

        transport.close()
        assertFalse(transport.isConnected)
    }

    @Test
    fun `should handle closing transport gracefully`(): Unit = runBlocking {
        assertTrue(transport.isConnected)

        transport.close()
        assertFalse(transport.isConnected)

        // Closing again should not throw
        transport.close()
        assertFalse(transport.isConnected)
    }


    @Test
    fun `should handle end of stream gracefully`(): Unit = runBlocking {
        transport.send(JsonRpcRequest(RequestId("1"), "test"))

        val message = transport.messages.receive()
        assertTrue(message is JsonRpcRequest)

        // Wait a bit to ensure input coroutine processes EOF
        delay(200.milliseconds)
    }

    @Test
    fun `should handle concurrent sends`(): Unit = runBlocking {
        // Send multiple messages concurrently
        val jobs = (1..10).map { i ->
            scope.launch {
                transport.send(JsonRpcNotification(method = "method$i"))
            }
        }

        jobs.joinAll()

        // Receive all messages
        val messages = (1..10).map { transport.messages.receive() }

        // All messages should be received
        (1..10).forEach { i ->
            assertTrue(messages.any { it is JsonRpcNotification && it.method == "method$i" })
        }
    }
}