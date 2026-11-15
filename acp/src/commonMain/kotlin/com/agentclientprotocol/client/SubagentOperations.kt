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
 * and with the `subagents` client capability, which the client declares in
 * [com.agentclientprotocol.model.ClientCapabilities] to receive the announcements. Without the capability the agent
 * announces nothing, and this interface is never called.
 *
 * If the operations don't implement this interface, or [createSubagentOperations] throws, the subagent is not
 * registered in [BaseClientSession.subagents]. Its updates still reach the parent's
 * [ClientSessionOperations.notify] as plain updates.
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
     * The parent's updates wait while this call runs, in the order they arrived. So it must not wait for a later
     * event of the parent's stream, such as [ClientSessionOperations.notify], or it never returns.
     *
     * @param sessionId the id of the subagent session, as the agent announced it
     * @return the operations of the subagent session. They must also implement the interfaces for the extra
     * capabilities that the client declares, like file system or terminal support.
     */
    public suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations
}
