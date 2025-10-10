package com.agentclientprotocol.samples.agent

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.AuthenticateRequest
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.Plan
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionModeRequest
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

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
class SimpleAgent : Agent {
    private val sessions = ConcurrentHashMap<SessionId, SessionContext>()
    private var clientCapabilities: ClientCapabilities? = null

    // Callback for sending session updates - set by the connection wrapper
    internal var onSessionUpdate: (suspend (SessionNotification) -> Unit)? = null

    data class SessionContext(
        val sessionId: SessionId,
        val cwd: String,
        val mcpServers: List<McpServer>,
        var cancelled: Boolean = false
    )
    
    override suspend fun initialize(request: InitializeRequest): InitializeResponse {
        logger.info { "Initializing agent with protocol version ${request.protocolVersion}" }
        clientCapabilities = request.clientCapabilities
        
        return InitializeResponse(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            agentCapabilities = AgentCapabilities(
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
    
    override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
        logger.info { "Authentication requested with method: ${request.methodId}" }
        // No-op: this agent doesn't require authentication
        return AuthenticateResponse()
    }
    
    override suspend fun sessionNew(request: NewSessionRequest): NewSessionResponse {
        val sessionId = SessionId("session-${System.currentTimeMillis()}")
        val context = SessionContext(
            sessionId = sessionId,
            cwd = request.cwd,
            mcpServers = request.mcpServers
        )
        sessions[sessionId] = context
        
        logger.info { "Created new session: $sessionId in directory: ${request.cwd}" }
        return NewSessionResponse(sessionId)
    }
    
    override suspend fun sessionLoad(request: LoadSessionRequest): LoadSessionResponse {
        val context = SessionContext(
            sessionId = request.sessionId,
            cwd = request.cwd,
            mcpServers = request.mcpServers
        )
        sessions[request.sessionId] = context

        logger.info { "Loaded session: ${request.sessionId}" }
        return LoadSessionResponse()
    }

    override suspend fun sessionSetMode(request: SetSessionModeRequest): SetSessionModeResponse {
        logger.info { "Session mode change requested for session ${request.sessionId} to mode ${request.modeId}" }
        // This simple agent doesn't support multiple modes, so just acknowledge
        return SetSessionModeResponse()
    }

    override suspend fun sessionPrompt(request: PromptRequest): PromptResponse {
        val context = sessions[request.sessionId]
            ?: throw IllegalArgumentException("Unknown session: ${request.sessionId}")
            
        if (context.cancelled) {
            return PromptResponse(StopReason.CANCELLED)
        }
        
        logger.info { "Processing sessionPrompt for session ${request.sessionId}" }
        
        try {
            // Send initial plan
            sendPlan(request.sessionId)
            
            // Echo the user's message
            for (block in request.prompt) {
                onSessionUpdate?.invoke(
                    SessionNotification(
                        sessionId = request.sessionId,
                        update = SessionUpdate.UserMessageChunk(block)
                    )
                )
                delay(100) // Simulate processing time
            }

            // Send agent response
            val responseText = "I received your message: ${
                request.prompt.filterIsInstance<ContentBlock.Text>()
                    .joinToString(" ") { it.text }
            }"

            onSessionUpdate?.invoke(
                SessionNotification(
                    sessionId = request.sessionId,
                    update = SessionUpdate.AgentMessageChunk(
                        ContentBlock.Text(responseText)
                    )
                )
            )
            
            // Simulate a tool call if client supports file operations
            if (clientCapabilities?.fs?.readTextFile == true) {
                simulateToolCall(request.sessionId)
            }
            
            return PromptResponse(StopReason.END_TURN)
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing sessionPrompt" }
            return PromptResponse(StopReason.REFUSAL)
        }
    }
    
    override suspend fun sessionCancel(notification: CancelNotification) {
        logger.info { "Cancellation requested for session: ${notification.sessionId}" }
        sessions[notification.sessionId]?.cancelled = true
    }
    
    private suspend fun sendPlan(sessionId: SessionId) {
        val plan = Plan(
            listOf(
                PlanEntry("Process user input", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                PlanEntry("Generate response", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING),
                PlanEntry("Execute tools if needed", PlanEntryPriority.MEDIUM, PlanEntryStatus.PENDING)
            )
        )
        
        onSessionUpdate?.invoke(
            SessionNotification(
                sessionId = sessionId,
                update = SessionUpdate.PlanUpdate(plan.entries)
            )
        )
    }
    
    private suspend fun simulateToolCall(sessionId: SessionId) {
        val toolCallId = ToolCallId("tool-${System.currentTimeMillis()}")

        // Start tool call
        onSessionUpdate?.invoke(
            SessionNotification(
                sessionId = sessionId,
                update = SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    title = "Reading current directory",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.PENDING,
                    locations = listOf(ToolCallLocation(".")),
                    content = emptyList()
                )
            )
        )

        delay(500) // Simulate work

        // Update to in progress
        onSessionUpdate?.invoke(
            SessionNotification(
                sessionId = sessionId,
                update = SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    status = ToolCallStatus.IN_PROGRESS
                )
            )
        )

        delay(500) // Simulate more work

        // Complete the tool call
        onSessionUpdate?.invoke(
            SessionNotification(
                sessionId = sessionId,
                update = SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(
                        ToolCallContent.Content(
                            ContentBlock.Text("Directory listing completed successfully")
                        )
                    )
                )
            )
        )
    }
}