package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.channels.Channels
import java.nio.channels.Pipe

/**
 * This app uses the terminal client and the simple agent implementations
 */
suspend fun main() = coroutineScope {
    val clientToAgent = Pipe.open()
    val agentToClient = Pipe.open()

    val clientTransport = StdioTransport(
        this, Dispatchers.IO,
        input = Channels.newInputStream(agentToClient.source()).asSource().buffered(),
        output = Channels.newOutputStream(clientToAgent.sink()).asSink().buffered(),
        "client"
    )

    val agentTransport = StdioTransport(
        this, Dispatchers.IO,
        input = Channels.newInputStream(clientToAgent.source()).asSource().buffered(),
        output = Channels.newOutputStream(agentToClient.sink()).asSink().buffered(),
        "agent"
    )

    val agentProtocol = Protocol(this, agentTransport)

    val agent = Agent(
        agentProtocol,
        SimpleAgentSupport(),
        remoteSideExtensions = listOf(FileSystemOperations, TerminalOperations)
    )
    agentProtocol.start()

    runTerminalClient(clientTransport)
}