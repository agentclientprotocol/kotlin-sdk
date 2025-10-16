package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

class SimpleAgentSession(
    override val sessionId: SessionId,
    private val clientCapabilities: ClientCapabilities?
) : AgentSession {
    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = flow {

        logger.info { "Processing prompt for session $sessionId" }

        try {
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
            if (clientCapabilities?.fs?.readTextFile == true) {
                simulateToolCall()
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
 * Use [withClient] to create a wrapper that can send updates.
 */
class SimpleAgentSupport : AgentSupport {
    private var clientCapabilities: ClientCapabilities? = null

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        logger.info { "Initializing agent with protocol version ${clientInfo.protocolVersion}" }
        clientCapabilities = clientInfo.capabilities

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

    override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
        val sessionId = SessionId("session-${System.currentTimeMillis()}")
        return SimpleAgentSession(sessionId, clientCapabilities)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
    ): AgentSession {
        return SimpleAgentSession(sessionId, clientCapabilities)
    }
}