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
 */
@UnstableApi
public interface AgentSubagentContext {
    /**
     * The id of the child being built, as passed to [AgentSubagents.announce].
     */
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
     * sending that announcement: a [SessionUpdate.SubagentUpdate] for this child. Once the child is removed with its
     * root session (see [AgentSubagents]), every operation fails with [IllegalStateException] again.
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
     * sends nothing more. The SDK cancels the collection when the root session is closed or the connection ends,
     * and sends nothing for it. A feed that ends doesn't stop the collections of the child's own subagents.
     */
    public val events: Flow<SessionUpdate> get() = emptyFlow()

    /**
     * Called on `session/cancel` for this child while the current cancel grant is in effect. Report the outcome
     * through [events]. The SDK does not stop the collection.
     *
     * A cancel that arrives while the child's [AgentSubagentFactory.createSubagent] still runs, with a grant in
     * effect, is held until the factory returns this session. It is then delivered once, outside the call to
     * [AgentSubagents.announce], if a grant is still in effect at that point. A cancel that arrives with no grant in
     * effect is ignored, and so is one held for a factory that throws.
     *
     * A grant sent during the parent's `session/load`, before its response, is history and never in effect: to
     * enable cancel for a replayed child, send a fresh grant, for example through [events].
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
     * through [context] before returning, and announce its recorded subagents through [AgentSubagentContext.subagents]
     * the same way. The SDK collects the returned session's events afterwards: return a session with empty events for
     * a child that only has history.
     *
     * The call runs in the child's session context, so `currentCoroutineContext().client` is the child's
     * [AgentSubagentContext.client].
     *
     * A cancel grant sent from here is in effect at once: a `session/cancel` for the child that arrives before this
     * returns is held, and delivered to the returned session's [AgentSubagentSession.cancel] if the grant is still
     * in effect then. If this throws, the held cancel is dropped.
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
 * obtained it. There is no update method: patches to a child are ordinary events of the child.
 *
 * Every subagent belongs to a root session and lives until that root is closed by `session/close`. The SDK then
 * removes every subagent below the root, at every depth, after the root's [AgentSession.close] returns and before
 * the close is answered: it cancels their event collections without sending anything for them, frees their ids,
 * ignores `session/cancel` for them, and makes their client operations and their [announce] fail with
 * [IllegalStateException]. [announce] on the closed root fails the same way. The SDK calls no app code for the
 * subagents: stopping their work and freeing their resources belongs to the root's [AgentSession.close]. If that
 * throws, the root and its subagents stay registered. When `session/load` or `session/resume` re-opens a root that
 * is still open, the SDK removes the old root's subagents the same way once [AgentSupport.loadSession] or
 * [AgentSupport.resumeSession] returns, before the replay and the response. The old [AgentSession] isn't closed and
 * its turn isn't cancelled, so stopping the old subagents' work is up to the app. When the connection ends, the
 * collections are cancelled the same way.
 *
 * Only the subagents' updates are guaranteed not to follow the close response. A request a subagent already had in
 * flight (permission, fs, terminal or elicitation), or the `$/cancel_request` the SDK sends for one whose call the
 * cleanup cancelled, may still reach the client after the response.
 *
 * During the root's `session/load`, until the transport has accepted its response, the root is replaying:
 * announcements made from its [AgentSession.replayHistory], or from a factory called from there, replay recorded
 * subagents. Every update sent for them before the response is history, as the subagents spec defines it: a cancel
 * grant sent then is never in effect, and a state sent then doesn't count as active. If the load fails, including
 * when its response can't be built or sent, every subagent announced during it is removed with the root session.
 * Subagents announced while a session is opened by any other method, or after the response, are live.
 */
@UnstableApi
public interface AgentSubagents {
    /**
     * Registers [sessionId] under [parent], calls [factory] with the child's context, then collects the returned
     * session's events. Returns the session once the child's announcement has been sent and [factory] has returned.
     *
     * [parent] defaults to the session this object is bound to. It may name an announced descendant of that session
     * that is still registered, for relays that learn about nested children from a flat event stream.
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
     * from the app's code. If [factory] throws after the announcement was sent, the child stays registered and,
     * outside the root's replay, the SDK revokes the child's cancel grant and reports its work state as
     * [StateUpdate.Unknown] when the factory left it active. This call then rethrows the exception unchanged, except
     * a [CancellationException] from the app's code while the caller is active: that one is wrapped in an
     * [IllegalStateException] too, so it doesn't tear down the caller's scope.
     *
     * If the caller is cancelled before the announcement was sent, the child is unregistered and its id is free
     * again. Once it was sent, the child stays registered and its events keep being collected; only the caller gets
     * the [CancellationException].
     *
     * While the root is replaying (see [AgentSubagents]), [factory] must send the announcement itself, through
     * [AgentSubagentContext.client]: if it returns without one, the child is unregistered and this call throws
     * [IllegalStateException] right away. The call doesn't wait for the events: their collection starts after the
     * root's `session/load` response, before the root's [AgentSession.postInitialize], and their updates are fresh
     * patches to the replayed child.
     *
     * @throws IllegalArgumentException if [parent] is neither the bound session nor a registered, announced
     * descendant of it, or if [sessionId] is already registered on the connection, as a root session or a subagent
     * @throws IllegalStateException if the bound session's root is closed, if the bound session is a subagent that
     * has not been announced yet or was removed with its root, if the child's announcement could not be sent as
     * described above, or if [factory] threw a [CancellationException] of its own after the announcement
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
