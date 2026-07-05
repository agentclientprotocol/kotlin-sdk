package com.agentclientprotocol.transport

import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import javax.servlet.ServletContext
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

class AcpServletServerWebSocketTest {
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
    fun `connection close closes javax session`(): Unit = runTest {
        val fakeSession = FakeJavaxWebSocketSession()
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

    private class FakeJavaxWebSocketSession {
        val sentText = Channel<String>(Channel.UNLIMITED)
        var closed: Boolean = false
            private set
        private var textHandler: MessageHandler.Whole<String>? = null

        val asyncRemote: RemoteEndpoint.Async = Proxy.newProxyInstance(
            RemoteEndpoint.Async::class.java.classLoader,
            arrayOf(RemoteEndpoint.Async::class.java),
        ) { _, method, args ->
            when (method.name) {
                "sendText" -> {
                    sentText.trySend(args?.get(0) as String)
                    (args?.get(1) as? SendHandler)?.onResult(SendResult())
                    null
                }
                "getSendTimeout" -> 0L
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
}
