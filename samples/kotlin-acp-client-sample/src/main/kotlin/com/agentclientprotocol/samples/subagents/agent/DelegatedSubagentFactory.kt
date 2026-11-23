@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.agent.AgentSubagentContext
import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.samples.subagents.Transcript

/**
 * Builds the child that runs [task] for the parent [parentId], for `AgentSubagents.announce`.
 *
 * Scenario in this file: 2. announcing a child.
 */
internal class DelegatedSubagentFactory(
    private val task: DelegatedTask,
    private val parentId: SessionId,
    private val transcript: Transcript,
) : AgentSubagentFactory<DelegatedSubagentSession> {
    /*
     * 2. Announcing a child
     *
     * `announce` registers the id, calls this, then collects the `events` of the session it returns. The announcement,
     * a `subagent_update` for the child that the SDK sends on the parent's stream, must be the first thing sent for
     * the child. Here it goes through the child's `client`, with:
     * - the title and description the client lists the child with;
     * - the cancel grant, `capabilities.cancel`, which lets the client send `session/cancel` for the child;
     * - the state `running`, because the work starts with the announcement.
     */
    override suspend fun createSubagent(context: AgentSubagentContext): DelegatedSubagentSession {
        context.client.notify(
            SessionUpdate.SubagentUpdate(
                task.id,
                title = MaybeUndefined.Value(task.title),
                description = MaybeUndefined.Value(task.description),
                capabilities = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities())),
                state = MaybeUndefined.Value(StateUpdate.Running()),
            )
        )
        return DelegatedSubagentSession(task, parentId, context, transcript)
    }
}
