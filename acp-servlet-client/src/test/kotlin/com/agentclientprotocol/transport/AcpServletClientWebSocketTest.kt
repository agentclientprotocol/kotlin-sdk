package com.agentclientprotocol.transport

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.Proxy
import java.net.URI
import javax.websocket.ClientEndpointConfig
import javax.websocket.Endpoint
import javax.websocket.MessageHandler
import javax.websocket.RemoteEndpoint
import javax.websocket.SendHandler
import javax.websocket.SendResult
import javax.websocket.Session
import javax.websocket.WebSocketContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AcpServletClientWebSocketTest {
    private companion object {
        object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>(
            "test/testRequest",
            TestRequest.serializer(),
            TestResponse.serializer(),
        )
    }

    @Test
    fun `container helper creates protocol that carries request response and close lifecycle`(): Unit = runTest {
        val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientSession = FakeJavaxWebSocketSession()
        val serverConnection = LinkedServerWebSocketConnection()
        val container = FakeWebSocketContainer(clientSession)
        clientSession.onSend = { text -> serverConnection.receiveText(text) }
        serverConnection.onSend = { text -> clientSession.receiveText(text) }

        var clientProtocol: Protocol? = null
        var serverProtocol: Protocol? = null
        try {
            clientProtocol = container.container.acpProtocolOnClientWebSocket(
                uri = URI.create("ws://localhost/acp"),
                parentScope = protocolScope,
                protocolOptions = ProtocolOptions(protocolDebugName = "servlet client protocol"),
            )
            serverProtocol = Protocol(
                parentScope = protocolScope,
                transport = RemoteWebSocketTransport(protocolScope, serverConnection, "servlet server transport"),
                options = ProtocolOptions(protocolDebugName = "servlet server protocol"),
            )
            serverProtocol.setRequestHandler(TestMethod) { request ->
                TestResponse("server:${request.message}")
            }
            serverProtocol.start()
            clientProtocol.start()

            val response = withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    clientProtocol.sendRequest(TestMethod, TestRequest("ping"))
                }
            }

            assertEquals("server:ping", response.message)
            assertEquals(30_000, clientSession.sendTimeout)

            clientProtocol.close()

            assertTrue(clientSession.closed)
        } finally {
            clientProtocol?.close()
            serverProtocol?.close()
            protocolScope.cancel()
        }
    }

    @Test
    fun `container helper applies configured send timeout`(): Unit = runTest {
        val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientSession = FakeJavaxWebSocketSession()
        val container = FakeWebSocketContainer(clientSession)
        val protocol = container.container.acpProtocolOnClientWebSocket(
            uri = URI.create("ws://localhost/acp"),
            parentScope = protocolScope,
            protocolOptions = ProtocolOptions(protocolDebugName = "servlet client protocol"),
            connectionOptions = JavaxWebSocketClientConnectionOptions(sendTimeoutMillis = 1234),
        )

        try {
            assertEquals(1234, clientSession.sendTimeout)
        } finally {
            protocol.close()
            protocolScope.cancel()
        }
    }

    private class FakeWebSocketContainer(
        private val clientSession: FakeJavaxWebSocketSession,
    ) {
        var connectedUri: URI? = null
            private set

        val container: WebSocketContainer = Proxy.newProxyInstance(
            WebSocketContainer::class.java.classLoader,
            arrayOf(WebSocketContainer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "connectToServer" -> {
                    val arguments = requireNotNull(args)
                    val endpoint = arguments[0] as Endpoint
                    @Suppress("UNUSED_VARIABLE")
                    val config = arguments[1] as ClientEndpointConfig
                    connectedUri = arguments[2] as URI
                    endpoint.onOpen(clientSession.session, emptyClientEndpointConfig())
                    clientSession.session
                }
                "toString" -> "FakeWebSocketContainer"
                "hashCode" -> System.identityHashCode(this)
                else -> null
            }
        } as WebSocketContainer
    }

    private class FakeJavaxWebSocketSession {
        var onSend: ((String) -> Unit)? = null
        var closed: Boolean = false
            private set
        private var textHandler: MessageHandler.Whole<String>? = null
        var sendTimeout: Long = -1
            private set

        val asyncRemote: RemoteEndpoint.Async = Proxy.newProxyInstance(
            RemoteEndpoint.Async::class.java.classLoader,
            arrayOf(RemoteEndpoint.Async::class.java),
        ) { _, method, args ->
            when (method.name) {
                "sendText" -> {
                    val arguments = requireNotNull(args)
                    val text = arguments[0] as String
                    onSend?.invoke(text)
                    (arguments[1] as? SendHandler)?.onResult(SendResult())
                    null
                }
                "getSendTimeout" -> sendTimeout
                "setSendTimeout" -> {
                    val arguments = requireNotNull(args)
                    sendTimeout = arguments[0] as Long
                    null
                }
                "toString" -> "FakeRemoteEndpointAsync"
                "hashCode" -> System.identityHashCode(this)
                else -> null
            }
        } as RemoteEndpoint.Async

        val session: Session = Proxy.newProxyInstance(
            Session::class.java.classLoader,
            arrayOf(Session::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addMessageHandler" -> {
                    val arguments = requireNotNull(args)
                    @Suppress("UNCHECKED_CAST")
                    textHandler = arguments[1] as MessageHandler.Whole<String>
                    null
                }
                "removeMessageHandler" -> {
                    textHandler = null
                    null
                }
                "getAsyncRemote" -> asyncRemote
                "isOpen" -> !closed
                "close" -> {
                    closed = true
                    null
                }
                "toString" -> "FakeSession"
                "hashCode" -> System.identityHashCode(this)
                else -> null
            }
        } as Session

        fun receiveText(text: String) {
            textHandler?.onMessage(text)
        }
    }

    private class LinkedServerWebSocketConnection : AcpWebSocketConnection {
        private val inboundTextFrames = Channel<String>(Channel.UNLIMITED)
        var onSend: ((String) -> Unit)? = null

        override val incomingTextFrames: Flow<String> = inboundTextFrames.receiveAsFlow()

        override suspend fun sendText(text: String) {
            onSend?.invoke(text)
        }

        override fun close() {
            inboundTextFrames.close()
        }

        fun receiveText(text: String) {
            inboundTextFrames.trySend(text)
        }
    }

}

@Serializable
private data class TestRequest(val message: String) : AcpRequest {
    override val _meta: JsonElement? = null
}

@Serializable
private data class TestResponse(val message: String) : AcpResponse {
    override val _meta: JsonElement? = null
}

private fun emptyClientEndpointConfig(): javax.websocket.EndpointConfig =
    Proxy.newProxyInstance(
        javax.websocket.EndpointConfig::class.java.classLoader,
        arrayOf(javax.websocket.EndpointConfig::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getUserProperties" -> mutableMapOf<String, Any>()
            "toString" -> "EmptyEndpointConfig"
            "hashCode" -> 0
            else -> null
        }
    } as javax.websocket.EndpointConfig
