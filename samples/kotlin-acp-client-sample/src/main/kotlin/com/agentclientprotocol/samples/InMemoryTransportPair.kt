package com.agentclientprotocol.samples

import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class InMemoryTransportPair(val client: Transport, val agent: Transport)

/** Creates two STDIO transports connected to each other without an external process. */
fun CoroutineScope.createInMemoryTransportPair(): InMemoryTransportPair {
    val clientToAgent = Channel<String>(Channel.UNLIMITED)
    val agentToClient = Channel<String>(Channel.UNLIMITED)
    return InMemoryTransportPair(
        client = StdioTransport(
            parentScope = this,
            ioDispatcher = Dispatchers.IO,
            input = agentToClient.receiveAsFlow(),
            output = clientToAgent::send,
            name = "v2-sample-client",
        ),
        agent = StdioTransport(
            parentScope = this,
            ioDispatcher = Dispatchers.IO,
            input = clientToAgent.receiveAsFlow(),
            output = agentToClient::send,
            name = "v2-sample-agent",
        ),
    )
}
