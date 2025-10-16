package com.agentclientprotocol.client

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

public interface ClientSession {
    public val sessionId: SessionId
    public val parameters: SessionParameters

//    public val availableModes: List<SessionMode>
//    public val currentMode: StateFlow<SessionModeId?>
//    public suspend fun changeMode(modeId: SessionModeId)
//
//    public val availableModels: List<ModelInfo>
//    public val currentModel: StateFlow<ModelId?>
//    public suspend fun changeModel(modelId: ModelId)

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