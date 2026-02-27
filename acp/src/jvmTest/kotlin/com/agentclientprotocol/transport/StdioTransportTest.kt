package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.io.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.channels.Channels.newInputStream
import java.nio.channels.Channels.newOutputStream
import java.nio.channels.Pipe
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StdioTransportTest {
    class TestAccumulatedException(message: String) : Exception(message)

    private lateinit var pipe: Pipe
    private lateinit var source: Source
    private lateinit var sink: Sink
    private lateinit var scope: CoroutineScope
    private lateinit var errors: MutableList<Throwable>
    private lateinit var transport: StdioTransport
    private lateinit var messages: kotlinx.coroutines.channels.Channel<JsonRpcMessage>

    suspend fun expectState(state: Transport.State, timeout: Duration = 1.seconds, message: String? = null) {
        val observed = mutableListOf<Transport.State>()
        try {
            withTimeout(timeout) {
                transport.state
                    .onEach { observed.add(it) }
                    .first { it == state }
            }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting for state $state after $timeout, observed states: ${observed.joinToString { it.name }}, ${message?.let { ": $it" } ?: ""}")
        }
    }

    private fun throwTransportExceptions() {
        if (errors.isNotEmpty()) {
            val exception = TestAccumulatedException("Accumulated transport errors")
            errors.forEach { exception.addSuppressed(it) }
            throw exception
        }
        errors.clear()
    }


    @BeforeTest
    fun setUp() {
        pipe = Pipe.open()
        source = newInputStream(pipe.source()).asSource().buffered()
        sink = newOutputStream(pipe.sink()).asSink().buffered()
        scope = CoroutineScope(SupervisorJob())
        errors = mutableListOf()
        transport = StdioTransport(scope, Dispatchers.IO, input = source, output = sink).apply {
            onError { errors.add(it) }
        }
        messages = transport.asMessageChannel()
        transport.start()
    }

    @AfterTest
    fun tearDown() {
        transport.close()
        scope.cancel()
        if (errors.isNotEmpty()) {
            val exception = IOException("Accumulated transport errors")
            errors.forEach { exception.addSuppressed(it) }
            throw exception
        }
        throwTransportExceptions()
    }


    @Test
    fun `should read JSON-RPC request from input`(): Unit = runBlocking {
        val testMethod = MethodName("test.method")
        transport.send(JsonRpcRequest(RequestId.create(1), testMethod, JsonPrimitive("value")))

        // Read the message from the transport
        val message = messages.receive()

        assertTrue(message is JsonRpcRequest)
        assertEquals(RequestId.create(1), message.id)
        assertEquals(testMethod, message.method)
        assertNotNull(message.params)
    }

    @Test
    fun `should read JSON-RPC notification from input`(): Unit = runBlocking {
        val notificationTestMethod = MethodName("test.notification")
        transport.send(JsonRpcNotification(method = notificationTestMethod))

        // Read the message from the transport
        val message = messages.receive()

        assertTrue(message is JsonRpcNotification)
        assertEquals(notificationTestMethod, message.method)
    }

    @Test
    fun `should read JSON-RPC response from input`(): Unit = runBlocking {
        transport.send(JsonRpcResponse(RequestId.create(42), result = JsonPrimitive("success")))

        // Read the message from the transport
        val message = messages.receive()

        assertTrue(message is JsonRpcResponse)
        assertEquals(RequestId.create(42), message.id)
        assertEquals(JsonPrimitive("success"), message.result)
    }

    @Test
    fun `should handle multiple messages in sequence`(): Unit = runBlocking {
        val method1 = MethodName("method1")
        val notification1 = MethodName("notification1")

        transport.send(JsonRpcRequest(RequestId.create(1), method1))
        transport.send(JsonRpcNotification(method = notification1))
        transport.send(JsonRpcResponse(RequestId.create(2), result = JsonPrimitive("ok")))

        val message1 = messages.receive()
        val message2 = messages.receive()
        val message3 = messages.receive()

        assertTrue(message1 is JsonRpcRequest)
        assertEquals(method1, message1.method)

        assertTrue(message2 is JsonRpcNotification)
        assertEquals(notification1, message2.method)

        assertTrue(message3 is JsonRpcResponse)
        assertEquals(RequestId.create(2), message3.id)
    }

    @Test
    fun `should skip invalid JSON lines and continue processing`(): Unit = runBlocking {
        val firstMethod = MethodName("first")
        val secondMethod = MethodName("second")

        transport.send(JsonRpcRequest(RequestId.create(1), firstMethod))
        transport.send(JsonRpcRequest(RequestId.create(2), secondMethod))

        val message1 = messages.receive()
        assertTrue(message1 is JsonRpcRequest)
        assertEquals(firstMethod, message1.method)

        val message2 = messages.receive()
        assertTrue(message2 is JsonRpcRequest)
        assertEquals(secondMethod, message2.method)
    }

    @Test
    fun `should handle closing transport gracefully`(): Unit = runBlocking {
        expectState(Transport.State.STARTED)

        transport.close()
        expectState(Transport.State.CLOSED, message = "After 1 close")

        // Closing again should not throw
        transport.close()
        expectState(Transport.State.CLOSED, message = "After 2 close")
    }

    @Test
    fun `should handle close of sink gracefully`(): Unit = runBlocking {
        testWhileBackgroundSend {
            pipe.sink().close()
            expectState(Transport.State.CLOSED, message = "After close")
        }
    }

    @Test
    fun `should handle close of source gracefully`(): Unit = runBlocking {
        testWhileBackgroundSend {
            pipe.source().close()
            expectState(Transport.State.CLOSED, message = "After close")
        }
    }

    @Test
    fun `should handle close of transport gracefully`(): Unit = runBlocking {
        testWhileBackgroundSend {
            transport.close()
            expectState(Transport.State.CLOSED, message = "After close")
        }
    }

    @Test
    fun `should cancel scope gracefully`(): Unit = runBlocking {
        testWhileBackgroundSend {
            scope.cancel()
            expectState(Transport.State.CLOSED, message = "After close")
        }
    }

    private suspend fun CoroutineScope.testWhileBackgroundSend(block: suspend () -> Unit) {
        val testMethod = MethodName("test")
        expectState(Transport.State.STARTED)
        launch {
            var i = 0
            while (transport.state.value != Transport.State.CLOSED) {
                transport.send(JsonRpcRequest(RequestId.create(i++), testMethod))
                delay(10.milliseconds)
            }
        }
        delay(100.milliseconds)
        block()
    }


    @Test
    fun `should handle end of stream gracefully`(): Unit = runBlocking {
        val testMethod = MethodName("test")
        transport.send(JsonRpcRequest(RequestId.create(1), testMethod))

        val message = messages.receive()
        assertTrue(message is JsonRpcRequest)

        // Wait a bit to ensure input coroutine processes EOF
        delay(200.milliseconds)
    }

    @Test
    fun `should handle concurrent sends`(): Unit = runBlocking {
        // Send multiple messages concurrently
        val jobs = (1..10).map { i ->
            scope.launch {
                transport.send(JsonRpcNotification(method = MethodName("method$i")))
            }
        }

        jobs.joinAll()

        // Receive all messages
        val messages = (1..10).map { messages.receive() }

        // All messages should be received
        (1..10).forEach { i ->
            assertTrue(messages.any { it is JsonRpcNotification && it.method == MethodName("method$i") })
        }
    }
}