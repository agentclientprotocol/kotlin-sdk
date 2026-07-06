package com.agentclientprotocol.framework

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpJavaxWebSocketServerEndpointConfig
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import java.lang.reflect.Proxy
import java.net.URI
import javax.websocket.ClientEndpointConfig
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.RemoteEndpoint
import javax.websocket.SendHandler
import javax.websocket.SendResult
import javax.websocket.Session
import javax.websocket.WebSocketContainer

class ServletWebSocketProtocolDriver : ProtocolDriver {
    override fun testWithProtocols(block: suspend CoroutineScope.(clientProtocol: Protocol, agentProtocol: Protocol) -> Unit): TestResult =
        runBlocking {
            coroutineScope {
                val serverProtocolDeferred = CompletableDeferred<Protocol>()
                val protocolScope = CoroutineScope(coroutineContext + SupervisorJob())
                val serverEndpointConfig = acpJavaxWebSocketServerEndpointConfig(
                    path = "/acp",
                    parentScope = protocolScope,
                    protocolOptions = ProtocolOptions(protocolDebugName = "servlet agent protocol"),
                ) { protocol ->
                    serverProtocolDeferred.complete(protocol)
                    awaitCancellation()
                }
                val serverEndpoint = serverEndpointConfig.configurator
                    .getEndpointInstance(serverEndpointConfig.endpointClass) as Endpoint

                val clientSession = LinkedJavaxWebSocketSession()
                val serverSession = LinkedJavaxWebSocketSession()
                clientSession.peer = serverSession
                serverSession.peer = clientSession

                val container = fakeWebSocketContainer(
                    clientSession = clientSession,
                    serverSession = serverSession,
                    serverEndpoint = serverEndpoint,
                    serverEndpointConfig = serverEndpointConfig,
                )

                var clientProtocol: Protocol? = null
                var agentProtocol: Protocol? = null
                try {
                    clientProtocol = container.acpProtocolOnClientWebSocket(
                        uri = URI.create("ws://localhost/acp"),
                        parentScope = protocolScope,
                        protocolOptions = ProtocolOptions(protocolDebugName = "servlet client protocol"),
                    )
                    agentProtocol = serverProtocolDeferred.await()
                    agentProtocol.start()
                    clientProtocol.start()
                    block(clientProtocol, agentProtocol)
                } finally {
                    agentProtocol?.close()
                    clientProtocol?.close()
                    protocolScope.cancel()
                }
            }
        }
}

private class LinkedJavaxWebSocketSession {
    var peer: LinkedJavaxWebSocketSession? = null
    var closed: Boolean = false
        private set
    private var textHandler: MessageHandler.Whole<String>? = null
    private var sendTimeout: Long = -1

    val asyncRemote: RemoteEndpoint.Async = Proxy.newProxyInstance(
        RemoteEndpoint.Async::class.java.classLoader,
        arrayOf(RemoteEndpoint.Async::class.java),
    ) { _, method, args ->
        when (method.name) {
            "sendText" -> {
                val arguments = requireNotNull(args)
                peer?.receiveText(arguments[0] as String)
                (arguments[1] as? SendHandler)?.onResult(SendResult())
                null
            }
            "getSendTimeout" -> sendTimeout
            "setSendTimeout" -> {
                val arguments = requireNotNull(args)
                sendTimeout = arguments[0] as Long
                null
            }
            "toString" -> "LinkedRemoteEndpointAsync"
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
            "toString" -> "LinkedJavaxWebSocketSession"
            "hashCode" -> System.identityHashCode(this)
            else -> null
        }
    } as Session

    fun receiveText(text: String) {
        textHandler?.onMessage(text)
    }
}

private fun fakeWebSocketContainer(
    clientSession: LinkedJavaxWebSocketSession,
    serverSession: LinkedJavaxWebSocketSession,
    serverEndpoint: Endpoint,
    serverEndpointConfig: EndpointConfig,
): WebSocketContainer =
    Proxy.newProxyInstance(
        WebSocketContainer::class.java.classLoader,
        arrayOf(WebSocketContainer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "connectToServer" -> {
                val arguments = requireNotNull(args)
                val clientEndpoint = arguments[0] as Endpoint
                val clientEndpointConfig = arguments[1] as ClientEndpointConfig
                serverEndpoint.onOpen(serverSession.session, serverEndpointConfig)
                clientEndpoint.onOpen(clientSession.session, clientEndpointConfig)
                clientSession.session
            }
            "toString" -> "FakeWebSocketContainer"
            "hashCode" -> System.identityHashCode(clientSession)
            else -> null
        }
    } as WebSocketContainer
