package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId

@Deprecated("Use ClientOperationsFactory instead")
public typealias ClientSupport = ClientOperationsFactory

/*
 * TODO Deprecate ClientOperationsFactory and every opening overload that takes it.
 *
 * Roots don't need a factory. What session/new and session/fork need is only that the client buffers updates until
 * the answer names the session id, and the holder does that whether the operations come from a factory or from the
 * caller. The factory's arguments aren't needed either:
 * - sessionId: every operations call runs with the session in its context (`Client.kt`, the handlers wrap the call
 *   in `executeWithSession`), so `coroutineContext.clientSession.sessionId` works there. The early load/resume
 *   overloads already rely on the caller knowing the id.
 * - sessionResponse: it's harmful. The factory gets it before the buffered history is delivered, but the history is
 *   older than the response, so state built from the response and then from `notify` ends up stale. That's the same
 *   problem as the deprecated ClientSession.configOptions and currentMode.
 * The only v1 sample ignores both arguments (`TerminalClientSupport.kt`), and v2 has no factory: its newSession,
 * resumeSession and forkSession take `operations` directly.
 *
 * Fix:
 * - Add `newSession(sessionParameters, operations: ClientSessionOperations)` and
 *   `forkSession(sessionParameters, operations: ClientSessionOperations)`, like the load/resume overloads from #146.
 *   They keep the current buffering and drain the buffer into the given object after the answer, instead of calling
 *   the factory. Overload resolution stays as for loadSession: a lambda converts to the factory, and an object that
 *   implements ClientSessionOperations picks the new overload.
 * - Deprecate this interface and the four factory overloads in favour of them, and rewrite the KDoc above, which
 *   still says the factory is "necessary" for session/new and session/fork.
 * - Tests: the new overloads deliver updates that arrived before the answer, in order; a failing open cleans up the
 *   holder, like "new session should cleanup holder when createClientOperations fails" in SimpleAgentTest; samples
 *   move to the new overloads. Run apiDump and review the diff.
 *
 * History versus live updates is solved separately, by `SessionEvent.Opened` in the update stream, which also keeps
 * this path in the right order. Children keep a genuine factory, `createSubagentOperations`, because the agent
 * creates them. See "Side findings" and decision 18 in docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md.
 */

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