# ACP Kotlin SDK

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blueviolet?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![JVM](https://img.shields.io/badge/Platform-JVM-orange?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)

Modern Kotlin toolkit for building software that speaks the [Agent Client Protocol (ACP)](https://agentclientprotocol.com). Ship ACP-compliant agents, clients, and transports for IDE plugins, CLIs, backend services, or any JVM host—all with one cohesive SDK.

## What is ACP Kotlin SDK?

ACP standardises how AI agents and clients exchange messages, negotiate capabilities, and move files. This SDK provides a Kotlin implementation of that spec:

- Type-safe models for every ACP message and capability
- Agent and client connection stacks (JSON-RPC over STDIO)
- Ktor utilities for HTTP/WebSocket transports (optional modules)
- Comprehensive samples demonstrating end-to-end sessions and tool calls

### Common scenarios

- Embed an ACP client in your IDE/plugin to talk to external agents
- Build a headless automation agent that serves ACP prompts and tools
- Prototype new transports with the connection layer and model modules
- Validate your ACP integration using the supplied test utilities

## Modules at a glance

| Module                              | Description                                               | Main packages                              |
|-------------------------------------|-----------------------------------------------------------|--------------------------------------------|
| `:acp-model`                        | Pure data model for ACP messages, capabilities, and enums | `com.agentclientprotocol.model.*`          |
| `:acp`                              | Core agent/client runtime with STDIO transport            | `agent`, `client`, `protocol`, `transport` |
| `:acp-ktor`                         | Shared infrastructure for Ktor-based transports           | `ktor`                                     |
| `:acp-ktor-client`                  | Ktor HTTP/WebSocket client helper                         | `ktor.client`                              |
| `:acp-ktor-server`                  | Ktor server-side transport utilities                      | `ktor.server`                              |
| `:acp-ktor-test`                    | Test fixtures and fake transports for ACP flows           | `ktor.test`                                |
| `:samples:kotlin-acp-client-sample` | Complete runnable client + agent reference implementation | `samples`                                  |

## Requirements

- JDK 21 (toolchain configured through Gradle)
- Kotlin 2.2.20 or newer (Gradle Kotlin DSL plugin)
- Gradle 8.6+ (wrapper included)
- JVM target today; additional targets (JS, Native, Wasm) are on the roadmap

## Installation

Artifacts are published under `com.agentclientprotocol`. The default build version is `0.3.0-SNAPSHOT`; release builds use `0.3.0`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.agentclientprotocol:acp:0.3.0-SNAPSHOT")
    // Optional extras:
    // implementation("com.agentclientprotocol:acp-ktor-client:0.3.0-SNAPSHOT")
    // implementation("com.agentclientprotocol:acp-ktor-server:0.3.0-SNAPSHOT")
}
```

> **Snapshot builds:** When consuming the `-SNAPSHOT` artifacts outside Maven Central, add the repository that hosts your snapshot (e.g. GitHub Packages or an internal mirror).

## Quick start

### Write your first agent

Set up an `AgentSupport`, wire the standard STDIO transport, and stream responses. The example below also shows how to call the optional `FileSystemOperations` extension so the agent can read files through the client.

```kotlin
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.clientInfo
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.FileSystemOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.common.remoteSessionOperations
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

// 1. Build a dedicated AgentSession implementation for each connection.
private class TerminalAgentSession(
    override val sessionId: SessionId
) : AgentSession {
    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?
    ): Flow<Event> = flow {
        // Echo back what the user typed.
        val userText = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        emit(
            Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(ContentBlock.Text("Agent heard: $userText"))
            )
        )

        // Optional extension call via the coroutine context.
        val context = currentCoroutineContext()
        val clientCapabilities = context.clientInfo.capabilities
        if (clientCapabilities.fs?.readTextFile == true) {
            val fs = context.remoteSessionOperations(FileSystemOperations)
            val readmeSnippet = fs.fsReadTextFile("README.md").content.take(120)
            emit(
                Event.SessionUpdateEvent(
                    SessionUpdate.AgentMessageChunk(ContentBlock.Text("README preview: $readmeSnippet…"))
                )
            )
        }

        // Finish the turn once updates are sent.
        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }

    override suspend fun cancel() {
        // No long-running work in this demo, so nothing to clean up yet.
    }
}

// 2. Implement AgentSupport: negotiate capabilities and build per-session handlers.
private class TerminalAgentSupport : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        protocolVersion = LATEST_PROTOCOL_VERSION,
        capabilities = AgentCapabilities() // advertise baseline agent features
    )

    override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
        // 3. Instantiate the session implementation defined above.
        val sessionId = SessionId("session-${System.currentTimeMillis()}")
        return TerminalAgentSession(sessionId)
    }

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): AgentSession =
        // Rehydrate existing sessions with the provided identifier.
        TerminalAgentSession(sessionId)
}

fun main(): Unit = runBlocking {
    // 4. Bridge STDIO to the Protocol so the agent can speak ACP over stdin/stdout.
    val transport = StdioTransport(
        parentScope = this,
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )
    val protocol = Protocol(this, transport)

    // 5. Register the agent and declare which remote extensions it will use.
    Agent(
        protocol = protocol,
        agentSupport = TerminalAgentSupport(),
        remoteSideExtensions = listOf(FileSystemOperations)
    )

    // 6. Start listening for messages from the client.
    protocol.start()
}
```

### Write your first client

Create a `Client` with your own `ClientSessionOperations` implementation. This sample exposes `FileSystemOperations`, grants tool-call permissions, and prints streamed updates from the agent.

```kotlin
import com.agentclientprotocol.client.*
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

// 1. Describe how the client should create session handlers for each connection.
private class TerminalClientSupport(private val projectDir: Path) : ClientSupport {
    override suspend fun createClientSession(
        session: ClientSession,
        _sessionResponseMeta: JsonElement?
    ): ClientSessionOperations = TerminalSession(projectDir)
}

private class TerminalSession(
    private val projectDir: Path
) : ClientSessionOperations, FileSystemOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?
    ): RequestPermissionResponse =
        // Grant whichever option was first in the list (swap for real UX).
        RequestPermissionResponse(RequestPermissionOutcome.Selected(permissions.first().optionId))

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        // Surface streaming updates back to the host application.
        println("Agent update: $notification")
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?
    ): ReadTextFileResponse =
        // Resolve file paths relative to the workspace root the client chose.
        ReadTextFileResponse(projectDir.resolve(path).readText())

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?
    ): WriteTextFileResponse {
        // Allow the agent to write files through the same extension API.
        projectDir.resolve(path).writeText(content)
        return WriteTextFileResponse()
    }
}

fun main(): Unit = runBlocking {
    val transport = StdioTransport(
        parentScope = this,
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )
    val protocol = Protocol(this, transport)

    val projectRoot = Paths.get("").toAbsolutePath()

    val client = Client(
        // 2. Register the client support and advertise which extensions you implement.
        protocol = protocol,
        clientSupport = TerminalClientSupport(projectRoot),
        handlerSideExtensions = listOf(FileSystemOperations)
    )

    protocol.start()

    client.initialize(
        // 3. Negotiate capabilities so the agent knows extensions are available.
        ClientInfo(
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(readTextFile = true, writeTextFile = true)
            )
        )
    )

    val session = client.newSession(
        // 4. Launch a session pointing at the project workspace.
        SessionParameters(
            cwd = projectRoot.toString(),
            mcpServers = emptyList()
        )
    )

    session.prompt(listOf(ContentBlock.Text("Hello agent!"))).collect { event ->
        when (event) {
            // 5. React to streaming updates and final responses.
            is Event.SessionUpdateEvent -> println("Agent update: ${event.update}")
            is Event.PromptResponseEvent -> println("Prompt finished: ${event.response.stopReason}")
        }
    }
}
```

### Run the reference sample

Prefer a fully wired example? Launch the repository sample that pairs the agent and client shown above:

```bash
./gradlew :samples:kotlin-acp-client-sample:run

# Gemini interactive client (requires external Gemini ACP agent)
./gradlew :samples:kotlin-acp-client-sample:run \
    -PmainClass=com.agentclientprotocol.samples.client.GeminiClientAppKt
```

## Sample projects

| Project | Shows | Command |
| --- | --- | --- |
| `samples:kotlin-acp-client-sample` | End-to-end agent + client with STDIO transport | `./gradlew :samples:kotlin-acp-client-sample:run` |
| `samples/client/GeminiClientApp.kt` | Interactive CLI client that talks to an external Gemini ACP agent | `./gradlew :samples:kotlin-acp-client-sample:run -PmainClass=...GeminiClientAppKt` |

Each sample includes comments that explain the protocol lifecycle and can be used as templates for real applications.

## Capabilities

- **Protocol**
  - ✅ Full ACP v1 coverage with JSON-RPC framing
  - ✅ Typed request/response wrappers (`AcpRequest`, `AcpResponse`, `AcpWithMeta`)
  - ✅ Message correlation, error propagation, and structured logging hooks
- **Agent runtime**
  - ✅ Capability negotiation, session lifecycle, prompt streaming
  - ✅ Tool-call progress, execution plans, permission requests routed to clients
  - ✅ File-system operations executed via client callbacks
- **Client runtime**
  - ✅ Capability advertising and lifecycle management
  - ✅ File-system helpers, permission handling, and session update listeners
  - ✅ Utilities for embedding ACP into desktop/CLI experiences
- **Transports**
  - ✅ STDIO binding out of the box
  - ✅ Ktor-based HTTP/WebSocket helpers (`acp-ktor*` modules)
  - 🚧 Additional transports (Node.js, Native, Wasm) planned

## Architecture

```
┌─────────────────┐    ┌─────────────────┐
│   Agent App     │    │   Client App    │
│ (AgentSupport & │    │ (ClientSupport &│
│ AgentSession)   │    │ ClientSessionOps│
├─────────────────┤    ├─────────────────┤
│   Agent runtime │    │  Client runtime │
│     (`Agent`)   │    │     (`Client`)  │
├─────────────────┤    ├─────────────────┤
│    Protocol     │    │    Protocol     │
├─────────────────┤    ├─────────────────┤
│   Transport     │    │   Transport     │
│  (STDIO, Ktor)  │◄──►│  (STDIO, Ktor)  │
└─────────────────┘    └─────────────────┘
```

**Lifecycle overview:** clients establish a transport, call `initialize` to negotiate capabilities, open sessions (`session.new`), send prompts (`session.prompt`), and react to streamed updates (tool calls, permissions, status). Agents implement the mirrors of these methods, delegating file and permission requests back to the client when required. The `Agent` and `Client` runtime classes sit between your business logic (AgentSupport/AgentSession or ClientSupport/ClientSessionOperations) and the lower-level `Protocol`/transport layers.

## Contributing

Contributions are welcome! Please open an issue to discuss significant changes before submitting a PR.

1. Fork and clone the repo.
2. Run `./gradlew check` to execute the test suite.
3. Use the supplied GitHub Actions workflows to verify compatibility.

## Support

- File bugs and feature requests through GitHub Issues.
- For questions or integration help, start a discussion or reach out to the maintainers through the issue tracker.

## License

Distributed under the MIT License. See [`LICENSE.txt`](LICENSE.txt) for details.
