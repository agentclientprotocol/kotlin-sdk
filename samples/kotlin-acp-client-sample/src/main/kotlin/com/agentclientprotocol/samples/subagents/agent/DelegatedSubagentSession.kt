@file:OptIn(UnstableApi::class, ExperimentalAtomicApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.describe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A delegated child: it runs one [task] for its parent [parentId].
 *
 * The work runs inside [events], which the SDK collects once the factory has returned, so each update the work emits
 * is sent before the work goes on. That flow is the one path for every update of the child. What the child says and
 * how its work ends also go into [record], for `session/load`.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 6. cancel: the client cancels the child, which stops its work and reports `idle(cancelled)`;
 * - 7. failure: work that throws reports `idle(error)`.
 *
 * @param parentMessageId the id of the parent's own view of the task's message, if the parent sent one; the child's
 * view links to it
 * @property record what the child did; a child that continues after a load adds to the record it was replayed from
 */
internal class DelegatedSubagentSession(
    val task: DelegatedTask,
    private val parentId: SessionId,
    private val parentMessageId: MessageId?,
    private val context: AgentSubagentContext,
    val record: SubagentRecord,
    private val transcript: Transcript,
) : AgentSubagentSession {
    private val cancelRequested = CompletableDeferred<Unit>()

    /**
     * How the task ended, once the child has reported it.
     */
    val outcome = CompletableDeferred<IdleStopReason>()

    override val events: Flow<SessionUpdate> = flow {
        val scope = DelegatedTaskScope(context, this, record, transcript)

        // The incoming view of the parent's message starts the child's stream, before the child's answer to it.
        scope.say(
            SessionUpdate.SessionMessage(
                task.receivedMessageId,
                senderSessionId = MaybeUndefined.Value(parentId),
                recipientSessionId = MaybeUndefined.Value(task.id),
                senderMessageId = parentMessageId?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
                content = MaybeUndefined.Value(listOf(ContentBlock.Text(task.instruction))),
            )
        )

        val stopReason = runDelegatedWork(cancelRequested) { scope.(task.work)() }
        record.ended(stopReason)
        scope.setState(StateUpdate.Idle(stopReason))
        transcript.agentLine(task.id, "task ended: ${stopReason.describe()}")
        outcome.complete(stopReason)

        /*
         * A child is a conversation, not one task: it stays associated, and the parent could give it more work. This
         * sample gives it none, so the feed stays open until the SDK removes the child with its root.
         */
        awaitCancellation()
    }

    /*
     * 6. Cancel
     *
     * The SDK calls this on `session/cancel` for the child while the cancel grant is in effect. It stops the running
     * task, which then reports `idle(cancelled)` through `events`. A cancel after the task ended changes nothing.
     */
    override suspend fun cancel() {
        cancelRequested.complete(Unit)
    }

    /**
     * Stops the task, if it still runs, and waits until the child has reported how it ended.
     */
    suspend fun stop() {
        cancelRequested.complete(Unit)
        outcome.await()
    }
}

/**
 * Runs [work] until it ends or [cancelRequested] completes, and tells how it ended:
 * - [IdleStopReason.EndTurn] when [work] returns;
 * - [IdleStopReason.Cancelled] when [cancelRequested] completes first, or the work's permission request is answered
 *   `cancelled` ([PermissionCancelledException]);
 * - [IdleStopReason.Error] when [work] throws, a timeout inside it included.
 *
 * A cancellation of the caller, as when the SDK removes the child, isn't an outcome: it propagates.
 */
internal suspend fun runDelegatedWork(cancelRequested: Deferred<Unit>, work: suspend () -> Unit): IdleStopReason {
    // Decides once whether the work finished or the cancel stopped it, so a late cancel changes nothing.
    val ending = AtomicReference(WorkEnding.RUNNING)

    return try {
        coroutineScope {
            val cancelWatch = launch {
                cancelRequested.await()
                if (ending.compareAndSet(WorkEnding.RUNNING, WorkEnding.CANCELLED)) this@coroutineScope.cancel()
            }
            work()
            ending.compareAndSet(WorkEnding.RUNNING, WorkEnding.FINISHED)
            cancelWatch.cancel()
        }
        if (ending.load() == WorkEnding.CANCELLED) IdleStopReason.Cancelled else IdleStopReason.EndTurn
    } catch (e: PermissionCancelledException) {
        IdleStopReason.Cancelled
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        if (ending.load() == WorkEnding.CANCELLED) {
            IdleStopReason.Cancelled
        } else {
            /*
             * 7. Failure
             *
             * A failure that ends the work is `idle` with the `error` stop reason and, when available, a JSON-RPC
             * error. It doesn't close the child. A `TimeoutCancellationException` from a `withTimeout` in the work
             * lands here too: the caller is still active, so it is the work's failure, not a cancellation.
             */
            IdleStopReason.Error(JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, e.message ?: "The task failed"))
        }
    }
}

private enum class WorkEnding { RUNNING, FINISHED, CANCELLED }
