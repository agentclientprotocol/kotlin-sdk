package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationFormCapabilities
import com.agentclientprotocol.model.ElicitationUrlCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V1
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Starts a fresh testy agent, initializes a v1 [Client] against it with every client capability, and runs [block].
 *
 * Elicitations that are not scoped to a session go to [elicitations].
 */
suspend fun TestyAgent.client(
    elicitations: RecordingElicitationHandler = RecordingElicitationHandler(),
    block: suspend (Client) -> Unit,
) {
    run { protocol ->
        @OptIn(UnstableApi::class)
        val client = Client(protocol, globalElicitationHandler = elicitations)
        client.initialize(
            @OptIn(UnstableApi::class)
            ClientInfo(
                protocolVersion = PROTOCOL_VERSION_V1,
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                    terminal = true,
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities(),
                        url = ElicitationUrlCapabilities(),
                    ),
                ),
                implementation = Implementation("acp-kotlin-e2e", "1"),
            )
        )
        block(client)
    }
}

/**
 * A testy prompt command, such as `{"command":"greet"}`.
 */
fun prompt(command: String, vararg fields: Pair<String, String>): String =
    buildJsonObject {
        put("command", command)
        fields.forEach { (key, value) -> put(key, value) }
    }.toString()

/**
 * A testy `run_scenario` command for [scenario].
 */
fun scenario(scenario: String): String = prompt("run_scenario", "scenario" to scenario)

@OptIn(UnstableApi::class)
fun sessionParameters(cwd: Path, additionalDirectories: List<Path>? = null): SessionCreationParameters =
    SessionCreationParameters(cwd.toString(), emptyList(), additionalDirectories?.map { it.toString() })

suspend fun Client.newSession(cwd: Path, operations: RecordingClientOperations = RecordingClientOperations()): ClientSession =
    newSession(sessionParameters(cwd)) { _, _ -> operations }

/**
 * The outcome of one prompt turn: every session update in arrival order and the stop reason.
 */
class Turn(val updates: List<SessionUpdate>, val stopReason: StopReason) {
    /**
     * The concatenated text of the agent message chunks, which carries testy's reply.
     */
    val reply: String
        get() = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
            .mapNotNull { (it.content as? ContentBlock.Text)?.text }
            .joinToString("\n")
}

suspend fun ClientSession.promptTurn(text: String, vararg extraBlocks: ContentBlock): Turn {
    val updates = mutableListOf<SessionUpdate>()
    var stopReason: StopReason? = null
    prompt(listOf(ContentBlock.Text(text)) + extraBlocks).collect { event ->
        when (event) {
            is Event.SessionUpdateEvent -> updates += event.update
            is Event.PromptResponseEvent -> stopReason = event.response.stopReason
        }
    }
    return Turn(updates, checkNotNull(stopReason) { "The prompt flow ended without a response" })
}
