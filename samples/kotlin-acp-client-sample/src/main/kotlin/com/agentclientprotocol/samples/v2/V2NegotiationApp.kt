@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.v2

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.samples.support.createInMemoryTransportPair
import kotlinx.coroutines.coroutineScope

/** Runs the same v2 conversation with the client selected by `ClientNegotiator`. */
suspend fun main() = coroutineScope {
    val transports = createInMemoryTransportPair()
    val agentProtocol = Protocol(this, transports.agent)
    Agent(agentProtocol, SimpleV2AgentSupport())
    agentProtocol.start()

    try {
        runNegotiatedClient(transports.client)
    } finally {
        agentProtocol.close()
    }
}
