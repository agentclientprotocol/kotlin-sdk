@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.SubagentOperations
import com.agentclientprotocol.client.internal.RootSessionHolder.Stage
import com.agentclientprotocol.common.ClientSessionOperations
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement

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
