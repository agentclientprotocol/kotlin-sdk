package com.agentclientprotocol

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.channels.Channels
import java.nio.channels.Pipe

class StdioSimpleAgentTest : SimpleAgentTest() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun testWithProtocols(block: suspend (clientProtocol: Protocol, agentProtocol: Protocol) -> Unit): TestResult = runBlocking {
        coroutineScope {
            val clientToAgent = Pipe.open()
            val agentToClient = Pipe.open()

            val clientTransport = StdioTransport(this, Dispatchers.IO,
                input = Channels.newInputStream(agentToClient.source()).asSource().buffered(),
                output = Channels.newOutputStream(clientToAgent.sink()).asSink().buffered(),
                "client"
            )

            val agentTransport = StdioTransport(this, Dispatchers.IO,
                input = Channels.newInputStream(clientToAgent.source()).asSource().buffered(),
                output = Channels.newOutputStream(agentToClient.sink()).asSink().buffered(),
                "agent"
            )

            val clientProtocol = Protocol(this, clientTransport)
            val agentProtocol = Protocol(this, agentTransport)

            clientProtocol.start()
            agentProtocol.start()
            block(clientProtocol, agentProtocol)
            agentProtocol.close()
            clientProtocol.close()
        }
    }
}