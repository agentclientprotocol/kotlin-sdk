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
import com.agentclientprotocol.model.AcpCreatedSessionResponse
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
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * The session map of a [Client]: decides which [ClientSessionHolder] owns each session id and routes
 * `session/update` notifications to the holders. Subagents' holders form a tree under their root's.
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
    private data class SessionsStorage(val initializingSessionsCount: Int = 0, val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf())

    private val _sessions = atomic(SessionsStorage())

    /**
     * Returns the holder that owns [sessionId]. While an opening call runs, an unknown id gets a speculative root
     * holder that buffers its early updates. Returns `null` otherwise.
     */
    private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
        // Fast path for the common case of an already registered session.
        _sessions.value.sessions[sessionId]?.takeIf { it.blocksId }?.let { return it }
        var clientSessionHolder: ClientSessionHolder? = null
        /*
         * Look the id up again on every attempt: an opening that registers it and ends in between would otherwise
         * make it look missing.
         */
        _sessions.update { currentStorage ->
            val existingHolder = currentStorage.sessions[sessionId]?.takeIf { it.blocksId }
            when {
                existingHolder != null -> {
                    clientSessionHolder = existingHolder
                    currentStorage
                }
                currentStorage.initializingSessionsCount > 0 -> {
                    // No holder owns the id: create a speculative one. A failed holder it replaces handed its queue on.
                    val newHolder = RootSessionHolder(sessionId, protocol, subagentScope, ::failSessionTree)
                    clientSessionHolder = newHolder
                    currentStorage.copy(sessions = currentStorage.sessions.replacing(sessionId, newHolder))
                }
                else -> {
                    clientSessionHolder = null
                    currentStorage
                }
            }
        }
        return clientSessionHolder
    }

    private fun getOrCreateSessionHolder(sessionId: SessionId): ClientSessionHolder =
        getOrCreateSessionHolderOrNull(sessionId) ?: acpFail("Session $sessionId not found")

    /**
     * Whether [root] still owns its id, so a `session/close` for that id reaches the session that [root] serves.
     */
    fun isOpen(root: RootSessionHolder): Boolean = _sessions.value.sessions[root.sessionId] === root && root.blocksId

    /**
     * Returns the holder of the subagent [sessionId], or `null`. It doesn't suspend, so a request handler can take the
     * holder before its first suspension.
     */
    fun subagentHolderOrNull(sessionId: SessionId): SubagentSessionHolder? =
        (_sessions.value.sessions[sessionId] as? SubagentSessionHolder)?.takeIf { it.blocksId }

    /**
     * Removes [root] and every subagent below it after the agent answered its `session/close`, and closes the
     * subagents. A second removal of [root] removes nothing from the map.
     *
     * @return the ids that were removed from the session map
     */
    suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
        // Sealed top-down, so a late announcement can't add a subagent that this walk misses.
        val subtree = buildList {
            fun visit(holder: ClientSessionHolder) {
                add(holder)
                holder.sealChildren().forEach(::visit)
            }
            visit(root)
        }

        // Running openings are cancelled, not joined, so a subagent's `notify` may still run after this returns.
        val settlesHolder = subtree.filterIsInstance<SubagentSessionHolder>().associateWith(::stopOpening)

        // The agent has already dropped the sessions, so finish even if the caller is cancelled.
        return withContext(NonCancellable) {
            /*
             * A registration adds its child to the parent before its own map update, which checks the root's entry.
             * So a child added before the seal is in the subtree, and is removed here or sees the root gone.
             */
            val previousSessions = _sessions.getAndUpdate { sessionsStorage ->
                val sessions = sessionsStorage.sessions.mutate { sessions ->
                    for (holder in subtree) {
                        if (sessions[holder.sessionId] === holder) sessions.remove(holder.sessionId)
                    }
                }
                sessionsStorage.copy(sessions = sessions)
            }

            for ((holder, settles) in settlesHolder.entries.reversed()) {
                holder.permissions.close()
                // Fail it only if this stop settled it; otherwise its opening finished or an earlier stop settles it.
                if (settles) failHolder(holder, AcpExpectedError("Session ${holder.sessionId} closed")) else holder.markClosed()
            }
            subtree.filter { previousSessions.sessions[it.sessionId] === it }.mapTo(mutableSetOf()) { it.sessionId }
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
     * Returns the session [sessionId] for a request of the agent, waiting until its opening finishes. Only a
     * subagent's holder or a root holder claimed by an opening call is waited for.
     */
    suspend fun awaitSession(sessionId: SessionId): BaseClientSession {
        /*
         * Don't create a holder or wait for an unclaimed one: that wait would last until the last opening ends, and a
         * holder made here would block a later subagent with this id. The Rust SDK fails such a request at once too.
         */
        val holder = when (val registered = _sessions.value.sessions[sessionId]?.takeIf { it.blocksId }) {
            is RootSessionHolder -> registered.takeIf { it.phase.isAtLeast(Stage.CLAIMED) }
            is SubagentSessionHolder -> registered
            null -> null
        } ?: throw JsonRpcException(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, "Session $sessionId not found")
        return holder.session.await()
    }

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

        val registered = parent.childOrNull(update.sessionId)
        if (registered != null) {
            followSubagentState(parent, registered, update)
            return QueuedItem.Patch(update, meta, registered)
        }

        val child = registerChild(parent, update.sessionId) ?: return QueuedItem.Update(update, meta)
        followSubagentState(parent, child, update)
        return QueuedItem.Announcement(update, meta, child)
    }

    /**
     * Feeds a subagent's `state` to its permission requests in wire order, which the parent's consumer can lag behind.
     */
    private fun followSubagentState(parent: ClientSessionHolder, child: SubagentSessionHolder, update: SessionUpdate.SubagentUpdate) {
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
        if (!parent.addChild(child)) return null // also closes its permissions if the root is closing

        val root = parent.root
        var rootEnded = false
        var blocked = false
        _sessions.update { storage ->
            /*
             * Every removal of a root's tree takes the root's entry out no later than its subagents', and the child is
             * already in its parent. So a commit before the removal is removed with the tree, and a later one sees
             * the root gone.
             */
            rootEnded = storage.sessions[root.sessionId] !== root
            blocked = !rootEnded && storage.sessions[childId]?.blocksId == true
            if (rootEnded || blocked) storage else storage.copy(sessions = storage.sessions.replacing(childId, child))
        }

        // The removal or cascade that ended the root fails the child.
        if (rootEnded) return null
        if (blocked) {
            // A cascade may have seen the child meanwhile, but failing a holder outside the map is harmless.
            parent.removeChild(childId)
            logger.warn {
                "Rejected the announcement of subagent session $childId in session ${parent.sessionId}: " +
                    "the id belongs to another session"
            }
            return null
        }
        return child
    }

    /**
     * Claims the holder of [sessionId] for `session/load` or `session/resume`, before the request goes out. Must run
     * under [withInitializingSession], so that the holder exists.
     */
    fun claimSessionHolder(
        sessionId: SessionId,
        replayRequested: Boolean,
        parameters: SessionCreationParameters,
    ): RootSessionHolder {
        val sessionHolder = getOrCreateSessionHolder(sessionId)
        check(sessionHolder is RootSessionHolder && sessionHolder.claimForOpening(replayRequested, parameters)) {
            "Session $sessionId is already open or being opened"
        }
        return sessionHolder
    }

    /**
     * Claims the holder of [sessionId] for `session/new` or `session/fork`, whose answer first names the id, and marks
     * it opened. Returns `null` if the id is taken.
     *
     * It runs in the call's response hook, so it must not block, and under [withInitializingSession].
     */
    fun claimAnsweredSessionHolder(
        sessionId: SessionId,
        response: AcpCreatedSessionResponse,
        parameters: SessionCreationParameters,
    ): RootSessionHolder? {
        val sessionHolder = getOrCreateSessionHolderOrNull(sessionId) as? RootSessionHolder ?: return null
        if (!sessionHolder.claimForOpening(replayRequested = false, parameters)) return null
        sessionHolder.markOpened(response)
        return sessionHolder
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
     * Fails [holder] and every subagent below it, parent first, and hands their queues to [globalSessionUpdateHandler].
     *
     * @return `false` if [holder] is a subagent whose opening had already finished or been stopped, so this failed
     * nothing
     */
    suspend fun failSessionTree(holder: ClientSessionHolder, cause: Throwable): Boolean = withContext(NonCancellable) {
        // Only the stop that settles a subagent's opening may fail its holder, so two failures never drain one queue.
        if (holder is SubagentSessionHolder && !stopOpening(holder)) return@withContext false

        suspend fun failTree(failing: ClientSessionHolder, reason: Throwable) {
            val children = failing.sealChildren()
            failHolder(failing, reason)
            for (child in children) {
                // Skipped with its subtree: its opening finished, or an earlier stop settles it.
                if (!stopOpening(child)) continue
                failTree(child, IllegalStateException("Session ${failing.sessionId}, the parent of subagent session ${child.sessionId}, failed", reason))
            }
        }
        failTree(holder, cause)
        true
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
            OpeningState.Finished, OpeningState.Stopped -> false
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
     * Runs the opening call [block] as an initializing session, so early updates for unknown ids get speculative
     * holders. When the last one ends, the root holders that no opening completed are removed and failed.
     */
    suspend fun <T> withInitializingSession(block: suspend () -> T): T {
        _sessions.update { it.copy(initializingSessionsCount = it.initializingSessionsCount + 1) }
        try {
            return block()
        } finally {
            var hangingSessions: Map<SessionId, ClientSessionHolder>? = null
            _sessions.update { currentStorage ->
                hangingSessions = null
                if (currentStorage.initializingSessionsCount == 0) {
                    logger.error { "Assertion failed: initializingSessionsCount should be positive, got ${currentStorage.initializingSessionsCount}" }
                    return@update currentStorage
                }
                val newCount = currentStorage.initializingSessionsCount - 1
                return@update if (newCount == 0) {
                    /*
                     * No opening runs any more, so an incomplete or failed root holder belongs to no session. Subagent
                     * holders are exempt: they end with their own setup or with their parent.
                     */
                    hangingSessions = currentStorage.sessions.filterValues {
                        it is RootSessionHolder && (!it.session.isCompleted || it.isFailed)
                    }
                    var aliveSessions: PersistentMap<SessionId, ClientSessionHolder> = currentStorage.sessions
                    for ((id, _) in hangingSessions) {
                        aliveSessions = aliveSessions.remove(id)
                    }
                    currentStorage.copy(initializingSessionsCount = newCount, sessions = aliveSessions)
                } else {
                    currentStorage.copy(initializingSessionsCount = newCount)
                }
            }
            val reaped = hangingSessions
            if (reaped != null) {
                for ((id, holder) in reaped) {
                    logger.trace { "Removing hanging session $id" }
                    // No opening claimed it, so its buffered updates are unconnected after all.
                    failSessionTree(holder, AcpExpectedError("Session $id not found"))
                }
                /*
                 * The cascade sealed every holder, so these subtrees are final. Their failed subagents no longer block
                 * their ids, so they leave the map too.
                 */
                if (reaped.isNotEmpty()) {
                    _sessions.update { storage ->
                        storage.copy(sessions = storage.sessions.mutate { map -> reaped.values.forEach { map.removeDescendantsOf(it) } })
                    }
                }
            }
        }
    }

}

/**
 * Puts [holder] under [sessionId]. A replaced holder must no longer block the id; its subagents, which failed with it,
 * leave the map too.
 */
private fun PersistentMap<SessionId, ClientSessionHolder>.replacing(
    sessionId: SessionId,
    holder: ClientSessionHolder,
): PersistentMap<SessionId, ClientSessionHolder> = mutate { map ->
    map[sessionId]?.let { oldHolder -> map.removeDescendantsOf(oldHolder) }
    map[sessionId] = holder
}

private fun MutableMap<SessionId, ClientSessionHolder>.removeDescendantsOf(holder: ClientSessionHolder) {
    holder.forEachDescendant { if (this[it.sessionId] === it) remove(it.sessionId) }
}
