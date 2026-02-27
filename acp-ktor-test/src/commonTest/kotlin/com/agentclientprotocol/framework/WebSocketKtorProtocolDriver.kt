package com.agentclientprotocol.framework

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import com.agentclientprotocol.transport.acpProtocolOnServerWebSocket
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope

class WebSocketKtorProtocolDriver : ProtocolDriver {
    override fun testWithProtocols(block: suspend CoroutineScope.(clientProtocol: Protocol, agentProtocol: Protocol) -> Unit) =
        testApplication {
            val agentProtocolDeferred = CompletableDeferred<Protocol>()

            install(WebSockets)
            routing {
                acpProtocolOnServerWebSocket("acp", ProtocolOptions(protocolDebugName = "agent protocol")) { protocol ->
                    agentProtocolDeferred.complete(protocol)
                    awaitCancellation()
                }
            }

            val httpClient = createClient {
                install(io.ktor.client.plugins.websocket.WebSockets.Plugin)
            }
            val clientProtocol =
                httpClient.acpProtocolOnClientWebSocket("acp", ProtocolOptions(protocolDebugName = "client protocol"))
            val agentProtocol = agentProtocolDeferred.await()
            agentProtocol.start()
            clientProtocol.start()
            coroutineScope {
                block(clientProtocol, agentProtocol)
            }
            agentProtocol.close()
            clientProtocol.close()
        }
}