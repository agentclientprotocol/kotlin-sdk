@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.update
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * The [AgentSubagents] of [bound], a root session or a subagent.
 */
internal class SessionSubagents(private val bound: Agent.ChatSessionWrapper) : AgentSubagents {
    override suspend fun <S : AgentSubagentSession> announce(
        sessionId: SessionId,
        factory: AgentSubagentFactory<S>,
        parent: SessionId?,
    ): S {
        val parentWrapper = resolveParent(parent)
        val child = SubagentWrapper(bound.agent, bound.protocol, sessionId, parentWrapper)
        bound.agent.registerSubagent(child)
        return child.announce(factory)
    }

    private fun resolveParent(parent: SessionId?): Agent.ChatSessionWrapper {
        check(!bound.root.isSubtreeClosed) { "Session ${bound.root.sessionId} is closed" }
        /*
         * A child that isn't announced yet must not announce grandchildren: their announcements would put traffic on
         * a stream the client doesn't know.
         */
        if (bound is SubagentWrapper) bound.checkAnnounced()
        if (parent == null || parent == bound.sessionId) return bound

        val candidate = bound.agent.subagentOrNull(parent)
        require(candidate != null && candidate.isAnnounced && candidate.isDescendantOf(bound)) {
            "Parent $parent is neither session ${bound.sessionId} nor an announced subagent below it"
        }
        return candidate
    }
}

/**
 * A subagent registered on the connection: its client operations, its own [AgentSubagents], and what the SDK tracks
 * about it.
 *
 * The child is pending until its announcement, the first [SessionUpdate.SubagentUpdate] for it, has been sent. Until
 * then every other operation fails. If its announcement can't be sent, it's dropped: unregistered, and every
 * operation fails for good. Once announced, it stays registered until subtree cleanup removes it, and then every
 * operation fails for good too.
 *
 * A child announced while its root's replay window is open is a replayed one: its factory must send the
 * announcement, its collection starts after the load response, and the SDK doesn't track the grants and states sent
 * in the window, because they are history.
 *
 * @property parent the session whose stream carries this child's [SessionUpdate.SubagentUpdate]s
 */
internal class SubagentWrapper(
    agent: Agent,
    protocol: Protocol,
    override val sessionId: SessionId,
    val parent: Agent.ChatSessionWrapper,
) : Agent.ChatSessionWrapper(agent, protocol) {
    /**
     * [DROPPED] is a child that was never announced, [REMOVED] an announced one that subtree cleanup removed.
     */
    private enum class Phase { PENDING, ANNOUNCED, DROPPED, REMOVED }

    /**
     * Makes each own [SessionUpdate.SubagentUpdate] send atomic with the phase change and the tracking it implies,
     * and orders every update sent for the child against dropping or removing it. Held only around non-suspending
     * sends.
     */
    private val lock = SynchronizedObject()

    /**
     * Written under [lock]. Becomes [Phase.ANNOUNCED] only after the announcement's send returned, so an operation
     * that observes it can't overtake the announcement.
     */
    private val _phase = atomic(Phase.PENDING)

    /**
     * Whether cancel is currently granted, from the live [SessionUpdate.SubagentUpdate]s sent for this child on any
     * path. Written under [lock], before the update is sent, so a `session/cancel` that the client sends right after
     * receiving the grant is routed.
     */
    private val _cancelGranted = atomic(false)

    /**
     * Whether the factory is running, and the last `state` the child's operations sent while it ran. Guarded by
     * [lock]. Used only when the factory throws after the announcement.
     */
    private var factoryRunning = false
    private var factoryState: StateUpdate? = null

    /**
     * The last `state` sent through the session's events, for the rule on a feed that ends while the child is
     * active. Only the collection writes it: state sent through the client operations is not seen here.
     */
    private var eventsState: StateUpdate? = null

    /**
     * Completes once the announcement has been sent, or fails with [IllegalStateException] when the child is dropped
     * before that.
     */
    private val announcement = CompletableDeferred<Unit>()

    private val _session = atomic<AgentSubagentSession?>(null)

    override val root: Agent.SessionWrapper = parent.root

    /**
     * Whether this child was announced in its root's replay window.
     */
    private val isReplayed = root.isReplaying

    private val subagentOperations = SubagentClientOperations(agent.getClientInfoOrThrow().capabilities)
    override val clientOperations: RemoteClientSessionOperations get() = subagentOperations

    override val subagents: AgentSubagents = SessionSubagents(this)

    private val context = object : AgentSubagentContext {
        override val sessionId: SessionId get() = this@SubagentWrapper.sessionId
        override val client: RemoteClientSessionOperations get() = subagentOperations
        override val subagents: AgentSubagents get() = this@SubagentWrapper.subagents
    }

    val isAnnounced: Boolean get() = _phase.value == Phase.ANNOUNCED

    /**
     * Whether `session/cancel` for this child is currently granted to the client.
     */
    val isCancelGranted: Boolean get() = _cancelGranted.value

    /**
     * The session the factory returned, or `null` while the factory runs or after it threw.
     */
    val session: AgentSubagentSession? get() = _session.value

    fun checkAnnounced() {
        if (_phase.value == Phase.PENDING) error("Subagent $sessionId is not announced yet: send its SubagentUpdate first")
        checkRegistered()
    }

    private fun checkRegistered() {
        when (_phase.value) {
            Phase.PENDING, Phase.ANNOUNCED -> {}
            Phase.DROPPED -> error("Subagent $sessionId is not registered")
            Phase.REMOVED -> error("Subagent $sessionId is not registered: its root session ${root.sessionId} is closed")
        }
    }

    fun isDescendantOf(ancestor: Agent.ChatSessionWrapper): Boolean {
        var current: Agent.ChatSessionWrapper = parent
        while (current !== ancestor) {
            current = (current as? SubagentWrapper)?.parent ?: return false
        }
        return true
    }

    /**
     * Runs the `announce` steps after registration: the factory, then the collection, then the wait for the
     * announcement. A replayed child's collection waits for the load response instead, and its factory must have
     * sent the announcement.
     */
    suspend fun <S : AgentSubagentSession> announce(factory: AgentSubagentFactory<S>): S {
        val session = try {
            runFactory(factory)
        } catch (e: Throwable) {
            if (dropUnlessAnnounced()) {
                /*
                 * Only the caller's own cancellation passes as is. A CancellationException from the app while the
                 * caller is active would tear down the caller's scope, usually the parent's prompt flow.
                 */
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                throw IllegalStateException("Subagent $sessionId is not announced: its factory failed", e)
            }
            reportFactoryFailure()
            throw e
        }

        if (isReplayed) {
            /*
             * Waiting for the events would deadlock: their collection starts only after the load response, which
             * waits for the replay that called this.
             */
            if (dropUnlessAnnounced()) {
                throw IllegalStateException(
                    "Subagent $sessionId is not announced: during the parent's session/load, its factory must send " +
                        "the announcement"
                )
            }
            _session.value = session
            root.startAfterReplay { startCollection(session) }
            return session
        }
        _session.value = session

        val collection = startCollection(session)
        try {
            announcement.await()
        } catch (e: CancellationException) {
            if (dropUnlessAnnounced()) collection.cancel()
            throw e
        }
        return session
    }

    private suspend fun <S : AgentSubagentSession> runFactory(factory: AgentSubagentFactory<S>): S {
        synchronized(lock) { factoryRunning = true }
        try {
            return executeWithSession { factory.createSubagent(context) }
        } finally {
            synchronized(lock) { factoryRunning = false }
        }
    }

    /**
     * Unregisters this child unless its announcement has been sent, and makes its operations fail. Returns whether
     * the child is dropped.
     */
    private fun dropUnlessAnnounced(): Boolean {
        val dropped = synchronized(lock) {
            when (_phase.value) {
                Phase.ANNOUNCED, Phase.REMOVED -> false
                Phase.PENDING -> {
                    _phase.value = Phase.DROPPED
                    true
                }
                Phase.DROPPED -> true
            }
        }
        if (dropped) agent.unregisterSubagent(this)
        return dropped
    }

    /**
     * Subtree cleanup for this child, after the caller removed it from the child table: every operation fails from
     * now on, and an `announce` still waiting for the announcement fails.
     */
    fun remove() {
        synchronized(lock) {
            when (_phase.value) {
                Phase.PENDING -> _phase.value = Phase.DROPPED
                Phase.ANNOUNCED -> _phase.value = Phase.REMOVED
                Phase.DROPPED, Phase.REMOVED -> {}
            }
        }
        announcement.completeExceptionally(
            IllegalStateException("Subagent $sessionId is not announced: its root session ${root.sessionId} is closed")
        )
    }

    /**
     * The factory threw after the announcement: the child exists on the wire, but there is no session to cancel or
     * collect. Revoke the grant, since the client can't learn otherwise that its cancel is ignored, and report
     * `unknown` if the factory left the child active.
     *
     * The send doesn't suspend, so a cancelled caller doesn't prevent it. Nothing is sent once the root's subtree is
     * being torn down, and a failed send is only logged. In the replay window there is nothing to correct: history
     * grants are never in effect, and the client treats the child's state as unconfirmed after the load anyway.
     */
    private fun reportFactoryFailure() {
        if (root.isReplaying || !root.subtreeScope.isActive) return
        runCatching { subagentOperations.sendFactoryFailureCorrection() }
            .onFailure { logger.debug(it) { "Could not correct subagent $sessionId after its factory failed" } }
    }

    private fun startCollection(session: AgentSubagentSession): Job {
        val collection = root.subtreeScope.launch(asContextElement() + CoroutineName("Subagent $sessionId events")) {
            val failure = try {
                session.events.collect { update ->
                    subagentOperations.notify(update)
                    if (update is SessionUpdate.SubagentUpdate) eventsState = update.state.update(eventsState)
                }
                null
            } catch (e: CancellationException) {
                /*
                 * A cancellation thrown by the app's flow while this job is still active is a feed failure. Only the
                 * cancellation of this job ends the collection as cancelled.
                 */
                if (currentCoroutineContext().isActive) e else throw e
            } catch (e: Throwable) {
                e
            }
            onFeedEnd(failure)
        }
        collection.invokeOnCompletion { cause ->
            if (cause != null && dropUnlessAnnounced()) {
                announcement.completeExceptionally(
                    IllegalStateException("Subagent $sessionId is not announced: its events were cancelled", cause)
                )
            }
        }
        return collection
    }

    /**
     * The events completed, or failed with [failure].
     *
     * A feed that ends while the last state it sent is active owes `unknown`: the client can no longer observe the
     * child, and the SDK doesn't invent an outcome. The child stays registered.
     */
    private suspend fun onFeedEnd(failure: Throwable?) {
        if (dropUnlessAnnounced()) {
            val reason = if (failure == null) "completed" else "failed"
            announcement.completeExceptionally(
                IllegalStateException("Subagent $sessionId is not announced: its events $reason before announcing it", failure)
            )
            return
        }
        if (failure != null) {
            logger.warn(failure) { "Events of subagent $sessionId failed" }
        } else {
            logger.debug { "Events of subagent $sessionId completed" }
        }

        // A collection the SDK cancelled is torn down with its parent or the connection, and owes nothing.
        if (!currentCoroutineContext().isActive || !eventsState.isActiveState()) return
        runCatching { subagentOperations.sendUnknownState() }
            .onFailure { logger.debug(it) { "Could not report subagent $sessionId unknown after its events ended" } }
    }

    /**
     * Serves `session/cancel` for this child: calls the session's [AgentSubagentSession.cancel] while the cancel
     * grant is in effect, and otherwise ignores it, because a notification can't get an error. The SDK neither stops
     * the collection nor reports an outcome: the app reports it through the events.
     */
    suspend fun cancel() {
        val session = session
        if (!isCancelGranted || session == null) {
            logger.info {
                if (session == null) "Ignoring session/cancel for subagent $sessionId: it has no session"
                else "Ignoring session/cancel for subagent $sessionId: cancel is not granted"
            }
            return
        }
        try {
            executeWithSession { session.cancel() }
        } catch (e: Throwable) {
            // Only this handler's own cancellation passes: a notification has no response to fail.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            logger.warn(e) { "Cancel of subagent $sessionId failed" }
        }
    }

    /**
     * The child's client operations: requests for the child's session, gated until the announcement, and the
     * subagents routing rule for updates.
     */
    private inner class SubagentClientOperations(
        clientCapabilities: ClientCapabilities,
    ) : RemoteClientSessionOperations(protocol, this@SubagentWrapper.sessionId, clientCapabilities) {
        override fun checkCanSend() = checkAnnounced()

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            if (notification is SessionUpdate.SubagentUpdate) {
                require(notification.sessionId == sessionId) {
                    "SubagentUpdate for ${notification.sessionId} cannot be sent through the client operations of " +
                        "subagent $sessionId: announce nested subagents through subagents.announce"
                }
                sendOwnUpdate(notification, _meta)
            } else {
                // Under the lock, so nothing is sent for the child once subtree cleanup has removed it.
                synchronized(lock) {
                    checkAnnounced()
                    sendUpdate(sessionId, notification, _meta)
                }
            }
        }

        private fun sendOwnUpdate(update: SessionUpdate.SubagentUpdate, _meta: JsonElement?) {
            synchronized(lock) { sendOwnUpdateLocked(update, _meta) }
            announcement.complete(Unit)
        }

        fun sendFactoryFailureCorrection() {
            synchronized(lock) {
                val revokeGrant = _cancelGranted.value
                val reportUnknown = factoryState.isActiveState()
                if (!revokeGrant && !reportUnknown) return

                val correction = SessionUpdate.SubagentUpdate(
                    sessionId = sessionId,
                    capabilities = if (revokeGrant) MaybeUndefined.Null else MaybeUndefined.Undefined,
                    state = if (reportUnknown) MaybeUndefined.Value(StateUpdate.Unknown()) else MaybeUndefined.Undefined,
                )
                sendOwnUpdateLocked(correction, null)
            }
        }

        fun sendUnknownState() {
            val update = SessionUpdate.SubagentUpdate(sessionId, state = MaybeUndefined.Value(StateUpdate.Unknown()))
            synchronized(lock) { sendOwnUpdateLocked(update, null) }
        }

        /**
         * Sends a [SessionUpdate.SubagentUpdate] for this child on the parent's stream. The caller holds [lock].
         *
         * What it sends in the root's replay window is history: it neither grants cancel nor counts as the
         * factory's last state.
         */
        private fun sendOwnUpdateLocked(update: SessionUpdate.SubagentUpdate, _meta: JsonElement?) {
            checkRegistered()

            val isLive = !root.isReplaying
            val wasGranted = _cancelGranted.value
            if (isLive) {
                _cancelGranted.value = when (val capabilities = update.capabilities) {
                    MaybeUndefined.Undefined -> wasGranted
                    MaybeUndefined.Null -> false
                    is MaybeUndefined.Value -> capabilities.value.cancel != null
                }
            }
            try {
                sendUpdate(parent.sessionId, update, _meta)
            } catch (e: Throwable) {
                _cancelGranted.value = wasGranted
                throw e
            }

            if (isLive && factoryRunning) factoryState = update.state.update(factoryState)
            _phase.value = Phase.ANNOUNCED
        }
    }
}

/**
 * Whether this last known state shows the child working, so a feed that can no longer report on it owes `unknown`.
 * An unrecognized state counts as active.
 */
private fun StateUpdate?.isActiveState(): Boolean = when (this) {
    is StateUpdate.Running, is StateUpdate.RequiresAction, is StateUpdate.Other -> true
    is StateUpdate.Idle, is StateUpdate.Unknown, null -> false
}
