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
            transport.receive(transport.prompt())
            val (receipt, updates) = transport.nextTurn(updates = 3)
            assertEquals(messageId, receipt)

            // Only the first user message update answers the prompt; a later upsert is an ordinary update.
            assertIs<SessionUpdate.UserMessageChunk>(updates[0])
            assertIs<SessionUpdate.UserMessage>(updates[1])
            assertEquals(idle(StopReason.EndTurn), updates[2])
        }
    }

    @Test
    fun `a turn that ends before insertion fails the prompt without an idle update`() {
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
            for ((index, failure) in failures.withIndex()) {
                transport.receive(transport.prompt())
                // What the turn reported before failing has already been sent.
                if (index > 0) assertEquals(running(), transport.nextUpdate())
                val error = transport.nextError()
                assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, error.code)
                assertEquals(failure.message, error.message)
            }

            transport.receive(transport.prompt())
            assertEquals(running(), transport.nextUpdate())
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, transport.nextError().code)

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `cancellation before insertion rejects the prompt as cancelled without an idle update`() {
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
            transport.receive(transport.prompt())
            started.receive()
            assertEquals(running(), transport.nextUpdate())
            transport.receive(TransportFrame.Single(JsonRpcNotification(
                AcpMethod.AgentMethods.V2.SessionCancel.methodName,
                ACPJson.encodeToJsonElement(CancelSessionNotification(sessionId)),
            )))
            assertEquals(JsonRpcErrorCode.CANCELLED.code, transport.nextError().code)
            assertFalse(returnedFromCancelledIdle, "emitting a cancelled idle update before insertion must not return")

            // Cancelled locally.
            transport.receive(transport.prompt())
            started.receive()
            assertEquals(running(), transport.nextUpdate())
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
            transport.receive(transport.prompt())
            val (receipt, updates) = transport.nextTurn(updates = 2)
            assertEquals(messageId, receipt)
            assertIs<SessionUpdate.UserMessage>(updates[0])
            assertEquals(idle(stopReason = null), updates[1], "no defined stop reason describes a failure")

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
            transport.receive(transport.prompt())
            val (receipt, updates) = transport.nextTurn(updates = 1)
            assertEquals(messageId, receipt)
            assertIs<SessionUpdate.UserMessage>(updates.single())
            inserted.await()
            protocol.cancelPendingIncomingRequests()
            assertEquals(idle(StopReason.Cancelled), transport.nextUpdate())

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `turn updates and client operations reach the client in the order the turn made them`() {
        val session = { client: ClientOperations ->
            Session {
                flow {
                    emit(userMessage())
                    emit(running())
                    client.notify(requiresAction())
                    client.requestPermission("Run the tool?", emptyList())
                    emit(running())
                    emit(idle(StopReason.EndTurn))
                }
            }
        }
        test(session) { transport, _ ->
            transport.receive(transport.prompt())

            val messages = mutableListOf<JsonRpcMessage>()
            while (messages.none { it is JsonRpcRequest }) messages += transport.nextMessage()
            transport.answerPermission(messages.last() as JsonRpcRequest)
            while (messages.size < 7) messages += transport.nextMessage()

            val receipt = messages.single { it is JsonRpcSuccessResponse }
            assertEquals(messageId, receipt.receipt())
            assertEquals(
                listOf(
                    userMessage(),
                    running(),
                    requiresAction(),
                    AcpMethod.ClientMethods.V2.SessionRequestPermission.methodName,
                    running(),
                    idle(StopReason.EndTurn),
                ),
                messages.filter { it !== receipt }.map { it.sessionTraffic() },
            )
        }
    }

    @Test
    fun `a batched prompt does not hold the session lock while waiting for a sibling reply`() {
        val mutex = Mutex()
        val locked = CompletableDeferred<Unit>()
        val session = { client: ClientOperations ->
            object : Session({
                flow {
                    mutex.withLock {
                        locked.complete(Unit)
                        emit(running())
                        emit(userMessage())
                        // A round trip to the client while the batch reply is still waiting for its sibling.
                        client.requestPermission("Run the tool?", emptyList())
                        emit(idle(StopReason.EndTurn))
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
            transport.receive(TransportFrame.Batch(listOf(transport.prompt(), setConfigOption)))

            val traffic = mutableListOf<Any>()
            var frame = transport.sent.receive()
            while (frame is TransportFrame.Single) {
                val message = frame.message
                if (message is JsonRpcRequest) transport.answerPermission(message)
                traffic += message.sessionTraffic()
                frame = transport.sent.receive()
            }
            val response = assertIs<TransportFrame.Batch>(frame)
            val replies = response.entries.map { assertIs<JsonRpcSuccessResponse>(assertIs<TransportFrame.Single>(it).message) }
            assertEquals(listOf(RequestId.create(3), RequestId.create(4)), replies.map { it.id })
            assertEquals(messageId, replies.first().receipt())
            assertEquals(
                listOf(
                    running(),
                    userMessage(),
                    AcpMethod.ClientMethods.V2.SessionRequestPermission.methodName,
                    idle(StopReason.EndTurn),
                ),
                traffic,
            )
        }
    }

    @Test
    fun `a rejected receipt stops the turn as cancelled and allows the next prompt`() {
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
            transport.receive(transport.prompt())
            stopped.await()
            assertIs<SessionUpdate.UserMessage>(transport.nextUpdate())
            assertEquals(idle(StopReason.Cancelled), transport.nextUpdate())
            assertEquals(Transport.State.STARTED, transport.state.value)

            transport.assertAcceptsPrompt()
        }
    }

    @Test
    fun `closing a session mid-turn answers after the cancelled turn has ended`() {
        val cancelled = CompletableDeferred<Unit>()
        val finalUpdate = SessionUpdate.AgentMessageChunk(ContentChunk(MessageId("agent"), ContentBlock.Text("stopped")))
        val session = object : Session({
            flow {
                emit(userMessage())
                emit(running())
                cancelled.await()
                emit(finalUpdate)
                emit(idle(StopReason.Cancelled))
            }
        }) {
            override suspend fun cancel() {
                cancelled.complete(Unit)
            }
        }
        test(session) { transport, _ ->
            transport.receive(transport.prompt())
            assertEquals(listOf(userMessage(), running()), transport.nextTurn(updates = 2).second)

            transport.receive(transport.closeSession())
            assertEquals(finalUpdate, transport.nextUpdate())
            assertEquals(idle(StopReason.Cancelled), transport.nextUpdate())
            assertIs<JsonRpcSuccessResponse>(transport.nextMessage())
        }
    }

    @Test
    fun `closing an idle session answers without cancelling or reporting anything`() {
        val session = object : Session({ acceptedTurn() }) {
            override suspend fun cancel() {
                fail("an idle session has nothing to cancel")
            }
        }
        test(session) { transport, _ ->
            transport.receive(transport.prompt())
            transport.nextTurn(updates = 2)

            transport.receive(transport.closeSession())
            assertIs<JsonRpcSuccessResponse>(transport.nextMessage())
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

    private fun test(session: AgentSession, block: suspend (FrameTransport, Protocol) -> Unit) =
        test({ session }, block)

    private fun test(
        session: (ClientOperations) -> AgentSession,
        block: suspend (FrameTransport, Protocol) -> Unit,
    ) = runBlocking {
        withTimeout(5.seconds) {
            val transport = FrameTransport()
            val protocol = Protocol(this, transport)
            Agent(protocol, object : AgentSupport {
                override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(Implementation("test", "1"))
                override suspend fun createSession(parameters: SessionCreationParameters, client: ClientOperations) =
                    session(client)

                override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?) = CloseSessionResponse()
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
        private var nextPromptId = 3

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        fun receive(frame: TransportFrame) = fireFrame(frame)

        /**
         * A prompt with a fresh request id. A reply can reach the client before the agent releases the id
         * of the request it answers, so reusing an id for the next prompt races against that release.
         */
        fun prompt(): TransportFrame.Single = TransportFrame.Single(JsonRpcRequest(
            RequestId.create(nextPromptId++), AcpMethod.AgentMethods.V2.SessionPrompt.methodName,
            ACPJson.encodeToJsonElement(PromptRequest(sessionId, listOf(ContentBlock.Text("hello")))),
        ))

        fun closeSession(): TransportFrame.Single = TransportFrame.Single(JsonRpcRequest(
            RequestId.create(nextPromptId++), AcpMethod.AgentMethods.V2.SessionClose.methodName,
            ACPJson.encodeToJsonElement(CloseSessionRequest(sessionId)),
        ))

        suspend fun nextMessage(): JsonRpcMessage = assertIs<TransportFrame.Single>(sent.receive()).message

        /**
         * Reads the next message as an error reply, which fails if any update was sent ahead of it.
         */
        suspend fun nextError(): JsonRpcError = assertIs<JsonRpcErrorResponse>(nextMessage()).error

        suspend fun nextUpdate(): SessionUpdate = nextMessage().sessionUpdate()

        fun answerPermission(request: JsonRpcRequest) {
            assertEquals(AcpMethod.ClientMethods.V2.SessionRequestPermission.methodName, request.method)
            receive(TransportFrame.Single(JsonRpcSuccessResponse(
                request.id,
                ACPJson.encodeToJsonElement(RequestPermissionResponse(RequestPermissionOutcome.Cancelled)),
            )))
        }

        /**
         * Reads one receipt and [updates] session updates, in whatever order the receipt arrives among them.
         */
        suspend fun nextTurn(updates: Int): Pair<MessageId, List<SessionUpdate>> {
            val messages = List(updates + 1) { nextMessage() }
            val receipt = messages.single { it is JsonRpcSuccessResponse }
            return receipt.receipt() to messages.filter { it !== receipt }.map { it.sessionUpdate() }
        }

        /**
         * Checks that a further prompt is accepted and that nothing from an earlier turn arrives among its messages.
         */
        suspend fun assertAcceptsPrompt() {
            receive(prompt())
            val (receipt, updates) = nextTurn(updates = 2)
            assertEquals(messageId, receipt)
            assertEquals(listOf(userMessage(), idle(StopReason.EndTurn)), updates)
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

        fun requiresAction(): SessionUpdate = SessionUpdate.StateUpdate(StateUpdate.RequiresAction())

        fun idle(stopReason: StopReason?): SessionUpdate = SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = stopReason))

        fun acceptedTurn(): Flow<SessionUpdate> = flow {
            emit(userMessage())
            emit(idle(StopReason.EndTurn))
        }

        fun JsonRpcMessage.receipt(): MessageId = ACPJson.decodeFromJsonElement<PromptResponse>(
            assertNotNull(assertIs<JsonRpcSuccessResponse>(this).result),
        ).messageId

        /**
         * A session update, or the method of a request the agent made of the client.
         */
        fun JsonRpcMessage.sessionTraffic(): Any = if (this is JsonRpcRequest) method else sessionUpdate()

        fun JsonRpcMessage.sessionUpdate(): SessionUpdate = ACPJson.decodeFromJsonElement<UpdateSessionNotification>(
            assertNotNull(assertIs<JsonRpcNotification>(this).params),
        ).update
    }
}
