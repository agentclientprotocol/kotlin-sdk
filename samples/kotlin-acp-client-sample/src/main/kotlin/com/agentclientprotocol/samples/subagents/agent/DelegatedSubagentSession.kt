@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.samples.subagents.Transcript
import com.agentclientprotocol.samples.subagents.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * A delegated child: it runs one [task] for its parent [parentId].
 *
 * The work runs inside [events], which the SDK collects once the factory has returned, so each update the work emits
 * is sent before the work goes on. That flow is the one path for every update of the child.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 6. cancel: the client cancels the child, which stops its work and reports `idle(cancelled)`;
 * - 7. failure: work that throws reports `idle(error)`.
 */
internal class DelegatedSubagentSession(
    val task: DelegatedTask,
    private val parentId: SessionId,
    private val context: AgentSubagentContext,
    private val transcript: Transcript,
) : AgentSubagentSession {
    private val cancelRequested = CompletableDeferred<Unit>()

    /**
     * How the task ended, once the child has reported it.
     */
    val outcome = CompletableDeferred<IdleStopReason>()

    override val events: Flow<SessionUpdate> = flow {
        // The incoming view of the parent's message starts the child's stream, before the child's answer to it.
        emit(
            SessionUpdate.SessionMessage(
                task.receivedMessageId,
                senderSessionId = MaybeUndefined.Value(parentId),
                recipientSessionId = MaybeUndefined.Value(task.id),
                senderMessageId = MaybeUndefined.Value(task.sentMessageId),
                content = MaybeUndefined.Value(listOf(ContentBlock.Text(task.instruction))),
            )
        )

        val stopReason = runTask(this)
        emit(subagentState(task.id, StateUpdate.Idle(stopReason)))
        transcript.agentLine(task.id, "task ended: ${stopReason.describe()}")
        outcome.complete(stopReason)

        /*
         * A child is a conversation, not one task: it stays associated, and the parent could give it more work. This
         * sample gives it none, so the feed stays open until the SDK removes the child with its root.
         */
        awaitCancellation()
    }

    private suspend fun runTask(collector: FlowCollector<SessionUpdate>): IdleStopReason = try {
        coroutineScope {
            val cancelWatch = launch {
                cancelRequested.await()
                this@coroutineScope.cancel("Cancelled by the client")
            }
            DelegatedTaskScope(context, collector, transcript).(task.work)()
            cancelWatch.cancel()
        }
        IdleStopReason.EndTurn
    } catch (e: CancellationException) {
        // A cancellation of the collection itself, as when the SDK removes the child, is not an outcome to report.
        currentCoroutineContext().ensureActive()
        IdleStopReason.Cancelled
    } catch (e: Exception) {
        /*
         * 7. Failure
         *
         * A failure that ends the work is `idle` with the `error` stop reason and, when available, a JSON-RPC error.
         * It doesn't close the child.
         */
        IdleStopReason.Error(JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, e.message ?: "The task failed"))
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
