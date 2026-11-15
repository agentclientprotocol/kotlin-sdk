package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionId
import kotlin.coroutines.CoroutineContext

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * What every session of the client has: a [ClientSession] that the application opened, and a
 * [ClientSubagentSession] that the agent started under another session.
 *
 * The type is sealed, and these are its only two direct subtypes. Inside a call of a session's operations,
 * [kotlin.coroutines.CoroutineContext.baseClientSession] returns the session of the call.
 */
@UnstableApi
public sealed interface BaseClientSession {
    /**
     * The id of this session.
     */
    public val sessionId: SessionId

    /**
     * The operations that handle this session's updates and the agent's requests for it.
     *
     * They are valid for the whole life of the session object: a [ClientSession] is built after its operations
     * exist, and so is a [ClientSubagentSession], from [SubagentOperations.createSubagentOperations]. So this is
     * the link from a session to the application's own per-session object, and the application needs no map.
     */
    public val operations: ClientSessionOperations

    /**
     * The subagent sessions that the agent has started directly under this session, in the order it announced
     * them. A subagent of a subagent is in that subagent's list.
     *
     * The list only grows: a subagent whose setup failed is never in it, and it stays as it was after this session
     * is closed. A `subagent_update` whose subagent is not in the list is one that the client didn't accept: the
     * setup of the subagent failed, or the update was rejected because the agent announced an id that another
     * session owns.
     *
     * Each read returns a snapshot, so a list never changes under its reader. A reloaded session is a new object
     * with a new list, and the list of the closed object stays as it was.
     *
     * See [ClientSubagentSession] for when the value is up to date.
     */
    @UnstableApi
    public val subagents: List<ClientSubagentSession>

    /**
     * The working directory of this session: the one the agent reported for it, otherwise that of its parent, so
     * for a [ClientSession] the one it was opened with.
     *
     * It is computed on each read, so a parent's later change reaches the subagents that inherit it. It combines
     * the values of the ancestors as they are at that moment.
     */
    @UnstableApi
    public val cwd: String

    /**
     * The additional directories of this session: the ones the agent reported for it, otherwise those of its
     * parent, so for a [ClientSession] the ones it was opened with, or an empty list if it was opened with none.
     *
     * Like [cwd], it is computed on each read, and is inherited independently of [cwd].
     */
    @UnstableApi
    public val additionalDirectories: List<String>
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The session that the current call of a session's operations belongs to: a [ClientSession], or a
 * [ClientSubagentSession] inside the operations that [SubagentOperations.createSubagentOperations] returned.
 *
 * @throws IllegalStateException if the context belongs to no session
 */
@UnstableApi
public val CoroutineContext.baseClientSession: BaseClientSession
    get() = this[ClientSessionContextElement.Key]?.session ?: error("No client session data found in context")
