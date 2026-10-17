package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId

@Deprecated("Use ClientOperationsFactory instead")
public typealias ClientSupport = ClientOperationsFactory

/**
 * Creates the [ClientSessionOperations] of a session after the agent answers the request that opens it.
 *
 * The client calls the factory only after the answer. Until then, it buffers every `session/update` of the session
 * and delivers the buffer to the new operations after the factory returns. This is necessary for `session/new` and
 * `session/fork`, because the client learns the session id only from the answer.
 *
 * For `session/load` and `session/resume`, the client knows the session id before the request. An agent can replay
 * the whole history before it answers. If the client has the operations object before the request, use the
 * [Client.loadSession] or [Client.resumeSession] overload with a [ClientSessionOperations] argument instead. It
 * delivers each update when it arrives, so the client can show the history without waiting for the answer.
 */
public fun interface ClientOperationsFactory {
    /**
     * This method should return an implementation of [ClientSessionOperations] that provides basic features on the client side.
     * If you need to provide additional features like file system or terminal support, you have to implement [com.agentclientprotocol.common.FileSystemOperations] and [com.agentclientprotocol.common.TerminalOperations] on this object as well.
     * Remember to specify a corresponding capability when initializing an agent.
     *
     * [sessionId] an existing id in the case when the session is being loaded or a new id when the session is newly created (id is returned from the agent)
     */
    public suspend fun createClientOperations(sessionId: SessionId, sessionResponse: AcpCreatedSessionResponse): ClientSessionOperations
}