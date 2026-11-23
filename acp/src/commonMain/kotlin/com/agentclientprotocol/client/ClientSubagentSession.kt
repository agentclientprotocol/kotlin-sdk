package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.update
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import kotlinx.atomicfu.atomic

/**
 * **UNSTABLE**
 *
 * Part of the SDK's Kotlin API for client-side subagents. The subagent protocol is stable in v1, but this Kotlin API
 * is unstable. It may change or be removed at any point.
 *
 * A session that the agent started under another session, to do work for it. The client builds it when the agent
 * announces the subagent, from the operations that [SubagentOperations.createSubagentOperations] returned, and it
 * lists it in the parent's [BaseClientSession.subagents]. The `subagents` client capability,
 * [com.agentclientprotocol.model.ClientCapabilities.subagents], enables the announcements. The client doesn't declare
 * it on its own: the application declares it in the [ClientInfo] it passes to [Client.initialize].
 *
 * Inside the parent's [ClientSessionOperations.notify] for the announcement, the subagent is already in the parent's
 * [BaseClientSession.subagents], with its operations and the announced values. The subagent's own stream gets
 * its history, then exactly one [com.agentclientprotocol.common.SessionEvent.Opened] without a response, at the
 * place of the root's answer, then its live updates. The live updates, and the agent's requests for the subagent,
 * wait until the opening call of the root session has completed. A permission request that waits there is still
 * answered `cancelled`, without reaching [operations], if the subagent's work is cancelled or its root is closed
 * meanwhile, see [cancel].
 *
 * The client never prompts a subagent, so there is no prompt, mode, model, configuration or close here. A subagent is
 * closed together with its root session, see [ClientSession.close], whether or not it has finished opening. It is also
 * closed when its stream fails before it has finished opening: when its own [ClientSessionOperations.notify] throws
 * before its [com.agentclientprotocol.common.SessionEvent.Opened], or when the stream of a session above it fails, as
 * when the root's opening fails. Its later updates then go to the [GlobalSessionUpdateHandler] and the agent's
 * requests for it fail. A closed subagent stays in its parent's [BaseClientSession.subagents] with the values it had:
 * [canCancel] is `false`, [cancel] throws, and later `subagent_update`s don't change it. Every update of the subagent reaches its
 * [operations] through [ClientSessionOperations.notify], including `config_option_update` and `current_mode_update`,
 * and none of them changes a root session's mode or options.
 *
 * Everything that changes on this object reaches the application as an event of a stream it already receives:
 * a `subagent_update` in the parent's [ClientSessionOperations.notify], or the [com.agentclientprotocol.common.SessionEvent.Opened]
 * of the parent. So the properties are plain getters, not flows. A flow beside the stream would be a second channel
 * with its own timing: a collector on another thread could see a value before the event that caused it.
 *
 * **Contract:** inside a `notify` of the parent's operations, the getters reflect every event delivered so far,
 * including the current one. Elsewhere they return the latest applied value. Each value is read atomically, so any
 * thread may read them, but values read one after the other may come from different moments. An application that
 * wants a flow keeps its own and updates it from `notify`.
 *
 * @property operations the operations that [SubagentOperations.createSubagentOperations] returned for this subagent
 * @param holder the holder of this subagent's session, which its parent holder registered when the announcement arrived
 * @param protocol the connection that `session/cancel` is sent on
 */
@UnstableApi
public class ClientSubagentSession internal constructor(
    private val holder: SubagentSessionHolder,
    override val operations: ClientSessionOperations,
    private val protocol: Protocol,
) : BaseClientSession {
    private val _title = atomic<String?>(null)
    private val _description = atomic<String?>(null)
    private val ownCwd = atomic<String?>(null)
    private val ownAdditionalDirectories = atomic<List<String>?>(null)
    private val capabilities = atomic<SubagentSessionCapabilities?>(null)
    private val _state = atomic<StateUpdate?>(null)
    private val closed = atomic(false)

    /*
     * The `capabilities` and `state` patches that arrived before the parent's answer, not yet applied. Each one holds
     * the latest patch of its field, `Undefined` if there was none. Only the parent's consumer writes them, in
     * [applyPatch] and [onParentOpened], so they need no atomics.
     */
    private var heldCapabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined
    private var heldState: MaybeUndefined<StateUpdate> = MaybeUndefined.Undefined

    override val sessionId: SessionId get() = holder.sessionId

    /**
     * The id of the session this subagent was started under.
     *
     * There is no parent object, because the parent's [ClientSession] may not exist yet when the subagent is built,
     * while the history of a loading session is being delivered.
     **/
    public val parentSessionId: SessionId get() = holder.parent.sessionId

    /**
     * The title of the subagent, or `null` if the agent reported none or cleared it.
     */
    public val title: String? get() = _title.value

    /**
     * The description of the subagent, or `null` if the agent reported none or cleared it.
     */
    public val description: String? get() = _description.value

    /**
     * The work state the agent last confirmed.
     *
     * `null` means no confirmed current state: before the first state that arrives after the parent's answer, and
     * after the agent reported the state as unconfirmed with `null`, which does not mean idle.
     * [StateUpdate.Unknown] is different: the agent confirmed that it lost track of the subagent's state. There is no
     * last known value, because a state that was replayed from history is never shown as current.
     */
    public val state: StateUpdate? get() = _state.value

    /**
     * Whether [cancel] may be called: the agent granted the cancel control, and this session isn't closed.
     *
     * The latest `capabilities` of the subagent decide it. There is no grant before the agent sends them, and none
     * if the cancel control is omitted or `null`. A [StateUpdate.Unknown] state doesn't revoke the grant. The grant
     * resets when the parent is loaded or resumed again, and a grant that was reported while the history was
     * replayed never takes effect.
     */
    public val canCancel: Boolean get() = !closed.value && capabilities.value?.cancel != null

    override val subagents: List<ClientSubagentSession> get() = holder.subagents

    override val cwd: String get() = ownCwd.value ?: holder.parent.sessionCwd()

    override val additionalDirectories: List<String> get() = ownAdditionalDirectories.value ?: holder.parent.sessionAdditionalDirectories()

    /**
     * Asks the agent to cancel the work of this subagent by sending `session/cancel` for its id.
     *
     * The call returns once the notification is sent. It checks the grant itself, so it never acts on an outdated
     * value of [canCancel].
     *
     * The subagent's pending permission requests are answered `cancelled`, even if sending fails. While the agent last
     * reported foreground work, `running` or `requires_action`, later requests are answered `cancelled` too, without
     * reaching [operations], until the agent reports the next state.
     *
     * @throws IllegalStateException without sending or answering anything, if the agent hasn't granted the cancel
     * control or this session is closed
     */
    public suspend fun cancel() {
        check(!closed.value) { "Subagent session $sessionId is closed" }
        check(capabilities.value?.cancel != null) { "Subagent session $sessionId doesn't allow cancel" }

        try {
            AcpMethod.AgentMethods.V1.SessionCancel(protocol, CancelNotification(sessionId))
        } finally {
            holder.permissions.onClientCancel()
        }
    }

    /**
     * Applies a `subagent_update` of this subagent, in the order the parent's stream delivers it.
     *
     * `title`, `description`, `cwd` and `additionalDirectories` are applied at once: the history may stay visible.
     * `capabilities` and `state` are applied only if [parentOpened], or else held back until [onParentOpened]. For
     * each field, an omitted value leaves it unchanged, `null` clears it, and a value replaces it. `cwd` and
     * `additionalDirectories` fall back to the parent's when cleared, each on its own. `capabilities` is replaced as a
     * whole.
     *
     * Only the parent's consumer calls it, so there is one writer. A closed session ignores it, so it keeps the
     * values it had when it was closed.
     *
     * @param parentOpened whether the parent's [com.agentclientprotocol.common.SessionEvent.Opened] was already delivered
     */
    internal fun applyPatch(update: SessionUpdate.SubagentUpdate, parentOpened: Boolean) {
        if (closed.value) return

        _title.value = update.title.update(_title.value)
        _description.value = update.description.update(_description.value)
        ownCwd.value = update.cwd.update(ownCwd.value)
        ownAdditionalDirectories.value = update.additionalDirectories.update(ownAdditionalDirectories.value)

        if (parentOpened) {
            applyCapabilities(update.capabilities)
            applyState(update.state)
        } else {
            heldCapabilities = update.capabilities.orElse(heldCapabilities)
            heldState = update.state.orElse(heldState)
        }
    }

    /**
     * Resolves the `capabilities` and `state` that [applyPatch] held back, when the parent's
     * [com.agentclientprotocol.common.SessionEvent.Opened] is delivered.
     *
     * @param promote `true` after a call that replayed no history: the held values were current, so they are applied.
     * `false` after a load: they were history, and they are dropped. A closed session drops them too.
     */
    internal fun onParentOpened(promote: Boolean) {
        if (promote && !closed.value) {
            applyCapabilities(heldCapabilities)
            applyState(heldState)
        }
        heldCapabilities = MaybeUndefined.Undefined
        heldState = MaybeUndefined.Undefined
    }

    /**
     * Ends this session, because its holder failed or its root was closed: [canCancel] becomes `false`, [cancel]
     * throws, and later patches are not applied.
     */
    internal fun markClosed() {
        closed.value = true
    }

    private fun applyCapabilities(patch: MaybeUndefined<SubagentSessionCapabilities>) {
        capabilities.value = patch.update(capabilities.value)
    }

    private fun applyState(patch: MaybeUndefined<StateUpdate>) {
        _state.value = patch.update(_state.value)
    }
}
