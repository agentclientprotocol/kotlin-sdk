@file:JvmName("ClientSessionImplKt")

// Keeps clientSession in the JVM class it had when it lived in ClientSessionImpl.kt, for binary compatibility.

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.internal.ClientSessionContextElement
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionId
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmName

/**
 * **UNSTABLE**
 *
 * Part of the SDK's Kotlin API for client-side subagents. The subagent protocol is stable in v1, but this Kotlin API
 * is unstable. It may change or be removed at any point.
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
     * The list only grows, until this session is closed or its stream fails, and then it stays as it was. A subagent
     * whose operations couldn't be created is never in it. A listed subagent may be closed, because its own stream or
     * that of a session above it failed, or its root session was closed, see [ClientSubagentSession].
     *
     * A `subagent_update` whose subagent is not in the list is one that the client didn't accept: the setup of the
     * subagent failed, the update arrived after this session was closed or failed, or the agent announced an id that
     * another session owns.
     *
     * Each read returns a snapshot, so a list never changes under its reader. A reloaded session is a new object
     * with a new list, and the list of the closed object stays as it was.
     *
     * ### At this session's Opened
     *
     * Each session's own stream sets up the subagents that its history announced, and the session lists them before
     * its own [com.agentclientprotocol.common.SessionEvent.Opened]. So at a session's `Opened`, this list has every
     * subagent that the session's history announced, except those whose setup failed.
     *
     * The levels below are not ready then:
     * - A subagent's own list is complete at that subagent's `Opened`, which comes on its own stream.
     * - Nothing orders a subagent's `Opened` against its parent's: it may come before or after.
     * - A session's `Opened` doesn't wait for any subagent's opening, and a slow `notify` of a subagent doesn't
     *   hold up its parent.
     *
     * An application that needs the whole replayed tree waits for the `Opened` of each subagent it finds, and reads
     * that subagent's list then.
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
 * Part of the SDK's Kotlin API for client-side subagents. The subagent protocol is stable in v1, but this Kotlin API
 * is unstable. It may change or be removed at any point.
 *
 * The session that the current call of a session's operations belongs to: a [ClientSession], or a
 * [ClientSubagentSession] inside the operations that [SubagentOperations.createSubagentOperations] returned.
 *
 * @throws IllegalStateException if the context belongs to no session
 */
@UnstableApi
public val CoroutineContext.baseClientSession: BaseClientSession
    get() = this[ClientSessionContextElement.Key]?.session ?: error("No client session data found in context")

/**
 * The root session that the current call of a session's operations belongs to.
 *
 * @throws IllegalStateException if the context belongs to no session, or to a subagent session. Use
 * [clientSubagentSession] for a subagent session, and [baseClientSession] where the operations may serve both.
 */
public val CoroutineContext.clientSession: ClientSession
    get() = this[ClientSessionContextElement.Key]?.session as? ClientSession ?: error("No client session data found in context")

/**
 * **UNSTABLE**
 *
 * Part of the SDK's Kotlin API for client-side subagents. The subagent protocol is stable in v1, but this Kotlin API
 * is unstable. It may change or be removed at any point.
 *
 * The subagent session that the current call of a subagent's operations belongs to: the operations that
 * [SubagentOperations.createSubagentOperations] returned.
 *
 * Inside [SubagentOperations.createSubagentOperations] itself, the context names the parent of the new subagent, so
 * there this returns the parent if it is a subagent, and throws if it is a root session.
 *
 * @throws IllegalStateException if the context belongs to no session, or to a root session. Use [clientSession] for
 * a root session, and [baseClientSession] where the operations may serve both.
 */
@UnstableApi
public val CoroutineContext.clientSubagentSession: ClientSubagentSession
    get() = this[ClientSessionContextElement.Key]?.session as? ClientSubagentSession
        ?: error("No client subagent session data found in context")
