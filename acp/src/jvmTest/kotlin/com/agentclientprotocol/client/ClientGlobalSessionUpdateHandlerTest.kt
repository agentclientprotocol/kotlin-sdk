@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A session can live on the server without this client ever having called `session/new` / `session/load` /
 * `session/resume` for it. [GlobalSessionUpdateHandler] lets a client observe such updates - e.g. to keep a
 * `session/list`-rendered list live without polling - instead of the update being silently dropped.
 *
 * See https://youtrack.jetbrains.com/issue/IJAI-1133
 */
class ClientGlobalSessionUpdateHandlerTest {
    @Test
    fun `session update for an unconnected session is delivered to the global session update handler`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = NotifyingTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()

            val received = CompletableDeferred<Pair<SessionId, SessionUpdate>>()
            val client = Client(
                protocol,
                globalSessionUpdateHandler = GlobalSessionUpdateHandler { sessionId, update, _ ->
                    received.complete(sessionId to update)
                },
            )

            val sessionId = SessionId("unconnected-session")
            val update = SessionUpdate.AgentMessageChunk(ContentBlock.Text("update"))

            runBlocking {
                transport.emitSessionUpdate(sessionId, update)
                val result = withTimeoutOrNull(5.seconds) { received.await() }
                assertNotNull(result, "the global session update handler must be invoked for an unconnected session")
                assertEquals(sessionId, result.first)
                assertEquals(update, result.second)
            }
        } finally {
            scope.cancel()
        }
    }
}

/** A transport whose only job is to let the test push arbitrary `session/update` notifications to the client. */
private class NotifyingTransport : BaseTransport() {
    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    override fun send(message: JsonRpcMessage) = Unit

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
