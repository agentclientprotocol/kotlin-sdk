package com.agentclientprotocol.transport

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.lang.reflect.Proxy
import javax.servlet.ServletContext
import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.RemoteEndpoint
import javax.websocket.SendHandler
import javax.websocket.SendResult
import javax.websocket.Session
import javax.websocket.server.ServerContainer
import javax.websocket.server.ServerEndpointConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AcpServletServerWebSocketTest {
    private companion object {
        object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>(
            "test/testRequest",
            TestRequest.serializer(),
            TestResponse.serializer(),
        )
    }

    @Test
    fun `connection adapts incoming javax text messages`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession()
        val connection = JavaxWebSocketConnection(fakeSession.session)

        fakeSession.receiveText("hello")

        assertEquals("hello", connection.incomingTextFrames.first())
    }

    @Test
    fun `connection sends text through async remote endpoint`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession()
        val connection = JavaxWebSocketConnection(fakeSession.session)

        connection.sendText("outbound")

        assertEquals("outbound", fakeSession.sentText.receive())
    }

    @Test
    fun `connection applies configured javax send timeout`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession()

        JavaxWebSocketConnection(fakeSession.session, JavaxWebSocketConnectionOptions(sendTimeoutMillis = 1234))

        assertEquals(1234, fakeSession.sendTimeout)
    }

    @Test
    fun `connection cancellation closes stuck javax send`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession(completeSends = false)
        val connection = JavaxWebSocketConnection(fakeSession.session)

        val sendJob = launch {
            connection.sendText("stuck")
        }
        assertEquals("stuck", fakeSession.sentText.receive())

        sendJob.cancelAndJoin()

        assertTrue(fakeSession.closed)
    }

    @Test
    fun `connection close closes javax session`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession()
        val connection = JavaxWebSocketConnection(fakeSession.session)

        connection.close()

        assertTrue(fakeSession.closed)
    }

    @Test
    fun `connection close suppresses expected javax close exception`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession(closeException = IOException("already closed"))
        val connection = JavaxWebSocketConnection(fakeSession.session)

        connection.close()

        assertTrue(fakeSession.closed)
    }

    @Test
    fun `endpoint config normalizes websocket path`(): Unit = runTest {
        val config = acpJavaxWebSocketServerEndpointConfig(
            path = "acp",
            parentScope = backgroundScope,
            protocolOptions = ProtocolOptions(),
            block = {},
        )

        assertEquals("/acp", config.path)
    }

    @Test
    fun `servlet context registration adds endpoint to server container`(): Unit = runTest {
        val fakeContainer = FakeServerContainer()
        val servletContext = fakeServletContext(fakeContainer.serverContainer)

        servletContext.acpProtocolOnServerWebSocket(
            path = "/acp",
            parentScope = backgroundScope,
            protocolOptions = ProtocolOptions(),
            block = {},
        )

        assertNotNull(fakeContainer.endpointConfig)
        assertEquals("/acp", fakeContainer.endpointConfig?.path)
    }

    @Test
    fun `endpoint config creates endpoint that carries protocol request response and close lifecycle`(): Unit = runTest {
        val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serverSession = FakeJavaxWebSocketSession()
        val clientConnection = LinkedClientWebSocketConnection(serverSession)
        serverSession.onServerSend = { text -> clientConnection.receiveText(text) }
        val serverProtocol = CompletableDeferred<Protocol>()
        val serverBlockClosed = CompletableDeferred<Unit>()
        val config = acpJavaxWebSocketServerEndpointConfig(
            path = "/acp",
            parentScope = protocolScope,
            protocolOptions = ProtocolOptions(protocolDebugName = "servlet agent protocol"),
        ) { protocol ->
            protocol.setRequestHandler(TestMethod) { request ->
                TestResponse("server:${request.message}")
            }
            protocol.start()
            serverProtocol.complete(protocol)
            try {
                awaitCancellation()
            } finally {
                serverBlockClosed.complete(Unit)
            }
        }
        val endpoint = config.configurator.getEndpointInstance(config.endpointClass) as Endpoint
        endpoint.onOpen(serverSession.session, emptyEndpointConfig())

        var clientProtocol: Protocol? = null
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { serverProtocol.await() }
            }
            clientProtocol = Protocol(
                parentScope = protocolScope,
                transport = RemoteWebSocketTransport(protocolScope, clientConnection, "servlet client transport"),
                options = ProtocolOptions(protocolDebugName = "servlet client protocol"),
            )
            clientProtocol.start()

            val response = withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    clientProtocol.sendRequest(TestMethod, TestRequest("ping"))
                }
            }
            assertEquals("server:ping", response.message)

            endpoint.onClose(serverSession.session, CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "done"))

            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { serverBlockClosed.await() }
            }
            assertTrue(serverSession.closed)
        } finally {
            clientProtocol?.close()
            protocolScope.cancel()
        }
    }

    private class FakeJavaxWebSocketSession(
        private val completeSends: Boolean = true,
        private val closeException: Throwable? = null,
    ) {
        val sentText = Channel<String>(Channel.UNLIMITED)
        var onServerSend: ((String) -> Unit)? = null
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
                    val text = args?.get(0) as String
                    sentText.trySend(text)
                    onServerSend?.invoke(text)
                    if (completeSends) {
                        (args?.get(1) as? SendHandler)?.onResult(SendResult())
                    }
                    null
                }
                "getSendTimeout" -> sendTimeout
                "setSendTimeout" -> {
                    sendTimeout = args?.get(0) as Long
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
                    @Suppress("UNCHECKED_CAST")
                    textHandler = args?.get(1) as MessageHandler.Whole<String>
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
                    if (closeException != null) {
                        throw closeException
                    }
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

    private class LinkedClientWebSocketConnection(
        private val serverSession: FakeJavaxWebSocketSession,
    ) : AcpWebSocketConnection {
        private val inboundTextFrames = Channel<String>(Channel.UNLIMITED)

        override val incomingTextFrames = inboundTextFrames.receiveAsFlow()

        override suspend fun sendText(text: String) {
            serverSession.receiveText(text)
        }

        override fun close() {
            inboundTextFrames.close()
        }

        fun receiveText(text: String) {
            inboundTextFrames.trySend(text)
        }
    }

    private class FakeServerContainer {
        var endpointConfig: ServerEndpointConfig? = null

        val serverContainer: ServerContainer = Proxy.newProxyInstance(
            ServerContainer::class.java.classLoader,
            arrayOf(ServerContainer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addEndpoint" -> {
                    endpointConfig = args?.get(0) as ServerEndpointConfig
                    null
                }
                "toString" -> "FakeServerContainer"
                "hashCode" -> System.identityHashCode(this)
                else -> null
            }
        } as ServerContainer
    }

    private fun fakeServletContext(serverContainer: ServerContainer): ServletContext =
        Proxy.newProxyInstance(
            ServletContext::class.java.classLoader,
            arrayOf(ServletContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAttribute" -> if (args?.get(0) == ServerContainer::class.java.name) serverContainer else null
                "toString" -> "FakeServletContext"
                "hashCode" -> System.identityHashCode(serverContainer)
                else -> null
            }
        } as ServletContext

    private fun emptyEndpointConfig(): EndpointConfig =
        Proxy.newProxyInstance(
            EndpointConfig::class.java.classLoader,
            arrayOf(EndpointConfig::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUserProperties" -> emptyMap<String, Any>()
                "getEncoders" -> emptyList<Class<*>>()
                "getDecoders" -> emptyList<Class<*>>()
                "toString" -> "EmptyEndpointConfig"
                else -> null
            }
        } as EndpointConfig
}

@Serializable
private data class TestRequest(
    val message: String,
    override val _meta: JsonElement? = null,
) : AcpRequest

@Serializable
private data class TestResponse(
    val message: String,
    override val _meta: JsonElement? = null,
) : AcpResponse
