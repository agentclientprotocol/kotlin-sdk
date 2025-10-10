package com.agentclientprotocol

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnServerWebSocket
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

class WebSocketSimpleAgentTest : SimpleAgentTest() {
    // TODO: inject implementation by rules of somehow else
    override fun testWithProtocols(block: suspend (clientProtocol: Protocol, agentProtocol: Protocol) -> Unit) = testApplication {
        val agentProtocolDeferred = CompletableDeferred<Protocol>()

        install(io.ktor.server.websocket.WebSockets)
        routing {
            acpProtocolOnServerWebSocket("acp", ProtocolOptions()) { protocol ->
                agentProtocolDeferred.complete(protocol)
                awaitCancellation()
            }
        }

        val httpClient = createClient {
            install(WebSockets)
        }
        val clientProtocol = httpClient.acpProtocolOnClientWebSocket("acp", ProtocolOptions())
        val agentProtocol = agentProtocolDeferred.await()
        agentProtocol.start()
        clientProtocol.start()
        block(clientProtocol, agentProtocol)
        agentProtocol.close()
        clientProtocol.close()
    }
}