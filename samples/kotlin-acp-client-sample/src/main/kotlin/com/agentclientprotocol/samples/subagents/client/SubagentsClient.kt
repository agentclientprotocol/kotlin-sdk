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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The two root session objects of one run: the one [runSubagentsClient] opened, and the one it loaded after closing
 * the first. Both are closed when the run ends.
 */
class SubagentsSessions(val opened: ClientSession, val loaded: ClientSession)

/**
 * Runs the client of the subagents sample over [transport], and the place to start reading it. The client opens a
 * session, sends two prompts, shows the subagents the agent announces, answers their permission requests through a
 * [ScriptedUser], and closes the session. Then it loads the session again, cancels the subagent that the agent granted
 * cancel again, and closes the session once more.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 1. declaring the `subagents` capability in [ClientInfo];
 * - 3. a parent's stream during a turn, where the root's updates come through the prompt's flow;
 * - 7. closing the root, which closes every subagent below it;
 * - 9. loading the closed session again;
 * - 10. cancelling a replayed subagent once the agent granted cancel again.
 *
 * The other client scenarios are in [TreeClientSessionOperations] and its subclasses, and in [ScriptedUser]. Every
 * line the client writes to [transcript] is marked [Side.CLIENT].
 *
 * @param declareSubagents whether to declare the `subagents` capability; without it the agent exposes no subagents
 * @return both root session objects, closed
 */
suspend fun runSubagentsClient(
    transport: Transport,
    transcript: Transcript,
    declareSubagents: Boolean = true,
): SubagentsSessions = coroutineScope {
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
        val session = client.newSession(parameters) { id, _ -> RootClientSessionOperations(id, view, user) }
        runTurn(session, view, "Review, clean up and test the change")
        runTurn(session, view, "Ask the $NATIVE_AGENT_REQUEST to plan the release")

        // Before closing, wait until every listed subagent has opened and none reports foreground work.
        view.awaitSettled(session)
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

        /*
         * 9. Loading the session again
         *
         * The close freed the id, so the client can load the session on the same connection. This overload of
         * `loadSession` takes the operations object up front and delivers the replayed history as it arrives, instead
         * of buffering it until the answer. Every stream of the tree, the root's and each replayed subagent's, gets its
         * history, then `Opened`, then live updates (section 8 in `TreeClientSessionOperations`).
         *
         * The loaded session is a new object with a new tree of subagents, rebuilt from the replayed announcements.
         * After a load, a replayed subagent starts with no confirmed state and no cancel grant: what the history said
         * about its state and grant is dropped at its parent's `Opened`, where the client shows the parent's
         * subagents.
         */
        view.show(session.sessionId, LOADING)
        val loadedOperations = RootClientSessionOperations(session.sessionId, view, user)
        val loaded = client.loadSession(session.sessionId, parameters, loadedOperations)

        /*
         * 10. Cancelling a replayed subagent
         *
         * A replayed subagent can be cancelled only once the agent grants cancel again, through a live
         * `subagent_update` after the load's answer. The user cancels the one that is running with such a grant.
         */
        if (loaded.subagents.isNotEmpty()) {
            val running = view.awaitValue {
                loaded.subagents.descendants().firstOrNull { it.canCancel && it.state is StateUpdate.Running }
            }
            view.show(running.sessionId, "user cancels the replayed subagent after its fresh grant")
            running.cancel()
        }

        view.awaitSettled(loaded)
        view.showTree(loaded.subagents, "before the second close")
        loaded.close()
        view.showTree(loaded.subagents, "after the second close")
        SubagentsSessions(session, loaded)
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

/**
 * The client's line that marks where the load of the closed session starts.
 */
const val LOADING = "user: load the closed session again"

/**
 * Waits until every subagent listed below [root] has delivered its `Opened` and none reports foreground work.
 *
 * A subagent's `Opened` follows the announcements of its own subagents in its stream, so once every listed one has
 * opened, the list is complete as far as the agent had announced before the opening call's answer.
 */
private suspend fun ClientTranscript.awaitSettled(root: ClientSession) = awaitUntil {
    val all = root.subagents.descendants()
    all.all(::hasOpened) && all.none { it.state.isActive() }
}

/**
 * Whether a state reports foreground work. An unrecognized state counts as active, as the SDK's agent side treats it:
 * the spec says it doesn't mean the work ended.
 */
private fun StateUpdate?.isActive() = when (this) {
    is StateUpdate.Running, is StateUpdate.RequiresAction, is StateUpdate.Other -> true
    is StateUpdate.Idle, is StateUpdate.Unknown, null -> false
}
