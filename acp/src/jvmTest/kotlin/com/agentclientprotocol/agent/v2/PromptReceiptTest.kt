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
    fun cancelBeforeInsertionRejectsWithoutPublishingUpdates() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = object : Session() {
            override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
                emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
                started.complete(Unit)
                cancelled.await()
                emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.Cancelled)))
            }

            override suspend fun cancel() {
                cancelled.complete(Unit)
            }
        }
        test(session) { transport ->
            transport.receive(prompt())
            started.await()
            transport.receive(TransportFrame.Single(JsonRpcNotification(
                AcpMethod.AgentMethods.V2.SessionCancel.methodName,
                ACPJson.encodeToJsonElement(CancelSessionNotification(sessionId)),
            )))
            val notifications = mutableListOf<JsonRpcMessage>()
            var message = transport.nextMessage()
            while (message is JsonRpcNotification) {
                notifications += message
                message = transport.nextMessage()
            }
            val response = assertIs<JsonRpcErrorResponse>(message)
            assertEquals(JsonRpcErrorCode.CANCELLED.code, response.error.code)
            assertTrue(notifications.isEmpty())
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test
    fun failedInsertionDiscardsUpdatesAndDoesNotPropagateCallbackCancellation() {
        val failures = listOf(IllegalStateException("insertion failed"), AcpRequestCancelledException("permission cancelled"))
        for (failure in failures) {
            val session = object : Session() {
                override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
                    emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
                    throw failure
                }
            }
            test(session) { transport ->
                transport.receive(prompt())
                val response = assertIs<JsonRpcErrorResponse>(transport.nextMessage())
                assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, response.error.code)
                assertEquals(failure.message, response.error.message)
                assertTrue(transport.sent.tryReceive().isFailure)
            }
        }
    }

    @Test
    fun batchedPromptDoesNotHoldSessionLockWhileWaitingForSiblingReply() {
        val mutex = Mutex()
        val locked = CompletableDeferred<Unit>()
        val expected = listOf(
            SessionUpdate.StateUpdate(StateUpdate.Running()),
            userMessage(),
            SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)),
        )
        val session = object : Session() {
            override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
                mutex.withLock {
                    locked.complete(Unit)
                    expected.forEach { emit(it) }
                }
            }

            override suspend fun setConfigOption(
                configId: SessionConfigId,
                value: SessionConfigOptionValue,
                _meta: JsonElement?,
            ): List<SessionConfigOption> {
                locked.await()
                return mutex.withLock { emptyList() }
            }
        }
        test(session) { transport ->
            transport.receive(TransportFrame.Batch(listOf(prompt(), TransportFrame.Single(JsonRpcRequest(
                RequestId.create(4),
                AcpMethod.AgentMethods.V2.SessionSetConfigOption.methodName,
                buildJsonObject {
                    put("sessionId", sessionId.value)
                    put("configId", "mode")
                    put("type", "value_id")
                    put("value", "ask")
                },
            )))))
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
            val updates = notifications.map {
                ACPJson.decodeFromJsonElement<UpdateSessionNotification>(assertNotNull(it.params)).update
            }
            assertEquals(expected, updates)
        }
    }

    @Test
    fun rejectedReceiptStopsCollectorWithoutPublishingTurnAndAllowsNextPrompt() {
        val stopped = CompletableDeferred<Unit>()
        val session = object : Session() {
            override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
                try {
                    emit(userMessage())
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        test(session) { transport ->
            transport.rejectReply = true
            transport.receive(prompt())
            stopped.await()
            assertTrue(transport.sent.tryReceive().isFailure, "A rejected receipt must not publish any turn updates")
            assertEquals(Transport.State.STARTED, transport.state.value)
            transport.receive(prompt())
            assertIs<JsonRpcSuccessResponse>(transport.nextMessage())
            assertIs<JsonRpcNotification>(transport.nextMessage())
        }
    }

    private abstract class Session : AgentSession {
        override val sessionId = PromptReceiptTest.sessionId
        abstract override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate>
    }

    private fun test(session: AgentSession, block: suspend (FrameTransport) -> Unit) = runBlocking {
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
                block(transport)
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

        fun prompt(): TransportFrame.Single = TransportFrame.Single(JsonRpcRequest(
            RequestId.create(3), AcpMethod.AgentMethods.V2.SessionPrompt.methodName,
            ACPJson.encodeToJsonElement(PromptRequest(sessionId, listOf(ContentBlock.Text("hello")))),
        ))
    }
}