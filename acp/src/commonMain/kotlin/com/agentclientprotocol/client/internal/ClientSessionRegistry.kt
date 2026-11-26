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
 * The session map of a [Client]: owns the rules about which [ClientSessionHolder] owns a session id, and delivers
 * `session/update` notifications to the holders. It registers the subagent sessions that the agent announces, as a
 * tree of holders under their roots, and removes a root's tree when the root is closed.
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
     * Looks up the holder for [sessionId], creating a new entry only if there are some currently initializing
     * sessions. Returns `null` if the session is neither registered nor being initialized, instead of throwing -
     * callers that need a session to exist should use [getOrCreateSessionHolder] instead. Requests of the agent
     * don't come here, see [awaitSession].
     *
     * A failed holder counts as missing and is replaced while sessions initialize, so a retry of a failed opening
     * gets a fresh holder, unless it still blocks its id, see [ClientSessionHolder.blocksId]. Dropping a failed holder
     * loses nothing, because [failSessionTree] drains it at once, and its subagents leave the map with it, see
     * [replacing]. A subagent's id never gets a speculative holder: it has its own while its parent lives.
     */
    private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
        // Fast path for the common case of an already registered session.
        _sessions.value.sessions[sessionId]?.takeIf { it.blocksId }?.let { return it }
        var clientSessionHolder: ClientSessionHolder? = null
        /*
         * Every branch below has to look the session up in `currentStorage` rather than rely on the read above:
         * `_sessions` can change between that read and the CAS, and it can change again between two attempts of
         * the CAS loop. A session that a concurrent newSession/loadSession registers in such a window - together
         * with decrementing `initializingSessionsCount` back to zero - would otherwise be reported as missing.
         */
        _sessions.update { currentStorage ->
            val existingHolder = currentStorage.sessions[sessionId]?.takeIf { it.blocksId }
            when {
                existingHolder != null -> {
                    clientSessionHolder = existingHolder
                    currentStorage
                }
                currentStorage.initializingSessionsCount > 0 -> {
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

    /**
     * Creates a new entry only if there are some currently initializing sessions. Otherwise, throws in the case of
     * missing session.
     */
    private fun getOrCreateSessionHolder(sessionId: SessionId): ClientSessionHolder =
        getOrCreateSessionHolderOrNull(sessionId) ?: acpFail("Session $sessionId not found")

    /**
     * Whether [root] is the registered holder of its id and blocks it, so the session that [root] serves is still
     * open on the agent and its close may be sent. While that holds no other opening of the id can start, see
     * [RootSessionHolder.claimForOpening], so a close of [root] reaches no other session.
     */
    fun isOpen(root: RootSessionHolder): Boolean = _sessions.value.sessions[root.sessionId] === root && root.blocksId

    /**
     * Returns the holder of the subagent [sessionId], or `null` if the id has no subagent holder. Doesn't suspend or
     * create a holder, so a request handler can join the subagent's race before anything else changes its state, and
     * then wait for this holder rather than for whatever the id maps to later.
     */
    fun subagentHolderOrNull(sessionId: SessionId): SubagentSessionHolder? =
        (_sessions.value.sessions[sessionId] as? SubagentSessionHolder)?.takeIf { it.blocksId }

    /**
     * Removes [root] and every subagent below it, after the agent answered its `session/close`.
     *
     * A second removal of [root], after a second close that was sent while the first was in flight, removes nothing
     * from the map.
     *
     * ### Steps
     *
     * 1. Each holder is sealed, top-down, so a late announcement registers no subagent that no close would reach.
     * 2. Every subagent's opening is stopped, see [stopOpening], and a running one is cancelled without being joined:
     *    - no opening starts afterwards;
     *    - an opening that hadn't claimed its completion never completes its holder;
     *    - one that the stop finds [OpeningState.Finished] had claimed it, and completes its holder right after, if it
     *      hasn't yet.
     *
     *    A cancelled opening's `notify`, or a `createSubagentOperations` it runs, may still run after this returns. It
     *    checks for cancellation before it takes each item, so it hands that `notify` at most the one item it took
     *    before the cancellation, and it doesn't fail its holder.
     * 3. The holders leave the session map in one compare-and-set, each only if its id still maps to it. So no
     *    half-removed tree is visible, and a subagent registered after it stays out of the map.
     * 4. Bottom-up, for each subagent:
     *    - its permission requests are answered `cancelled`, which changes nothing if [RootSessionHolder.startClosing]
     *      ran first;
     *    - its session object is closed;
     *    - its holder fails with a "closed" error if the stop in step 2 settles it, because it found the opening
     *      running or not started, so its waiting requests get the error and its queue goes to
     *      [globalSessionUpdateHandler]. A finished opening completes the holder instead, and one stopped earlier, by a
     *      failure or another removal, leaves the holder to whoever stopped it.
     *
     * ### Cancellation
     *
     * Steps 3 and 4 run under [NonCancellable], so the removal finishes even if the caller is cancelled, because the
     * agent has already dropped the sessions. Nothing before them suspends.
     *
     * @return the ids that were removed from the session map
     */
    suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
        val subtree = buildList {
            fun visit(holder: ClientSessionHolder) {
                add(holder)
                holder.sealChildren().forEach(::visit)
            }
            visit(root)
        }

        val settlesHolder = subtree.filterIsInstance<SubagentSessionHolder>().associateWith(::stopOpening)

        return withContext(NonCancellable) {
            /*
             * A registration adds its child to the parent before it touches the map, and checks the root's entry inside
             * its own compare-and-set of the map. So either:
             * - it added the child before the walk above sealed the parent. The child is in the subtree, and its map
             *   update lands before this one, which removes it, or after it, and sees the root gone;
             * - or it tried after the seal, and the add was refused.
             *
             * The two compare-and-sets can't miss each other, because every update allocates a new storage.
             */
            val previousSessions = _sessions.getAndUpdate { sessionsStorage ->
                val sessions = sessionsStorage.sessions.mutate { sessions ->
                    for (holder in subtree) {
                        if (sessions[holder.sessionId] === holder) sessions.remove(holder.sessionId)
                    }
                }
                sessionsStorage.copy(sessions = sessions)
            }

            // Bottom-up, so each holder fails with its own error rather than with its parent's.
            for (holder in subtree.asReversed()) {
                if (holder !is SubagentSessionHolder) continue
                holder.permissions.close()
                holder.markClosed()
                if (settlesHolder.getValue(holder)) {
                    failStoppedTree(holder, AcpExpectedError("Session ${holder.sessionId} closed"))
                }
            }
            subtree.filter { previousSessions.sessions[it.sessionId] === it }.mapTo(mutableSetOf()) { it.sessionId }
        }
    }

    /**
     * Returns the opened session [sessionId], without creating a holder.
     *
     * @throws IllegalStateException if the session is not registered or not initialized yet, or is a subagent session
     */
    fun getRootSession(sessionId: SessionId): ClientSession {
        val sessionHolder = _sessions.value.sessions[sessionId] ?: error("Session $sessionId not found")
        if (!sessionHolder.session.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return sessionHolder.session.getCompleted() as? ClientSession ?: error("Session $sessionId is a subagent session")
    }

    /**
     * Returns the session [sessionId] for a request of the agent, suspending until its opening finishes. Reads the
     * session map once and never changes it.
     *
     * The registered holder of the id decides:
     * - a holder whose session is complete: the call returns the session, or throws the holder's failure;
     * - a root holder claimed by an opening call, by `session/load` or `session/resume` before the request or by
     *   `session/new` or `session/fork` in the response hook, and a subagent holder: the call waits for the holder,
     *   and throws its failure if the opening fails or the session is closed before it finished opening;
     * - no holder, or a root holder that no opening has claimed, which an early update created: the call fails at
     *   once.
     *
     * A root's session completes only after its claim, and a failed root doesn't keep its id, so the first case needs
     * no check of its own.
     *
     * @throws JsonRpcException with [JsonRpcErrorCode.RESOURCE_NOT_FOUND] in the last case
     */
    suspend fun awaitSession(sessionId: SessionId): BaseClientSession {
        /*
         * A request doesn't create a holder, and doesn't wait for an opening that hasn't claimed the id: the Rust SDK
         * answers such a request at once too. A wait would have no bound, since an unclaimed holder is reaped only when
         * the last opening on the connection ends, and a holder created for a request would block a later announcement
         * of a subagent with that id.
         */
        val holder = when (val registered = _sessions.value.sessions[sessionId]?.takeIf { it.blocksId }) {
            is RootSessionHolder -> registered.takeIf { it.phase.isAtLeast(Stage.CLAIMED) }
            is SubagentSessionHolder -> registered
            null -> null
        } ?: throw JsonRpcException(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, "Session $sessionId not found")
        return holder.session.await()
    }

    /**
     * Whether the holder of [sessionId] exists and its session is completed, with a session or with a failure.
     */
    fun isSessionCompleted(sessionId: SessionId): Boolean {
        val holder = _sessions.value.sessions[sessionId]
        return holder != null && holder.session.isCompleted
    }

    /**
     * Delivers a `session/update` to the holder of [sessionId], or to [globalSessionUpdateHandler] if this client is
     * not connected to the session. A `subagent_update` that announces a subagent registers its holder.
     */
    suspend fun deliverSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        /*
         * The agent may report an update (e.g., a status change on `session/list`) for a session this client
         * never called `session/new` / `session/load` / `session/resume` for. That's not a protocol violation, so
         * unlike other session-scoped methods, an unknown/unconnected session here must not fail the call.
         * A speculative holder can also be reaped by `withInitializingSession` after we found it here.
         *
         * The lookup and the sorting are not `suspend`, and must stay that way: `Protocol` runs a notification handler
         * only until its first suspension before it handles the next message. So a subagent is registered before the
         * agent's next message for it is handled, even while the parent's `notify` runs app code. Otherwise that
         * message would count as unconnected, or, for a request, fail with "session not found".
         */
        val holder = getOrCreateSessionHolderOrNull(sessionId) ?: return handleUnconnectedSessionUpdate(sessionId, update, _meta)
        val item = queuedItemFor(holder, update, _meta)
        if (!holder.handleOrQueue(item)) {
            handleUnconnectedSessionUpdate(sessionId, update, _meta)
        }
    }

    /**
     * Tags [update] in the stream of [parent] when it arrives: a `subagent_update` of a subagent registered under
     * [parent] is a patch, one that announces a new id is an announcement and registers it, and every other update,
     * and a rejected announcement, is a plain update. The parent's consumer acts on the tag later.
     *
     * A subagent's permission requests follow the `state` of its patches here, in wire order, because the parent's
     * consumer can lag behind them by as long as the application's `notify` takes.
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

    private fun followSubagentState(parent: ClientSessionHolder, child: SubagentSessionHolder, update: SessionUpdate.SubagentUpdate) {
        if (update.state != MaybeUndefined.Undefined) {
            child.permissions.onState(update.state.valueOrNull(), live = parent.root.phase.isAtLeast(Stage.ANSWERED))
        }
    }

    /**
     * Registers the holder of the subagent [childId] that the agent announced in the stream of [parent].
     *
     * ### Steps
     *
     * 1. The child's holder is created. If the root was already answered, it takes its opened marker as its first
     *    item, before anyone else can reach it.
     * 2. The child is added to the parent, so a parent that fails at the same moment can't miss it, and a sealed
     *    parent takes none. If the root's close has started, the add answers the child's permission requests
     *    `cancelled` from the start, see [ClientSessionHolder.addChild].
     * 3. The child enters the session map, in one compare-and-set that checks that its root is still the registered
     *    holder of the root's id and that no other holder blocks the child's id.
     *
     * ### Rejection
     *
     * If another holder blocks the id, the announcement is malformed: it names the parent itself, an ancestor,
     * another parent's subagent, or a root that is open or being opened. The child is then removed from the parent
     * again, and the announcement is delivered to the parent as a plain update. Only a cascade can have seen the
     * child, and failing a holder that never reached the map does no harm.
     *
     * Entering the map first would need an undo just the same, if the parent turned out sealed. And the map's
     * compare-and-set has to check the id itself in either order, because an opening may create a root holder for
     * it at any time.
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
             * The close in [removeSessionTree], the reaping in [withInitializingSession] and [replacing] take the
             * root's entry out of this map no later than the subagents below it. The child is already among its
             * parent's subagents, so a registration that commits first is removed with them, and a later one sees
             * the root gone.
             */
            rootEnded = storage.sessions[root.sessionId] !== root
            blocked = !rootEnded && storage.sessions[childId]?.blocksId == true
            if (rootEnded || blocked) storage else storage.copy(sessions = storage.sessions.replacing(childId, child))
        }

        // The removal of the closed root's tree, or the cascade of the reaped or replaced root, fails the child.
        if (rootEnded) return null
        if (blocked) {
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
     * Returns the holder of [sessionId], claimed by an opening call that knows the id before its request:
     * `session/load` with [replayRequested], or `session/resume` without it, with its [parameters]. Must run under
     * [withInitializingSession], which makes sure the holder exists.
     *
     * @throws IllegalStateException if the session is open or being opened
     */
    fun claimSessionHolder(sessionId: SessionId, replayRequested: Boolean, parameters: SessionCreationParameters): RootSessionHolder {
        val sessionHolder = getOrCreateSessionHolder(sessionId)
        check(sessionHolder is RootSessionHolder && sessionHolder.claimForOpening(replayRequested, parameters)) {
            "Session $sessionId is already open or being opened"
        }
        return sessionHolder
    }

    /**
     * Claims the holder of [sessionId] for the call whose answer [response] names that id, and marks it opened, in
     * one step, with the [parameters] of the call. This serves `session/new` and `session/fork`, whose session id
     * first appears in the answer; neither asks for a replay.
     *
     * Runs in the response hook of the call, so it must not suspend or block. Must run under
     * [withInitializingSession], which makes sure the holder exists.
     *
     * @return `null` if the session is open or being opened
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
     * Fails [holder] with [cause] and hands its queued updates to [globalSessionUpdateHandler] at once, then does the
     * same for every subagent below it, parent first.
     *
     * Every failure of a holder goes through here, so a failed holder keeps no updates. Each holder is sealed and a
     * subagent's opening is stopped first, so no opening starts after its holder has failed. A subagent's session
     * object is closed, and stays in its parent's subagents. A failure of the handler is logged per update, so
     * it neither stops the drain nor replaces the failure that the caller sees.
     *
     * ### Who fails a subagent
     *
     * A subagent's holder fails only through the stop of its opening that settles it, see [stopOpening]:
     * - this call, for [holder] and for each subagent below it whose opening it stops that way;
     * - [removeSessionTree], for the subagents whose openings it stops that way.
     *
     * So a subagent is skipped with its subtree when its opening had finished, which completes the holder, or was
     * stopped before, by an earlier failure or a close, whose stop settles it. Two failures never drain one queue at
     * once.
     *
     * ### Cancellation
     *
     * A running opening is cancelled, not joined: this runs under [NonCancellable] and must not wait for the
     * application's `notify`. An opening checks for cancellation before it takes each item, so once the `notify` it is
     * in returns, the rest of the queue goes to [globalSessionUpdateHandler]. Only an opening that passed that check
     * just before the cancellation can still take one more item and hand it to its `notify`.
     *
     * @return `false` if [holder] is a subagent whose opening was already stopped or had finished, so this failed
     * nothing, `true` otherwise.
     */
    suspend fun failSessionTree(holder: ClientSessionHolder, cause: Throwable): Boolean = withContext(NonCancellable) {
        if (holder is SubagentSessionHolder && !stopOpening(holder)) return@withContext false
        failStoppedTree(holder, cause)
        true
    }

    /**
     * Stops the opening of [holder], see [SubagentSessionHolder.markOpeningStopped], and cancels it if it runs.
     *
     * @return whether this call settles the opening in the holder, so its caller must fail it:
     * - `true` if the opening was running or not started, so the holder must be failed
     * - `false` if the opening is already in the terminal state (finished, stopped), so no action is required.
     */
    private fun stopOpening(holder: SubagentSessionHolder): Boolean =
        when (val replaced = holder.markOpeningStopped()) {
            is OpeningState.Running -> {
                replaced.job.cancel()
                true
            }
            OpeningState.NotStarted -> true
            OpeningState.Finished, OpeningState.Stopped -> false
        }

    /**
     * Fails [failing], whose opening the caller has stopped if it is a subagent, and the subagents below it whose
     * openings this stops, see [failSessionTree]. Must run under [NonCancellable].
     */
    private suspend fun failStoppedTree(failing: ClientSessionHolder, reason: Throwable) {
        val children = failing.sealChildren()
        // After the stop, so a setup that stores the subagent later starts no opening and closes it itself.
        if (failing is SubagentSessionHolder) failing.markClosed()
        failing.failAndDrainQueue(reason) { update, meta ->
            runCatching { handleUnconnectedSessionUpdate(failing.sessionId, update, meta) }
                .onFailure { logger.error(it) { "Unconnected session update handler failed for ${failing.sessionId}" } }
        }
        for (child in children) {
            if (!stopOpening(child)) continue
            failStoppedTree(child, IllegalStateException("Session ${failing.sessionId}, the parent of subagent session ${child.sessionId}, failed", reason))
        }
    }

    /**
     * Runs [block], an opening call, while counting it as an initializing session, so that early updates for unknown
     * ids get speculative root holders. When the last such call ends, every root holder that is unclaimed, incomplete
     * or failed is removed and failed, with its subagents.
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
                     * No opening is in progress any more, so an unclaimed root holder, or a failed one, belongs to no
                     * session: the agent sent updates for an id that no opening claimed. Remove those and fail them
                     * below.
                     *
                     * A subagent's holder is exempt: it may wait for its operations long after any opening, and it ends
                     * with its own setup or with its parent.
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
                    /*
                     * Report it as a non-existent session. Its updates were buffered on the assumption they might
                     * belong to this (or another concurrent) initialization; since none claimed `id`, it's an
                     * unconnected session, same as if no initialization had been in progress when they arrived.
                     */
                    failSessionTree(holder, AcpExpectedError("Session $id not found"))
                }
                /*
                 * The subagents of the reaped roots have failed with them, and the cascade has sealed every holder,
                 * so the set below each root is final. A failed subagent keeps its id only while its
                 * parent lives, so they leave the map too.
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
 * Returns these sessions with [holder] under [sessionId], replacing a holder that no longer blocks the id. The
 * subagents below the replaced holder leave the map with it: they failed with it, and they no longer block their
 * ids either.
 */
private fun PersistentMap<SessionId, ClientSessionHolder>.replacing(
    sessionId: SessionId,
    holder: ClientSessionHolder,
): PersistentMap<SessionId, ClientSessionHolder> = mutate { map ->
    map[sessionId]?.let { oldHolder -> map.removeDescendantsOf(oldHolder) }
    map[sessionId] = holder
}

/**
 * Removes every subagent below [holder], each only if its id still maps to it.
 */
private fun MutableMap<SessionId, ClientSessionHolder>.removeDescendantsOf(holder: ClientSessionHolder) {
    holder.forEachDescendant { if (this[it.sessionId] === it) remove(it.sessionId) }
}
