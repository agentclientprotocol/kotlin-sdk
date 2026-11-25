@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.samples.subagents.Transcript
import kotlinx.coroutines.flow.FlowCollector

/**
 * Work that the root delegates to a child [id]. The `SampleTasks.kt` file defines the sample's tasks.
 *
 * @property instruction the task's text, sent to the child as a `session_message`
 * @property name names the task's messages, so that two tasks of one child don't share a message id
 * @property sentMessageId the id of that message in the parent's stream
 * @property receivedMessageId the id of that message in the child's stream
 * @property work what the child does, see [DelegatedTaskScope]
 */
internal class DelegatedTask(
    val id: SessionId,
    val title: String,
    val description: String,
    val instruction: String,
    val name: String = "task",
    val work: suspend DelegatedTaskScope.() -> Unit,
) {
    val sentMessageId = MessageId("${id.value}-$name-sent")
    val receivedMessageId = MessageId("${id.value}-$name")
}

/**
 * What a [DelegatedTask]'s work can do: send the child's updates, patch the child, and ask the client on the child's
 * behalf.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 3. a child's own requests, such as a permission request, go through its context's `client`;
 * - 5. patches to a child are ordinary events of the child.
 *
 * @property context the child's context from the factory
 * @property record what the child did, where its messages, tool calls and the children it starts are kept
 */
internal class DelegatedTaskScope(
    val context: AgentSubagentContext,
    private val collector: FlowCollector<SessionUpdate>,
    val record: SubagentRecord,
    private val transcript: Transcript,
) {
    /**
     * Sends [update] on the child's own stream through the child's `events`, and keeps it in [record]. It returns
     * once the SDK has sent it.
     */
    suspend fun say(update: SessionUpdate) {
        record.said(update)
        collector.emit(update)
    }

    /*
     * 5. Patching a child
     *
     * A new title or state is a `subagent_update` for the child, emitted like any other event: the SDK sends it on
     * the parent's stream. There is no update method. The record keeps only the latest title, which a load announces
     * the child with.
     */
    suspend fun patchTitle(title: String) {
        val patch = SessionUpdate.SubagentUpdate(context.sessionId, title = MaybeUndefined.Value(title))
        record.retitled(patch.title)
        collector.emit(patch)
    }

    suspend fun setState(state: StateUpdate) = collector.emit(subagentState(context.sessionId, state))

    /**
     * Grants the client `session/cancel` for this child again, and reports it `running`: the fresh grant after a
     * `session/load` (scenario 13, in `DelegatingAgentSession`).
     */
    suspend fun grantCancel() = collector.emit(
        SessionUpdate.SubagentUpdate(
            context.sessionId,
            capabilities = MaybeUndefined.Value(cancelGrant),
            state = MaybeUndefined.Value(StateUpdate.Running()),
        )
    )

    /*
     * 3. A child's own requests
     *
     * The child's `client` sends requests with the child's session id, so the client asks the user on the child's
     * behalf. While the work waits for the answer it reports `requires_action`. Its updates go through `events`
     * instead, so that the SDK sees the states it reports (scenario 7, in `RemoteProbeSubagentSession`).
     */
    suspend fun askPermission(toolCall: SessionUpdate.ToolCallUpdate): Boolean {
        setState(StateUpdate.RequiresAction())
        val outcome = context.client.requestPermissions(toolCall, listOf(allowOnce, rejectOnce)).outcome

        /*
         * A client answers a pending permission request `cancelled` when it cancels the session's work, or closes it or
         * a session above it, so the work stops.
         */
        if (outcome !is RequestPermissionOutcome.Selected) throw PermissionCancelledException()
        transcript.agentLine(context.sessionId, "permission for ${toolCall.title}: ${outcome.optionId.value}")

        setState(StateUpdate.Running())
        return outcome.optionId == allowOnce.optionId
    }

    private companion object {
        val allowOnce = PermissionOption(PermissionOptionId("allow-once"), "Allow", PermissionOptionKind.ALLOW_ONCE)
        val rejectOnce = PermissionOption(PermissionOptionId("reject-once"), "Reject", PermissionOptionKind.REJECT_ONCE)
    }
}

/**
 * Thrown by [DelegatedTaskScope.askPermission] when the client answers the request `cancelled`. It is a plain
 * exception, not a `CancellationException`: the work is stopped by a decision of the client, not by coroutine
 * cancellation, and `runDelegatedWork` reports it as `idle(cancelled)`.
 */
internal class PermissionCancelledException : Exception("The permission request was answered cancelled")
