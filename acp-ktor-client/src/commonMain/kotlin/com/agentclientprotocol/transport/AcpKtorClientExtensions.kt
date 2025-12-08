package com.agentclientprotocol.transport

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*

/**
 * Create a new [Protocol] on a websocket via [HttpClient].
 *
 * The protocol should be started manually by the calling site.
 */
public suspend fun HttpClient.acpProtocolOnClientWebSocket(
    url: String = ACP_PATH,
    protocolOptions: ProtocolOptions,
    requestBuilder: HttpRequestBuilder.() -> Unit = {}
): Protocol {
    val webSocketSession = webSocketSession(urlString = url, block = requestBuilder)
    val webSocketTransport = WebSocketTransport(parentScope = webSocketSession, wss = webSocketSession)
    val protocol = Protocol(parentScope = webSocketSession, transport = webSocketTransport, options = protocolOptions)
    return protocol
}