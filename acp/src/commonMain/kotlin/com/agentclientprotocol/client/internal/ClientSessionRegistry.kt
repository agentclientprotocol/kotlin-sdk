@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.GlobalSessionUpdateHandler
import com.agentclientprotocol.client.internal.RootSessionHolder.Stage
import com.agentclientprotocol.client.internal.SubagentSessionHolder.OpeningState
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.atomicfu.updateAndGet
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * The session map of a [Client]: decides which [ClientSessionHolder] owns each session id and routes
 * `session/update` notifications to the holders. Subagents' holders form a tree under their root's, which only this
 * map records.
 *
 * @param globalSessionUpdateHandler receives the updates for sessions that this client is not connected to
 * @param protocol the connection, for the subagents' `session/cancel`
 * @param subagentScope the scope of the subagents' openings, which belongs to the connection
 */
internal class ClientSessionRegistry(
    private val globalSessionUpdateHandler: GlobalSessionUpdateHandler?,
    private val protocol: Protocol,
    private val subagentScope: CoroutineScope,
) {
    /**
     * The registry's state in one value, so each rule that spans the tree, the map and a close is one update.
     *
     * @property sessions the holder that owns each id. It iterates in insertion order, so parents come before their
     * children.
     * @property failed the holders in the map that are failing or failed, and so take no children
     * @property closing the roots in the map whose close has started; a failed close leaves its root here
     */
    private data class SessionsStorage(
        val openingCallsCount: Int = 0,
        val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf(),
        val failed: PersistentSet<ClientSessionHolder> = persistentSetOf(),
        val closing: PersistentSet<RootSessionHolder> = persistentSetOf(),
    ) {
        fun owns(holder: ClientSessionHolder): Boolean = sessions[holder.sessionId] === holder

        fun childOrNull(parent: ClientSessionHolder, id: SessionId): SubagentSessionHolder? =
            (sessions[id] as? SubagentSessionHolder)?.takeIf { it.parent === parent }

        fun childrenOf(parent: ClientSessionHolder): List<SubagentSessionHolder> =
            sessions.values.filterIsInstance<SubagentSessionHolder>().filter { it.parent === parent }

        /**
         * The subagents below [root], failed ones included, parents first. It still finds them after the reap has
         * removed the root's own entry.
         */
        fun descendantsOf(root: RootSessionHolder): List<SubagentSessionHolder> =
            sessions.values.filterIsInstance<SubagentSessionHolder>().filter { it.root === root }

        /**
         * Without [root] and its descendants. The root's entry goes only if it is still [root]'s.
         */
        fun withoutTree(root: RootSessionHolder): SessionsStorage {
            val descendants = descendantsOf(root)
            return copy(
                sessions = sessions.mutate { map ->
                    if (map[root.sessionId] === root) map.remove(root.sessionId)
                    for (holder in descendants) map.remove(holder.sessionId)
                },
                failed = failed.removeAll { it.root === root },
                closing = closing.remove(root),
            )
        }

        /**
         * The root holders that no opening completed. Called only as the last opening ends, so no opening can complete
         * one meanwhile.
         */
        fun hangingRoots(): List<RootSessionHolder> =
            sessions.values.filterIsInstance<RootSessionHolder>().filter { !it.session.isCompleted }
    }

    private val _sessions = atomic(SessionsStorage())

    /**
     * Returns the holder that owns [sessionId]. While an opening call runs, an unknown id gets a speculative root
     * holder that buffers its early updates. Returns `null` otherwise.
     */
    private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
        val current = _sessions.value
        // Fast path for the common case of an already registered session.
        current.sessions[sessionId]?.let { return it }
        if (current.openingCallsCount == 0) return null

        // Built at most once, however often the update reruns.
        val speculative by lazy(LazyThreadSafetyMode.NONE) {
            RootSessionHolder(sessionId, protocol, subagentScope, ::failSubagentTree, ::descendantsOf)
        }
        return _sessions.updateAndGet { storage ->
            if (sessionId in storage.sessions || storage.openingCallsCount == 0) storage
            else storage.copy(sessions = storage.sessions.put(sessionId, speculative))
        }.sessions[sessionId]
    }

    /**
     * Returns the subagent [id] registered under [parent], failed or not, or `null`.
     */
    fun childOrNull(parent: ClientSessionHolder, id: SessionId): SubagentSessionHolder? = _sessions.value.childOrNull(parent, id)

    private fun descendantsOf(root: RootSessionHolder): List<SubagentSessionHolder> = _sessions.value.descendantsOf(root)

    /**
     * Whether [root] still owns its id, so a `session/close` for that id reaches the session that [root] serves. A
     * failed root owns nothing, though it stays in the map until its cascade ends.
     */
    fun isOpen(root: RootSessionHolder): Boolean = _sessions.value.owns(root) && !root.isFailed

    /**
     * Marks [root] closing, then answers the current and later permission requests of the root and of every subagent
     * below it with `cancelled`. The root stays closing if the close fails, and a second call changes nothing.
     *
     * @throws IllegalStateException if the root isn't answered yet
     */
    fun markClosing(root: RootSessionHolder) {
        // An answer is final, so this check can't go stale.
        check(root.phase.isAtLeast(Stage.ANSWERED)) { "Root session ${root.sessionId} is closed before its answer" }
        val before = _sessions.getAndUpdate { storage ->
            if (storage.owns(root)) storage.copy(closing = storage.closing.add(root)) else storage
        }

        // A subagent registered after the update sees the mark and closes its own permissions.
        root.permissions.close()
        for (holder in before.descendantsOf(root)) holder.permissions.close()
    }

    /**
     * Removes [root] and every subagent below it after the agent answered its `session/close`, and closes the
     * subagents. A second removal of [root] removes nothing from the map.
     *
     * @return the ids that were removed from the session map
     */
    suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
        // A registration after this update sees the root gone.
        val before = _sessions.getAndUpdate { it.withoutTree(root) }
        val subagents = before.descendantsOf(root)

        root.sealSubagents()
        for (holder in subagents) holder.sealSubagents()
        // Running openings are cancelled, not joined, so a subagent's `notify` may still run after this returns.
        val settlesHolder = subagents.associateWith(::stopOpening)

        // The agent has already dropped the sessions, so finish even if the caller is cancelled.
        return withContext(NonCancellable) {
            val subagentsBottomUp = settlesHolder.entries.reversed()
            for ((holder, settles) in subagentsBottomUp) {
                holder.permissions.close()
                // Fail it only if this stop settled it; otherwise its opening or an earlier stop settles it.
                if (settles) failHolder(holder, AcpExpectedError("Session ${holder.sessionId} closed")) else holder.markClosed()
            }
            buildSet {
                if (before.owns(root)) add(root.sessionId)
                subagents.mapTo(this) { it.sessionId }
            }
        }
    }

    /**
     * Returns the opened root session [sessionId], or throws [IllegalStateException].
     */
    fun getRootSession(sessionId: SessionId): ClientSession {
        val sessionHolder = _sessions.value.sessions[sessionId] ?: error("Session $sessionId not found")
        if (!sessionHolder.session.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return sessionHolder.session.getCompleted() as? ClientSession ?: error("Session $sessionId is a subagent session")
    }

    /**
     * Returns the holder that serves a request of the agent for [sessionId]: a subagent's holder or a root holder
     * claimed by an opening call. It doesn't suspend, so a request handler can take the holder before its first
     * suspension.
     *
     * @throws JsonRpcException with `-32002` if no such holder owns the id
     */
    fun holderForRequest(sessionId: SessionId): ClientSessionHolder =
        when (val registered = _sessions.value.sessions[sessionId]) {
            /*
             * Don't create a holder or serve an unclaimed one: a wait for it would last until the last opening
             * ends, and a holder made here would block a later subagent with this id. The Rust SDK fails such a
             * request at once too.
             */
            is RootSessionHolder -> registered.takeIf { it.phase.isAtLeast(Stage.CLAIMED) }
            is SubagentSessionHolder -> registered
            null -> null
        } ?: throw JsonRpcException(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, "Session $sessionId not found")

    /**
     * Returns the session [sessionId] for a request of the agent, waiting until its opening finishes.
     */
    suspend fun awaitSession(sessionId: SessionId): BaseClientSession = holderForRequest(sessionId).session.await()

    /**
     * Returns the session [sessionId] if its opening has succeeded, or `null`. Never suspends.
     */
    fun completedSessionOrNull(sessionId: SessionId): BaseClientSession? {
        val session = _sessions.value.sessions[sessionId]?.session ?: return null
        @OptIn(ExperimentalCoroutinesApi::class)
        return if (session.isCompleted && session.getCompletionExceptionOrNull() == null) session.getCompleted() else null
    }

    fun isSessionCompleted(sessionId: SessionId): Boolean {
        val holder = _sessions.value.sessions[sessionId]
        return holder != null && holder.session.isCompleted
    }

    /**
     * Delivers a `session/update` to the holder of [sessionId], or to [globalSessionUpdateHandler] if this client is
     * not connected to the session.
     */
    suspend fun deliverSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        /*
         * An update for a session this client isn't connected to, such as a `session/list` status change, is legal, so
         * it must not fail.
         *
         * The lookup and the sorting must not suspend: `Protocol` handles the agent's next message once this handler
         * first suspends, and a subagent announced here must be registered by then.
         */
        val holder = getOrCreateSessionHolderOrNull(sessionId) ?: return handleUnconnectedSessionUpdate(sessionId, update, _meta)
        val item = queuedItemFor(holder, update, _meta)
        // The holder may have failed or been reaped since the lookup.
        if (!holder.handleOrQueue(item)) {
            handleUnconnectedSessionUpdate(sessionId, update, _meta)
        }
    }

    /**
     * Sorts [update] for the queue of [parent] when it arrives, and registers the subagent that a `subagent_update`
     * announces.
     */
    private fun queuedItemFor(parent: ClientSessionHolder, update: SessionUpdate, meta: JsonElement?): QueuedItem {
        if (update !is SessionUpdate.SubagentUpdate) return QueuedItem.Update(update, meta)

        val registered = childOrNull(parent, update.sessionId)
        if (registered != null) {
            feedSubagentPermissions(parent, registered, update)
            return QueuedItem.Patch(update, meta, registered)
        }

        val child = registerChild(parent, update.sessionId) ?: return QueuedItem.Update(update, meta)
        feedSubagentPermissions(parent, child, update)
        return QueuedItem.Announcement(update, meta, child)
    }

    /**
     * Feeds a subagent's `state` to its permission requests in wire order, which the parent's consumer can lag behind.
     */
    private fun feedSubagentPermissions(parent: ClientSessionHolder, child: SubagentSessionHolder, update: SessionUpdate.SubagentUpdate) {
        if (update.state != MaybeUndefined.Undefined) {
            child.permissions.onState(update.state.valueOrNull(), live = parent.root.phase.isAtLeast(Stage.ANSWERED))
        }
    }

    /**
     * Registers the holder of the subagent [childId] that the agent announced in the stream of [parent].
     *
     * @return the child's holder, or `null` if it didn't enter the session map, so the announcement is a plain update
     */
    private fun registerChild(parent: ClientSessionHolder, childId: SessionId): SubagentSessionHolder? {
        val child = SubagentSessionHolder(childId, parent) // takes the root's opened marker if answered
        val root = parent.root
        fun SessionsStorage.parentLive() = owns(root) && parent !in failed
        fun SessionsStorage.accepts() = parentLive() && childId !in sessions

        val before = _sessions.getAndUpdate { storage ->
            if (storage.accepts()) storage.copy(sessions = storage.sessions.put(childId, child)) else storage
        }
        if (!before.accepts()) {
            // Only a taken id is the agent's mistake; a child of an ended parent is just dropped.
            if (before.parentLive()) {
                logger.warn {
                    "Rejected the announcement of subagent session $childId in session ${parent.sessionId}: " +
                        "the id belongs to another session"
                }
            }
            return null
        }

        /*
         * This runs on the read loop before the next message, so no permission request of the child reaches the app
         * first.
         */
        if (root in before.closing) child.permissions.close()
        return child
    }

    /**
     * Claims the holder of [sessionId] for `session/load` or `session/resume`, before the request goes out. Must run
     * under [withOpeningCall], so that the holder exists.
     */
    fun claimBeforeRequest(
        sessionId: SessionId,
        replayRequested: Boolean,
        parameters: SessionCreationParameters,
    ): RootSessionHolder {
        val sessionHolder = getOrCreateSessionHolderOrNull(sessionId) ?: acpFail("Session $sessionId not found")
        check(sessionHolder is RootSessionHolder && sessionHolder.claimForOpening(replayRequested, parameters)) {
            "Session $sessionId is already open or being opened"
        }
        return sessionHolder
    }

    /**
     * Claims the holder of [sessionId] for `session/new` or `session/fork`, whose answer first names the id. Returns
     * `null` if the id is taken.
     *
     * It runs in the call's response hook, so it must not block, and under [withOpeningCall].
     */
    fun claimOnAnswer(sessionId: SessionId, parameters: SessionCreationParameters): RootSessionHolder? {
        val sessionHolder = getOrCreateSessionHolderOrNull(sessionId) as? RootSessionHolder ?: return null
        return sessionHolder.takeIf { it.claimForOpening(replayRequested = false, parameters) }
    }

    private suspend fun handleUnconnectedSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        val handler = globalSessionUpdateHandler
        if (handler != null) {
            handler.onUnconnectedSessionUpdate(sessionId, update, _meta)
        } else {
            logger.debug { "Ignoring session/update for session $sessionId: client is not connected to it" }
        }
    }

    /**
     * Fails [root] and every subagent below it, then removes them from the session map, so a failed root owns no id
     * once this returns.
     */
    suspend fun failRootTree(root: RootSessionHolder, cause: Throwable): Unit = withContext(NonCancellable) {
        failTree(root, cause)
        // Removed after the drains, so an update that arrives during them still follows the queued ones.
        _sessions.update { it.withoutTree(root) }
    }

    /**
     * Fails the subagent [child] and every subagent below it. They keep their ids until their root's tree is removed.
     *
     * @return `false` if the opening of [child] was already completing or stopped, so this failed nothing
     */
    suspend fun failSubagentTree(child: SubagentSessionHolder, cause: Throwable): Boolean = withContext(NonCancellable) {
        // Only the stop that settles a subagent's opening may fail its holder, so two failures never drain one queue.
        if (!stopOpening(child)) return@withContext false
        failTree(child, cause)
        true
    }

    /**
     * Fails [failing] and every subagent below it, parent first, and hands their queues to [globalSessionUpdateHandler].
     * The caller must run this under [NonCancellable].
     */
    private suspend fun failTree(failing: ClientSessionHolder, reason: Throwable) {
        // A holder outside the map needs no mark: its root's entry is gone, so nothing registers under it.
        val before = _sessions.getAndUpdate { storage ->
            if (storage.owns(failing)) storage.copy(failed = storage.failed.add(failing)) else storage
        }
        val children = before.childrenOf(failing)
        failing.sealSubagents()
        failHolder(failing, reason)
        for (child in children) {
            // Skipped with its subtree: its opening completes it, or an earlier stop settles it.
            if (!stopOpening(child)) continue
            failTree(child, IllegalStateException("Session ${failing.sessionId}, the parent of subagent session ${child.sessionId}, failed", reason))
        }
    }

    /**
     * Stops the opening of [holder], cancelling it if it runs, and returns whether the caller must now fail the holder.
     */
    private fun stopOpening(holder: SubagentSessionHolder): Boolean =
        when (val replaced = holder.markOpeningStopped()) {
            is OpeningState.Running -> {
                // Not joined: the caller must not wait for the app's `notify`.
                replaced.job.cancel()
                true
            }
            OpeningState.NotStarted -> true
            OpeningState.Completing, OpeningState.Stopped -> false
        }

    /**
     * Fails [holder] alone and hands its queue to [globalSessionUpdateHandler]. The caller must have stopped a
     * subagent's opening first, and must run this under [NonCancellable].
     */
    private suspend fun failHolder(holder: ClientSessionHolder, reason: Throwable) {
        if (holder is SubagentSessionHolder) holder.markClosed()
        // A failing handler must not stop the drain or replace the caller's failure.
        holder.failAndDrainQueue(reason) { update, meta ->
            runCatching { handleUnconnectedSessionUpdate(holder.sessionId, update, meta) }
                .onFailure { logger.error(it) { "Unconnected session update handler failed for ${holder.sessionId}" } }
        }
    }

    /**
     * Runs the opening call [block], so that while it runs, early updates for unknown ids get speculative holders.
     * When the last one ends, the root holders that no opening completed are removed and failed.
     */
    suspend fun <T> withOpeningCall(block: suspend () -> T): T {
        _sessions.update { it.copy(openingCallsCount = it.openingCallsCount + 1) }
        try {
            return block()
        } finally {
            val before = _sessions.getAndUpdate { storage ->
                when (storage.openingCallsCount) {
                    0 -> storage
                    /*
                     * No opening runs any more, so an incomplete root holder belongs to no session. Subagent holders
                     * are exempt: they end with their own setup or with their parent.
                     */
                    1 -> storage.copy(
                        openingCallsCount = 0,
                        sessions = storage.sessions.mutate { map -> storage.hangingRoots().forEach { map.remove(it.sessionId) } },
                    )
                    else -> storage.copy(openingCallsCount = storage.openingCallsCount - 1)
                }
            }
            if (before.openingCallsCount == 0) {
                logger.error { "Assertion failed: openingCallsCount should be positive, got 0" }
            }
            /*
             * The same roots that the update removed: a root out of the map can't be claimed, so its completion can't
             * change meanwhile.
             */
            val hangingSessions = if (before.openingCallsCount == 1) before.hangingRoots() else emptyList()
            for (holder in hangingSessions) {
                logger.trace { "Removing hanging session ${holder.sessionId}" }
                // No opening claimed it, so its buffered updates are unconnected after all.
                failRootTree(holder, AcpExpectedError("Session ${holder.sessionId} not found"))
            }
        }
    }

}
