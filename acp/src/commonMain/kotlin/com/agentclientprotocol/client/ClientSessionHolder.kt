@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * An item of a [ClientSessionHolder]'s queue. The `session/update` handler sorts each update into one of these when
 * it arrives, and the consumer acts on the tag without looking the session map up again.
 */
internal sealed class QueuedItem {
    /**
     * A `session/update` of the holder's session.
     */
    class Update(val update: SessionUpdate, val meta: JsonElement?) : QueuedItem()

    /**
     * The `subagent_update` that announced [child], whose holder its arrival registered. The consumer that reaches
     * it sets the subagent up with [SubagentSetup], and then delivers [update].
     */
    class Announcement(val update: SessionUpdate.SubagentUpdate, val meta: JsonElement?, val child: SubagentSessionHolder) : QueuedItem()

    /**
     * A later `subagent_update` of [child]. The consumer applies it to the subagent, if its setup succeeded, and
     * then delivers [update].
     */
    class Patch(val update: SessionUpdate.SubagentUpdate, val meta: JsonElement?, val child: SubagentSessionHolder) : QueuedItem()

    /**
     * The point where the answer of the root's opening call fell among the updates, see [SessionEvent.Opened]. Only
     * [RootSessionHolder.markOpened] puts it into the queues, in the response hook of the opening call, and the
     * registration of a subagent after that answer.
     *
     * @property response the answer, for a root; `null` for a subagent
     */
    class Opened(val response: AcpCreatedSessionResponse?) : QueuedItem()
}

/**
 * Marks the coroutine of the consumer of [holder]'s queue, and everything that inherits its context, so a close of the
 * root that the subagent's own operations start there can tell that it runs in that consumer, see
 * [ClientSessionRegistry.removeSessionTree].
 */
internal class SubagentConsumer(val holder: SubagentSessionHolder) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubagentConsumer>
}

/**
 * The client's slot for one session id, a root or a subagent: buffers its updates until the session is created, then
 * delivers them to it.
 *
 * [ClientSessionRegistry] creates a [RootSessionHolder] when it starts initializing a session, and speculatively for
 * an unknown session id that receives an update while some initialization is in progress. One opening call at most
 * claims a root's holder, see [RootSessionHolder.claimForOpening]. A [SubagentSessionHolder] is created only when the
 * `session/update` handler registers it under its parent, and no call claims it.
 *
 * Every update that arrives before the session exists goes into one queue, and so does the [QueuedItem.Opened]
 * marker of the root's answer. The queue has one consumer at a time. For a root: [RootSessionHolder.deliverEarly]
 * for a load with ready operations, or [RootSessionHolder.drainHistory] for a call with a factory, until the marker,
 * then [RootSessionHolder.completeOpening]. For a subagent: [SubagentSessionHolder.consumeSubagentQueue], started by
 * the parent's consumer once it has set the subagent up. The consumer closes the queue to switch to the session. An
 * update that misses the closed queue waits until the consumer finished and until the update that missed the queue
 * before it is delivered, and then goes to the session. So the updates keep the order in which their handlers reach
 * the holder, and the marker keeps its place among them.
 *
 * Each holder keeps the subagents registered under it, failed ones included, so [RootSessionHolder.markOpened], the
 * failure cascade of [ClientSessionRegistry.failSessionTree] and the close of the root can walk the tree.
 *
 * @property sessionId the id of this holder's session
 * @param setup sets up the subagents announced in this holder's stream
 */
internal sealed class ClientSessionHolder(
    val sessionId: SessionId,
    private val setup: SubagentSetup,
) {
    private data class Children(
        val byId: PersistentMap<SessionId, SubagentSessionHolder> = persistentMapOf(),
        val closed: Boolean = false,
    )

    protected val sessionDeferred: CompletableDeferred<BaseClientSession> = CompletableDeferred()
    // Don't make the channel limited, because it leads to a deadlock also:
    // when client side makes loadSession/newSession and an agent sends updates more than channel.capacity
    // the message with call response suspends because protocol thread is suspended in handleNotification
    // if to address it we have to somehow reorder events, that's not obvious on the protocol level, so we pay with memory right now to handle it
    protected val queue = Channel<QueuedItem>(capacity = Channel.UNLIMITED)
    // The delivery of the last update that missed the closed queue, see [handleOrQueue].
    private val lateDeliveries = atomic<Job>(Job().apply { complete() })
    private val children = atomic(Children())

    val session: Deferred<BaseClientSession> get() = sessionDeferred

    /**
     * The [BaseClientSession.subagents] of this holder's session. It lives here, because children are added while the
     * history of a loading root is delivered, before the root's [ClientSession] exists.
     */
    val subagents = SubagentList()

    /**
     * The holder of the root session this holder belongs to, itself for a root.
     */
    abstract val root: RootSessionHolder

    /**
     * Whether the opening of this holder failed, so a new opening of the session id needs a new holder.
     */
    val isFailed: Boolean
        @OptIn(ExperimentalCoroutinesApi::class)
        get() = sessionDeferred.isCompleted && sessionDeferred.getCompletionExceptionOrNull() != null

    /**
     * Whether this holder keeps its session id from a new holder: unless it has failed and isn't a subagent of a live
     * holder. A failed subagent keeps its id while its parent lives, so its later patches stay patches, an update on
     * its id stays unconnected, and an opening of the id is refused.
     */
    abstract val blocksId: Boolean

    /**
     * The effective working directory of this holder's session, for the subagents announced in it.
     */
    abstract fun sessionCwd(): String

    /**
     * The effective additional directories of this holder's session, for the subagents announced in it.
     */
    abstract fun sessionAdditionalDirectories(): List<String>

    /**
     * Returns the subagent [id] registered under this holder, failed or not, or `null`.
     */
    fun childOrNull(id: SessionId): SubagentSessionHolder? = children.value.byId[id]

    /**
     * Adds [child] to this holder's subagents.
     *
     * @return `false` if this holder has failed, so it takes no subagents any more
     */
    fun addChild(child: SubagentSessionHolder): Boolean {
        var added = false
        children.update { current ->
            added = !current.closed
            if (added) current.copy(byId = current.byId.put(child.sessionId, child)) else current
        }
        return added
    }

    /**
     * Removes the subagent [id] that [addChild] added, when its registration is rejected.
     */
    fun removeChild(id: SessionId) {
        children.update { it.copy(byId = it.byId.remove(id)) }
    }

    /**
     * Stops taking subagents, both in the tree and in [subagents], and returns the ones registered so far.
     */
    fun closeChildren(): Collection<SubagentSessionHolder> {
        subagents.close()
        return children.getAndUpdate { it.copy(closed = true) }.byId.values
    }

    /**
     * Runs [action] on every subagent below this holder, failed ones included, each before its own subagents.
     */
    fun forEachDescendant(action: (SubagentSessionHolder) -> Unit) {
        for (child in children.value.byId.values) {
            action(child)
            child.forEachDescendant(action)
        }
    }

    /**
     * Fails the holder with [cause] and hands whatever was queued to [onQueuedUpdate], a `subagent_update` as a
     * plain update. The queued updates belong to no live session: either no initialization claimed the holder, or
     * its opening failed. A queued marker is dropped, because a failed opening delivers none.
     *
     * The channel is closed before draining, so an update can't be queued after the drain and get lost.
     * The session is failed only after the drain, so [handleOrQueue] callers that missed the channel see
     * their updates delivered after the queued ones.
     */
    suspend fun failAndDrainQueue(cause: Throwable, onQueuedUpdate: suspend (SessionUpdate, JsonElement?) -> Unit) {
        queue.close(cause)
        try {
            while (true) {
                when (val item = queue.tryReceive().getOrNull() ?: break) {
                    is QueuedItem.Update -> onQueuedUpdate(item.update, item.meta)
                    is QueuedItem.Announcement -> onQueuedUpdate(item.update, item.meta)
                    is QueuedItem.Patch -> onQueuedUpdate(item.update, item.meta)
                    is QueuedItem.Opened -> {}
                }
            }
        } finally {
            sessionDeferred.completeExceptionally(cause)
        }
    }

    /**
     * Applies the `capabilities` and `state` patches that the subagents of this holder's session held back, when the
     * marker of this session's stream is delivered: after a load they were history and are dropped, otherwise they
     * take effect.
     */
    protected fun resolveHeldSubagentState() {
        val promote = root.phase.opening?.replayRequested != true
        for (child in subagents.snapshot) child.onParentOpened(promote)
    }

    /**
     * Hands one queued [item] of this holder's stream to the application through [deliver]. An announcement first
     * sets its subagent up with [operations], the operations of this holder's session, and a patch is first applied
     * to its subagent, so the application never sees an update whose effect isn't visible yet.
     *
     * @param opened whether the marker of this holder's stream has been delivered, so a subagent's `capabilities` and
     * `state` patches take effect at once rather than being held back
     */
    protected suspend fun deliverItem(
        item: QueuedItem,
        operations: ClientSessionOperations,
        opened: Boolean,
        deliver: suspend (SessionUpdate, JsonElement?) -> Unit,
    ) {
        when (item) {
            is QueuedItem.Update -> deliver(item.update, item.meta)
            is QueuedItem.Announcement -> {
                setup.setUp(this, item, operations, opened)
                deliver(item.update, item.meta)
            }
            is QueuedItem.Patch -> {
                // A subagent whose setup failed has no session object, and its patches are plain updates.
                item.child.subagent?.applyPatch(item.update, opened)
                deliver(item.update, item.meta)
            }
            // The consumers take the marker themselves, and it never reaches a completed holder.
            is QueuedItem.Opened -> error("The opening marker of session $sessionId reached the delivery of an update")
        }
    }

    /**
     * Delivers [item] to the session, or queues it while the session is initializing.
     *
     * @return `false` if the holder ended without a session, so the update belongs to no connected session
     */
    suspend fun handleOrQueue(item: QueuedItem): Boolean {
        if (queue.trySend(item).isSuccess) return true

        /*
         * The common case: the session is open and no update before this one still waits for it. An announcement
         * takes the chain below even then: its setup may suspend in `createSubagentOperations`, and the updates
         * after it must wait for the setup, as they do in the queue.
         */
        if (item !is QueuedItem.Announcement && sessionDeferred.isCompleted && lateDeliveries.value.isCompleted) {
            return deliverToSession(item)
        }

        /*
         * The channel is closed by a drain. Wait for it to finish instead of awaiting the session: the holder
         * may be failed because no initialization claimed it, and that must not fail the notification.
         * Also wait for the update that missed the channel before this one. Its waiter can resume after this
         * update arrives, so without the chain this update could overtake it.
         */
        val delivered = CompletableDeferred<Unit>()
        val previous = lateDeliveries.getAndSet(delivered)
        try {
            previous.join()
            sessionDeferred.join()
            return deliverToSession(item)
        } finally {
            delivered.complete(Unit)
        }
    }

    private suspend fun deliverToSession(item: QueuedItem): Boolean {
        @OptIn(ExperimentalCoroutinesApi::class)
        if (sessionDeferred.getCompletionExceptionOrNull() != null) return false

        @OptIn(ExperimentalCoroutinesApi::class)
        val session = sessionDeferred.getCompleted()
        session.executeWithSession {
            deliverItem(item, session.operations, opened = true) { update, meta -> session.deliverUpdate(update, meta) }
        }
        return true
    }
}

/**
 * The holder of a root session, which one opening call claims and answers, and whose close marks it closing.
 *
 * @param setup sets up the subagents announced in this session's stream
 */
internal class RootSessionHolder(sessionId: SessionId, setup: SubagentSetup) : ClientSessionHolder(sessionId, setup) {
    /**
     * The opening call that claimed a root's holder.
     *
     * @property replayRequested whether the call asked the agent to replay the history: `true` for `session/load`,
     * `false` for the other opening calls
     * @property parameters the parameters of the call, which the effective working directories of the root's
     * subagents come from. They are known from the claim on, so subagents can read them before the root's
     * [ClientSession] exists.
     */
    class Opening(val replayRequested: Boolean, val parameters: SessionCreationParameters)

    /**
     * The stages of a root's lifecycle, in order: [claimForOpening] leads to [CLAIMED], [markOpened] to [ANSWERED],
     * and [markClosing] to [CLOSING].
     */
    enum class Stage { UNCLAIMED, CLAIMED, ANSWERED, CLOSING }

    /**
     * One snapshot of a root's lifecycle. [opening] is `null` only before the claim.
     */
    class RootPhase private constructor(private val stage: Stage, val opening: Opening?) {
        /**
         * Whether the root has reached [stage]: a closing root has also been answered.
         */
        fun isAtLeast(stage: Stage): Boolean = this.stage >= stage

        /**
         * The phase after the claim by [opening], or `null` if a call has already claimed the root.
         */
        fun claimedBy(opening: Opening): RootPhase? =
            if (stage == Stage.UNCLAIMED) RootPhase(Stage.CLAIMED, opening) else null

        /**
         * The phase after the answer, or `null` unless the root is claimed and not answered yet.
         */
        fun answered(): RootPhase? = if (stage == Stage.CLAIMED) RootPhase(Stage.ANSWERED, opening) else null

        /**
         * The phase once the close has started, or `null` if the root isn't answered yet. A closing root stays as it
         * is, for a close that follows a failed one or runs beside another.
         */
        fun closing(): RootPhase? = when (stage) {
            Stage.UNCLAIMED, Stage.CLAIMED -> null
            Stage.ANSWERED -> RootPhase(Stage.CLOSING, opening)
            Stage.CLOSING -> this
        }

        companion object {
            /**
             * The phase of a new holder.
             */
            val UNCLAIMED: RootPhase = RootPhase(Stage.UNCLAIMED, opening = null)
        }
    }

    private val _phase = atomic(RootPhase.UNCLAIMED)

    /*
     * The last session state that the updates of an early delivery carried. Only the consumer in [deliverEarly]
     * writes them, and [applyEarlyState] reads them after that consumer has finished. Unlike the other fields,
     * they need no atomic: the join of the consumer orders its writes before the read, and nothing reads them
     * concurrently.
     */
    // TODO remove together with the deprecated ClientSession.currentMode.
    private var earlyMode: SessionUpdate.CurrentModeUpdate? = null
    // TODO remove together with the deprecated ClientSession.configOptions.
    @OptIn(UnstableApi::class)
    private var earlyConfigOptions: SessionUpdate.ConfigOptionUpdate? = null

    /**
     * The current snapshot of this root's lifecycle. Read it once for a decision that needs both its stage and its
     * opening.
     */
    val phase: RootPhase get() = _phase.value

    override val root: RootSessionHolder get() = this

    override val blocksId: Boolean get() = !isFailed

    override fun sessionCwd(): String = claimedParameters().cwd

    override fun sessionAdditionalDirectories(): List<String> = claimedParameters().additionalDirectories ?: emptyList()

    private fun claimedParameters(): SessionCreationParameters =
        checkNotNull(phase.opening) { "Session $sessionId is not claimed" }.parameters

    /**
     * Records that the close of this root has started, before the subagents' permissions are closed, so a subagent
     * registered from now on has its permissions closed by its registration. The root stays closing if the close
     * fails, and a later close leaves it so.
     *
     * Only the close of this root's own [ClientSession] calls this, and that session exists only after the answer.
     *
     * @throws IllegalStateException if the root isn't answered yet
     */
    fun markClosing() {
        _phase.update { current -> checkNotNull(current.closing()) { "Root session $sessionId is closed before its answer" } }
    }

    /**
     * Makes the opening call that asked for [replayRequested] with [parameters] the owner of this holder.
     *
     * Only a root's holder that has no owner and hasn't completed can be claimed: the response hook can put the
     * marker only into an open queue, and two openings of one id would race for it.
     *
     * @return `false` if the holder already has an owner or has completed. The holder is then unchanged.
     */
    fun claimForOpening(replayRequested: Boolean, parameters: SessionCreationParameters): Boolean {
        if (sessionDeferred.isCompleted) return false
        val unclaimed = phase
        val claimed = unclaimed.claimedBy(Opening(replayRequested, parameters)) ?: return false
        return _phase.compareAndSet(unclaimed, claimed)
    }

    /**
     * Puts the [QueuedItem.Opened] marker with [response] into the queue, after every update that has reached the
     * holder, records that the opening call was answered, and puts a marker without a response into the queue of
     * every subagent below, which resolves their held-back permission state too.
     *
     * The response hook of the opening call calls this in the read loop, so it must not suspend or block. The
     * `session/update` handler registers subagents in read-loop order too, so each subagent gets its marker exactly
     * once: from here, or at its registration after the answer.
     *
     * @return `false` if the queue is closed because the holder failed. The marker is then dropped, and the holder
     * stays claimed.
     * @throws IllegalStateException if no call has claimed the holder, or it was already answered
     */
    fun markOpened(response: AcpCreatedSessionResponse): Boolean {
        val claimed = phase
        val answered = checkNotNull(claimed.answered()) { "Root session $sessionId is answered without a claim, or twice" }
        if (queue.trySend(QueuedItem.Opened(response)).isFailure) return false
        check(_phase.compareAndSet(claimed, answered)) { "The phase of root session $sessionId changed during its answer" }

        /*
         * Every subagent's queue is still open here: a subagent's consumer closes it only after it has delivered
         * this marker, and a failed one's is closed, so it is skipped.
         */
        val promote = answered.opening?.replayRequested != true
        forEachDescendant { it.markSubagentOpened(promote) }
        return true
    }

    /**
     * Hands the updates queued before the marker to [session], in queue order, and returns the marker without
     * delivering it. Pass the marker to [completeOpening].
     *
     * Must run after the opening call was answered, so the marker is in the queue. A failure of the session's
     * operations fails this call, and the remaining items stay in the queue for [failAndDrainQueue]. As in
     * [completeOpening], a cancellation that the operations throw while the caller is active becomes an
     * [IllegalStateException].
     */
    suspend fun drainHistory(session: ClientSessionImpl): QueuedItem.Opened {
        while (true) {
            when (val item = queue.receive()) {
                is QueuedItem.Opened -> return item
                else -> deliverOpening(session) {
                    deliverItem(item, session.operations, opened = false) { update, meta -> session.deliverUpdate(update, meta) }
                }
            }
        }
    }

    /**
     * Hands each queued update and each arriving update to [operations], one at a time and in arrival order, until
     * it takes the marker, and returns the marker without delivering it: only the opening call knows whether it
     * succeeded. Pass the marker to [completeOpening] once it has.
     *
     * The holder must be claimed. If [operations] throws, the remaining items stay in the queue for
     * [failAndDrainQueue].
     *
     * @return `null` if the queue was closed before the marker
     */
    suspend fun deliverEarly(operations: ClientSessionOperations): QueuedItem.Opened? {
        while (true) {
            when (val item = queue.receiveCatching().getOrNull() ?: return null) {
                is QueuedItem.Opened -> return item
                else -> deliverItem(item, operations, opened = false) { update, meta ->
                    @OptIn(UnstableApi::class)
                    when (update) {
                        // TODO remove together with the deprecated ClientSession.currentMode.
                        is SessionUpdate.CurrentModeUpdate -> earlyMode = update
                        // TODO remove together with the deprecated ClientSession.configOptions.
                        is SessionUpdate.ConfigOptionUpdate -> earlyConfigOptions = update
                        else -> {}
                    }
                    operations.notify(update, meta)
                }
            }
        }
    }

    // TODO remove together with the deprecated ClientSession.currentMode and configOptions.
    /**
     * Applies the session state that the updates of [deliverEarly] carried to [session]. Must run after
     * [deliverEarly] has returned.
     */
    fun applyEarlyState(session: ClientSessionImpl) {
        // TODO remove together with the deprecated ClientSession.currentMode.
        earlyMode?.let(session::trackState)
        // TODO remove together with the deprecated ClientSession.configOptions.
        earlyConfigOptions?.let(session::trackState)
    }

    /**
     * Delivers [opened] and the updates queued after it to [session], then makes [session] the receiver of all
     * later updates. The subagents' held-back state is resolved just before [opened] is delivered.
     *
     * Must run after the request of the opening call has returned, and after its consumer, [deliverEarly] or
     * [drainHistory], returned [opened]. The operations run in the caller's coroutine, so this can be cancelled. If
     * the caller is cancelled or the operations throw, this throws and the opening fails, even though the operations
     * may already have the marker: the caller must then fail the holder with
     * [ClientSessionRegistry.failSessionTree], which hands the rest of the queue on. A cancellation that the
     * operations throw while the caller is active becomes an [IllegalStateException], so the caller sees a failed
     * opening rather than a cancellation of its own.
     */
    suspend fun completeOpening(session: ClientSessionImpl, opened: QueuedItem.Opened) {
        val response = checkNotNull(opened.response) { "The opening marker of root session ${session.sessionId} has no answer" }
        deliverOpening(session) {
            resolveHeldSubagentState()
            // No prompt can be active yet, so the marker goes to `notify`.
            session.operations.notify(SessionEvent.Opened(response))
        }
        queue.close()
        for (item in queue) {
            when (item) {
                is QueuedItem.Opened -> logger.error { "Session ${session.sessionId} got a second opening marker" }
                else -> deliverOpening(session) {
                    deliverItem(item, session.operations, opened = true) { update, meta -> session.deliverUpdate(update, meta) }
                }
            }
        }

        // A cancellation that came after the last delivery still fails the opening, before the session is published.
        currentCoroutineContext().ensureActive()
        check(sessionDeferred.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    private suspend fun deliverOpening(session: BaseClientSession, block: suspend () -> Unit) {
        try {
            session.executeWithSession(block)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("ClientSessionOperations.notify was cancelled", ce)
        }
    }
}

/**
 * The holder of a subagent session, registered under the holder of the session it was announced in.
 *
 * @property parent the holder of the session this subagent was announced in
 * @param setup sets up the subagents announced in this subagent's stream
 */
internal class SubagentSessionHolder(
    sessionId: SessionId,
    val parent: ClientSessionHolder,
    setup: SubagentSetup,
) : ClientSessionHolder(sessionId, setup) {
    private sealed class ConsumerState {
        object NotStarted : ConsumerState()
        class Running(val job: Job) : ConsumerState()
        object Stopped : ConsumerState()
    }

    // The consumer of this subagent's queue; a root's consumer is its opening call.
    private val consumer = atomic<ConsumerState>(ConsumerState.NotStarted)
    private val builtSubagent = atomic<ClientSubagentSession?>(null)

    /**
     * The pending permission requests of this subagent. A load or resume of the root needs new holders, so it starts
     * with fresh ones. The registry drives them at arrival, and the setup hands them to the [ClientSubagentSession].
     */
    val permissions: SubagentPendingPermissions = SubagentPendingPermissions()

    override val root: RootSessionHolder get() = parent.root

    /**
     * The subagent session that the setup built for this holder, `null` while it isn't set up yet and when its setup
     * failed. It exists before the holder completes.
     */
    val subagent: ClientSubagentSession? get() = builtSubagent.value

    override val blocksId: Boolean get() = !isFailed || !parent.isFailed

    override fun sessionCwd(): String = checkNotNull(subagent) { "Subagent session $sessionId is not set up" }.cwd

    override fun sessionAdditionalDirectories(): List<String> =
        checkNotNull(subagent) { "Subagent session $sessionId is not set up" }.additionalDirectories

    /**
     * Puts the [QueuedItem.Opened] marker of a subagent into its queue, and resolves its held-back permission state.
     *
     * @param promote whether the root's opening call replayed no history, so held-back values take effect
     */
    fun markSubagentOpened(promote: Boolean) {
        if (queue.trySend(QueuedItem.Opened(null)).isSuccess) permissions.onOpened(promote)
    }

    /**
     * Records the subagent session that the setup built for this holder, before its consumer starts.
     */
    fun attachSubagent(session: ClientSubagentSession) {
        check(builtSubagent.compareAndSet(null, session)) { "Subagent session $sessionId is already set up" }
    }

    /**
     * Starts the consumer [block] of this subagent's queue in [scope], unless [stopConsumer] has run: a holder that
     * failed or closed gets no consumer. The consumer's context has a [SubagentConsumer] of this holder.
     *
     * @return `false` if [stopConsumer] has run, so no consumer started
     */
    fun startConsumer(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Boolean {
        val job = scope.launch(SubagentConsumer(this), start = CoroutineStart.LAZY, block = block)
        val started = consumer.compareAndSet(ConsumerState.NotStarted, ConsumerState.Running(job))
        if (started) job.start() else job.cancel()
        return started
    }

    /**
     * Keeps a consumer from starting, and returns the running one, if any, for the caller to cancel.
     */
    fun stopConsumer(): Job? = (consumer.getAndSet(ConsumerState.Stopped) as? ConsumerState.Running)?.job

    /**
     * Delivers this subagent's queue to [session]'s operations, and sets up the subagents announced in it, until the
     * marker; delivers the marker; and once the parent's holder has completed, closes the queue, delivers the rest
     * and completes this holder.
     *
     * The calls run with [session] in the context. A failure before the marker is delivered fails this call, and
     * the caller fails the holder; a failure after it is logged, as for any live update.
     */
    suspend fun consumeSubagentQueue(session: ClientSubagentSession) {
        val deliver: suspend (SessionUpdate, JsonElement?) -> Unit = { update, meta -> session.operations.notify(update, meta) }

        val opened = session.executeWithSession {
            while (true) {
                /*
                 * A receive that finds an item doesn't check for cancellation, and the failure cascade doesn't join
                 * this consumer, so check before each item.
                 */
                currentCoroutineContext().ensureActive()
                when (val item = queue.receiveCatching().getOrNull() ?: return@executeWithSession false) {
                    is QueuedItem.Opened -> break
                    else -> deliverItem(item, session.operations, opened = false, deliver)
                }
            }
            resolveHeldSubagentState()
            session.operations.notify(SessionEvent.Opened(null))
            true
        }
        // The queue was closed because this holder failed.
        if (!opened) return

        /*
         * The root's opening can still fail after its answer, while it delivers its marker and the updates queued
         * behind it, and then every holder below fails with it. So the later updates stay queued, and this holder stays
         * incomplete, until the parent's holder has completed.
         */
        parent.session.join()
        if (parent.isFailed) return

        queue.close()
        /*
         * Check for cancellation before taking each item, and nowhere between taking it and handing it to the
         * operations, so one loop runs in one context: an item that a stopped consumer took would reach neither the
         * session nor the drain of [failAndDrainQueue].
         */
        session.executeWithSession {
            while (true) {
                currentCoroutineContext().ensureActive()
                val item = queue.tryReceive().getOrNull() ?: break
                deliverLive(item, session.operations, deliver)
            }
        }
        currentCoroutineContext().ensureActive()
        sessionDeferred.complete(session)
    }

    /**
     * Delivers a live [item] of a subagent's queue, logging a failure as for any live update.
     */
    private suspend fun deliverLive(
        item: QueuedItem,
        operations: ClientSessionOperations,
        deliver: suspend (SessionUpdate, JsonElement?) -> Unit,
    ) {
        try {
            deliverItem(item, operations, opened = true, deliver)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of session $sessionId was cancelled" }
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of session $sessionId failed" }
        }
    }
}
