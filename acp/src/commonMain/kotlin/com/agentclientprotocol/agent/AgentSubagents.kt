package com.agentclientprotocol.agent

import com.agentclientprotocol.agent.internal.SessionWrapper
import com.agentclientprotocol.agent.internal.SubagentSessionWrapper
import com.agentclientprotocol.agent.internal.sessionWrapper
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
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
 *
 * What the SDK gives an [AgentSubagentFactory] that builds a child: the child's own operations, bound to its id.
 *
 * The context belongs to the child, not to the factory call: it stays valid after the factory returns or throws, so
 * work the factory launched can keep using it.
 *
 * [client] sends requests for the child's session and routes each update by the subagents rule:
 * - a [SessionUpdate.SubagentUpdate] for this child goes on the parent's stream;
 * - a [SessionUpdate.SubagentUpdate] for any other session is rejected with [IllegalArgumentException];
 * - every other update goes on the child's own stream.
 *
 * Until the child's announcement, a [SessionUpdate.SubagentUpdate] for this child, has been sent, every other
 * operation of [client] fails with [IllegalStateException]. Once the child is dropped, because its factory failed or
 * returned before announcing it, or removed (see [AgentSubagents]), every operation fails that way again.
 *
 * @property sessionId the id of the child being built, as passed to [AgentSubagents.announce]
 * @property client the child's client operations
 * @property subagents the child's own subagents, for announcing grandchildren
 */
@UnstableApi
public class AgentSubagentContext internal constructor(
    public val sessionId: SessionId,
    public val client: ClientSessionOperations,
    public val subagents: AgentSubagents,
)

/**
 * **UNSTABLE**
 *
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
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
     * The child's live work after its announcement, as the subagents spec defines it.
     *
     * A [SessionUpdate.SubagentUpdate] for this child goes on the parent's stream, where it patches the child and
     * carries its work state. Every other update goes on the child's own stream. The first item isn't special: the
     * factory has already sent the announcement.
     *
     * The SDK collects this once, in the child's session context, so it should be cold (or hot with replay). It
     * starts after the factory returns or, for a replayed child, after the root's `session/load` response has been
     * accepted by the transport, so everything it sends is live, not history. It may be empty, for example for a child
     * that only has history.
     *
     * The app reports the child's work state itself: `running` and `idle` as it observes work starting and stopping.
     * When it knows that the work failed, or that it stopped after [cancel], it emits `idle` with the `error` or
     * `cancelled` stop reason before the flow ends, and the SDK then sends nothing more. The SDK never reports an
     * outcome on its own: if the flow completes or throws while the last state it emitted is active (`running`,
     * `requires_action` or an unrecognized state), it reports [StateUpdate.Unknown], because the client can no longer
     * observe the child.
     *
     * The SDK cancels the collection, and sends nothing for it, when the child is removed (see [AgentSubagents]) or
     * the connection ends. A feed that ends doesn't stop the collections of the child's own subagents.
     */
    public val events: Flow<SessionUpdate> get() = emptyFlow()

    /**
     * Called on `session/cancel` for this child while the current cancel grant is in effect. Report the outcome
     * through [events]. The SDK does not stop the collection.
     *
     * A cancel that arrives with no grant in effect is ignored. One that arrives with a grant in effect while the
     * child's [AgentSubagentFactory.createSubagent] still runs is held until the factory returns this session, and
     * then delivered once, outside the call to [AgentSubagents.announce], if a grant is still in effect. It is
     * dropped if the factory throws.
     *
     * A grant sent while the root replays is never in effect (see [AgentSubagents]): to enable cancel for a
     * replayed child, send a fresh grant, for example through [events].
     *
     * An exception this method throws is logged and ignored: `session/cancel` is a notification.
     */
    public suspend fun cancel() {}
}

/**
 * **UNSTABLE**
 *
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
 *
 * Builds a child for [AgentSubagents.announce]. Mirrors the client-side
 * [com.agentclientprotocol.client.ClientOperationsFactory].
 */
@UnstableApi
public fun interface AgentSubagentFactory<out S : AgentSubagentSession> {
    /**
     * Builds the child. The call runs in the child's session context, so `currentCoroutineContext().client` is the
     * child's [AgentSubagentContext.client]. The SDK collects the returned session's events afterwards.
     *
     * The factory must send the child's announcement, a [SessionUpdate.SubagentUpdate] whose session id is
     * [AgentSubagentContext.sessionId], through [context] before it returns. Otherwise [AgentSubagents.announce] drops
     * the child and throws. The factory may suspend and start the child's work first: an app that needs something from
     * the work before announcing waits for it here. The announcement needs only the session id, so a state that isn't
     * known yet is left out and sent later as a patch. Everything sent through [context] before this returns goes out
     * before [AgentSubagents.announce] returns.
     *
     * A cancel grant sent from here takes effect at once unless the root replays: a `session/cancel` for the child that
     * arrives before this returns is held for the returned session, see [AgentSubagentSession.cancel].
     *
     * To replay a recorded child, [AgentSession.replayHistory] passes a factory of its own, see [AgentSubagents].
     */
    public suspend fun createSubagent(context: AgentSubagentContext): S
}

/**
 * **UNSTABLE**
 *
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
 *
 * The subagents of one session, a root session or a subagent, for announcing its children to the client. An instance
 * is bound to its session and stays valid for that session's lifetime, not only inside the call that obtained it.
 * There is no update method: patches to a child are ordinary events of the child.
 *
 * ### Removal
 *
 * Every subagent belongs to a root session. The SDK removes every subagent below a root, at every depth, and calls no
 * app code for them:
 * - on `session/close`, after the root's [AgentSession.close] returns and before the response. Stopping their work
 *   and freeing their resources belongs to that `close`. If it throws, the root and its subagents stay registered;
 * - when `session/load` or `session/resume` re-opens a root that is still open, once [AgentSupport.loadSession] or
 *   [AgentSupport.resumeSession] returns, before the replay and the response. The old [AgentSession] isn't closed
 *   and its turn isn't cancelled, so stopping the old subagents' work is up to the app.
 *
 * After the removal:
 * - the subagent's id is free again, and `session/cancel` for it is ignored;
 * - its event collection stops, and its client operations and [announce] fail, as each of them documents; [announce]
 *   of the closed root fails too;
 * - none of its updates follows the close response, but a request it already had in flight, or the
 *   `$/cancel_request` the SDK sends when the removal cancels that call, may still reach the client after it.
 *
 * When the connection ends, the collections stop the same way.
 *
 * ### Replay
 *
 * During the root's `session/load`, until the transport has accepted its response, every subagent announced below
 * the root replays a recorded one, and every update sent for them is history, as the subagents spec defines it: a
 * cancel grant is never in effect, and a state doesn't count as active. If the load fails, including when its
 * response can't be built or sent, every subagent announced during it is removed with the root. Subagents announced
 * at any other time are live.
 *
 * The returned session's [AgentSubagentSession.events] are live: their collection starts once the transport has
 * accepted the load response, before [AgentSession.postInitialize], and their updates are fresh patches to the
 * replayed child. A child that only has history returns empty events.
 *
 * To replay subagents, see [AgentSession.replayHistory].
 */
@UnstableApi
public class AgentSubagents internal constructor(private val bound: SessionWrapper) {
    /**
     * Registers [sessionId] under [parent], calls [factory] with the child's context, then collects the returned
     * session's events. Returns the session once [factory] has returned, after the child's announcement. Returning is
     * the caller's ordering point: an update the parent sends afterwards, such as a `session_message` naming the
     * child, follows the announcement.
     *
     * [parent] defaults to the session this object is bound to. It may name an announced descendant of that session
     * that is still registered, for relays that learn about nested children from a flat event stream.
     *
     * The announcement is a [SessionUpdate.SubagentUpdate] whose session id is [sessionId]. [factory] must send it
     * through [AgentSubagentContext.client] before it returns, and it must be the first thing sent for the child.
     * While the root replays, the events are collected later, see [AgentSubagents].
     *
     * ### Failure
     *
     * Before the announcement is sent, the child is unregistered, so its id is free again, and nothing more is sent:
     * - if [factory] throws, or returns without having sent the announcement, this call throws
     *   [IllegalStateException];
     * - if the caller is cancelled, this call rethrows the [CancellationException].
     *
     * After it is sent, the child stays registered and its events are never collected:
     * - if [factory] throws, the SDK revokes the child's cancel grant and, if the factory left the work state active,
     *   reports [StateUpdate.Unknown], except while the root is replaying. This call rethrows the exception unchanged,
     *   except that a [CancellationException] while the caller is active surfaces as [IllegalStateException], so it
     *   doesn't tear down the caller's scope;
     * - if the caller is cancelled, the SDK does the same, and this call rethrows the [CancellationException].
     *
     * @throws IllegalArgumentException if [parent] is neither the bound session nor a registered, announced
     * descendant of it, or if [sessionId] is already registered on the connection, as a root session or a subagent
     * @throws IllegalStateException if the bound session's root is no longer registered (closed, replaced by a
     * re-opening, or its load failed), if the bound session is a subagent that isn't announced yet, was dropped, or
     * was removed with its root, if the child's announcement could not be sent, or when [factory] fails as described
     * above
     */
    public suspend fun <S : AgentSubagentSession> announce(
        sessionId: SessionId,
        factory: AgentSubagentFactory<S>,
        parent: SessionId? = null,
    ): S {
        val parentWrapper = resolveParent(parent)
        val child = SubagentSessionWrapper(bound.agent, bound.protocol, sessionId, parentWrapper)
        bound.agent.registerSubagent(child)
        return child.announce(factory)
    }

    private fun resolveParent(parent: SessionId?): SessionWrapper {
        /*
         * A child that isn't announced yet must not announce grandchildren: their announcements would put traffic on
         * a stream the client doesn't know.
         */
        if (bound is SubagentSessionWrapper) bound.checkAnnounced()
        if (parent == null || parent == bound.sessionId) return bound

        val candidate = bound.agent.subagentOrNull(parent)
        require(candidate != null && candidate.isAnnounced && candidate.isDescendantOf(bound)) {
            "Parent $parent is neither session ${bound.sessionId} nor an announced subagent below it"
        }
        return candidate
    }
}

/**
 * **UNSTABLE**
 *
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
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
    get() = sessionWrapper("Subagents").subagents
