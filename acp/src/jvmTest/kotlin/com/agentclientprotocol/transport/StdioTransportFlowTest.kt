package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcJson
import com.agentclientprotocol.rpc.parseTransportFrame
import kotlinx.serialization.SerializationException
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.RequestOutcome
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
import kotlin.test.assertIs
import kotlin.test.assertFails
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
    fun `valid batch occupies one line`(): Unit = runBlocking {
        val written = Channel<String>(Channel.UNLIMITED)
        val input = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input.receiveAsFlow(), output = { written.send(it) })
        val received = transport.asFrameChannel()
        transport.start()
        val batch = parseTransportFrame("""[{"jsonrpc":"2.0","method":"test"},{"jsonrpc":"2.0","method":"other"}]""")
        transport.send(batch)
        val line = withTimeout(1.seconds) { written.receive() }
        assertEquals(batch, parseTransportFrame(line))
        assertTrue(written.tryReceive().isFailure)
        input.send(line)
        assertEquals(batch, withTimeout(1.seconds) { received.receive() })
    }

    @Test
    fun `incoming batch retains malformed siblings`(): Unit = runBlocking {
        val input = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input.receiveAsFlow())
        val received = transport.asFrameChannel()
        transport.start()
        input.send("""[{"jsonrpc":"2.0","method":"test"},42]""")
        val batch = assertIs<TransportFrame.Batch>(withTimeout(1.seconds) { received.receive() })
        assertIs<TransportFrame.Single>(batch.entries[0])
        assertEquals(-32600, assertIs<TransportFrame.Malformed>(batch.entries[1]).error.code)
    }

    @Test
    fun `outgoing malformed frames fail the writer without emitting partial output`(): Unit = runBlocking {
        for (frame in listOf(parseTransportFrame("["), parseTransportFrame("""[{"jsonrpc":"2.0","method":"test"},42]"""))) {
            val written = Channel<String>(Channel.UNLIMITED)
            val input = Channel<String>(Channel.UNLIMITED)
            val transport = makeTransport(input.receiveAsFlow(), output = { written.send(it) })
            val error = CompletableDeferred<Throwable>()
            transport.onError { error.complete(it) }
            transport.start()
            transport.send(frame)
            assertIs<SerializationException>(withTimeout(1.seconds) { error.await() })
            transport.expectState(Transport.State.CLOSED)
            assertTrue(written.tryReceive().isFailure)
        }
    }

    @Test
    fun `close before start fires once and rejects sends`() {
        val transport = makeTransport()
        var closes = 0
        transport.onClose { closes++ }
        transport.close()
        transport.close()
        assertEquals(1, closes)
        assertEquals(Transport.State.CLOSED, transport.state.value)
        assertFails { transport.send(TransportFrame.Single(JsonRpcNotification(MethodName("test")))) }
        assertFails { transport.start() }
        assertEquals(Transport.State.CLOSED, transport.state.value)
    }

    @Test
    fun `writer failure after response queue acceptance releases outcomes and pending calls`(): Unit = runBlocking {
        withTimeout(5.seconds) {
            val input = Channel<String>(Channel.UNLIMITED)
            val requestWritten = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val attempts = kotlinx.atomicfu.atomic(0)
            val transport = makeTransport(input.receiveAsFlow(), output = { text ->
                val message = assertIs<TransportFrame.Single>(parseTransportFrame(text)).message
                if (message is JsonRpcResponse) {
                    attempts.incrementAndGet()
                    throw RuntimeException("writer failed after acceptance")
                }
                requestWritten.complete(Unit)
            })
            val protocol = Protocol(scope, transport)
            val method = AcpMethod.AgentMethods.V1.Initialize
            protocol.setRequestHandlerWithOutcomeRaw(method) {
                RequestOutcome(kotlinx.serialization.json.JsonNull,
                    afterResponse = { awaitCancellation() }, onCompletion = { cleaned.complete(Unit) })
            }
            protocol.start()
            try {
                val pending = async { runCatching { protocol.sendRequestRaw(method.methodName) } }
                requestWritten.await()
                input.send(JsonRpcJson.encodeToString(TransportFrame.serializer(),
                    TransportFrame.Single(JsonRpcRequest(RequestId.create(10), method.methodName))))
                cleaned.await()
                assertIs<CancellationException>(pending.await().exceptionOrNull())
                assertEquals(1, attempts.value)
                assertEquals(Transport.State.CLOSED, transport.state.value)
            } finally { protocol.close() }
        }
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

        transport.send(TransportFrame.Single(JsonRpcRequest(RequestId.create(7), MethodName("ping"))))

        val line = withTimeout(1.seconds) { written.receive() }
        assertTrue(line.contains("\"method\":\"ping\""), "encoded line should carry the method, was: $line")
        assertTrue(line.contains("\"id\":7"), "encoded line should carry the id, was: $line")
    }

    @Test
    fun `input emission fires onFrame`(): Unit = runBlocking {
        val inputChannel = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input = inputChannel.receiveAsFlow())
        val received = transport.asFrameChannel()
        transport.start()
        transport.expectState(Transport.State.STARTED)

        inputChannel.send("""{"jsonrpc":"2.0","method":"hello"}""")

        val message = (withTimeout(1.seconds) { received.receive() } as TransportFrame.Single).message
        assertTrue(message is JsonRpcNotification)
        assertEquals(MethodName("hello"), message.method)
    }

    @Test
    fun `invalid JSON lines are delivered and valid ones still processed`(): Unit = runBlocking {
        val inputChannel = Channel<String>(Channel.UNLIMITED)
        val transport = makeTransport(input = inputChannel.receiveAsFlow())
        val received = transport.asFrameChannel()
        transport.start()
        transport.expectState(Transport.State.STARTED)

        inputChannel.send("not json at all")
        inputChannel.send("")
        inputChannel.send("{} {}") // The JSON decoder checks trailing input after its serializer returns.
        inputChannel.send("""{"jsonrpc":"2.0","method":"after-garbage","id":1}""")

        repeat(3) { assertTrue(withTimeout(1.seconds) { received.receive() } is TransportFrame.Malformed) }
        val message = (withTimeout(1.seconds) { received.receive() } as TransportFrame.Single).message
        assertTrue(message is JsonRpcRequest)
        assertEquals(MethodName("after-garbage"), message.method)
    }

    @Test
    fun `input flow completing drives state to CLOSED`(): Unit = runBlocking {
        // Flow that emits one valid message then completes naturally.
        val transport = makeTransport(
            input = flowOf("""{"jsonrpc":"2.0","method":"once"}"""),
        )
        val received = transport.asFrameChannel()
        transport.start()

        val message = (withTimeout(1.seconds) { received.receive() } as TransportFrame.Single).message
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

        transport.send(TransportFrame.Single(JsonRpcNotification(method = MethodName("ignored"))))

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

        transport.send(TransportFrame.Single(JsonRpcNotification(method = MethodName("ignored"))))

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
            scope.launch { transport.send(TransportFrame.Single(JsonRpcNotification(method = MethodName("method$i")))) }
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
