@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.RootSessionHolder.Stage
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
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
 *
 * Every item is either a [Received] update, which the agent sent, or the [Opened] marker, which the SDK adds.
 */
internal sealed class QueuedItem {
    /**
     * A `session/update` that arrived from the agent.
     */
    sealed class Received : QueuedItem() {
        /**
         * The update that arrived.
         */
        abstract val update: SessionUpdate

        /**
         * The `_meta` of the notification that carried [update].
         */
        abstract val meta: JsonElement?
    }

    /**
     * A `session/update` of the holder's session that needs nothing but its delivery.
     */
    class Update(override val update: SessionUpdate, override val meta: JsonElement?) : Received()

    /**
     * The `subagent_update` that announced [child], whose holder its arrival registered. The consumer that reaches
     * it sets the subagent up, see [ClientSessionHolder.setUpSubagent], and then delivers [update].
     */
    class Announcement(
        override val update: SessionUpdate.SubagentUpdate,
        override val meta: JsonElement?,
        val child: SubagentSessionHolder,
    ) : Received()

    /**
     * A later `subagent_update` of [child]. The consumer applies it to the subagent, if its setup succeeded, and
     * then delivers [update].
     */
    class Patch(
        override val update: SessionUpdate.SubagentUpdate,
        override val meta: JsonElement?,
        val child: SubagentSessionHolder,
    ) : Received()

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
 * Marks the coroutine of the opening of [holder], and everything that inherits its context, so a close of the root
 * that the subagent's own operations start there can tell that it runs in that opening, see
 * [ClientSessionRegistry.removeSessionTree].
 */
internal class SubagentOpening(val holder: SubagentSessionHolder) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubagentOpening>
}

/**
 * The client's slot for one session id, a root or a subagent: buffers its updates until the session is created, then
 * delivers them to it.
 *
 * ### Kinds
 *
 * - A [RootSessionHolder] is created by [ClientSessionRegistry] when it starts initializing a session, and
 *   speculatively for an unknown session id that receives an update while some initialization is in progress. One
 *   opening call at most claims it, see [RootSessionHolder.claimForOpening].
 * - A [SubagentSessionHolder] is created only when the `session/update` handler registers it under its parent. No
 *   call claims it.
 *
 * ### Queue
 *
 * Every update that arrives before the session exists goes into one queue, and so does the [QueuedItem.Opened] marker
 * of the root's answer. The queue has one consumer at a time:
 * - for a root with ready operations (a load): [RootSessionHolder.deliverEarly], until the opened marker, then
 *   [RootSessionHolder.completeOpening] with that marker;
 * - for a root with a factory: [RootSessionHolder.drainHistory], until the opened marker, then
 *   [RootSessionHolder.completeOpening] with that marker;
 * - for a subagent: its opening, which the parent's consumer starts with [SubagentSessionHolder.startOpening] once it
 *   has set the subagent up.
 *
 * The consumer closes the queue to switch to the session.
 *
 * ### Late updates
 *
 * An update that misses the closed queue waits until the consumer has finished and until the update that missed the
 * queue before it is delivered, and then goes to the session. So the updates keep the order in which their handlers
 * reach the holder, and the opened marker keeps its place among them.
 *
 * ### Subagents
 *
 * Each holder keeps its subagents in two collections of one state, see [Children]:
 * - the holders by id, failed ones included, which [RootSessionHolder.markOpened], the failure cascade of
 *   [ClientSessionRegistry.failSessionTree] and the close of the root walk;
 * - the [subagents] that the application sees, in announcement order.
 *
 * @property sessionId the id of this holder's session
 * @property protocol the connection, for the subagents' `session/cancel`
 * @property openingScope the scope of the subagents' openings. It belongs to the connection, so they end with it, and
 * they don't run on the protocol's handler thread.
 * @property failSessionTree fails the holder of a subagent whose setup or opening failed, and every holder below it,
 * see [ClientSessionRegistry.failSessionTree]
 */
internal sealed class ClientSessionHolder(
    val sessionId: SessionId,
    val protocol: Protocol,
    val openingScope: CoroutineScope,
    val failSessionTree: suspend (ClientSessionHolder, Throwable) -> Unit,
) {
    /**
     * The subagents of a holder, in one atomic state, so [sealChildren] stops both collections in one update.
     *
     * @property byId the holders that the `session/update` handler registered when their announcements arrived,
     * failed ones included
     * @property listed the subagent sessions that the parent's consumer set up when it reached their announcements,
     * in that order
     * @property sealed whether the holder takes no subagents any more, because it failed or its root was closed
     */
    private data class Children(
        val byId: PersistentMap<SessionId, SubagentSessionHolder> = persistentMapOf(),
        val listed: PersistentList<ClientSubagentSession> = persistentListOf(),
        val sealed: Boolean = false,
    )

    protected val _session: CompletableDeferred<BaseClientSession> = CompletableDeferred()

    /**
     * The session of this holder.
     *
     * It completes when the session takes over the holder's updates, after the queue is drained, and fails when the
     * holder fails. Only the holder completes it: callers outside wait on it or read its state.
     */
    val session: Deferred<BaseClientSession> get() = _session

    /*
     * The queue is unbounded on purpose. With a bounded one, an agent that sends more updates than its capacity before
     * it answers the opening call fills it, and the `session/update` handler then suspends in the read loop. The loop
     * never reads the response of the opening call, which is what starts the drain, so nothing ever frees the queue.
     * Avoiding that would mean reordering messages, which the protocol layer can't do, so the queue pays with memory.
     */
    protected val queue = Channel<QueuedItem>(capacity = Channel.UNLIMITED)

    /*
     * The delivery of the last update that missed the closed queue, see [handleOrQueue]. The next such update waits
     * for it, so late updates keep their order.
     */
    private val lateDeliveries = atomic<Job>(Job().apply { complete() })
    private val children = atomic(Children())

    /**
     * The [BaseClientSession.subagents] of this holder's session, in announcement order. It lives here, because
     * subagents are set up while the history of a loading root is delivered, before the root's [ClientSession]
     * exists.
     *
     * It only grows, until [sealChildren] runs, and a later listing does not change a returned list.
     */
    val subagents: List<ClientSubagentSession> get() = children.value.listed

    /**
     * The holder of the root session this holder belongs to, itself for a root.
     */
    abstract val root: RootSessionHolder

    /**
     * Whether the opening of this holder failed, so a new opening of the session id needs a new holder.
     */
    val isFailed: Boolean
        @OptIn(ExperimentalCoroutinesApi::class)
        get() = _session.isCompleted && _session.getCompletionExceptionOrNull() != null

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
     * Adds [child] to this holder's subagents, and closes its permissions if the close of the root has started.
     *
     * The add and [RootSessionHolder.startClosing] each write first and read the other's write second: this reads the
     * root's stage after the add, and the walk of `startClosing` runs after it moved the root to [Stage.CLOSING]. So a
     * child that the walk misses sees the stage here, and has its permissions closed from the start.
     *
     * @return `false` if this holder is sealed, so [child] was not added
     */
    fun addChild(child: SubagentSessionHolder): Boolean {
        var added = false
        children.update { current ->
            added = !current.sealed
            if (added) current.copy(byId = current.byId.put(child.sessionId, child)) else current
        }
        if (!added) return false

        if (root.phase.isAtLeast(Stage.CLOSING)) child.permissions.close()
        return true
    }

    /**
     * Removes the subagent [id] that [addChild] added, when its registration is rejected.
     */
    fun removeChild(id: SessionId) {
        children.update { it.copy(byId = it.byId.remove(id)) }
    }

    /**
     * Adds the set-up [subagent] to [subagents].
     *
     * @return `false` if this holder is sealed, so [subagent] was not listed
     */
    fun listSubagent(subagent: ClientSubagentSession): Boolean {
        var listed = false
        children.update { current ->
            listed = !current.sealed
            if (listed) current.copy(listed = current.listed.add(subagent)) else current
        }
        return listed
    }

    /**
     * Stops taking subagents, both by [addChild] and by [listSubagent], and returns the holders registered so far.
     */
    fun sealChildren(): Collection<SubagentSessionHolder> = children.getAndUpdate { it.copy(sealed = true) }.byId.values

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
     * its opening failed. A queued opened marker is dropped, because a failed opening delivers none.
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
                    is QueuedItem.Received -> onQueuedUpdate(item.update, item.meta)
                    is QueuedItem.Opened -> {}
                }
            }
        } finally {
            _session.completeExceptionally(cause)
        }
    }

    /**
     * Applies the `capabilities` and `state` patches that the subagents of this holder's session held back, when the
     * opened marker of this session's stream is delivered: after a load they were history and are dropped, otherwise
     * they take effect.
     */
    protected fun resolveHeldSubagentState() {
        val promote = root.phase.opening?.replayRequested != true
        for (child in subagents) child.onParentOpened(promote)
    }

    /**
     * Hands one queued [item] of this holder's stream to [session], as [deliverUpdate] does. An announcement first sets
     * its subagent up with the operations of [session], and a patch is first applied to its subagent, so the
     * application never sees an update whose effect isn't visible yet.
     *
     * @param opened whether the opened marker of this holder's stream has been delivered, so a subagent's
     * `capabilities` and `state` patches take effect at once rather than being held back
     */
    protected suspend fun deliverItem(item: QueuedItem, session: BaseClientSession, opened: Boolean) {
        deliverItem(item, session.operations, opened) { update, meta -> session.deliverUpdate(update, meta) }
    }

    /**
     * Like the overload for a session, for a consumer that has no session object yet: [deliver] hands the update on, and
     * [operations] are the operations of this holder's session.
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
                setUpSubagent(item, operations, opened)
                deliver(item.update, item.meta)
            }
            is QueuedItem.Patch -> {
                item.child.applyPatch(item.update, opened)
                deliver(item.update, item.meta)
            }
            // The consumers take the opened marker themselves, and it never reaches a completed holder.
            is QueuedItem.Opened -> error("The opened marker of session $sessionId reached the delivery of an update")
        }
    }

    /**
     * Sets up the subagent of [announcement], announced in this holder's stream, whose session has [operations].
     *
     * ### Steps
     *
     * 1. Creates the subagent's operations with [SubagentOperations.createSubagentOperations].
     * 2. Builds its [ClientSubagentSession], with the announcement's patch applied.
     * 3. Adds the session to this holder's `subagents`.
     * 4. Starts its opening, see [SubagentSessionHolder.startOpening].
     *
     * The caller delivers the announcement afterwards, so inside this session's `notify` for it the subagent exists.
     *
     * ### Failure
     *
     * - [operations] don't implement [SubagentOperations], or the factory throws: only the subagent's holder fails,
     *   with the holders below it, and the failure is logged. A [CancellationException] from the factory while the
     *   caller is active counts as a throw.
     * - The caller is cancelled and the factory throws a [CancellationException]: the cancellation is rethrown.
     * - This holder failed or was closed while the factory ran: the subagent is neither listed nor opened, and its
     *   holder never gets the session.
     * - The subagent's holder failed, or was removed with its root's tree, after the listing: the subagent is listed,
     *   but closed, and not opened.
     *
     * @param parentOpened whether the opened marker of this holder's stream has been delivered
     */
    private suspend fun setUpSubagent(
        announcement: QueuedItem.Announcement,
        operations: ClientSessionOperations,
        parentOpened: Boolean,
    ) {
        val child = announcement.child
        val childId = child.sessionId
        val factory = operations as? SubagentOperations
        if (factory == null) {
            val cause = IllegalStateException(
                "The operations of session $sessionId don't implement ${SubagentOperations::class.simpleName}, " +
                    "so its subagent session $childId can't be set up"
            )
            logger.error { cause.message }
            failSessionTree(child, cause)
            return
        }

        val childOperations = try {
            factory.createSubagentOperations(childId)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            failSetup(child, IllegalStateException("createSubagentOperations was cancelled", ce))
            return
        } catch (t: Throwable) {
            failSetup(child, t)
            return
        }

        val session = ClientSubagentSession(child, childOperations, protocol)
        session.applyPatch(announcement.update, parentOpened)
        /*
         * This holder failed or was closed while the factory ran. That failure or close also ends the subagent's
         * holder, so the subagent is dropped: this holder's list stays as it was, the subagent's holder doesn't keep
         * the session, and the subagent gets no opening.
         */
        if (!listSubagent(session)) {
            session.markClosed()
            return
        }
        child.startOpening(session)
    }

    private suspend fun failSetup(child: SubagentSessionHolder, cause: Throwable) {
        logger.error(cause) { "Failed to create the operations of subagent session ${child.sessionId}" }
        failSessionTree(child, IllegalStateException("Failed to set up subagent session ${child.sessionId}", cause))
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
        if (item !is QueuedItem.Announcement && _session.isCompleted && lateDeliveries.value.isCompleted) {
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
            _session.join()
            return deliverToSession(item)
        } finally {
            delivered.complete(Unit)
        }
    }

    private suspend fun deliverToSession(item: QueuedItem): Boolean {
        @OptIn(ExperimentalCoroutinesApi::class)
        if (_session.getCompletionExceptionOrNull() != null) return false

        @OptIn(ExperimentalCoroutinesApi::class)
        val session = _session.getCompleted()
        session.executeWithSession {
            deliverItem(item, session, opened = true)
        }
        return true
    }
}

/**
 * The holder of a root session, which one opening call claims and answers, and whose close marks it closing.
 */
internal class RootSessionHolder(
    sessionId: SessionId,
    protocol: Protocol,
    openingScope: CoroutineScope,
    failSessionTree: suspend (ClientSessionHolder, Throwable) -> Unit,
) : ClientSessionHolder(sessionId, protocol, openingScope, failSessionTree) {
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
     * and [startClosing] to [CLOSING].
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
     * Starts the close of this root: moves it to [Stage.CLOSING], and then answers the pending and the later permission
     * requests of every subagent below it with `cancelled`. Each close only completes a signal, so the order of the
     * walk doesn't matter.
     *
     * The stage comes first, so a subagent that this walk misses has its permissions closed by its own registration,
     * see [addChild]. The root stays closing if the close fails, as the closed permissions do, and a later close leaves
     * it so.
     *
     * Only the close of this root's own [ClientSession] calls this, and that session exists only after the answer.
     *
     * @throws IllegalStateException if the root isn't answered yet
     */
    fun startClosing() {
        _phase.update { current -> checkNotNull(current.closing()) { "Root session $sessionId is closed before its answer" } }
        forEachDescendant { it.permissions.close() }
    }

    /**
     * Makes the opening call that asked for [replayRequested] with [parameters] the owner of this holder.
     *
     * Only a root's holder that has no owner and hasn't completed can be claimed: the response hook can put the
     * opened marker only into an open queue, and two openings of one id would race for it.
     *
     * @return `false` if the holder already has an owner or has completed. The holder is then unchanged.
     */
    fun claimForOpening(replayRequested: Boolean, parameters: SessionCreationParameters): Boolean {
        if (_session.isCompleted) return false
        val unclaimed = phase
        val claimed = unclaimed.claimedBy(Opening(replayRequested, parameters)) ?: return false
        return _phase.compareAndSet(unclaimed, claimed)
    }

    /**
     * Puts the [QueuedItem.Opened] marker with [response] into the queue, after every update that has reached the
     * holder, records that the opening call was answered, and puts an opened marker without a response into the queue
     * of every subagent below, which resolves their held-back permission state too.
     *
     * The response hook of the opening call calls this in the read loop, so it must not suspend or block.
     *
     * Each subagent gets its opened marker exactly once:
     * - a subagent registered before the answer is in the tree, and gets it from the walk here;
     * - a subagent registered after the answer gets it in its holder's `init`.
     *
     * Both run in read-loop order, this in the response hook and the registration in the `session/update` handler
     * before it suspends, so they never interleave.
     *
     * @return `false` if the queue is closed because the holder failed. The opened marker is then dropped, and the
     * holder stays claimed.
     * @throws IllegalStateException if no call has claimed the holder, or it was already answered
     */
    fun markOpened(response: AcpCreatedSessionResponse): Boolean {
        val claimed = phase
        val answered = checkNotNull(claimed.answered()) { "Root session $sessionId is answered without a claim, or twice" }
        if (queue.trySend(QueuedItem.Opened(response)).isFailure) return false
        check(_phase.compareAndSet(claimed, answered)) { "The phase of root session $sessionId changed during its answer" }

        /*
         * Every subagent's queue is still open here: a subagent's opening closes it only after it has delivered
         * this opened marker, and a failed one's is closed, so it is skipped.
         */
        val promote = answered.opening?.replayRequested != true
        forEachDescendant { it.markOpened(promote) }
        return true
    }

    /**
     * Hands the updates queued before the opened marker to [session], in queue order, and returns the marker without
     * delivering it. Pass the marker to [completeOpening].
     *
     * Must run after the opening call was answered, so the opened marker is in the queue. A failure of the session's
     * operations fails this call, and the remaining items stay in the queue for [failAndDrainQueue]. As in
     * [completeOpening], a cancellation that the operations throw while the caller is active becomes an
     * [IllegalStateException].
     */
    suspend fun drainHistory(session: ClientSessionImpl): QueuedItem.Opened {
        while (true) {
            when (val item = queue.receive()) {
                is QueuedItem.Opened -> return item
                else -> deliverDuringOpening(session) { deliverItem(item, session, opened = false) }
            }
        }
    }

    /**
     * Hands each queued update and each arriving update to [operations], one at a time and in arrival order, until
     * it takes the opened marker, and returns that marker without delivering it: only the opening call knows whether
     * it succeeded. Pass the marker to [completeOpening] once it has.
     *
     * The holder must be claimed. If [operations] throws, the remaining items stay in the queue for
     * [failAndDrainQueue].
     *
     * @return `null` if the queue was closed before the opened marker
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
     * Must run after:
     * - the request of the opening call has returned;
     * - the consumer of the queue, [deliverEarly] or [drainHistory], has returned [opened].
     *
     * The operations run in the caller's coroutine, so this can be cancelled. This throws, and the opening fails,
     * when:
     * - the caller is cancelled;
     * - the operations throw. A cancellation that they throw while the caller is active becomes an
     *   [IllegalStateException], so the caller sees a failed opening rather than a cancellation of its own.
     *
     * The operations may already have the opened marker then. The caller must fail the holder with
     * [ClientSessionRegistry.failSessionTree], which hands the rest of the queue on.
     */
    suspend fun completeOpening(session: ClientSessionImpl, opened: QueuedItem.Opened) {
        val response = checkNotNull(opened.response) { "The opened marker of root session ${session.sessionId} has no answer" }
        deliverDuringOpening(session) {
            resolveHeldSubagentState()
            // No prompt can be active yet, so the opened marker goes to `notify`.
            session.operations.notify(SessionEvent.Opened(response))
        }
        queue.close()
        for (item in queue) {
            deliverDuringOpening(session) { deliverItem(item, session, opened = true) }
        }

        // A cancellation that came after the last delivery still fails the opening, before the session is published.
        currentCoroutineContext().ensureActive()
        check(_session.complete(session)) { "Session ${session.sessionId} is already complete" }
    }

    /**
     * Runs [block], a delivery to the application during the opening, with [session] in the context.
     *
     * Only the opening uses it, in [drainHistory] and [completeOpening]. A [CancellationException] that app code throws
     * while the caller is still active becomes an [IllegalStateException], so the opening fails rather than looks like
     * the caller's own cancellation. Live deliveries log such failures instead, see
     * [SubagentSessionHolder.completeOpening].
     */
    private suspend fun deliverDuringOpening(session: BaseClientSession, block: suspend () -> Unit) {
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
 * A holder created after the root's answer takes its [QueuedItem.Opened] marker at once, as its first item, before
 * it is added to [parent] or the session map. A holder created before the answer gets it from
 * [RootSessionHolder.markOpened]. Both run in read-loop order, so each subagent gets its opened marker exactly once.
 *
 * It takes the connection's dependencies from [parent].
 *
 * @property parent the holder of the session this subagent was announced in
 */
internal class SubagentSessionHolder(
    sessionId: SessionId,
    val parent: ClientSessionHolder,
) : ClientSessionHolder(sessionId, parent.protocol, parent.openingScope, parent.failSessionTree) {
    private sealed class OpeningState {
        object NotStarted : OpeningState()
        class Running(val job: Job) : OpeningState()
        object Stopped : OpeningState()
    }

    // The opening of this subagent, which consumes its queue; a root's opening is its opening call.
    private val opening = atomic<OpeningState>(OpeningState.NotStarted)

    /*
     * The subagent session that the setup built for this holder, stored by [startOpening]. It stays `null` while the
     * holder isn't set up, when its setup failed, and when the parent took no more subagents by the end of the setup.
     * It exists before the holder completes, so patches, the working directories of nested subagents and the close
     * after a failure reach it through the holder.
     */
    private val _subagent = atomic<ClientSubagentSession?>(null)

    /**
     * The pending permission requests of this subagent, answered `cancelled` on a cancel, an `idle(cancelled)` state
     * or a close. The registry feeds them when the requests and states arrive.
     *
     * They belong to this holder, so a load or resume of the root, which builds new holders, starts with none
     * pending.
     */
    val permissions: SubagentPendingPermissions = SubagentPendingPermissions()

    init {
        val phase = root.phase
        if (phase.isAtLeast(Stage.ANSWERED)) markOpened(promote = phase.opening?.replayRequested != true)
    }

    override val root: RootSessionHolder get() = parent.root

    override val blocksId: Boolean get() = !isFailed || !parent.isFailed

    override fun sessionCwd(): String = requireSubagent().cwd

    override fun sessionAdditionalDirectories(): List<String> = requireSubagent().additionalDirectories

    private fun requireSubagent(): ClientSubagentSession =
        checkNotNull(_subagent.value) { "Subagent session $sessionId is not set up" }

    /**
     * Puts the [QueuedItem.Opened] marker of a subagent into its queue, and resolves its held-back permission state.
     *
     * @param promote whether the root's opening call replayed no history, so held-back values take effect
     */
    fun markOpened(promote: Boolean) {
        if (queue.trySend(QueuedItem.Opened(null)).isSuccess) permissions.onOpened(promote)
    }

    /**
     * Applies a later `subagent_update` of this subagent to its session, see [ClientSubagentSession.applyPatch]. A
     * subagent that isn't set up has no session object, and then its patches are plain updates.
     *
     * @param parentOpened whether the opened marker of the parent's stream has been delivered
     */
    fun applyPatch(update: SessionUpdate.SubagentUpdate, parentOpened: Boolean) {
        _subagent.value?.applyPatch(update, parentOpened)
    }

    /**
     * Closes this subagent's session, if it is set up, see [ClientSubagentSession.markClosed].
     */
    fun markClosed() {
        _subagent.value?.markClosed()
    }

    /**
     * Stores [session], the subagent session that the setup built for this holder, and then starts the opening of
     * this subagent in [openingScope], see [completeOpening], unless [markOpeningStopped] has run: a holder that
     * failed or was removed with its root's tree gets no opening, and this closes [session] instead. The opening's
     * context has a [SubagentOpening] of this holder.
     *
     * The session is stored first, so a failure that stops the opening after the store closes the session itself, and
     * a failure that stopped it before leaves the close to this call.
     */
    fun startOpening(session: ClientSubagentSession) {
        check(_subagent.compareAndSet(null, session)) { "Subagent session $sessionId is already set up" }

        val job = openingScope.launch(SubagentOpening(this), start = CoroutineStart.LAZY) { completeOpening() }
        if (opening.compareAndSet(OpeningState.NotStarted, OpeningState.Running(job))) {
            job.start()
        } else {
            job.cancel()
            /*
             * This holder failed, or was removed with its root's tree after a `session/close`, possibly before it
             * stored [session], so that failure or removal may not have closed it. The session stays in the parent's
             * `subagents`, closed, like any subagent whose holder failed.
             */
            session.markClosed()
        }
    }

    /**
     * Marks the opening stopped, so [startOpening] starts none from now on, and returns the running one, if any.
     * It doesn't cancel the opening: the caller does, and decides whether to join it.
     */
    fun markOpeningStopped(): Job? = (opening.getAndSet(OpeningState.Stopped) as? OpeningState.Running)?.job

    /**
     * Delivers this subagent's queue to its session's operations, with the session in the context of the calls:
     * - the updates queued up to the opened marker, setting up the nested subagents announced in them;
     * - the opened marker, as `SessionEvent.Opened(null)`;
     * - once the parent's holder has completed: closes the queue, delivers the rest and completes this holder.
     *
     * It is the body of the opening that [startOpening] starts, after the session is stored.
     *
     * Failures:
     * - before the opened marker is delivered, a failure is logged and fails this holder, with [failSessionTree]. A
     *   [CancellationException] that the operations throw while the opening is active counts as such a failure;
     * - after it, a failure is logged, as for any live update;
     * - if the parent's holder failed, this returns, and the failure cascade fails this holder;
     * - the opening's own cancellation is rethrown and fails nothing.
     */
    private suspend fun completeOpening() {
        try {
            val session = requireSubagent()
            val opened = session.executeWithSession {
                while (true) {
                    /*
                     * `receiveCatching` doesn't check for cancellation when it finds an item without suspending, and
                     * the client application's `notify` may return without suspending too. The failure cascade cancels
                     * this opening without joining it, so without this check a cancelled opening would keep taking
                     * items meant for the drain of [failAndDrainQueue].
                     */
                    currentCoroutineContext().ensureActive()
                    when (val item = queue.receiveCatching().getOrNull() ?: return@executeWithSession false) {
                        is QueuedItem.Opened -> break
                        else -> deliverItem(item, session, opened = false)
                    }
                }
                resolveHeldSubagentState()
                session.operations.notify(SessionEvent.Opened(null))
                true
            }
            // The queue was closed because this holder failed.
            if (!opened) return

            /*
             * The root's opening can still fail after its answer, while it delivers its opened marker and the updates
             * queued behind it, and then every holder below fails with it. So the later updates stay queued, and this
             * holder stays incomplete, until the parent's holder has completed.
             */
            parent.session.join()
            if (parent.isFailed) return

            queue.close()
            /*
             * `tryReceive` never suspends, so it never checks for cancellation, the client application's `notify` may
             * return without suspending, and [deliverLive] swallows its failures. The failure cascade cancels this
             * opening without joining it, so without this check a cancelled opening would keep taking items meant for
             * the drain of [failAndDrainQueue].
             *
             * Check before taking each item, and nowhere between taking it and handing it to the operations, so one
             * loop runs in one context: an item that a stopped opening took would reach neither the session nor the
             * drain.
             */
            session.executeWithSession {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = queue.tryReceive().getOrNull() ?: break
                    deliverLive(item, session)
                }
            }
            _session.complete(session)
        } catch (ce: CancellationException) {
            // A stopped opening's holder is already failing, and the end of the connection fails nothing.
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of subagent session $sessionId was cancelled" }
            failSessionTree(this, IllegalStateException("ClientSessionOperations.notify was cancelled", ce))
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of subagent session $sessionId failed" }
            failSessionTree(
                this,
                IllegalStateException("Failed to deliver the history of subagent session $sessionId", t),
            )
        }
    }

    /**
     * Delivers a live [item] of a subagent's queue, logging a failure as for any live update.
     */
    private suspend fun deliverLive(item: QueuedItem, session: ClientSubagentSession) {
        try {
            deliverItem(item, session, opened = true)
        } catch (ce: CancellationException) {
            /*
             * Rethrow only the opening's own cancellation; one that the client application's `notify` throws itself,
             * such as its own `withTimeout`, is logged.
             */
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of session $sessionId was cancelled" }
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of session $sessionId failed" }
        }
    }
}
