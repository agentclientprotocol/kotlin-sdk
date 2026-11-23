package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.clientSession
import kotlinx.coroutines.currentCoroutineContext

/**
 * The operations of the root session. They get the root's updates outside a prompt turn; during a turn those come
 * through the prompt's flow instead, see `runSubagentsClient`.
 */
internal class RootClientSessionOperations(view: ClientTranscript, user: ScriptedUser) :
    TreeClientSessionOperations<ClientSession>(view, user) {
    override suspend fun session(): ClientSession = currentCoroutineContext().clientSession

    override fun showOpened(session: ClientSession) {
        view.show(session.sessionId, "session opened")
    }
}
