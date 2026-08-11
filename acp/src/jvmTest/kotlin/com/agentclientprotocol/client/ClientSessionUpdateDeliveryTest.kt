@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * An agent may put a `session/update` on the wire immediately after the `session/new` response, so the client
 * receives both back to back while `newSession` is still returning on another thread. The notification must
 * still reach the session's operations: the client either queues it on the holder being initialized, or hands
 * it to the session that has just been registered.
 *
 * Getting this wrong used to be observable as `AcpExpectedError: Session <id> not found` logged by the
 * protocol, plus a silently dropped update, whenever the notification was handled while the concurrent
 * `newSession` was registering its holder and dropping `initializingSessionsCount` back to zero.
 */
class ClientSessionUpdateDeliveryTest {
    @Test
    fun `session update sent right after the session new response reaches the session`() {
        val delivered = atomic(0)
        // The interleaving is only reachable when the notification is handled while a concurrent newSession is
        // registering its session, so this is a probabilistic stress test: correct code can never fail it, while
        // the regression it guards against showed up in roughly one run out of three on a warm 10-core laptop.
        // Each batch gets a fresh client to keep the session map - and with it the cost of a round - from
        // growing over the run.
        repeat(BATCHES) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val transport = EagerUpdateAgentTransport()
                val protocol = Protocol(scope, transport)
                protocol.start()
                transport.start()
                val client = Client(protocol)

                runBlocking {
                    // Real parallelism between the protocol read loop and the `newSession` continuation is the
                    // whole point, so sessions are created off the (single-threaded) runBlocking dispatcher.
                    withContext(Dispatchers.Default) {
                        repeat(ROUNDS_PER_BATCH) {
                            (1..CONCURRENCY).map {
                                async {
                                    val update = CompletableUpdate()
                                    client.newSession(SessionCreationParameters(cwd = ".", mcpServers = emptyList())) { _, _ ->
                                        update
                                    }
                                    if (update.awaitNotification()) delivered.incrementAndGet()
                                }
                            }.awaitAll()
                        }
                    }
                }
            } finally {
                scope.cancel()
            }
        }

        assertEquals(BATCHES * ROUNDS_PER_BATCH * CONCURRENCY, delivered.value, "every session/update must reach its session")
    }

    private companion object {
        private const val BATCHES = 60
        private const val ROUNDS_PER_BATCH = 250
        private const val CONCURRENCY = 4
    }
}

/** Records the single `session/update` the fake agent sends for a session. */
private class CompletableUpdate : ClientSessionOperations {
    private val notified = CompletableDeferred<Unit>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = error("not expected in this test")

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        notified.complete(Unit)
    }

    suspend fun awaitNotification(): Boolean = withTimeoutOrNull(NOTIFICATION_TIMEOUT) { notified.await() } != null

    private companion object {
        private val NOTIFICATION_TIMEOUT = 5.seconds
    }
}

/**
 * A minimal agent that answers `session/new` and, without yielding in between, sends one `session/update` for
 * the session it has just created - the sequence a real agent produces when it reports its available commands
 * as soon as the session exists.
 */
private class EagerUpdateAgentTransport : BaseTransport() {
    private val sessionCounter = atomic(0)

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    override fun send(message: JsonRpcMessage) {
        if (message !is JsonRpcRequest || message.method != AcpMethod.AgentMethods.SessionNew.methodName) return
        val sessionId = SessionId("session-${sessionCounter.incrementAndGet()}")
        fireMessage(
            JsonRpcResponse(
                id = message.id,
                result = ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.SessionNew.responseSerializer,
                    NewSessionResponse(sessionId),
                ),
            )
        )
        fireMessage(
            JsonRpcNotification(
                method = AcpMethod.ClientMethods.SessionUpdate.methodName,
                params = ACPJson.encodeToJsonElement(
                    AcpMethod.ClientMethods.SessionUpdate.serializer,
                    SessionNotification(sessionId, SessionUpdate.AgentMessageChunk(ContentBlock.Text("update"))),
                ),
            )
        )
    }
}
