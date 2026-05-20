package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the primary [StdioTransport] [Flow]-based constructor.
 *
 * The deprecated Source/Sink constructor has its own coverage in
 * [StdioTransportTest]; tests here exercise paths that only matter for the Flow
 * primary path (input flow termination/error, custom writer exception contract,
 * close-without-stream-close).
 */
class StdioTransportFlowTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `close on never-completing input reaches CLOSED`(): Unit = runBlocking {
        val transport = makeTransport(
            input = Channel<String>(Channel.UNLIMITED).receiveAsFlow(),
        )
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.close()
        transport.expectState(
            Transport.State.CLOSED,
            message = "close() did not drive state to CLOSED for a never-completing input Flow",
        )
    }

    @Test
    fun `send writes encoded line to output`(): Unit = runBlocking {
        val written = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(
            output = { line -> written.send(line) },
        )
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.send(JsonRpcRequest(RequestId.create(7), MethodName("ping")))

        val line = withTimeout(1.seconds) { written.receive() }
        assertTrue(line.contains("\"method\":\"ping\""), "encoded line should carry the method, was: $line")
        assertTrue(line.contains("\"id\":7"), "encoded line should carry the id, was: $line")
    }

    @Test
    fun `input emission fires onMessage`(): Unit = runBlocking {
        val inputChannel = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input = inputChannel.receiveAsFlow())
        val received = transport.asMessageChannel()
        transport.start()
        transport.expectState(Transport.State.STARTED)

        inputChannel.send("""{"jsonrpc":"2.0","method":"hello"}""")

        val message = withTimeout(1.seconds) { received.receive() }
        assertTrue(message is JsonRpcNotification)
        assertEquals(MethodName("hello"), message.method)
    }

    @Test
    fun `invalid JSON lines are skipped and valid ones still processed`(): Unit = runBlocking {
        val inputChannel = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input = inputChannel.receiveAsFlow())
        val received = transport.asMessageChannel()
        transport.start()
        transport.expectState(Transport.State.STARTED)

        inputChannel.send("not json at all")
        inputChannel.send("")
        inputChannel.send("""{"jsonrpc":"2.0","method":"after-garbage","id":1}""")

        val message = withTimeout(1.seconds) { received.receive() }
        assertTrue(message is JsonRpcRequest)
        assertEquals(MethodName("after-garbage"), message.method)
    }

    @Test
    fun `input flow completing drives state to CLOSED`(): Unit = runBlocking {
        // Flow that emits one valid message then completes naturally.
        val transport = makeTransport(
            input = flowOf("""{"jsonrpc":"2.0","method":"once"}"""),
        )
        val received = transport.asMessageChannel()
        transport.start()

        val message = withTimeout(1.seconds) { received.receive() }
        assertNotNull(message)

        transport.expectState(Transport.State.CLOSED, message = "completion of input flow should close transport")
    }

    @Test
    fun `input flow throwing drives state to CLOSED and fires onError`(): Unit = runBlocking {
        val sentinel = IllegalStateException("upstream blew up")
        val errors = mutableListOf<Throwable>()
        val transport = makeTransport(
            input = flow { throw sentinel },
        ).apply { onError { errors.add(it) } }
        transport.start()

        transport.expectState(Transport.State.CLOSED, message = "input flow error should close transport")
        assertTrue(errors.any { it === sentinel }, "expected upstream error to be reported via onError, got: $errors")
    }

    @Test
    fun `output IOException closes write loop without firing onError`(): Unit = runBlocking {
        val errors = mutableListOf<Throwable>()
        val transport = makeTransport(
            output = { throw IOException("peer gone") },
        ).apply { onError { errors.add(it) } }
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.send(JsonRpcNotification(method = MethodName("ignored")))

        transport.expectState(Transport.State.CLOSED, message = "IOException from output should close the transport")
        assertTrue(errors.isEmpty(), "IOException should be treated as clean shutdown, got: $errors")
    }

    @Test
    fun `output unexpected exception fires onError and closes`(): Unit = runBlocking {
        val sentinel = RuntimeException("writer bug")
        val errors = mutableListOf<Throwable>()
        val transport = makeTransport(
            output = { throw sentinel },
        ).apply { onError { errors.add(it) } }
        transport.start()
        transport.expectState(Transport.State.STARTED)

        transport.send(JsonRpcNotification(method = MethodName("ignored")))

        transport.expectState(Transport.State.CLOSED, message = "unexpected output error should close the transport")
        assertTrue(errors.any { it === sentinel }, "expected writer error to be reported via onError, got: $errors")
    }

    @Test
    fun `parent scope cancellation drives state to CLOSED`(): Unit = runBlocking {
        val transport = makeTransport(
            input = Channel<String>(Channel.UNLIMITED).receiveAsFlow(),
        )
        transport.start()
        transport.expectState(Transport.State.STARTED)

        scope.cancel()
        transport.expectState(Transport.State.CLOSED, message = "parent scope cancellation should close transport")
    }

    @Test
    fun `concurrent sends are all delivered to output`(): Unit = runBlocking {
        val written = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(
            output = { line -> written.send(line) },
        )
        transport.start()
        transport.expectState(Transport.State.STARTED)

        val sendJobs = (1..10).map { i ->
            scope.launch { transport.send(JsonRpcNotification(method = MethodName("method$i"))) }
        }
        sendJobs.joinAll()

        val received = withTimeout(1.seconds) { List(10) { written.receive() } }
        (1..10).forEach { i ->
            assertTrue(received.any { it.contains("\"method\":\"method$i\"") }, "missing method$i in $received")
        }
    }

    private fun makeTransport(
        input: Flow<String> = Channel<String>(Channel.UNLIMITED).receiveAsFlow(),
        output: suspend (String) -> Unit = { /* discard */ },
    ): StdioTransport = StdioTransport(
        parentScope = scope,
        ioDispatcher = Dispatchers.IO,
        input = input,
        output = output,
    )

    private suspend fun Transport.expectState(state: Transport.State, timeout: Duration = 1.seconds, message: String? = null) {
        val observed = mutableListOf<Transport.State>()
        try {
            withTimeout(timeout) {
                this@expectState.state
                    .onEach { observed.add(it) }
                    .first { it == state }
            }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting for state $state after $timeout, observed: ${observed.joinToString { it.name }}${message?.let { " — $it" } ?: ""}")
        }
    }
}
