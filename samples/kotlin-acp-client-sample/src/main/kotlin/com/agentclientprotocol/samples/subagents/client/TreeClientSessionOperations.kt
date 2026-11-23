@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.SubagentOperations
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * The operations of any session in the client's tree, the root or a subagent. The subclasses differ only in how they
 * find their session in the call's context: [RootClientSessionOperations] and [SubagentClientSessionOperations].
 *
 * Scenarios in this file, numbered as its section comments:
 * - 2. operations that implement [SubagentOperations], so that their session can have subagents;
 * - 3. a parent's stream: announcements and patches of its subagents, and links to them.
 */
internal abstract class TreeClientSessionOperations<S : BaseClientSession>(
    protected val view: ClientTranscript,
    private val user: ScriptedUser,
) : ClientSessionOperations, SubagentOperations {
    /**
     * The session of the current call, read from its coroutine context.
     */
    protected abstract suspend fun session(): S

    /**
     * Shows the [SessionEvent.Opened] of [session].
     */
    protected abstract fun showOpened(session: S)

    /*
     * 2. Operations that support subagents
     *
     * The client checks for `SubagentOperations` when the agent announces a subagent of a session. The subagent's
     * operations implement it too, for the subagents it announces in turn. Without it, the subagent's updates and
     * requests go nowhere useful: see the Failure section of `SubagentOperations`.
     *
     * The subagent's events wait until this returns, and so do the parent's later events: it must not wait for one of
     * them. Inside the returned operations, the context gives the subagent's session.
     */
    override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations =
        SubagentClientSessionOperations(view, user)

    /*
     * 3. A parent's stream
     *
     * A `subagent_update` on this stream announces a subagent or patches it. Inside `notify`, the parent's `subagents`
     * and the getters of each subagent reflect every event delivered so far, including this one, so the client reads
     * the subagent from there.
     */
    override suspend fun notify(event: SessionEvent) {
        val session = session()
        when (event) {
            is SessionEvent.Opened -> showOpened(session)
            is SessionEvent.Update -> showUpdate(session, event.update)
        }
    }

    private fun showUpdate(parent: BaseClientSession, update: SessionUpdate) {
        if (update !is SessionUpdate.SubagentUpdate) {
            view.show(parent.sessionId, update.describe(), update)
            return
        }
        val child = parent.subagents.firstOrNull { it.sessionId == update.sessionId }
        val text = if (child != null) "subagent ${child.describe()}" else "update of unknown ${update.sessionId.value}"
        view.show(parent.sessionId, text, update)
    }

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = user.askPermission(session(), toolCall, permissions)
}
