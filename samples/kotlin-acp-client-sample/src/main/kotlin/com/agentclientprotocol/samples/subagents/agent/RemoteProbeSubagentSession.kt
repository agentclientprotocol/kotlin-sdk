@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * 8. A grandchild
 *
 * The child's own `subagents`, from its context, announce under the child: the announcement goes on the child's
 * stream. As for the root, `announce` returning is the point after which the child's updates may name the grandchild.
 */
internal suspend fun DelegatedTaskScope.startRemoteProbe(id: SessionId): RemoteProbeSubagentSession =
    context.subagents.announce(id, AgentSubagentFactory { RemoteProbeSubagentSession(id, parent = context.sessionId) })

/**
 * The tester's grandchild: a probe on a remote runner that the agent observes only through the runner's connection.
 * The runner is simulated: its connection drops as soon as the probe has started.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 7. lost observability: the SDK reports `unknown` for a feed that ends while active;
 * - 8. a grandchild, announced through the child's own `subagents`.
 *
 * Unlike a delegated child, the probe's announcement is the first item of its [events], not sent by the factory.
 */
internal class RemoteProbeSubagentSession(val id: SessionId, private val parent: SessionId) : AgentSubagentSession {
    private val assigned = CompletableDeferred<String>()
    private val result = CompletableDeferred<String>()

    override val events: Flow<SessionUpdate> = flow {
        emit(
            SessionUpdate.SubagentUpdate(
                id,
                title = MaybeUndefined.Value("Linux probe"),
                description = MaybeUndefined.Value("Reruns a test on a remote Linux runner"),
                state = MaybeUndefined.Value(StateUpdate.Running()),
            )
        )

        val instruction = assigned.await()
        emit(
            SessionUpdate.SessionMessage(
                MessageId("probe-task"),
                senderSessionId = MaybeUndefined.Value(parent),
                recipientSessionId = MaybeUndefined.Value(id),
                content = MaybeUndefined.Value(listOf(ContentBlock.Text(instruction))),
            )
        )
        emit(SessionUpdate.ToolCall(ToolCallId("rerun"), instruction, ToolKind.EXECUTE, ToolCallStatus.IN_PROGRESS))

        /*
         * 7. Lost observability
         *
         * The runner's connection drops here, and with it the agent's view of the probe. The feed ends while the last
         * state it sent is `running`, so the SDK reports `unknown` for the probe on the tester's stream: the agent
         * can't tell whether the work goes on, and it doesn't invent an outcome. An agent that knows how the work
         * ended reports `idle` with a stop reason instead.
         */
        result.completeExceptionally(IllegalStateException("Lost the connection to the Linux runner"))
    }

    /**
     * Gives the probe its task and waits for the result.
     */
    suspend fun rerun(instruction: String): String {
        assigned.complete(instruction)
        return result.await()
    }
}
