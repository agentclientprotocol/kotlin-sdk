package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * What the SDK gives an [AgentSubagentFactory] that builds a child: the child's own operations, bound to its id.
 *
 * The context belongs to the child, not to the factory call: it stays valid after the factory returns or throws, so
 * work the factory launched can keep using it.
 *
 * @property sessionId the id of the child being built, as passed to [AgentSubagents.announce]
 */
@UnstableApi
public interface AgentSubagentContext {
    public val sessionId: SessionId

    /**
     * The child's client operations.
     *
     * They route each update by the subagents rule: a [SessionUpdate.SubagentUpdate] for this child goes on the
     * parent's stream, a [SessionUpdate.SubagentUpdate] for any other session is rejected with
     * [IllegalArgumentException], and every other update goes on the child's own stream. Requests are sent for the
     * child's session.
     *
     * Until the child's announcement has been sent, every operation fails with [IllegalStateException], except
     * sending that announcement: a [SessionUpdate.SubagentUpdate] for this child.
     */
    public val client: ClientSessionOperations

    /**
     * The child's own subagents, for announcing grandchildren.
     */
    public val subagents: AgentSubagents
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A subagent the app exposes to the client. Built by an [AgentSubagentFactory].
 *
 * The SDK does not run the child's work. The work may be a coroutine in this process or a subroutine of a native
 * agent the app relays: the session only reports what the child does through [events], and receives the client's
 * cancellation through [cancel].
 */
@UnstableApi
public interface AgentSubagentSession {
    /**
     * The child's live events, as the subagents spec defines them.
     *
     * A [SessionUpdate.SubagentUpdate] for this child goes on the parent's stream: it announces the child if nothing
     * was sent for the child before, and otherwise patches it and carries its work state. Every other update goes on
     * the child's own stream. The SDK collects this once, after the factory returns, in the child's session context,
     * so it should be cold (or hot with replay). For a child replayed during the parent's `session/load`, collection
     * starts after the parent's response. It may be empty for a history-only child.
     *
     * The app reports the child's work state itself: `running` and `idle` as it observes work starting and stopping.
     * If the flow completes or throws while the last state it emitted is active (`running`, `requires_action` or an
     * unrecognized state), the SDK reports [StateUpdate.Unknown], because the client can no longer observe the child.
     * It never reports an outcome on its own. When the app knows that the work failed, or that it stopped after
     * [cancel], it emits `idle` with the `error` or `cancelled` stop reason before the flow ends, and the SDK then
     * sends nothing more. The SDK cancels the collection when the parent session is closed or the connection ends,
     * and sends nothing for it.
     */
    public val events: Flow<SessionUpdate> get() = emptyFlow()

    /**
     * Called on `session/cancel` for this child while the current cancel grant is in effect. Report the outcome
     * through [events]. The SDK does not stop the collection.
     */
    public suspend fun cancel() {}
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Builds a child for [AgentSubagents.announce]. Follows `ClientOperationsFactory` on the client side.
 */
@UnstableApi
public fun interface AgentSubagentFactory<out S : AgentSubagentSession> {
    /**
     * Builds the child. When called from the parent's replay, send the child's announcement and recorded history
     * through [context] before returning. The SDK collects the returned session's events afterwards.
     *
     * The call runs in the child's session context, so `currentCoroutineContext().client` is the child's
     * [AgentSubagentContext.client].
     */
    public suspend fun createSubagent(context: AgentSubagentContext): S
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The subagents of one session, a root session or a subagent, for announcing its children to the client.
 *
 * An instance is bound to its session and stays valid for that session's lifetime, not only inside the call that
 * obtained it. After the session is closed, [announce] fails. There is no update method: patches to a child are
 * ordinary events of the child.
 */
@UnstableApi
public interface AgentSubagents {
    /**
     * Registers [sessionId] under [parent], calls [factory] with the child's context, then collects the returned
     * session's events. Returns the session once the child's announcement has been sent and [factory] has returned.
     *
     * [parent] defaults to the session this object is bound to. It may name a live, announced descendant of that
     * session, for relays that learn about nested children from a flat event stream.
     *
     * The first thing sent for the child must be its announcement: a [SessionUpdate.SubagentUpdate] whose session id
     * is [sessionId], sent either through [AgentSubagentContext.client] while [factory] runs or as the first item of
     * [AgentSubagentSession.events]. A child that announces itself only in its events makes this call wait for that
     * first item, with no timeout; wrap the call in `withTimeout` to bound it. Returning is the caller's ordering
     * point: an update the parent sends afterwards, such as a `session_message` naming the child, follows the
     * announcement.
     *
     * If nothing has been sent for the child when [factory] throws, when the events complete or throw before
     * emitting, or when their first item isn't the child's announcement, the child is unregistered, nothing more is
     * sent for it, and this call throws [IllegalStateException], even when the cause is a [CancellationException]
     * from the app's code. If [factory] throws after the announcement was sent, the child stays registered, this call
     * rethrows the exception unchanged, and the SDK revokes the child's cancel grant and reports its work state as
     * [StateUpdate.Unknown] when the factory left it active.
     *
     * If the caller is cancelled before the announcement was sent, the child is unregistered and its id is free
     * again. Once it was sent, the child stays registered and its events keep being collected; only the caller gets
     * the [CancellationException].
     *
     * @throws IllegalArgumentException if [parent] is neither the bound session nor a registered, announced
     * descendant of it, or if [sessionId] is already registered on the connection, as a root session or a subagent
     * @throws IllegalStateException if the bound session is a subagent that has not been announced yet, or if the
     * child's announcement could not be sent as described above
     */
    public suspend fun <S : AgentSubagentSession> announce(
        sessionId: SessionId,
        factory: AgentSubagentFactory<S>,
        parent: SessionId? = null,
    ): S
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The subagents of the current session: a root session or a subagent. `null` when the client didn't advertise
 * `subagents`: run the child without exposing it.
 *
 * Without the capability, a root session must not send the updates the subagents spec defines either: its client
 * operations reject `session_message`, `session_message_chunk`, and tool calls with session content.
 *
 * @throws IllegalStateException if called outside a session context or from a NES session context
 */
@UnstableApi
public val CoroutineContext.subagents: AgentSubagents?
    get() = chatSessionWrapper("Subagents").subagents
