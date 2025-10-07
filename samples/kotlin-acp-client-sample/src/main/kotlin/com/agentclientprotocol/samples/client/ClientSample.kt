package com.agentclientprotocol.samples.client

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.client.ClientInstance
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Sample ACP client using STDIO transport.
 * 
 * This demonstrates how to create and run an ACP client that communicates
 * with an agent via standard input/output streams.
 * 
 * Usage:
 * ```
 * ./gradlew :samples:kotlin-acp-client-sample:run
 * ```
 */
fun main() = runBlocking {
    // TODO: invalid sample. need to connect and emulate counterside via protocol
    logger.info { "Starting ACP Client Sample" }
    
    try {
        // Create the client implementation
        val client = SimpleClient(File("."))
        
        // Create STDIO transport (for subprocess communication)
        // In a real scenario, this would connect to an agent process
        val transport = StdioTransport(
            parentScope = this,
            ioDispatcher = Dispatchers.IO,
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered()
        )

        val protocol = Protocol(this, transport)
        // Create client-side connection
        val clientInstance = ClientInstance(protocol, client)

        // Connect to agent
        protocol.start()

        // TODO rewrite, emulate other side
//        // Initialize the agent
//        val initResponse = cl.initialize(
//            InitializeRequest(
//                protocolVersion = LATEST_PROTOCOL_VERSION,
//                clientCapabilities = ClientCapabilities(
//                    fs = FileSystemCapability(
//                        readTextFile = true,
//                        writeTextFile = true
//                    )
//                )
//            )
//        )
//
//        println("Connected to agent:")
//        println("  Protocol version: ${initResponse.protocolVersion}")
//        println("  Agent capabilities: ${initResponse.agentCapabilities}")
//        println("  Auth methods: ${initResponse.authMethods}")
//
//
//        // Create a session
//        val sessionResponse = clientInstance.sessionNew(
//            NewSessionRequest(
//                cwd = System.getProperty("user.dir"),
//                mcpServers = emptyList()
//            )
//        )
//
//        println("Created session: ${sessionResponse.sessionId}")
//
//        // Send a test sessionPrompt
//        val promptResponse = clientInstance.sessionPrompt(
//            PromptRequest(
//                sessionId = sessionResponse.sessionId,
//                prompt = listOf(
//                    ContentBlock.Text("Hello, I'm testing the ACP Kotlin SDK!")
//                )
//            )
//        )
        
        // Keep client running for a bit to receive any final updates
        kotlinx.coroutines.delay(2000)
        
    } catch (e: Exception) {
        logger.error(e) { "Client error" }
    } finally {
        logger.info { "Client shutting down" }
    }
}