package com.agentclientprotocol.transport

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.servlet.ServletContext
import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.SendHandler
import javax.websocket.SendResult
import javax.websocket.Session
import javax.websocket.server.ServerContainer
import javax.websocket.server.ServerEndpointConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

public const val ACP_SERVLET_PATH: String = "/acp"

/**
 * Javax WebSocket connection adapter for servlet container WebSocket sessions.
 */
public class JavaxWebSocketConnection(
    public val session: Session,
) : AcpWebSocketConnection {
    private val inboundTextFrames = Channel<String>(Channel.UNLIMITED)
    private val textHandler = MessageHandler.Whole<String> { text ->
        inboundTextFrames.trySend(text)
    }

    override val incomingTextFrames: Flow<String> = inboundTextFrames.receiveAsFlow()

    init {
        session.addMessageHandler(String::class.java, textHandler)
    }

    override suspend fun sendText(text: String): Unit = suspendCoroutine { continuation ->
        session.asyncRemote.sendText(text, SendHandler { result: SendResult ->
            if (result.isOK) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(result.exception ?: IOException("WebSocket send failed"))
            }
        })
    }

    override fun close() {
        inboundTextFrames.close()
        runCatching { session.removeMessageHandler(textHandler) }
        if (session.isOpen) {
            session.close()
        }
    }
}

/**
 * Builds a Javax WebSocket endpoint config that serves ACP over WebSocket.
 */
public fun acpJavaxWebSocketServerEndpointConfig(
    path: String = ACP_SERVLET_PATH,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    block: suspend (Protocol) -> Unit,
): ServerEndpointConfig =
    ServerEndpointConfig.Builder
        .create(AcpJavaxWebSocketEndpoint::class.java, normalizeServletWebSocketPath(path))
        .configurator(object : ServerEndpointConfig.Configurator() {
            override fun <T : Any?> getEndpointInstance(endpointClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AcpJavaxWebSocketEndpoint(parentScope, protocolOptions, block) as T
            }
        })
        .build()

/**
 * Registers an ACP WebSocket endpoint on a Javax servlet container.
 */
public fun ServletContext.acpProtocolOnServerWebSocket(
    path: String = ACP_SERVLET_PATH,
    parentScope: CoroutineScope,
    protocolOptions: ProtocolOptions,
    block: suspend (Protocol) -> Unit,
) {
    val serverContainer = getAttribute(ServerContainer::class.java.name) as? ServerContainer
        ?: error("No javax.websocket.server.ServerContainer found in ServletContext")
    serverContainer.addEndpoint(acpJavaxWebSocketServerEndpointConfig(path, parentScope, protocolOptions, block))
}

private class AcpJavaxWebSocketEndpoint(
    private val parentScope: CoroutineScope,
    private val protocolOptions: ProtocolOptions,
    private val block: suspend (Protocol) -> Unit,
) : Endpoint() {
    private var protocol: Protocol? = null
    private var endpointScope: CoroutineScope? = null

    override fun onOpen(session: Session, config: EndpointConfig) {
        val scope = CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineName(AcpJavaxWebSocketEndpoint::class.simpleName!!)
        )
        val transport = RemoteWebSocketTransport(
            parentScope = scope,
            connection = JavaxWebSocketConnection(session),
            name = AcpJavaxWebSocketEndpoint::class.simpleName!!,
        )
        val connectionProtocol = Protocol(scope, transport, protocolOptions)

        endpointScope = scope
        protocol = connectionProtocol
        scope.launch {
            try {
                block(connectionProtocol)
                awaitCancellation()
            } finally {
                connectionProtocol.close()
            }
        }
    }

    override fun onClose(session: Session, closeReason: CloseReason) {
        closeProtocol()
    }

    override fun onError(session: Session?, thr: Throwable) {
        closeProtocol(thr)
    }

    private fun closeProtocol(cause: Throwable? = null) {
        protocol?.close()
        endpointScope?.cancel("ACP Javax WebSocket endpoint closed", cause)
        protocol = null
        endpointScope = null
    }
}

private fun normalizeServletWebSocketPath(path: String): String =
    if (path.startsWith("/")) path else "/$path"
