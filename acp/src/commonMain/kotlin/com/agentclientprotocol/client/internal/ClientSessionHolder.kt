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
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * An item of a [ClientSessionHolder]'s queue: an update that was sorted when it arrived, or the SDK's [Opened] marker.
 */
internal sealed class QueuedItem {
    /**
     * A `session/update` that arrived from the agent.
     */
    sealed class Received : QueuedItem() {
        abstract val update: SessionUpdate
        abstract val meta: JsonElement?
    }

    /**
     * An update that needs nothing but its delivery.
     */
    class Update(override val update: SessionUpdate, override val meta: JsonElement?) : Received()

    /**
     * The `subagent_update` that announced [child]. The consumer sets the subagent up before it delivers [update].
     */
    class Announcement(
        override val update: SessionUpdate.SubagentUpdate,
        override val meta: JsonElement?,
        val child: SubagentSessionHolder,
    ) : Received()

    /**
     * A later `subagent_update` of [child]. The consumer applies it to the subagent before it delivers [update].
     */
    class Patch(
        override val update: SessionUpdate.SubagentUpdate,
        override val meta: JsonElement?,
        val child: SubagentSessionHolder,
    ) : Received()

    /**
     * Where the answer of the root's opening call fell among the updates, delivered as [SessionEvent.Opened].
     *
     * @property response the answer, for a root; `null` for a subagent
     */
    class Opened(val response: AcpCreatedSessionResponse?) : QueuedItem()
}

/**
 * The client's slot for one session id, a root or a subagent: buffers its updates, and the opened marker, in one
 * queue until the session is created, then delivers them to it.
 *
 * The queue has one consumer at a time, which closes it to switch to the session. An update that misses the closed
 * queue waits for the consumer and for the late update before it, so the updates keep their arrival order.
 *
 * @property protocol the connection, for the subagents' `session/cancel`
 * @property openingScope the connection's scope for the subagents' openings, so they don't run on the handler thread
 * @property failSessionTree fails a subagent's holder and every holder below it, unless its opening was already
 * stopped
 */
internal sealed class ClientSessionHolder(
    val sessionId: SessionId,
    val protocol: Protocol,
    val openingScope: CoroutineScope,
    val failSessionTree: suspend (ClientSessionHolder, Throwable) -> Boolean,
) {
    /**
     * The subagents of a holder in one atomic state, so [sealChildren] seals both collections at once.
     *
     * @property byId the holders registered when their announcements arrived, failed ones included
     * @property listed the subagent sessions set up so far, in announcement order
     * @property sealed set when the holder fails or its root is closed
     */
    private data class Children(
        val byId: PersistentMap<SessionId, SubagentSessionHolder> = persistentMapOf(),
        val listed: PersistentList<ClientSubagentSession> = persistentListOf(),
        val sealed: Boolean = false,
    )

    protected val _session: CompletableDeferred<BaseClientSession> = CompletableDeferred()

    /**
     * Completes when the session takes over the holder's updates, and fails when the holder fails.
     */
    val session: Deferred<BaseClientSession> get() = _session

    /*
     * Unbounded on purpose: a full queue would suspend the read loop before it reads the opening call's answer, which
     * starts the drain.
     */
    protected val queue = Channel<QueuedItem>(capacity = Channel.UNLIMITED)

    // The delivery of the last update that missed the closed queue; the next one waits for it.
    private val lateDeliveries = atomic<Job>(Job().apply { complete() })
    private val children = atomic(Children())

    /**
     * The [BaseClientSession.subagents] of this holder's session. They live here because a loading root's history sets
     * them up before its [ClientSession] exists.
     */
    val subagents: List<ClientSubagentSession> get() = children.value.listed

    /**
     * The holder of this holder's root session, itself for a root.
     */
    abstract val root: RootSessionHolder

    /**
     * Whether the opening of this holder failed, so a new opening of the session id needs a new holder.
     */
    val isFailed: Boolean
        @OptIn(ExperimentalCoroutinesApi::class)
        get() = _session.isCompleted && _session.getCompletionExceptionOrNull() != null

    /**
     * Whether this holder still owns its session id. A failed root gives it up, and a failed subagent keeps it while
     * its parent lives.
     */
    abstract val blocksId: Boolean

    /**
     * The effective working directory of this holder's session, which its subagents inherit.
     */
    abstract fun sessionCwd(): String

    /**
     * The effective additional directories of this holder's session, which its subagents inherit.
     */
    abstract fun sessionAdditionalDirectories(): List<String>

    fun childOrNull(id: SessionId): SubagentSessionHolder? = children.value.byId[id]

    /**
     * Adds [child] to this holder's subagents, and closes its permissions if the root's close has started.
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

        // Read after the add: `startClosing` sets the stage before its walk, so a child the walk misses is closed here.
        if (root.phase.isAtLeast(Stage.CLOSING)) child.permissions.close()
        return true
    }

    /**
     * Removes the subagent [id] whose registration was rejected.
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
     * Stops taking subagents and returns the holders registered so far.
     */
    fun sealChildren(): Collection<SubagentSessionHolder> = children.getAndUpdate { it.copy(sealed = true) }.byId.values

    /**
     * Runs [action] on every subagent below this holder, failed ones included, parents first.
     */
    fun forEachDescendant(action: (SubagentSessionHolder) -> Unit) {
        for (child in children.value.byId.values) {
            action(child)
            child.forEachDescendant(action)
        }
    }

    /**
     * Fails the holder with [cause] and hands its queued updates, which belong to no live session, to [onQueuedUpdate].
     */
    suspend fun failAndDrainQueue(cause: Throwable, onQueuedUpdate: suspend (SessionUpdate, JsonElement?) -> Unit) {
        // Closed first, so no update is queued after the drain and lost.
        queue.close(cause)
        try {
            while (true) {
                when (val item = queue.tryReceive().getOrNull() ?: break) {
                    is QueuedItem.Received -> onQueuedUpdate(item.update, item.meta)
                    // A failed opening delivers no opened marker.
                    is QueuedItem.Opened -> {}
                }
            }
        } finally {
            // Failed after the drain, so the late updates in [handleOrQueue] follow the queued ones.
            _session.completeExceptionally(cause)
        }
    }

    /**
     * Resolves the subagent patches held back until this stream's opened marker: dropped after a load, applied
     * otherwise.
     */
    protected fun resolveHeldSubagentState() {
        val promote = root.phase.opening?.replayRequested != true
        for (child in subagents) child.onParentOpened(promote)
    }

    /**
     * Hands one queued [item] to [session]. An announcement sets its subagent up first, and a patch is applied first,
     * so the application never sees an update before its effect.
     *
     * @param opened whether this stream's opened marker was delivered, so subagent patches are no longer held back
     */
    protected suspend fun deliverItem(item: QueuedItem, session: BaseClientSession, opened: Boolean) {
        deliverItem(item, session.operations, opened) { update, meta -> session.deliverUpdate(update, meta) }
    }

    /**
     * Like the overload for a session, for a consumer that has no session object yet.
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
            // The consumers take the opened marker themselves.
            is QueuedItem.Opened -> error("The opened marker of session $sessionId reached the delivery of an update")
        }
    }

    /**
     * Sets up the subagent of [announcement] before its delivery, so the subagent exists inside this session's
     * `notify` for it: creates its operations, builds its session, lists it, and starts its opening.
     *
     * A failed setup fails only the subagent's holder, with the holders below it.
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
            // Only the caller's own cancellation propagates; the factory's counts as a failure.
            currentCoroutineContext().ensureActive()
            failSetup(child, IllegalStateException("createSubagentOperations was cancelled", ce))
            return
        } catch (t: Throwable) {
            failSetup(child, t)
            return
        }

        val session = ClientSubagentSession(child, childOperations, protocol)
        session.applyPatch(announcement.update, parentOpened)
        // This holder failed or was closed while the factory ran, which ends the subagent's holder too.
        if (!listSubagent(session)) {
            session.markClosed()
            return
        }
        child.startOpening(session)
    }

    private suspend fun failSetup(child: SubagentSessionHolder, cause: Throwable) {
        /*
         * A cancelled caller's cause comes from the cancellation, even if wrapped. The cascade or the close then fails
         * the child, and the end of the connection fails nothing.
         */
        val failed = currentCoroutineContext().isActive &&
            failSessionTree(child, IllegalStateException("Failed to set up subagent session ${child.sessionId}", cause))
        if (failed) {
            logger.error(cause) { "Failed to create the operations of subagent session ${child.sessionId}" }
        } else {
            logger.debug(cause) { "Ignored the failed setup of subagent session ${child.sessionId}, which was stopped" }
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
         * The common case: the session is open and no late update waits. An announcement takes the chain even then,
         * because its setup may suspend and the updates after it must wait for it.
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
