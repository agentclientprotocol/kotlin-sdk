package com.agentclientprotocol.transport

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import kotlinx.coroutines.awaitCancellation

/**
 * Binds ACP [Protocol] to a route on [path] via websocket connection.
 *
 * When [block] exits the websocket connection is closed.
 */
@KtorDsl
public fun Route.acpProtocolOnServerWebSocket(path: String = ACP_PATH, protocolOptions: ProtocolOptions, block: suspend (Protocol) -> Unit) {
    webSocket(path) {
        val webSocketTransport = WebSocketTransport(parentScope = this, wss = this)
        val protocol = Protocol(parentScope = this, transport = webSocketTransport, options = protocolOptions)
        block(protocol)
        awaitCancellation()
    }
}

/**
 * Binds ACP [Protocol] to a route on [path] via websocket connection.
 *
 * When [block] exits the websocket connection is closed.
 */
@KtorDsl
public fun Application.acpProtocolOnServerWebSocket(path: String = ACP_PATH, protocolOptions: ProtocolOptions, withAuth: (Route.(Route.() -> Unit) -> Unit)?, block: suspend (Protocol) -> Unit) {
    routing {
        if (withAuth != null) {
            withAuth {
                acpProtocolOnServerWebSocket(path, protocolOptions) { protocol ->
                    block(protocol)
                }
            }
        } else {
            log.warn("No authentication provided for ACP WebSocket server")
            acpProtocolOnServerWebSocket(path, protocolOptions) { protocol ->
                block(protocol)
            }
        }
    }
}