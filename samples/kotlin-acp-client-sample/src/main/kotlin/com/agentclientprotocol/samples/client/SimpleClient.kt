package com.agentclientprotocol.samples.client

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.KillTerminalCommandRequest
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.ReleaseTerminalRequest
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.TerminalOutputRequest
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.model.WaitForTerminalExitRequest
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileRequest
import com.agentclientprotocol.model.WriteTextFileResponse

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Simple example client implementation.
 * 
 * This client demonstrates basic ACP functionality including:
 * - File system operations
 * - Permission handling
 * - Session update processing
 */
class SimpleClient(private val workingDirectory: File = File(".")) : Client {
    
    override suspend fun fsReadTextFile(request: ReadTextFileRequest): ReadTextFileResponse {
        logger.info { "Reading file: ${request.path}" }
        
        val file = File(workingDirectory, request.path).canonicalFile
        
        // Basic security check - ensure file is within working directory
        if (!file.path.startsWith(workingDirectory.canonicalPath)) {
            throw SecurityException("File access outside working directory: ${request.path}")
        }
        
        if (!file.exists()) {
            throw NoSuchFileException(file, null, "File not found")
        }
        
        val lines = file.readLines()
        val startLine = (request.line?.toInt() ?: 1) - 1 // Convert to 0-based
        val limit = request.limit?.toInt()
        
        val selectedLines = when {
            startLine >= lines.size -> emptyList()
            limit != null -> lines.drop(startLine).take(limit)
            else -> lines.drop(startLine)
        }
        
        return ReadTextFileResponse(selectedLines.joinToString("\n"))
    }
    
    override suspend fun fsWriteTextFile(request: WriteTextFileRequest): WriteTextFileResponse {
        logger.info { "Writing file: ${request.path}" }

        val file = File(workingDirectory, request.path).canonicalFile

        // Basic security check - ensure file is within working directory
        if (!file.path.startsWith(workingDirectory.canonicalPath)) {
            throw SecurityException("File access outside working directory: ${request.path}")
        }

        // Create parent directories if needed
        file.parentFile?.mkdirs()

        file.writeText(request.content)
        return WriteTextFileResponse()
    }
    
    override suspend fun sessionRequestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        logger.info { "Permission requested for tool call: ${request.toolCall.title}" }
        
        // For this simple example, we'll auto-approve read operations
        // and sessionPrompt for write operations
        val autoApprove = when (request.toolCall.kind) {
            ToolKind.READ, ToolKind.SEARCH -> true
            else -> false
        }
        
        if (autoApprove) {
            val allowOnceOption = request.options.find { it.kind == PermissionOptionKind.ALLOW_ONCE }
            if (allowOnceOption != null) {
                logger.info { "Auto-approving read operation" }
                return RequestPermissionResponse(
                    RequestPermissionOutcome.Selected(allowOnceOption.optionId)
                )
            }
        }
        
        // For demo purposes, simulate user interaction
        println("Agent requesting permission for: ${request.toolCall.title}")
        println("Tool kind: ${request.toolCall.kind}")
        println("Available options:")
        request.options.forEachIndexed { index, option ->
            println("  ${index + 1}. ${option.name} (${option.kind})")
        }
        
        // Simulate user delay and selection
        delay(1000)
        val selectedOption = request.options.firstOrNull() 
            ?: return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
            
        logger.info { "Selected option: ${selectedOption.name}" }
        return RequestPermissionResponse(
            RequestPermissionOutcome.Selected(selectedOption.optionId)
        )
    }
    
    override suspend fun sessionUpdate(notification: SessionNotification) {
        when (val update = notification.update) {
            is SessionUpdate.UserMessageChunk -> {
                when (val content = update.content) {
                    is ContentBlock.Text -> println("User: ${content.text}")
                    else -> println("User: [${content::class.simpleName}]")
                }
            }
            
            is SessionUpdate.AgentMessageChunk -> {
                when (val content = update.content) {
                    is ContentBlock.Text -> println("Agent: ${content.text}")
                    else -> println("Agent: [${content::class.simpleName}]")
                }
            }
            
            is SessionUpdate.AgentThoughtChunk -> {
                when (val content = update.content) {
                    is ContentBlock.Text -> println("Agent thinks: ${content.text}")
                    else -> println("Agent thinks: [${content::class.simpleName}]")
                }
            }
            
            is SessionUpdate.ToolCallUpdate -> {
                println("Tool call started: ${update.title} (${update.kind})")
                if (update.status != null) {
                    println("  Status: ${update.status}")
                }
            }

            is SessionUpdate.PlanUpdate -> {
                println("Agent plan:")
                update.entries.forEach { entry ->
                    println("  [${entry.status}] ${entry.content} (${entry.priority})")
                }
            }

            is SessionUpdate.AvailableCommandsUpdate -> {
                println("Available commands updated:")
                update.availableCommands.forEach { command ->
                    println("  /${command.name} - ${command.description}")
                    command.input?.let { input ->
                        when (input) {
                            is AvailableCommandInput.UnstructuredCommandInput -> {
                                println("    Input hint: ${input.hint}")
                            }
                        }
                    }
                }
            }
            
            is SessionUpdate.CurrentModeUpdate -> {
                println("Session mode changed to: ${update.currentModeId.value}")
            }
            
            is SessionUpdate.ToolCall -> {
                println("Tool call: ${update.title} [${update.toolCallId.value}]")
                update.kind?.let { println("  Kind: $it") }
                update.status?.let { println("  Status: $it") }
                update.content.forEach { content ->
                    when (content) {
                        is ToolCallContent.Content -> {
                            when (val block = content.content) {
                                is ContentBlock.Text -> println("  Content: ${block.text}")
                                else -> println("  Content: [${block::class.simpleName}]")
                            }
                        }
                        is ToolCallContent.Diff -> {
                            println("  Diff in ${content.path}:")
                            println("    Old: ${content.oldText?.take(50) ?: "(new file)"}")
                            println("    New: ${content.newText.take(50)}")
                        }
                        is ToolCallContent.Terminal -> {
                            println("  Terminal output: ${content.terminalId}")
                        }
                    }
                }
                update.locations.forEach { location ->
                    println("  Location: ${location.path}${location.line?.let { ":$it" } ?: ""}")
                }
            }
        }
    }

    override suspend fun terminalCreate(request: CreateTerminalRequest): CreateTerminalResponse {
        TODO("Terminal support not implemented in this sample client")
    }

    override suspend fun terminalOutput(request: TerminalOutputRequest): TerminalOutputResponse {
        TODO("Terminal support not implemented in this sample client")
    }

    override suspend fun terminalRelease(request: ReleaseTerminalRequest): ReleaseTerminalResponse {
        TODO("Terminal support not implemented in this sample client")
    }

    override suspend fun terminalWaitForExit(request: WaitForTerminalExitRequest): WaitForTerminalExitResponse {
        TODO("Terminal support not implemented in this sample client")
    }

    override suspend fun terminalKill(request: KillTerminalCommandRequest): KillTerminalCommandResponse {
        TODO("Terminal support not implemented in this sample client")
    }
}