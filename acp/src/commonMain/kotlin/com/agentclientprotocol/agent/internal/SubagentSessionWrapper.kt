@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.update
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * A subagent registered on the connection: its client operations, its own [AgentSubagents], and its announcement
 * phase, cancel grant and work state as the SDK tracks them.
 *
 * @property parent the session whose stream carries this child's [SessionUpdate.SubagentUpdate]s
 */
internal class SubagentSessionWrapper(
    agent: Agent,
    protocol: Protocol,
    override val sessionId: SessionId,
    val parent: SessionWrapper,
) : SessionWrapper(agent, protocol) {
    /**
     * [DROPPED] is a child that was never announced, [REMOVED] an announced one that subtree cleanup removed.
     */
    private enum class Phase { PENDING, ANNOUNCED, DROPPED, REMOVED }

    /**
     * Makes each own [SessionUpdate.SubagentUpdate] send atomic with its tracking, and orders every send against
     * dropping or removing the child. Held only around non-suspending sends.
     */
    private val lock = SynchronizedObject()

    /**
     * Written under [lock], and [Phase.ANNOUNCED] only once the announcement was sent, so an operation that sees it
     * can't overtake the announcement.
     */
    private val _phase = atomic(Phase.PENDING)

    /**
     * Whether cancel is granted, from the live [SessionUpdate.SubagentUpdate]s sent for this child on any path.
     * Written under [lock] before the update is sent, so a `session/cancel` right after the grant is routed.
     */
    private val _cancelGranted = atomic(false)

    /**
     * Whether the factory is running. Guarded by [lock].
     */
    private var factoryRunning = false

    /**
     * The last `state` the child's operations sent while the factory ran, for a factory that throws after the
     * announcement. Guarded by [lock].
     */
    private var factoryState: StateUpdate? = null

    /**
     * Whether a `session/cancel` arrived while the factory ran with cancel granted, to deliver once it returns.
     * Guarded by [lock].
     */
    private var isCancelLatched = false

    /**
     * The last `state` sent through the session's events. State sent through the client operations isn't seen here.
     */
    private var eventsState: StateUpdate? = null

    private val _session = atomic<AgentSubagentSession?>(null)

    override val root: RootSessionWrapper = parent.root

    override val clientOperations: SubagentClientOperations =
        SubagentClientOperations(protocol, sessionId, agent.getClientInfoOrThrow().capabilities, this)

    override val subagents: AgentSubagents = AgentSubagents(this)

    private val context = AgentSubagentContext(sessionId, clientOperations, subagents)

    val isAnnounced: Boolean get() = _phase.value == Phase.ANNOUNCED

    /**
     * Whether `session/cancel` for this child is currently granted to the client.
     */
    val isCancelGranted: Boolean get() = _cancelGranted.value

    /**
     * The session the factory returned, or `null` while the factory runs or after it threw.
     */
    val session: AgentSubagentSession? get() = _session.value

    /**
     * Throws [IllegalStateException] unless the announcement has been sent and the child is still registered.
     */
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

    fun isDescendantOf(ancestor: SessionWrapper): Boolean {
        var current: SessionWrapper = parent
        while (current !== ancestor) {
            current = (current as? SubagentSessionWrapper)?.parent ?: return false
        }
        return true
    }

    /**
     * Runs the factory, which must send the announcement, then starts the collection. A replayed child's collection
     * waits until the transport has accepted the root's load response.
     */
    suspend fun <S : AgentSubagentSession> announce(factory: AgentSubagentFactory<S>): S {
        val session = try {
            runFactory(factory)
        } catch (e: Throwable) {
            // Only the caller's own cancellation passes as is: the app's would tear down the caller's scope.
            val callerCancelled = e is CancellationException && !currentCoroutineContext().isActive
            val announced = !dropUnlessAnnounced()
            if (announced) reportFactoryFailure()
            throw when {
                callerCancelled -> e
                announced && e !is CancellationException -> e
                announced -> IllegalStateException("Subagent $sessionId is announced, but its factory failed", e)
                else -> IllegalStateException("Subagent $sessionId is not announced: its factory failed", e)
            }
        }

        if (dropUnlessAnnounced()) {
            throw IllegalStateException(
                "Subagent $sessionId is not announced: its factory must send the announcement before returning"
            )
        }

        root.startAfterReplay { startCollection(session) }
        return session
    }

    /**
     * Runs the factory, then makes its session the target of `session/cancel` and delivers a cancel latched meanwhile.
     */
    private suspend fun <S : AgentSubagentSession> runFactory(factory: AgentSubagentFactory<S>): S {
        synchronized(lock) { factoryRunning = true }
        val session = try {
            executeWithSession { factory.createSubagent(context) }
        } catch (e: Throwable) {
            synchronized(lock) {
                factoryRunning = false
                // There is no session for a latched cancel to reach.
                isCancelLatched = false
            }
            throw e
        }

        // One step, so a session/cancel either is latched or finds the session.
        val cancelLatched = synchronized(lock) {
            factoryRunning = false
            _session.value = session
            isCancelLatched.also { isCancelLatched = false }
        }
        if (cancelLatched) deliverLatchedCancel(session)
        return session
    }

    /**
     * Unregisters this child unless it was announced, so its operations fail, and returns whether it is dropped.
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
     * Makes every operation fail from now on, once the child has left the session table. A pending child can no longer
     * be announced, so its `announce` fails.
     */
    fun remove() {
        synchronized(lock) {
            when (_phase.value) {
                Phase.PENDING -> _phase.value = Phase.DROPPED
                Phase.ANNOUNCED -> _phase.value = Phase.REMOVED
                Phase.DROPPED, Phase.REMOVED -> {}
            }
        }
    }

    /**
     * The factory threw after the announcement, so there is no session to cancel or collect: revokes the grant, since
     * the client couldn't learn otherwise that its cancel is ignored, and reports `unknown` if the factory left the
     * child active. It doesn't suspend, so it runs for a cancelled caller too.
     */
    private fun reportFactoryFailure() {
        // Grants and states sent in the replay window are history, so there is nothing to correct.
        if (root.isReplaying) return
        runCatching { sendFactoryFailureCorrection() }
            .onFailure { logger.debug(it) { "Could not correct subagent $sessionId after its factory failed" } }
    }

    private fun startCollection(session: AgentSubagentSession) {
        root.subtreeScope.launch(asContextElement() + CoroutineName("Subagent $sessionId events")) {
            val failure = try {
                session.events.collect { update ->
                    clientOperations.notify(update)
                    if (update is SessionUpdate.SubagentUpdate) eventsState = update.state.update(eventsState)
                }
                null
            } catch (e: CancellationException) {
                // A cancellation from the app's flow while this job is active is a feed failure.
                if (currentCoroutineContext().isActive) e else throw e
            } catch (e: Throwable) {
                e
            }
            onFeedEnd(failure)
        }
    }

    /**
     * Reports `unknown` if the events, which completed or failed with [failure], ended while the last state they sent
     * is active, since the client can no longer observe the child. The child stays registered.
     */
    private suspend fun onFeedEnd(failure: Throwable?) {
        if (failure != null) {
            logger.warn(failure) { "Events of subagent $sessionId failed" }
        } else {
            logger.debug { "Events of subagent $sessionId completed" }
        }

        // A collection that the SDK cancelled owes nothing.
        if (!currentCoroutineContext().isActive || !eventsState.isActiveState()) return
        runCatching { sendUnknownState() }
            .onFailure { logger.debug(it) { "Could not report subagent $sessionId unknown after its events ended" } }
    }

    /**
     * Serves `session/cancel` for this child: calls [AgentSubagentSession.cancel] while the grant is in effect,
     * latches it while the factory runs, and otherwise ignores it, because a notification can't get an error.
     */
    suspend fun cancel() {
        val session = _session.value ?: latchCancel() ?: return
        if (!isCancelGranted) {
            logger.info { "Ignoring session/cancel for subagent $sessionId: cancel is not granted" }
            return
        }
        callCancel(session)
    }

    /**
     * Latches a `session/cancel` that found no session if the factory runs with cancel granted. Returns the session if
     * the factory returned it meanwhile.
     */
    private fun latchCancel(): AgentSubagentSession? {
        val latched = synchronized(lock) {
            _session.value?.let { return it }
            if (factoryRunning && _cancelGranted.value) isCancelLatched = true
            isCancelLatched
        }
        logger.info {
            if (latched) "Deferring session/cancel for subagent $sessionId until its factory returns"
            else "Ignoring session/cancel for subagent $sessionId: it has no session"
        }
        return null
    }

    /**
     * Delivers a `session/cancel` latched while the factory ran, if the cancel grant is still in effect.
     */
    private fun deliverLatchedCancel(session: AgentSubagentSession) {
        root.subtreeScope.launch(asContextElement() + CoroutineName("Subagent $sessionId cancel")) {
            if (!isCancelGranted) {
                logger.info { "Dropping the session/cancel latched for subagent $sessionId: cancel is no longer granted" }
                return@launch
            }
            callCancel(session)
        }
    }

    private suspend fun callCancel(session: AgentSubagentSession) {
        try {
            executeWithSession { session.cancel() }
        } catch (e: Throwable) {
            // Only the caller's own cancellation passes: a notification has no response to fail.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            logger.warn(e) { "Cancel of subagent $sessionId failed" }
        }
    }

    /**
     * Sends an update other than this child's own [SessionUpdate.SubagentUpdate] on the child's stream.
     */
    fun sendPlainUpdate(notification: SessionUpdate, _meta: JsonElement?) {
        // Under the lock, so nothing is sent once the child was removed.
        synchronized(lock) {
            checkAnnounced()
            AcpMethod.ClientMethods.V1.SessionUpdate(protocol, SessionNotification(sessionId, notification, _meta))
        }
    }

    /**
     * Sends this child's own [SessionUpdate.SubagentUpdate] on the parent's stream.
     */
    fun sendOwnUpdate(update: SessionUpdate.SubagentUpdate, _meta: JsonElement?) {
        synchronized(lock) { sendOwnUpdateLocked(update, _meta) }
    }

    private fun sendFactoryFailureCorrection() {
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

    private fun sendUnknownState() {
        val update = SessionUpdate.SubagentUpdate(sessionId, state = MaybeUndefined.Value(StateUpdate.Unknown()))
        synchronized(lock) { sendOwnUpdateLocked(update, null) }
    }

    /**
     * Sends a [SessionUpdate.SubagentUpdate] for this child on the parent's stream, with [lock] held. In the root's
     * replay window it is history: it neither grants cancel nor counts as the factory's last state.
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
            AcpMethod.ClientMethods.V1.SessionUpdate(protocol, SessionNotification(parent.sessionId, update, _meta))
        } catch (e: Throwable) {
            _cancelGranted.value = wasGranted
            throw e
        }

        if (isLive && factoryRunning) factoryState = update.state.update(factoryState)
        _phase.value = Phase.ANNOUNCED
    }
}

/**
 * Whether this last known state shows the child working. An unrecognized state counts as active.
 */
private fun StateUpdate?.isActiveState(): Boolean = when (this) {
    is StateUpdate.Running, is StateUpdate.RequiresAction, is StateUpdate.Other -> true
    is StateUpdate.Idle, is StateUpdate.Unknown, null -> false
}
