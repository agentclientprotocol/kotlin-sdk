# ACP Kotlin SDK

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![JVM](https://img.shields.io/badge/Platform-JVM-orange?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Kotlin implementation of the [Agent Client Protocol](https://agentclientprotocol.com) (ACP), providing both client and agent capabilities for integrating with AI agents across various platforms.

## Overview

The Agent Client Protocol allows applications to provide a standardized interface for AI agents, enabling seamless communication between clients (like code editors) and agents (AI assistants). This SDK implements the ACP specification for Kotlin, currently targeting JVM with future multiplatform support planned.

- Build ACP clients that can connect to any ACP agent
- Create ACP agents that expose capabilities to clients  
- Use STDIO transport for process communication
- Handle all ACP protocol messages and lifecycle events
- Full support for sessions, tool calls, permissions, and file system operations

## Project Structure

- **kotlin-acp**: Single module containing all ACP functionality
  - `agent/`: Agent-side implementation (`Agent.kt`, `AgentSideConnection.kt`)
  - `client/`: Client-side implementation (`Client.kt`, `ClientSideConnection.kt`)
  - `model/`: Protocol data models and types
  - `protocol/`: Core protocol handling
  - `rpc/`: JSON-RPC implementation
  - `transport/`: Transport layer implementation (STDIO)
- **samples/**: Example implementations demonstrating usage

## Installation

Add the repository to your build file:

```kotlin
repositories {
    mavenCentral()
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("io.agentclientprotocol:kotlin-acp:0.1.0-SNAPSHOT")
}
```

## Quick Start

### Creating an Agent

```kotlin
import io.agentclientprotocol.agent.*
import io.agentclientprotocol.model.*
import io.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.coroutineScope
import kotlinx.io.*

class MyAgent : Agent {
    override suspend fun initialize(request: InitializeRequest): InitializeResponse {
        return InitializeResponse(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            agentCapabilities = AgentCapabilities(
                promptCapabilities = PromptCapabilities(
                    image = true,
                    embeddedContext = true
                )
            )
        )
    }

    override suspend fun sessionNew(request: NewSessionRequest): NewSessionResponse {
        val sessionId = SessionId("session-${System.currentTimeMillis()}")
        return NewSessionResponse(sessionId)
    }

    override suspend fun sessionPrompt(request: PromptRequest): PromptResponse {
        // Process the user's prompt and send updates via client connection
        return PromptResponse(StopReason.END_TURN)
    }

    // Implement other required methods...
}

// Set up agent with STDIO transport
suspend fun main() = coroutineScope {
    val agent = MyAgent()

    // Create transport with parent scope
    val transport = StdioTransport(
        parentScope = this,
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )

    // Create connection with parent scope and transport
    val connection = AgentSideConnection(
        parentScope = this,
        agent = agent,
        transport = transport
    )

    // Start the connection
    connection.start()
}
```

### Creating a Client

```kotlin
import io.agentclientprotocol.client.*
import io.agentclientprotocol.model.*
import io.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.coroutineScope
import kotlinx.io.*
import java.io.File

class MyClient : Client {
    override suspend fun fsReadTextFile(request: ReadTextFileRequest): ReadTextFileResponse {
        val content = File(request.path).readText()
        return ReadTextFileResponse(content)
    }

    override suspend fun fsWriteTextFile(request: WriteTextFileRequest): WriteTextFileResponse {
        File(request.path).writeText(request.content)
        return WriteTextFileResponse()
    }

    override suspend fun sessionRequestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        // Present options to user and return their choice
        val selectedOption = request.options.first()
        return RequestPermissionResponse(
            RequestPermissionOutcome.Selected(selectedOption.optionId)
        )
    }

    override suspend fun sessionUpdate(notification: SessionNotification) {
        // Handle real-time updates from agent
        println("Agent update: ${notification.update}")
    }

    // Implement other required methods...
}

// Set up client
suspend fun main() = coroutineScope {
    val client = MyClient()

    // Create transport from external process (agent)
    val agentProcess = ProcessBuilder("gemini", "--experimental-acp").start()
    val transport = StdioTransport(
        parentScope = this,
        input = agentProcess.inputStream.asSource().buffered(),
        output = agentProcess.outputStream.asSink().buffered()
    )

    // Create connection with parent scope
    val connection = ClientSideConnection(
        parentScope = this,
        transport = transport,
        client = client
    )

    // Start the connection
    connection.start()

    // Initialize agent
    val initResponse = connection.initialize(
        InitializeRequest(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            clientCapabilities = ClientCapabilities(
                fs = FileSystemCapability(
                    readTextFile = true,
                    writeTextFile = true
                )
            )
        )
    )

    // Create session and send prompts
    val sessionResponse = connection.sessionNew(
        NewSessionRequest(
            cwd = "/path/to/working/directory",
            mcpServers = emptyList()
        )
    )

    val promptResponse = connection.sessionPrompt(
        PromptRequest(
            sessionId = sessionResponse.sessionId,
            prompt = listOf(ContentBlock.Text("Hello, agent!"))
        )
    )
}
```

## Samples

The `samples/` directory contains complete working examples:

- **kotlin-acp-client-sample**: Contains both agent and client examples
  - `SimpleAgent.kt`: A basic agent implementation that echoes messages
  - `SimpleClient.kt`: A client implementation with file operations and permissions
  - `GeminiClientApp.kt`: Interactive chat application that connects to Gemini agent
  - `AgentSample.kt` & `ClientSample.kt`: Sample runners

Run the samples:

```bash
# Run the default client sample
./gradlew :samples:kotlin-acp-client-sample:run

# Run the Gemini interactive chat client
./gradlew :samples:kotlin-acp-client-sample:run -PmainClass=io.agentclientprotocol.samples.client.GeminiClientAppKt
```

## Features

### Protocol Support
- ✅ Full ACP v1 protocol implementation
- ✅ JSON-RPC message handling with request/response correlation
- ✅ Support for all ACP message types (requests, responses, notifications)
- ✅ Type-safe method enums (`AcpMethod.AgentMethods` and `AcpMethod.ClientMethods`)
- ✅ Structured request/response interfaces (`AcpRequest`, `AcpResponse`, `AcpWithMeta`)

### Agent Features
- ✅ Agent initialization and capability negotiation
- ✅ Session management (create, load, cancel)
- ✅ Prompt processing with real-time updates
- ✅ Tool call reporting and progress updates
- ✅ Execution plan reporting
- ✅ File system operations (via client)
- ✅ Permission requests

### Client Features  
- ✅ Client initialization and capability advertising
- ✅ File system operations (read/write text files)
- ✅ Permission handling and user prompts
- ✅ Real-time session update processing
- ✅ Agent lifecycle management

### Transport Support
- ✅ STDIO transport for process communication

### Multiplatform Support
- ✅ JVM target
- 🚧 JavaScript/Node.js (planned)
- 🚧 Native targets (planned)
- 🚧 WebAssembly (planned)

## Architecture

The SDK follows a clean architecture with clear separation of concerns:

```
┌─────────────────┐    ┌─────────────────┐
│   Agent App     │    │   Client App    │
├─────────────────┤    ├─────────────────┤
│ AgentSideConn   │    │ ClientSideConn  │
├─────────────────┤    ├─────────────────┤
│    Protocol     │    │    Protocol     │
├─────────────────┤    ├─────────────────┤
│   Transport     │    │   Transport     │
│     (STDIO)     │◄──►│     (STDIO)     │
└─────────────────┘    └─────────────────┘
```

- **Transport Layer**: Handles raw message transmission via STDIO
- **Protocol Layer**: Manages JSON-RPC framing, request correlation, error handling, and type-safe method dispatch using `AcpMethod` enums
- **Connection Layer**: Provides type-safe ACP method calls and handles serialization with structured `AcpRequest`/`AcpResponse` interfaces
- **Application Layer**: Your agent or client implementation