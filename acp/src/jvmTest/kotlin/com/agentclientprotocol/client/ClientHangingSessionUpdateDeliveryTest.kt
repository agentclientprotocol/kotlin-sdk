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
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * `findSessionHolder` buffers updates for *any* unknown session id while some session is initializing, since the
 * client can't yet tell a legitimate concurrent `newSession`/`loadSession` apart from a genuinely unconnected
 * session. When the in-flight initialization finishes without ever claiming that id, the buffered holder is
 * reaped as "hanging" - its queued updates must still reach [GlobalSessionUpdateHandler] instead of being
 * silently discarded.
 *
 * See https://youtrack.jetbrains.com/issue/IJAI-1133
 */
class ClientHangingSessionUpdateDeliveryTest {
    @Test
    fun `update buffered for an unrelated session during a concurrent session-new still reaches the global handler`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = DeferredSessionNewTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()

            val received = CompletableDeferred<Pair<SessionId, SessionUpdate>>()
            val client = Client(
                protocol,
                globalElicitationHandler = null,
                globalSessionUpdateHandler = { sessionId, update, _ ->
                    received.complete(sessionId to update)
                },
            )

            val unrelatedSessionId = SessionId("unrelated-session")
            val unrelatedUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text("update for an unrelated session"))

            runBlocking {
                val newSessionResult = async(Dispatchers.Default) {
                    client.newSession(SessionCreationParameters(cwd = ".", mcpServers = emptyList())) { _, _ ->
                        NoOpSessionOperations
                    }
                }

                // Wait until `session/new` is actually on the wire: `initializingSessionsCount` is now positive.
                assertNotNull(
                    withTimeoutOrNull(5.seconds) { transport.sessionNewSent.await() },
                    "session/new was never sent",
                )

                // An update for a totally unrelated session arrives while our own session/new is still in flight.
                transport.emitSessionUpdate(unrelatedSessionId, unrelatedUpdate)

                // Completing session/new drops `initializingSessionsCount` back to zero, reaping the buffered
                // holder for the unrelated session.
                transport.completePendingSessionNew()
                newSessionResult.await()

                val result = withTimeoutOrNull(5.seconds) { received.await() }
                assertNotNull(result, "the buffered update for the unrelated session must reach the global handler")
                assertEquals(unrelatedSessionId, result.first)
                assertEquals(unrelatedUpdate, result.second)
            }
        } finally {
            scope.cancel()
        }
    }
}

/** Operations for the session actually being created; this test doesn't expect it to receive any updates. */
private object NoOpSessionOperations : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = error("not expected in this test")

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        error("no updates expected for the session being created in this test")
    }
}

/** A transport that holds the `session/new` response back until the test explicitly releases it. */
private class DeferredSessionNewTransport : BaseTransport() {
    val sessionNewSent = CompletableDeferred<Unit>()
    private var pendingRequestId: RequestId? = null

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
        pendingRequestId = message.id
        sessionNewSent.complete(Unit)
    }

    fun completePendingSessionNew() {
        val requestId = checkNotNull(pendingRequestId) { "session/new was not sent yet" }
        fireMessage(
            JsonRpcResponse(
                id = requestId,
                result = ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.SessionNew.responseSerializer,
                    NewSessionResponse(SessionId("session-created")),
                ),
            )
        )
    }

    fun emitSessionUpdate(sessionId: SessionId, update: SessionUpdate) {
        fireMessage(
            JsonRpcNotification(
                method = AcpMethod.ClientMethods.SessionUpdate.methodName,
                params = ACPJson.encodeToJsonElement(
                    AcpMethod.ClientMethods.SessionUpdate.serializer,
                    SessionNotification(sessionId, update),
                ),
            )
        )
    }
}
