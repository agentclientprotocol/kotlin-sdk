package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId

@Deprecated("Use ClientOperationsFactory instead")
public typealias ClientSupport = ClientOperationsFactory

/*
 * TODO Deprecate ClientOperationsFactory and its opening overloads in favour of `newSession` and `forkSession`
 * overloads that take `ClientSessionOperations`, see https://github.com/agentclientprotocol/kotlin-sdk/issues/167.
 */

/**
 * Creates the [ClientSessionOperations] of a session after the agent answers the request that opens it.
 *
 * The client calls the factory only after the answer. Until then, it buffers every `session/update` of the session
 * and delivers the buffer to the new operations after the factory returns: the updates that came before the answer,
 * then [com.agentclientprotocol.common.SessionEvent.Opened] with the answer, then the updates that came after it.
 * This is necessary for `session/new` and `session/fork`, because the client learns the session id only from the
 * answer.
 *
 * ### Failure
 *
 * The opening call fails when:
 * - the agent answers with an error;
 * - the factory throws;
 * - the caller is cancelled, or `notify` throws, before the call returns.
 *
 * A call that fails before the answer arrives doesn't call the factory, so nothing gets
 * [com.agentclientprotocol.common.SessionEvent.Opened]. Once the answer has arrived, `Opened` may have been delivered
 * even though the call then fails, so treat a failed call as a failed opening. The session's updates that the
 * operations did not receive yet, and its later ones, then go to the client's [GlobalSessionUpdateHandler], and the
 * session id can be opened again. After the call has returned, a failure of `notify` is logged, as for any live update.
 *
 * ### Load and resume
 *
 * For `session/load` and `session/resume`, the client knows the session id before the request. An agent can replay
 * the whole history before it answers. If the client has the operations object before the request, use the
 * [Client.loadSession] or [Client.resumeSession] overload with a [ClientSessionOperations] argument instead. It
 * delivers each update when it arrives, so the client can show the history without waiting for the answer.
 */
public fun interface ClientOperationsFactory {
    /**
     * Returns the [ClientSessionOperations] that provides the basic features on the client side.
     *
     * If you need additional features like file system or terminal support, implement
     * [com.agentclientprotocol.common.FileSystemOperations] and [com.agentclientprotocol.common.TerminalOperations] on
     * the returned object as well, and specify the corresponding capability when initializing the client.
     *
     * @param sessionId the id of the session being loaded or resumed, or the id that the agent returned for a newly
     * created one
     * @param sessionResponse the agent's answer to the call that opens the session
     */
    public suspend fun createClientOperations(sessionId: SessionId, sessionResponse: AcpCreatedSessionResponse): ClientSessionOperations
}