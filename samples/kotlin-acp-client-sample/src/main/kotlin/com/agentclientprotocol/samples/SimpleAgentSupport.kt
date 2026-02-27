@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.*
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

class SimpleAgentSession(
    override val sessionId: SessionId
) : AgentSession {

    // Example: Expose boolean and select config options for the session
    override val configOptions: List<SessionConfigOption>
        get() = listOf(
            SessionConfigOption.boolean(
                id = "auto_approve",
                name = "Auto Approve",
                currentValue = false,
                description = "Automatically approve all tool calls without prompting the user"
            ),
            SessionConfigOption.boolean(
                id = "verbose",
                name = "Verbose",
                currentValue = true,
                description = "Enable verbose logging output"
            )
        )

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?
    ): SetSessionConfigOptionResponse {
        logger.info { "Setting config option $configId to $value" }
        // In a real agent, you would update internal state based on the config option
        return SetSessionConfigOptionResponse(configOptions = configOptions)
    }

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = flow {

        logger.info { "Processing prompt for session $sessionId" }

        try {
            val clientCapabilities = currentCoroutineContext().clientInfo.capabilities

            // Send initial plan
            sendPlan()

            // Echo the user's message
            for (block in content) {
                emit(Event.SessionUpdateEvent(SessionUpdate.UserMessageChunk(block)))
                delay(100) // Simulate processing time
            }

            // Send agent response
            val responseText = "I received your message: ${
                content.filterIsInstance<ContentBlock.Text>()
                    .joinToString(" ") { it.text }
            }"

            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text(responseText)
                )
            ))

            // Simulate a tool call if client supports file operations
            if (clientCapabilities.fs?.readTextFile == true) {
                simulateToolCall()
            }

            // Demonstrate file system operations
            if (clientCapabilities.fs?.readTextFile == true) {
                demonstrateFileSystemOperations()
            }

            // Demonstrate terminal operations
            if (clientCapabilities.terminal == true) {
                demonstrateTerminalOperations()
            }

            emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))

        } catch (e: Exception) {
            logger.error(e) { "Error processing prompt" }
//            emit(Event.PromptResponseEvent(PromptResponse(StopReason.REFUSAL)))
        }
    }

    override suspend fun cancel() {
        logger.info { "Cancellation requested for session: $sessionId" }
    }

    private suspend fun FlowCollector<Event>.sendPlan() {
        val plan = Plan(
            listOf(
                PlanEntry("Process user input", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                PlanEntry("Generate response", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING),
                PlanEntry("Execute tools if needed", PlanEntryPriority.MEDIUM, PlanEntryStatus.PENDING)
            )
        )

        emit(Event.SessionUpdateEvent(
            SessionUpdate.PlanUpdate(plan.entries)
        ))
    }

    private suspend fun FlowCollector<Event>.simulateToolCall() {
        val toolCallId = ToolCallId("tool-${System.currentTimeMillis()}")

        // Start tool call
        emit(Event.SessionUpdateEvent(
            SessionUpdate.ToolCallUpdate(
                toolCallId = toolCallId,
                title = "Reading current directory",
                kind = ToolKind.READ,
                status = ToolCallStatus.PENDING,
                locations = listOf(ToolCallLocation(".")),
                content = emptyList()
            )
        ))

        delay(500) // Simulate work

        // Update to in progress
        emit(Event.SessionUpdateEvent(
            SessionUpdate.ToolCallUpdate(
                toolCallId = toolCallId,
                status = ToolCallStatus.IN_PROGRESS
            )
        ))

        delay(500) // Simulate more work

        // Complete the tool call
        emit(Event.SessionUpdateEvent(
            SessionUpdate.ToolCallUpdate(
                toolCallId = toolCallId,
                status = ToolCallStatus.COMPLETED,
                content = listOf(
                    ToolCallContent.Content(
                        ContentBlock.Text("Directory listing completed successfully")
                    )
                )
            )
        ))
    }

    private suspend fun FlowCollector<Event>.demonstrateFileSystemOperations() {
        try {
            val clientOperation = currentCoroutineContext().client

            // Example: Write a file
            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nDemonstrating file system operations...")
                )
            ))

            val testContent = "Hello from ACP agent!"
            clientOperation.fsWriteTextFile("/tmp/acp_test.txt", testContent)

            // Example: Read the file back
            val readResponse = clientOperation.fsReadTextFile("/tmp/acp_test.txt")

            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nFile content read: ${readResponse.content}")
                )
            ))
        } catch (e: Exception) {
            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nFile system operation failed: ${e.message}")
                )
            ))
        }
    }

    private suspend fun FlowCollector<Event>.demonstrateTerminalOperations() {
        try {
            val terminalOps = currentCoroutineContext().client

            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nDemonstrating terminal operations...")
                )
            ))

            // Example: Execute a simple command
            val createResponse = terminalOps.terminalCreate("echo", listOf("Hello from terminal!"))
            val exitResponse = terminalOps.terminalWaitForExit(createResponse.terminalId)
            val outputResponse = terminalOps.terminalOutput(createResponse.terminalId)
            terminalOps.terminalRelease(createResponse.terminalId)

            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nTerminal output: ${outputResponse.output} (exit code: ${exitResponse.exitCode})")
                )
            ))
        } catch (e: Exception) {
            emit(Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(
                    ContentBlock.Text("\nTerminal operation failed: ${e.message}")
                )
            ))
        }
    }
}

/**
 * Simple example agent implementation.
 *
 * This agent demonstrates basic ACP functionality including:
 * - Session management
 * - Content processing
 * - Tool call simulation
 * - Plan reporting
 *
 * Note: This agent needs a way to send updates back to the client.
 */
class SimpleAgentSupport : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        logger.info { "Initializing agent with protocol version ${clientInfo.protocolVersion}" }

        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = false,
                promptCapabilities = PromptCapabilities(
                    audio = false,
                    image = false,
                    embeddedContext = true
                )
            ),
            authMethods = emptyList() // No authentication required
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val sessionId = SessionId("session-${System.currentTimeMillis()}")
        return SimpleAgentSession(sessionId)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
    ): AgentSession {
        return SimpleAgentSession(sessionId)
    }
}