package com.agentclientprotocol.agent

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

public interface AgentSession {
    public val sessionId: SessionId

    /**
     * Sends a message to the agent for execution and waits for the whole turn to be completed.
     * During execution, the agent can send notifications or requests to the client.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.AgentMethods.SessionPrompt]
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): Flow<Event>

    /**
     * Cancels the current agent turn and returns after the agent canceled all activities of the current turn.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.AgentMethods.SessionCancel]
     */
    public suspend fun cancel()
}
