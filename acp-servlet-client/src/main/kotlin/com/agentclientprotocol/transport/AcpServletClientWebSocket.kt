package com.agentclientprotocol.transport

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.websocket.ClientEndpointConfig
import javax.websocket.CloseReason
import javax.websocket.ContainerProvider
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.SendHandler
import javax.websocket.SendResult
import javax.websocket.Session
import javax.websocket.WebSocketContainer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class JavaxWebSocketClientConnectionOptions(
    public val sendTimeoutMillis: Long = 30_000,
) {
    init {
        require(sendTimeoutMillis >= 0) { "sendTimeoutMillis must be non-negative" }
    }
}

/**
 * Creates an ACP [Protocol] over a Javax WebSocket client connection.
 *
 * The protocol should be started manually by the calling site.
 */
public fun WebSocketContainer.acpProtocolOnClientWebSocket(
    uri: URI,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    clientEndpointConfig: ClientEndpointConfig = ClientEndpointConfig.Builder.create().build(),
    connectionOptions: JavaxWebSocketClientConnectionOptions = JavaxWebSocketClientConnectionOptions(),
): Protocol {
    val endpoint = AcpJavaxWebSocketClientEndpoint(parentScope, protocolOptions, connectionOptions)
    connectToServer(endpoint, clientEndpointConfig, uri)
    return endpoint.protocol()
}

/**
 * Creates an ACP [Protocol] over a Javax WebSocket client connection.
 *
 * The protocol should be started manually by the calling site.
 */
public fun WebSocketContainer.acpProtocolOnClientWebSocket(
    url: String,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    clientEndpointConfig: ClientEndpointConfig = ClientEndpointConfig.Builder.create().build(),
    connectionOptions: JavaxWebSocketClientConnectionOptions = JavaxWebSocketClientConnectionOptions(),
): Protocol =
    acpProtocolOnClientWebSocket(URI.create(url), parentScope, protocolOptions, clientEndpointConfig, connectionOptions)

/**
 * Creates an ACP [Protocol] with the default Javax [WebSocketContainer].
 *
 * The protocol should be started manually by the calling site.
 */
public fun acpProtocolOnClientWebSocket(
    uri: URI,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    clientEndpointConfig: ClientEndpointConfig = ClientEndpointConfig.Builder.create().build(),
    connectionOptions: JavaxWebSocketClientConnectionOptions = JavaxWebSocketClientConnectionOptions(),
): Protocol =
    ContainerProvider.getWebSocketContainer()
        .acpProtocolOnClientWebSocket(uri, parentScope, protocolOptions, clientEndpointConfig, connectionOptions)

/**
 * Creates an ACP [Protocol] with the default Javax [WebSocketContainer].
 *
 * The protocol should be started manually by the calling site.
 */
public fun acpProtocolOnClientWebSocket(
    url: String,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    clientEndpointConfig: ClientEndpointConfig = ClientEndpointConfig.Builder.create().build(),
    connectionOptions: JavaxWebSocketClientConnectionOptions = JavaxWebSocketClientConnectionOptions(),
): Protocol =
    acpProtocolOnClientWebSocket(URI.create(url), parentScope, protocolOptions, clientEndpointConfig, connectionOptions)

private class AcpJavaxWebSocketClientEndpoint(
    private val parentScope: CoroutineScope,
    private val protocolOptions: ProtocolOptions,
    private val connectionOptions: JavaxWebSocketClientConnectionOptions,
) : Endpoint() {
    private var protocol: Protocol? = null
    private var endpointScope: CoroutineScope? = null

    fun protocol(): Protocol =
        protocol ?: error("Javax WebSocket client endpoint did not open")

    override fun onOpen(session: Session, config: EndpointConfig) {
        val scope = CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineName(AcpJavaxWebSocketClientEndpoint::class.simpleName!!)
        )
        val transport = RemoteWebSocketTransport(
            parentScope = scope,
            connection = JavaxWebSocketClientConnection(session, connectionOptions),
            name = AcpJavaxWebSocketClientEndpoint::class.simpleName!!,
        )
        transport.onClose {
            scope.cancel("ACP Javax WebSocket client transport closed")
        }
        endpointScope = scope
        protocol = Protocol(scope, transport, protocolOptions)
    }

    override fun onClose(session: Session, closeReason: CloseReason) {
        closeProtocol()
    }

    override fun onError(session: Session?, thr: Throwable) {
        closeProtocol(thr)
    }

    private fun closeProtocol(cause: Throwable? = null) {
        protocol?.close()
        endpointScope?.cancel("ACP Javax WebSocket client endpoint closed", cause)
        protocol = null
        endpointScope = null
    }
}

private class JavaxWebSocketClientConnection(
    private val session: Session,
    private val options: JavaxWebSocketClientConnectionOptions,
) : AcpWebSocketConnection {
    private val inboundTextFrames = Channel<String>(Channel.UNLIMITED)
    private val textHandler = MessageHandler.Whole<String> { text ->
        inboundTextFrames.trySend(text)
    }

    override val incomingTextFrames: Flow<String> = inboundTextFrames.receiveAsFlow()

    init {
        session.asyncRemote.sendTimeout = options.sendTimeoutMillis
        session.addMessageHandler(String::class.java, textHandler)
    }

    override suspend fun sendText(text: String): Unit = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                close()
            }
        }

        try {
            session.asyncRemote.sendText(text, SendHandler { result: SendResult ->
                if (!completed.compareAndSet(false, true)) {
                    return@SendHandler
                }
                if (result.isOK) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(result.exception ?: IOException("WebSocket send failed"))
                }
            })
        } catch (e: Throwable) {
            if (completed.compareAndSet(false, true)) {
                continuation.resumeWithException(e)
            }
        }
    }

    override fun close() {
        inboundTextFrames.close()
        runCatching { session.removeMessageHandler(textHandler) }
        if (runCatching { session.isOpen }.getOrDefault(false)) {
            runCatching { session.close() }
        }
    }
}
