package com.agentclientprotocol.common

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

public sealed class Event {
    public class SessionUpdateEvent(public val update: SessionUpdate) : Event()
    public class PromptResponseEvent(public val response: PromptResponse) : Event()
}

public interface Session {
    public val sessionId: SessionId

    public val availableModes: List<SessionMode>
    public val currentMode: StateFlow<SessionModeId?>
    public suspend fun changeMode(modeId: SessionModeId)

    public val availableModels: List<ModelInfo>
    public val currentModel: StateFlow<ModelId?>
    public suspend fun changeModel(modelId: ModelId)

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

    /**
     * Requests permission from the user for a tool call operation.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.ClientMethods.SessionRequestPermission]
     */
    public suspend fun requestPermissions(toolCall: ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement? = null): RequestPermissionResponse

    /**
     * Sends update notifications to the agent.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.ClientMethods.SessionUpdate]
     */
    public suspend fun update(params: SessionUpdate, _meta: JsonElement? = null)
}