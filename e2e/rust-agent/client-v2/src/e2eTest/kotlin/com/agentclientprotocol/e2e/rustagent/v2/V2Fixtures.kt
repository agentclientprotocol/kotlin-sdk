@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.e2e.rustagent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.Client
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.SessionInfo
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Starts a fresh testy agent, initializes a v2 [Client] against it, and runs [block].
 *
 * Testy v2 sends no client callbacks, so the client advertises no capabilities.
 */
suspend fun TestyAgent.client(block: suspend (Client) -> Unit) {
    run { protocol ->
        val client = Client(protocol)
        client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("acp-kotlin-e2e", "1")))
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

suspend fun Client.newSession(cwd: Path): ClientSession = newSession(cwd.toString())

suspend fun Client.listed(sessionId: SessionId): SessionInfo? =
    listSessions().sessions.find { it.sessionId == sessionId }

/**
 * Prompts with [text] and collects the session's updates in arrival order until the turn ends.
 *
 * The turn ends at the first [StateUpdate.Idle] after a [StateUpdate.Running], because a resumed session
 * first receives an idle snapshot that belongs to no turn. [onRunning] is called when the running update
 * arrives, which is the first sign that the agent is working on the prompt.
 *
 * [ClientSession.updates] can be collected only once, so call this at most once per session.
 */
suspend fun ClientSession.promptAndCollect(text: String, onRunning: () -> Unit = {}): List<SessionUpdate> =
    coroutineScope {
        var running = false
        val turn = async(start = CoroutineStart.UNDISPATCHED) {
            updates.map { it.update }.transformWhile { update ->
                emit(update)
                val state = (update as? SessionUpdate.StateUpdate)?.state
                if (state is StateUpdate.Running && !running) {
                    running = true
                    onRunning()
                }
                !(running && state is StateUpdate.Idle)
            }.toList()
        }
        prompt(listOf(ContentBlock.Text(text)))
        turn.await()
    }

/**
 * The stop reason of the idle update that ended the turn.
 */
val List<SessionUpdate>.stopReason: StopReason?
    get() = ((lastOrNull() as? SessionUpdate.StateUpdate)?.state as? StateUpdate.Idle)?.stopReason

/**
 * The concatenated text of the agent message chunks, which carries testy's reply.
 */
val List<SessionUpdate>.reply: String
    get() = filterIsInstance<SessionUpdate.AgentMessageChunk>()
        .mapNotNull { (it.chunk.content as? ContentBlock.Text)?.text }
        .joinToString("\n")
