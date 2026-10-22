package com.agentclientprotocol.tck.v1

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Runs the stable (protocol v1) TCK agent over stdin/stdout.
 *
 * acp-tck launches a fresh process per test and ends it by closing stdin.
 */
fun main() {
    runBlocking {
        // System.out is a PrintStream that swallows IOException, so a dead stdout would never close the transport.
        val stdout = FileOutputStream(FileDescriptor.out).bufferedWriter(Charsets.UTF_8)
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
            name = "acp-tck-agent-v1",
        )
        val closed = CompletableDeferred<Unit>()
        transport.onClose { closed.complete(Unit) }

        val protocol = Protocol(this, transport)
        Agent(protocol, TckAgentSupport())
        protocol.start()

        closed.await()
        protocol.close()

        /*
         * A stdin read blocked on another path cannot be interrupted, and runBlocking would wait for it,
         * so exit from inside the block as soon as the transport is closed.
         */
        exitProcess(0)
    }
}
