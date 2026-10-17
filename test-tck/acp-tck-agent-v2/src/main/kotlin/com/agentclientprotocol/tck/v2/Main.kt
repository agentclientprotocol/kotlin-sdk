@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.tck.v2

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Runs the protocol v2 TCK agent over stdin/stdout.
 *
 * acp-tck launches a fresh process per test and ends it by closing stdin.
 */
fun main() {
    runBlocking {
        val stdout = System.out.bufferedWriter(Charsets.UTF_8)
        // Anything printed by mistake must not corrupt the protocol stream.
        System.setOut(System.err)

        val transport = StdioTransport(
            parentScope = this,
            ioDispatcher = Dispatchers.IO,
            input = System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().asFlow(),
            output = { line ->
                stdout.write(line)
                stdout.newLine()
                stdout.flush()
            },
            name = "acp-tck-agent-v2",
        )
        val closed = CompletableDeferred<Unit>()
        transport.onClose { closed.complete(Unit) }

        val protocol = Protocol(this, transport)
        Agent(protocol, TckAgentSupport())
        protocol.start()

        closed.await()
        protocol.close()
    }
    /*
     * A stdin read blocked on another path cannot be interrupted, so exit explicitly once the
     * transport is closed instead of waiting for every coroutine to unwind.
     */
    exitProcess(0)
}
