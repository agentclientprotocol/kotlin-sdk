@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
 * @param subagentScope the scope of the subagents' consumers, which belongs to the connection
 */
internal class ClientSessionRegistry(
    private val globalSessionUpdateHandler: GlobalSessionUpdateHandler?,
    protocol: Protocol,
    subagentScope: CoroutineScope,
) {
    private data class SessionsStorage(val initializingSessionsCount: Int = 0, val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf())

    private val _sessions = atomic(SessionsStorage())
    private val subagentSetup = SubagentSetup(protocol, subagentScope, ::failSessionHolder)

    /**
     * Looks up the holder for [sessionId], creating a new entry only if there are some currently initializing
     * sessions. Returns `null` if the session is neither registered nor being initialized, instead of throwing -
     * callers that need a session to exist (e.g., to service a request against it) should use
     * [getOrCreateSessionHolder] instead.
     *
     * A failed holder counts as missing and is replaced while sessions initialize, so a retry of a failed opening
     * gets a fresh holder, unless it still blocks its id, see [ClientSessionHolder.blocksId]. Dropping a failed holder
     * loses nothing, because [failSessionHolder] drains it at once, and its subagents leave the map with it, see
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
                    val newHolder = ClientSessionHolder(sessionId, parent = null, subagentSetup)
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
     * Creates a new entry only if there are some currently initializing sessions. Otherwise, throws in the case of missing session.
     */
    private fun getOrCreateSessionHolder(sessionId: SessionId): ClientSessionHolder =
        getOrCreateSessionHolderOrNull(sessionId) ?: acpFail("Session $sessionId not found")

    /**
     * Returns the holder of the root session [rootId], or `null` if the id is unknown or belongs to a subagent.
     */
    private fun rootHolderOrNull(rootId: SessionId): ClientSessionHolder? =
        _sessions.value.sessions[rootId]?.takeIf { it.parent == null }

    /**
     * Answers the pending and the later permission requests of every subagent below the root session [rootId] with
     * `cancelled`, when its close starts. Each close only completes a signal, so the order doesn't matter.
     */
    fun closeDescendantPermissions(rootId: SessionId) {
        val root = rootHolderOrNull(rootId) ?: return
        root.forEachDescendant { it.subagentPermissions?.close() }
    }

    /**
     * Removes the root session [rootId] and every subagent below it, after the agent answered its `session/close`.
     *
     * Each holder first stops taking subagents, top-down, so a late announcement registers no subagent that no close
     * would reach. Then every consumer is stopped and joined, so none starts or delivers afterwards. The joins wait
     * for a subagent's `notify` to react to cancellation. The consumer that the close runs in, because a subagent's
     * own operations called it there, is not joined, which would never finish, and is cancelled at the end instead.
     * Then the holders leave the session map in one compare-and-set, each only if its id still maps to it, so no
     * half-removed tree is visible. Last, the permission requests of each subagent are answered `cancelled`, which
     * also covers a subagent announced after [closeDescendantPermissions] ran, each subagent object is closed, and
     * each subagent holder that hasn't completed fails with a "closed" error, so its waiting requests get the error
     * and its queue goes to [globalSessionUpdateHandler].
     *
     * The removal finishes even if the caller is cancelled while it joins the consumers, because the agent has
     * already dropped the sessions.
     *
     * @return the ids that were removed from the session map
     */
    suspend fun removeSessionTree(rootId: SessionId): Set<SessionId> {
        val root = rootHolderOrNull(rootId) ?: return emptySet()

        val subtree = buildList {
            fun visit(holder: ClientSessionHolder) {
                add(holder)
                holder.closeChildren().forEach(::visit)
            }
            visit(root)
        }

        val current = currentCoroutineContext()[Job]
        val consumers = subtree.mapNotNull { holder -> holder.stopConsumer() }
        val ownConsumer = current?.let { job -> consumers.firstOrNull { it.isSelfOrAncestorOf(job) } }
        val removed = mutableSetOf<SessionId>()
        try {
            for (job in consumers) if (job !== ownConsumer) job.cancel()
            for (job in consumers) if (job !== ownConsumer) job.join()
        } finally {
            withContext(NonCancellable) {
                _sessions.update { storage ->
                    removed.clear()
                    val sessions = storage.sessions.mutate { map ->
                        for (holder in subtree) {
                            if (map[holder.sessionId] === holder) {
                                map.remove(holder.sessionId)
                                removed += holder.sessionId
                            }
                        }
                    }
                    storage.copy(sessions = sessions)
                }

                // Bottom-up, so each holder fails with its own error rather than with its parent's.
                for (holder in subtree.asReversed()) {
                    if (holder === root) continue
                    holder.subagentPermissions?.close()
                    holder.subagent?.markClosed()
                    if (!holder.session.isCompleted) failSessionHolder(holder, AcpExpectedError("Session ${holder.sessionId} closed"))
                }
            }
            ownConsumer?.cancel()
        }
        return removed
    }

    /**
     * Whether [job] is this job or a job below it. The close that a subagent's `notify` calls runs below its consumer's
     * job, directly or in a child coroutine that `notify` started. A coroutine that only copied the consumer's context
     * into another scope is not below it, and its close joins the consumer like any other.
     */
    private fun Job.isSelfOrAncestorOf(job: Job): Boolean = this === job || children.any { it.isSelfOrAncestorOf(job) }

    /**
     * Returns the opened session [sessionId], without creating a holder.
     *
     * @throws IllegalStateException if the session is not registered or not initialized yet, or is a subagent session
     */
    fun getSession(sessionId: SessionId): ClientSession {
        val sessionHolder = _sessions.value.sessions[sessionId] ?: error("Session $sessionId not found")
        if (!sessionHolder.session.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return sessionHolder.session.getCompleted() as? ClientSession ?: error("Session $sessionId is a subagent session")
    }

    /**
     * Returns the session [sessionId], waiting until it is created when its opening is in progress.
     *
     * @throws com.agentclientprotocol.protocol.AcpExpectedError if the session is unknown
     */
    suspend fun getSessionOrThrow(sessionId: SessionId): BaseClientSession {
        return getOrCreateSessionHolder(sessionId).session.await()
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
         * never called `session/new` / `session/load` / `session/resume` for. It can live on the server,
         * created from another IDE window, the web, or another machine. That's not a protocol violation, so
         * unlike other session-scoped methods, an unknown/unconnected session here must not fail the call.
         * A speculative holder can also be reaped by `withInitializingSession` after we found it here.
         *
         * The lookup and the sorting are not `suspend`, and must stay that way: `Protocol` runs a notification handler
         * only until its first suspension before it handles the next message. So a subagent is registered before the
         * agent's next message for it, which would otherwise be unconnected, or a request for it would fail with
         * "session not found", while the parent's `notify` runs app code.
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

    private fun followSubagentState(parent: ClientSessionHolder, child: ClientSessionHolder, update: SessionUpdate.SubagentUpdate) {
        if (update.state != MaybeUndefined.Undefined) {
            child.subagentPermissions?.onState(update.state.valueOrNull(), live = parent.root.isAnswered)
        }
    }

    /**
     * Registers the holder of the subagent [childId] that the agent announced in the stream of [parent].
     *
     * The child goes into the parent's subagents first, so a parent that fails at the same moment can't miss it, and
     * into the session map second, in one compare-and-set that checks the id. If another holder blocks the id, the
     * announcement is malformed: it names the parent itself, an ancestor, another parent's subagent, or a root that
     * is open or being opened. The child is then removed from the parent again, and the announcement is delivered to
     * the parent as a plain update. Only a cascade can have seen it, and failing a holder that never reached the map
     * does no harm.
     *
     * A subagent registered after the root's answer gets its marker as its first item.
     *
     * @return the child's holder, or `null` if nothing was registered
     */
    private fun registerChild(parent: ClientSessionHolder, childId: SessionId): ClientSessionHolder? {
        if (parent.isFailed) return null

        val child = ClientSessionHolder(childId, parent, subagentSetup)
        val root = parent.root
        if (root.isAnswered) child.markSubagentOpened(promote = root.replayRequested != true)
        if (!parent.addChild(child)) return null

        var rootFailed = false
        var blocked = false
        _sessions.update { storage ->
            /*
             * A failed root's subagents leave the map once its cascade has closed every holder's subagents, see
             * [withInitializingSession] and [replacing]. Reading the root's failure here keeps a registration that
             * comes after that removal out of the map.
             */
            rootFailed = root.isFailed
            blocked = !rootFailed && storage.sessions[childId]?.blocksId == true
            if (rootFailed || blocked) storage else storage.copy(sessions = storage.sessions.replacing(childId, child))
        }
        // The cascade of the failed root fails the child, which is already among its parent's subagents.
        if (rootFailed) return null
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
     * Returns the holder of [sessionId], claimed by an opening call that knows the id before its request: `session/load`
     * with [replayRequested], or `session/resume` without it, with its [parameters]. Must run under
     * [withInitializingSession], which makes sure the holder exists.
     *
     * @throws IllegalStateException if the session is open or being opened
     */
    fun claimSessionHolder(sessionId: SessionId, replayRequested: Boolean, parameters: SessionCreationParameters): ClientSessionHolder {
        val sessionHolder = getOrCreateSessionHolder(sessionId)
        check(sessionHolder.claimForOpening(replayRequested, parameters)) { "Session $sessionId is already open or being opened" }
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
    ): ClientSessionHolder? {
        val sessionHolder = getOrCreateSessionHolderOrNull(sessionId) ?: return null
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
     * Every failure of a holder goes through here, so a failed holder keeps no updates. Each holder stops taking
     * subagents and its consumer is stopped first, so no consumer starts after its holder has failed. A failure of the
     * handler is logged per update, so it neither stops the drain nor replaces the failure that the caller sees.
     *
     * A running consumer is cancelled, not joined: this runs under [NonCancellable] and must not wait for the
     * application's `notify`. A consumer checks for cancellation before it takes each item, so once the `notify` it is
     * in returns, the rest of the queue goes to [globalSessionUpdateHandler]. Only a consumer that passed that check
     * just before the cancellation can still take one more item and hand it to its `notify`.
     */
    suspend fun failSessionHolder(holder: ClientSessionHolder, cause: Throwable) {
        withContext(NonCancellable) {
            failSessionTree(holder, cause)
        }
    }

    private suspend fun failSessionTree(holder: ClientSessionHolder, cause: Throwable) {
        val children = holder.closeChildren()
        holder.stopConsumer()?.cancel()
        holder.failAndDrainQueue(cause) { update, meta ->
            runCatching { handleUnconnectedSessionUpdate(holder.sessionId, update, meta) }
                .onFailure { logger.error(it) { "Unconnected session update handler failed for ${holder.sessionId}" } }
        }
        for (child in children) {
            failSessionTree(child, IllegalStateException("Session ${holder.sessionId}, the parent of subagent session ${child.sessionId}, failed", cause))
        }
    }

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
                     * this means that currently no sessions can be in initializing state during to ongoing load/new/fork/resume calls
                     * so if on exit from these methods we observe any entries with not completed or failed state we assume that someone sent us events with non-existent session ids
                     * and we have to remove them and report errors.
                     * A subagent's holder is exempt: it may wait for its operations long after any opening, and it ends
                     * with its own setup or with its parent.
                     */
                    hangingSessions = currentStorage.sessions.filterValues {
                        @OptIn(ExperimentalCoroutinesApi::class)
                        it.parent == null && (!it.session.isCompleted || it.session.getCompletionExceptionOrNull() != null)
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
                    failSessionHolder(holder, AcpExpectedError("Session $id not found"))
                }
                /*
                 * The subagents of the reaped roots have failed with them, and the cascade has closed every holder's
                 * subagents, so the set below each root is final. A failed subagent keeps its id only while its
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

    /**
     * Returns these sessions with [holder] under [sessionId], replacing a holder that no longer blocks the id. The
     * subagents below the replaced holder leave the map with it: they failed with it, and they no longer block their
     * ids either.
     */
    private fun PersistentMap<SessionId, ClientSessionHolder>.replacing(
        sessionId: SessionId,
        holder: ClientSessionHolder,
    ): PersistentMap<SessionId, ClientSessionHolder> = mutate { map ->
        map[sessionId]?.let { map.removeDescendantsOf(it) }
        map[sessionId] = holder
    }

    /**
     * Removes every subagent below [holder], each only if its id still maps to it.
     */
    private fun MutableMap<SessionId, ClientSessionHolder>.removeDescendantsOf(holder: ClientSessionHolder) {
        holder.forEachDescendant { if (this[it.sessionId] === it) remove(it.sessionId) }
    }
}
