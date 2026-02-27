package com.agentclientprotocol.samples

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}


/**
 * Interactive console chat app that communicates with a Gemini agent via ACP.
 * 
 * This demonstrates how to:
 * 1. Start an external agent process (`gemini --experimental-acp`)
 * 2. Create a client transport to communicate with the process
 * 3. Support interactive console-based conversation with the agent
 * 4. Handle real-time session updates and agent responses
 * 
 * Usage:
 * ```
 * ./gradlew :samples:kotlin-acp-client-sample:run -PmainClass=com.agentclientprotocol.samples.client.GeminiClientAppKt
 * ```
 */
suspend fun main() = coroutineScope {
    logger.info { "Starting Gemini ACP Client App" }
    // Create process transport to start Gemini agent
    val transport = createProcessStdioTransport(this, "gemini", "--experimental-acp")
    runTerminalClient(transport)
}