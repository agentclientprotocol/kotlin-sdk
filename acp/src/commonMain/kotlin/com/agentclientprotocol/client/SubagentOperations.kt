package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Lets the operations of a session handle the subagent sessions that the agent starts under it.
 *
 * [ClientSessionOperations] does not inherit this interface. The operations of a session implement it to support
 * subagents, and the client checks for it when the agent announces a subagent of that session. The operations of a
 * subagent session can implement it too, for the subagents that the subagent starts.
 *
 * It belongs with [ClientSubagentSession], the session object that the client builds from the returned operations,
 * and with the `subagents` client capability, [com.agentclientprotocol.model.ClientCapabilities.subagents], which
 * enables the announcements. The client doesn't declare the capability on its own: the application declares it in
 * the [ClientInfo] it passes to [Client.initialize]. Without the capability the agent announces nothing, and this
 * interface is never called.
 *
 * If the operations don't implement this interface, or [createSubagentOperations] throws, the subagent is not
 * registered in [BaseClientSession.subagents], and the failure is logged. Its announcement and later
 * `subagent_update`s still reach the parent's [ClientSessionOperations.notify] as plain updates. The subagent's own
 * updates, and those of the subagents it announces, go to the [GlobalSessionUpdateHandler], and the agent's requests
 * for it fail. The parent and its other subagents are not affected.
 */
@UnstableApi
public interface SubagentOperations {
    /**
     * Creates the operations of the subagent session [sessionId], which the agent has just announced under the
     * session of these operations.
     *
     * The operations do not hold their session: inside every call of the returned operations,
     * [kotlin.coroutines.CoroutineContext.baseClientSession] names the subagent session.
     *
     * This call itself runs with the parent session in the coroutine context, with one exception. When
     * [Client.loadSession] or [Client.resumeSession] gets ready [ClientSessionOperations], the subagents announced in
     * the root's stream before the answer are set up before the root's [ClientSession] exists, so the context has no
     * session then, as for the root's own `notify` calls at that point.
     *
     * The parent's updates wait while this call runs, in the order they arrived. So it must not wait for a later
     * event of the parent's stream, such as [ClientSessionOperations.notify], or it never returns.
     *
     * @param sessionId the id of the subagent session, as the agent announced it
     * @return the operations of the subagent session. They must also implement the interfaces for the extra
     * capabilities that the client declares, like file system or terminal support.
     */
    public suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations
}
