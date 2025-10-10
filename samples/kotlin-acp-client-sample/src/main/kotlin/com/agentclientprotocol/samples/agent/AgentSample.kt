package com.agentclientprotocol.samples.agent

import com.agentclientprotocol.agent.AgentInstance
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered

private val logger = KotlinLogging.logger {}

/**
 * Sample ACP agent using STDIO transport.
 * 
 * This demonstrates how to create and run an ACP agent that communicates
 * via standard input/output streams.
 * 
 * Usage:
 * ```
 * ./gradlew :samples:kotlin-acp-agent-sample:run
 * ```
 */
//suspend fun main() = coroutineScope {
//    // TODO: invalid sample. need to connect and emulate counterside via protocol
//    logger.info { "Starting ACP Agent Sample" }
//
//    try {
//        // Create STDIO transport
//        val transport = StdioTransport(
//            parentScope = this,
//            ioDispatcher = Dispatchers.IO,
//            input = System.`in`.asSource().buffered(),
//            output = System.out.asSink().buffered()
//        )
//
//        // Create agent
//        val agent = SimpleAgent()
//
//        // Create agent-side connection - this implements Client interface
//        val protocol = Protocol(this, transport)
//        val agentInstance = AgentInstance(protocol, agent)
//
//
//        // Connect and start processing
//        protocol.start()
//
//        logger.info { "Agent connected and ready" }
//
//        // Keep the agent running
//        // In a real implementation, you might want to handle shutdown signals
//        while (transport.state.value == Transport.State.STARTED) {
//            delay(1000)
//        }
//
//    } catch (e: Exception) {
//        logger.error(e) { "Agent error" }
//    } finally {
//        logger.info { "Agent shutting down" }
//    }
//}