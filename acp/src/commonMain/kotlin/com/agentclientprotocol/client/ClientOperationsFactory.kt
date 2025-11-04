package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId

@Deprecated("Use ClientOperationsFactory instead")
public typealias ClientSupport = ClientOperationsFactory

public fun interface ClientOperationsFactory {
    /**
     * This method should return an implementation of [ClientSessionOperations] that provides basic features on the client side.
     * If you need to provide additional features like file system or terminal support, you have to implement [FileSystemOperations] and [TerminalOperations] on this object as well.
     * Remember to specify a corresponding capability when initializing an agent.
     *
     * [sessionId] an existing id in the case when the session is being loaded or a new id when the session is newly created (id is returned from the agent)
     */
    public suspend fun createClientOperations(sessionId: SessionId, sessionResponse: AcpCreatedSessionResponse): ClientSessionOperations
}