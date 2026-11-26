package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.internal.SubagentSessionHolder
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
 * announces the subagent, from the operations that [SubagentOperations.createSubagentOperations] returned, and lists
 * it in the parent's [BaseClientSession.subagents]. The agent announces subagents only if the application declares
 * the `subagents` client capability, [com.agentclientprotocol.model.ClientCapabilities.subagents], in the
 * [ClientInfo] it passes to [Client.initialize]. The client doesn't declare it on its own.
 *
 * The client never prompts a subagent, so there is no prompt, mode, model, configuration or close here. Every update
 * of the subagent, `config_option_update` and `current_mode_update` included, reaches its [operations] through
 * [ClientSessionOperations.notify], and none of them changes a root session's mode or options.
 *
 * ### Stream
 *
 * Inside the parent's [ClientSessionOperations.notify] for the announcement, the subagent is already in the parent's
 * [BaseClientSession.subagents], with its operations and the announced values. The subagent's own stream then gets:
 * - its history;
 * - exactly one [com.agentclientprotocol.common.SessionEvent.Opened] without a response, at the place of the root's
 *   answer. Nothing orders it against the parent's `Opened`, and by then the subagent's own
 *   [BaseClientSession.subagents] lists what its history announced;
 * - its live updates.
 *
 * The live updates, and the agent's requests for the subagent, wait until the opening call of the root session has
 * completed. A permission request that waits there is still answered `cancelled`, without reaching [operations], if
 * the subagent's work is cancelled meanwhile, by [cancel] or by an `idle` state with `cancelled`, or if its root is
 * closed.
 *
 * ### Closing
 *
 * A subagent is closed:
 * - with its root session, see [ClientSession.close], whether or not it has finished opening;
 * - when its own [ClientSessionOperations.notify] throws before its `Opened` is delivered, or while it is;
 * - when the stream of a session above it fails, as when the root's opening fails.
 *
 * Its later updates then go to the [GlobalSessionUpdateHandler], and the agent's requests for it fail. A closed
 * subagent stays in its parent's [BaseClientSession.subagents] with the values it had: [canCancel] is `false`,
 * [cancel] throws, and later `subagent_update`s don't change it.
 *
 * ### Reading the properties
 *
 * The getters return the values the client has received from the agent so far. Every change reaches the application
 * as an event of a stream it already receives: a `subagent_update` in the parent's [ClientSessionOperations.notify],
 * or the parent's `Opened`. Inside a `notify` of the parent's operations the getters match the parent's stream: they
 * reflect every event delivered so far, including the current one.
 *
 * The properties are plain getters, not flows, because a flow beside the stream could show a value before the event
 * that caused it. An application that wants a flow keeps its own and updates it from `notify`. Each value is read
 * atomically, so any thread may read them, but values read one after the other may come from different moments.
 *
 * During a turn, a root's updates come from the buffered [ClientSession.prompt] flow, so the getters may be ahead of
 * it. To show what an update changed, read the update, which carries the patch. A subagent that an update from the
 * flow names is already in the parent's [BaseClientSession.subagents], unless its setup failed.
 *
 * @property operations the operations that [SubagentOperations.createSubagentOperations] returned for this subagent
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
     * The latest `capabilities` and `state` patches held back until the parent's `Opened`, `Undefined` if none. Only
     * the parent's consumer writes them, so they need no atomics.
     */
    private var heldCapabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined
    private var heldState: MaybeUndefined<StateUpdate> = MaybeUndefined.Undefined

    override val sessionId: SessionId get() = holder.sessionId

    /**
     * The id of the session this subagent was started under.
     *
     * There is no parent object, because the parent's [ClientSession] may not exist yet when the subagent is built,
     * while the history of a loading session is being delivered.
     */
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
     * `null` means no confirmed current state: before the first state takes effect, and after the agent reported the
     * state as unconfirmed with `null`, which does not mean idle. [StateUpdate.Unknown] is different: the agent
     * confirmed that it lost track of the subagent's state.
     *
     * A state that arrives before the root's answer takes effect at the parent's
     * [com.agentclientprotocol.common.SessionEvent.Opened] if the root's opening call replayed no history, and is
     * dropped after a load: a replayed state is never shown as current.
     */
    public val state: StateUpdate? get() = _state.value

    /**
     * Whether [cancel] may be called: the agent granted the cancel control, and this session isn't closed.
     *
     * The latest `capabilities` of the subagent decide it. There is no grant before the agent sends them, and none
     * if the cancel control is omitted or `null`. A [StateUpdate.Unknown] state doesn't revoke the grant.
     *
     * A load or resume of the root builds new subagent objects, which start without a grant. A grant that arrives
     * before the root's answer takes effect at the parent's `Opened` if the root's opening replayed no history, and
     * never after a load.
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
     * Applies a `subagent_update` of this subagent, unless it is closed.
     *
     * The descriptive fields apply at once, so values replayed from history stay visible. `capabilities` and `state`
     * are held back until [onParentOpened] unless [parentOpened].
     *
     * @param parentOpened whether the parent's `Opened` was already delivered
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
     * Applies or drops the `capabilities` and `state` that [applyPatch] held back, when the parent's `Opened` is
     * delivered.
     *
     * @param promote `true` when the root's opening replayed no history, so the held values are current; `false` after
     * a load, so they are history and dropped
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
     * Marks this session closed, when its holder fails or its root is closed.
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
