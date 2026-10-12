@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.AcpRequestCancelledException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PromptReceiptTest {
    @Test
    fun `the first user message update is the receipt`() {
        val session = Session {
            flow {
                emit(SessionUpdate.UserMessageChunk(ContentChunk(messageId, ContentBlock.Text("hello"))))
                emit(userMessage())
                emit(idle(StopReason.EndTurn))
            }
        }
        test(session) { transport, _ ->
            transport.receive(prompt())
            assertEquals(messageId, transport.nextReceipt())
            assertIs<SessionUpdate.UserMessageChunk>(transport.nextUpdate())

            // Only the first user message update answers the prompt; a later upsert is an ordinary update.
            assertIs<SessionUpdate.UserMessage>(transport.nextUpdate())
            assertEquals(idle(StopReason.EndTurn), transport.nextUpdate())
        }
    }

    @Test
    fun `a turn that ends before insertion fails the prompt and publishes nothing`() {
        val failures = listOf(
            IllegalStateException("rejected before the turn"),
            IllegalStateException("insertion failed"),
            // A callback answered with -32800 fails this prompt, but the client did not cancel it.
            AcpRequestCancelledException("permission cancelled"),
        )
        val session = Session { number ->
            val failure = failures.getOrNull(number - 1)
            when {
                number == 1 -> throw assertNotNull(failure)
                failure != null -> flow {
                    emit(running())
                    throw failure
                }
                number == failures.size + 1 -> flow { emit(running()) }
                else -> acceptedTurn()
            }
        }
        test(session) { transport, _ ->
            for (failure in failures) {
                transport.receive(prompt())
                val error = transport.nextError()
                assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, error.code)
                assertEquals(failure.message, error.message)
            }

            transport.receive(prompt())
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, transport.nextError().code)

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `cancellation before insertion rejects the prompt as cancelled and publishes nothing`() {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cancelled = CompletableDeferred<Unit>()
        var returnedFromCancelledIdle = false
        val session = object : Session({ number ->
            if (number > 2) {
                acceptedTurn()
            } else {
                flow {
                    emit(running())
                    started.send(Unit)
                    if (number == 1) {
                        cancelled.await()
                        emit(idle(StopReason.Cancelled))
                        returnedFromCancelledIdle = true
                    } else {
                        awaitCancellation()
                    }
                }
            }
        }) {
            override suspend fun cancel() {
                cancelled.complete(Unit)
            }
        }
        test(session) { transport, protocol ->
            // Cancelled by the client: the implementation reports the cancelled idle update itself.
            transport.receive(prompt())
            started.receive()
            transport.receive(TransportFrame.Single(JsonRpcNotification(
                AcpMethod.AgentMethods.V2.SessionCancel.methodName,
                ACPJson.encodeToJsonElement(CancelSessionNotification(sessionId)),
            )))
            assertEquals(JsonRpcErrorCode.CANCELLED.code, transport.nextError().code)
            assertFalse(returnedFromCancelledIdle, "emitting a cancelled idle update before insertion must not return")

            // Cancelled locally.
            transport.receive(prompt())
            started.receive()
            protocol.cancelPendingIncomingRequests()
            assertEquals(JsonRpcErrorCode.CANCELLED.code, transport.nextError().code)

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `a turn that fails after insertion keeps its receipt and is reported idle`() {
        val session = Session { number ->
            flow {
                emit(userMessage())
                if (number == 1) error("failed mid-turn")
                emit(idle(StopReason.EndTurn))
            }
        }
        test(session) { transport, _ ->
            transport.receive(prompt())
            assertEquals(messageId, transport.nextReceipt())
            assertIs<SessionUpdate.UserMessage>(transport.nextUpdate())
            assertEquals(idle(stopReason = null), transport.nextUpdate(), "no defined stop reason describes a failure")

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `local cancellation after insertion reports the turn idle as cancelled`() {
        val inserted = CompletableDeferred<Unit>()
        val session = Session { number ->
            flow {
                emit(userMessage())
                if (number == 1) {
                    inserted.complete(Unit)
                    awaitCancellation()
                }
                emit(idle(StopReason.EndTurn))
            }
        }
        test(session) { transport, protocol ->
            transport.receive(prompt())
            assertEquals(messageId, transport.nextReceipt())
            assertIs<SessionUpdate.UserMessage>(transport.nextUpdate())
            inserted.await()
            protocol.cancelPendingIncomingRequests()
            assertEquals(idle(StopReason.Cancelled), transport.nextUpdate())

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `a batched prompt does not hold the session lock while waiting for a sibling reply`() {
        val mutex = Mutex()
        val locked = CompletableDeferred<Unit>()
        val expected = listOf(running(), userMessage(), idle(StopReason.EndTurn))
        val session = object : Session({
            flow {
                mutex.withLock {
                    locked.complete(Unit)
                    expected.forEach { emit(it) }
                }
            }
        }) {
            override suspend fun setConfigOption(
                configId: SessionConfigId,
                value: SessionConfigOptionValue,
                _meta: JsonElement?,
            ): List<SessionConfigOption> {
                locked.await()
                return mutex.withLock { emptyList() }
            }
        }
        test(session) { transport, _ ->
            val setConfigOption = TransportFrame.Single(JsonRpcRequest(
                RequestId.create(4),
                AcpMethod.AgentMethods.V2.SessionSetConfigOption.methodName,
                buildJsonObject {
                    put("sessionId", sessionId.value)
                    put("configId", "mode")
                    put("type", "value_id")
                    put("value", "ask")
                },
            ))
            transport.receive(TransportFrame.Batch(listOf(prompt(), setConfigOption)))

            val notifications = mutableListOf<JsonRpcNotification>()
            var frame = transport.sent.receive()
            while (frame is TransportFrame.Single) {
                notifications += assertIs<JsonRpcNotification>(frame.message)
                frame = transport.sent.receive()
            }
            val response = assertIs<TransportFrame.Batch>(frame)
            val replies = response.entries.map { assertIs<JsonRpcSuccessResponse>(assertIs<TransportFrame.Single>(it).message) }
            assertEquals(listOf(RequestId.create(3), RequestId.create(4)), replies.map { it.id })
            assertEquals(messageId, ACPJson.decodeFromJsonElement<PromptResponse>(assertNotNull(replies.first().result)).messageId)
            repeat(expected.size - notifications.size) {
                notifications += assertIs<JsonRpcNotification>(transport.nextMessage())
            }
            assertEquals(expected, notifications.map { it.sessionUpdate() })
        }
    }

    @Test
    fun `a rejected receipt stops the turn without publishing it and allows the next prompt`() {
        val stopped = CompletableDeferred<Unit>()
        val session = Session { number ->
            if (number > 1) {
                acceptedTurn()
            } else {
                flow {
                    try {
                        emit(userMessage())
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }
        test(session) { transport, _ ->
            transport.rejectReply = true
            transport.receive(prompt())
            stopped.await()
            assertTrue(transport.sent.tryReceive().isFailure, "A rejected receipt must not publish any turn updates")
            assertEquals(Transport.State.STARTED, transport.state.value)

            transport.assertAcceptsPrompt()
        }
    }

    /**
     * A session whose turns come from [turn], called with the 1-based number of each turn.
     */
    private open class Session(private val turn: (number: Int) -> Flow<SessionUpdate>) : AgentSession {
        override val sessionId = PromptReceiptTest.sessionId
        private val turns = atomic(0)

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = turn(turns.incrementAndGet())
    }

    private fun test(session: AgentSession, block: suspend (FrameTransport, Protocol) -> Unit) = runBlocking {
        withTimeout(5.seconds) {
            val transport = FrameTransport()
            val protocol = Protocol(this, transport)
            Agent(protocol, object : AgentSupport {
                override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(Implementation("test", "1"))
                override suspend fun createSession(parameters: SessionCreationParameters, client: ClientOperations) = session
            })
            protocol.start()
            try {
                transport.receive(TransportFrame.Single(JsonRpcRequest(
                    RequestId.create(1), AcpMethod.AgentMethods.V2.Initialize.methodName,
                    ACPJson.encodeToJsonElement(InitializeRequest(PROTOCOL_VERSION_V2, info = Implementation("test", "1"))),
                )))
                assertIs<JsonRpcSuccessResponse>(transport.nextMessage())
                transport.receive(TransportFrame.Single(JsonRpcRequest(
                    RequestId.create(2), AcpMethod.AgentMethods.V2.SessionNew.methodName,
                    ACPJson.encodeToJsonElement(NewSessionRequest(cwd = ".")),
                )))
                assertIs<JsonRpcSuccessResponse>(transport.nextMessage())
                block(transport, protocol)
            } finally {
                protocol.close()
            }
        }
    }

    private class FrameTransport : BaseTransport() {
        val sent = Channel<TransportFrame>(Channel.UNLIMITED)
        var rejectReply = false

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        fun receive(frame: TransportFrame) = fireFrame(frame)

        suspend fun nextMessage(): JsonRpcMessage = assertIs<TransportFrame.Single>(sent.receive()).message

        suspend fun nextReceipt(): MessageId = ACPJson.decodeFromJsonElement<PromptResponse>(
            assertNotNull(assertIs<JsonRpcSuccessResponse>(nextMessage()).result),
        ).messageId

        /**
         * Reads the next message as an error reply, which fails if any update was published ahead of it.
         */
        suspend fun nextError(): JsonRpcError = assertIs<JsonRpcErrorResponse>(nextMessage()).error

        suspend fun nextUpdate(): SessionUpdate = nextMessage().sessionUpdate()

        /**
         * Checks that a further prompt is accepted and that nothing from an earlier turn arrives ahead of its receipt.
         */
        suspend fun assertAcceptsPrompt() {
            receive(prompt())
            assertEquals(messageId, nextReceipt())
            assertIs<SessionUpdate.UserMessage>(nextUpdate())
            assertEquals(idle(StopReason.EndTurn), nextUpdate())
        }

        override fun send(frame: TransportFrame) {
            if (rejectReply && frame is TransportFrame.Single && frame.message is JsonRpcResponse) {
                rejectReply = false
                error("reply rejected")
            }
            sent.trySend(frame).getOrThrow()
        }

        override fun close() {
            _state.value = Transport.State.CLOSED
            fireClose()
        }
    }

    private companion object {
        val sessionId = SessionId("receipt-test")
        val messageId = MessageId("inserted-user-message")

        fun userMessage(): SessionUpdate = SessionUpdate.UserMessage(
            UserMessage(messageId, MaybeUndefined.Value(listOf(ContentBlock.Text("hello")))),
        )

        fun running(): SessionUpdate = SessionUpdate.StateUpdate(StateUpdate.Running())

        fun idle(stopReason: StopReason?): SessionUpdate = SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = stopReason))

        fun acceptedTurn(): Flow<SessionUpdate> = flow {
            emit(userMessage())
            emit(idle(StopReason.EndTurn))
        }

        fun prompt(): TransportFrame.Single = TransportFrame.Single(JsonRpcRequest(
            RequestId.create(3), AcpMethod.AgentMethods.V2.SessionPrompt.methodName,
            ACPJson.encodeToJsonElement(PromptRequest(sessionId, listOf(ContentBlock.Text("hello")))),
        ))

        fun JsonRpcMessage.sessionUpdate(): SessionUpdate = ACPJson.decodeFromJsonElement<UpdateSessionNotification>(
            assertNotNull(assertIs<JsonRpcNotification>(this).params),
        ).update
    }
}
