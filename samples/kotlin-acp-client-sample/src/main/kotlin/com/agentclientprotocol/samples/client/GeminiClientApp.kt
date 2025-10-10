package com.agentclientprotocol.samples.client

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientInstance
import com.agentclientprotocol.client.ClientSessionBase
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

internal class TerminalClient(protocol: Protocol, clientInfo: ClientInfo) : ClientInstance(protocol, clientInfo) {
    override suspend fun createSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?,
    ): ClientSessionBase {
        return TerminalSession(sessionId, protocol, modeState, modelState)
    }

    override suspend fun loadSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?,
    ): ClientSessionBase {
        TODO("Not yet implemented")
    }
}

internal class TerminalSession(
    sessionId: SessionId,
    protocol: Protocol,
    modeState: SessionModeState?,
    modelState: SessionModelState?
) : ClientSessionBase(sessionId, protocol, modeState, modelState) {

    override suspend fun requestPermissions(
        toolCall: ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        println("Agent requested permissions for tool call: ${toolCall.title}. Choose one of the following options:")
        for ((i, permission) in permissions.withIndex()) {
            println("${i + 1}. ${permission.name}")
        }
        while (true) {
            val read = readln()
            val optionIndex = read.toIntOrNull()
            if (optionIndex != null && optionIndex in permissions.indices) {
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(permissions[optionIndex].optionId), _meta)
            }
            println("Invalid option selected. Try again.")
        }
    }

    override suspend fun update(
        params: SessionUpdate,
        _meta: JsonElement?,
    ) {
        when (params) {
            is SessionUpdate.AgentMessageChunk -> {
                println("Agent: ${params.content.render()}")
            }
            is SessionUpdate.AgentThoughtChunk -> {
                println("Agent thinks: ${params.content.render()}")
            }
            is SessionUpdate.AvailableCommandsUpdate -> {
                println("Available commands updated:")
            }
            is SessionUpdate.CurrentModeUpdate -> {
                println("Session mode changed to: ${params.currentModeId.value}")
            }
            is SessionUpdate.PlanUpdate -> {
                println("Agent plan: ")
                for (entry in params.entries) {
                    println("  [${entry.status}] ${entry.content} (${entry.priority})")
                }
            }
            is SessionUpdate.ToolCall -> {
                println("Tool call started: ${params.title} (${params.kind})")
            }
            is SessionUpdate.ToolCallUpdate -> {
                println("Tool call updated: ${params.title} (${params.kind})")
            }
            is SessionUpdate.UserMessageChunk -> {
                println("User: ${params.content.render()}")
            }
        }
    }
}

private fun ContentBlock.render(): String {
    return when (this) {
        is ContentBlock.Text -> text
        else -> {
            "Unsupported chunk: [${this::class.simpleName}]"
        }
    }
}

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
    
    try {
        
        // Create process transport to start Gemini agent
        val transport = createProcessStdioTransport(this, "gemini", "--experimental-acp")
        
        // Create client-side connection
        val protocol = Protocol(this, transport)
        val clientInstance = TerminalClient(protocol, ClientInfo())
        
        logger.info { "Starting Gemini agent process..." }
        
        // Connect to agent and start transport
        protocol.start()
        
        logger.info { "Connected to Gemini agent, initializing..." }

        val agentInfo = clientInstance.initialize()
        println("Agent info: $agentInfo")

        println()
        
        // Create a session
        val session = clientInstance.newSession(
            SessionParameters(Paths.get("").absolutePathString(), emptyList())
        )
        
        println("=== Session created: ${session.sessionId} ===")
        println("Type your messages below. Use 'exit', 'quit', or Ctrl+C to stop.")
        println("=".repeat(60))
        println()
        
        // Start interactive chat loop
        while (true) {
            print("You: ")
            val userInput = readLine()
            
            // Check for exit conditions
            if (userInput == null || userInput.lowercase() in listOf("exit", "quit", "bye")) {
                println("\n=== Goodbye! ===")
                break
            }
            
            // Skip empty inputs
            if (userInput.isBlank()) {
                continue
            }
            
            try {
                val response = session.prompt(listOf(ContentBlock.Text(userInput.trim())))

                
                when (response.stopReason) {
                    StopReason.END_TURN -> {
                        // Normal completion - no action needed
                    }
                    StopReason.MAX_TOKENS -> {
                        println("\n[Response truncated due to token limit]")
                    }
                    StopReason.MAX_TURN_REQUESTS -> {
                        println("\n[Turn limit reached]")
                    }
                    StopReason.REFUSAL -> {
                        println("\n[Agent declined to respond]")
                    }
                    StopReason.CANCELLED -> {
                        println("\n[Response was cancelled]")
                    }
                }
                
                println() // Extra newline for readability
                
            } catch (e: Exception) {
                println("\n[Error: ${e.message}]")
                logger.error(e) { "Error during chat interaction" }
                println()
            }
        }
        
    } catch (e: Exception) {
        logger.error(e) { "Client error occurred" }
        println("Error: ${e.message}")
        e.printStackTrace()
    } finally {
        logger.info { "Gemini ACP client shutting down" }
    }
}