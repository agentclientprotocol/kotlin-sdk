package com.agentclientprotocol

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentInstance
import com.agentclientprotocol.agent.AgentSessionBase
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientInstance
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.acpProtocolOnServerWebSocket
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test

class WebSocketTest {

    @Test
    fun `agent initialize is called`() = testWithProtocols { clientProtocol, agentProtocol ->
    }

    fun testWithProtocols(block: suspend (clientProtocol: Protocol, agentProtocol: Protocol) -> Unit) = testApplication {
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