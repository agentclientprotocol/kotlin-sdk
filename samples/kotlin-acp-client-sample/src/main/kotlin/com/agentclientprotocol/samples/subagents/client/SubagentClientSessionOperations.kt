@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.clientSubagentSession
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.currentCoroutineContext

/**
 * The operations of a subagent session, which the client builds from `createSubagentOperations` when the agent
 * announces the subagent.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 4. a subagent's own stream: history, then `Opened`, then live updates;
 * - 5. a subagent's permission request, which the user answers on the subagent's behalf (see [ScriptedUser]).
 */
internal class SubagentClientSessionOperations(sessionId: SessionId, view: ClientTranscript, user: ScriptedUser) :
    TreeClientSessionOperations<ClientSubagentSession>(sessionId, view, user) {
    /*
     * 4. A subagent's own stream
     *
     * The operations don't hold their session: `clientSubagentSession` gives it inside each call. The stream starts
     * with the history the agent replays, if any, then exactly one `Opened`, which carries no response, then live
     * updates. A live subagent has no history, so `Opened` comes first. The stream also carries the announcements of
     * the subagent's own subagents, as a parent's stream does.
     *
     * 5. A subagent's permission request
     *
     * A request that carries the subagent's id reaches these operations, and `requestPermissions` hands it to the user
     * with this session, so that the user can cancel the subagent instead of answering.
     */
    override suspend fun session(): ClientSubagentSession = currentCoroutineContext().clientSubagentSession

    override fun showOpened(session: ClientSubagentSession) {
        view.markOpened(session)
        val parent = session.parentSessionId.value
        view.show(session.sessionId, "opened under $parent as \"${session.title}\", live from here")
    }
}
