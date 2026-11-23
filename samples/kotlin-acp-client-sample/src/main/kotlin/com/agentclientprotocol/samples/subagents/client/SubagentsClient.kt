@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentCapabilities
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.samples.subagents.Side
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.agent.NATIVE_AGENT_REQUEST
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the client of the subagents sample over [transport], and the place to start reading it. The client opens a
 * session, sends two prompts, shows the subagents the agent announces, answers their permission requests through a
 * [ScriptedUser], and closes the session at the end.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 1. declaring the `subagents` capability in [ClientInfo];
 * - 3. a parent's stream during a turn, where the root's updates come through the prompt's flow;
 * - 7. closing the root, which closes every subagent below it.
 *
 * The other client scenarios are in [TreeClientSessionOperations] and its subclasses, and in [ScriptedUser]. Every
 * line the client writes to [transcript] is marked [Side.CLIENT].
 *
 * @param declareSubagents whether to declare the `subagents` capability; without it the agent exposes no subagents
 * @return the root session, closed
 */
suspend fun CoroutineScope.runSubagentsClient(
    transport: Transport,
    transcript: Transcript,
    declareSubagents: Boolean = true,
): ClientSession {
    val protocol = Protocol(this, transport)
    val client = Client(protocol)
    protocol.start()

    val view = ClientTranscript(transcript)
    val user = ScriptedUser(view)
    val userJob = launch { user.run() }
    try {
        /*
         * 1. Declaring the capability
         *
         * The client doesn't declare `subagents` on its own. Without it, the agent announces nothing.
         */
        val capabilities = ClientCapabilities(subagents = if (declareSubagents) SubagentCapabilities() else null)
        val implementation = Implementation("subagents-sample-client", "1.0.0")
        val agentInfo = client.initialize(ClientInfo(capabilities = capabilities, implementation = implementation))
        val declared = if (declareSubagents) "declared" else "not declared"
        val agentName = agentInfo.implementation?.name
        view.show(null, "initialized ACP v${agentInfo.protocolVersion} with $agentName, subagents $declared")

        val parameters = SessionCreationParameters("/work/project", emptyList())
        val session = client.newSession(parameters) { _, _ -> RootClientSessionOperations(view, user) }
        runTurn(session, view, "Review, clean up and test the change")
        runTurn(session, view, "Ask the $NATIVE_AGENT_REQUEST to plan the release")

        // Before closing, wait until no subagent reports foreground work.
        view.awaitUntil { session.subagents.descendants().none { it.state.isActive() } }
        view.showTree(session.subagents, "before close")

        /*
         * 7. Closing the root
         *
         * `close` answers the pending permission requests of the whole tree `cancelled` when it starts. After the
         * agent's response, every subagent below the root is closed: `canCancel` is `false` and `cancel()` throws, but
         * each one keeps its last values and stays in its parent's `subagents`.
         */
        session.close()
        view.showTree(session.subagents, "after close")
        return session
    } finally {
        userJob.cancel()
        protocol.close()
    }
}

private suspend fun runTurn(session: ClientSession, view: ClientTranscript, request: String) {
    view.show(session.sessionId, "user: $request")

    /*
     * 3. A parent's stream, during a turn
     *
     * While a prompt runs, the root's updates come through the prompt's flow instead of `notify`. The flow is
     * buffered, so the getters of `session.subagents` may already reflect later updates: show the update itself.
     * Outside a turn they reach `notify`, see `TreeClientSessionOperations`.
     */
    session.prompt(listOf(ContentBlock.Text(request))).collect { event ->
        when (event) {
            is Event.SessionUpdateEvent -> view.show(session.sessionId, event.update.describe(), event.update)
            is Event.PromptResponseEvent -> view.show(session.sessionId, "turn ended: ${event.response.stopReason}")
        }
    }
}

private fun StateUpdate?.isActive() = this is StateUpdate.Running || this is StateUpdate.RequiresAction
