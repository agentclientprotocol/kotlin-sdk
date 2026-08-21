@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.coroutineScope

/** Runs a v2 client and v2 agent directly against each other. */
suspend fun main() = coroutineScope {
    val transports = createInMemoryTransportPair()
    val agentProtocol = Protocol(this, transports.agent)
    Agent(agentProtocol, SimpleV2AgentSupport())
    agentProtocol.start()

    try {
        runDirectV2Client(transports.client)
    } finally {
        agentProtocol.close()
    }
}
