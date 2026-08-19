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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A session can live on the server without this client ever having called `session/new` / `session/load` /
 * `session/resume` for it - e.g. it was created from another IDE window, the web, or another machine. An agent
 * notifying this client about such a session via `session/update` (for example a `session_info_update` reporting
 * a status change) is not a protocol violation and must not be treated as an error.
 *
 * See https://youtrack.jetbrains.com/issue/IJAI-1133
 */
class ClientSessionUpdateForUnconnectedSessionTest {
    @Test
    fun `session update for a session the client never connected to is ignored, not failed`() {
        val capturedErr = ByteArrayOutputStream()
        val originalErr = System.err
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            System.setErr(PrintStream(capturedErr))
            val transport = ManualUpdateAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val client = Client(protocol)

            runBlocking {
                val connectedSessionUpdate = UpdateRecorder()
                val connectedSession = withContext(Dispatchers.Default) {
                    client.newSession(SessionCreationParameters(cwd = ".", mcpServers = emptyList())) { _, _ ->
                        connectedSessionUpdate
                    }
                }

                // An update for a session this client never created/loaded.
                transport.emitSessionUpdate(SessionId("unconnected-session"), SessionUpdate.AgentMessageChunk(ContentBlock.Text("update")))

                // Sent right after: the handler dispatcher processes notifications in the order they arrive, so this
                // one being delivered proves the unconnected-session update above didn't wedge or crash the pipeline.
                transport.emitSessionUpdate(connectedSession.sessionId, SessionUpdate.AgentMessageChunk(ContentBlock.Text("update")))
                assertTrue(connectedSessionUpdate.awaitNotification(), "update for the connected session must still be delivered")
            }
        } finally {
            scope.cancel()
            System.setErr(originalErr)
        }

        val loggedOutput = capturedErr.toString()
        assertFalse(loggedOutput.contains("not found"), "an update for an unconnected session must not fail: $loggedOutput")
    }
}

/** Records the single `session/update` the fake agent sends for a session. */
private class UpdateRecorder : ClientSessionOperations {
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

/** A minimal agent transport that answers `session/new` and lets the test fire arbitrary `session/update`s. */
private class ManualUpdateAgentTransport : BaseTransport() {
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
