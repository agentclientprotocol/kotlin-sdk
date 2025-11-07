package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

public interface ClientSession {
    public val sessionId: SessionId
    public val parameters: SessionCreationParameters

    public val client: Client
    public val operations: ClientSessionOperations

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
     * The flag indicates whether the agent supports the session mode changing.
     */
    public val modesSupported: Boolean

    /**
     * Returns a list of available modes. Returns an empty list if the mode changing is not supported.
     */
    public val availableModes: List<SessionMode>

    /**
     * Returns the current mode for the session. Check for [modelsSupported] before calling this method.
     * @throws IllegalStateException if the mode changing is not supported.
     */
    public val currentMode: StateFlow<SessionModeId>
    /**
     * Changes the session mode to the specified mode. The real change will be reported by an agent via [currentMode] and [ClientSessionOperations.notify].
     */
    public suspend fun setMode(modeId: SessionModeId, _meta: JsonElement? = null): SetSessionModeResponse

    /**
     * The flag indicates whether the agent supports the session model changing.
     */
    public val modelsSupported: Boolean

    /**
     * Returns a list of available models. Returns an empty list if the model changing is not supported.
     */
    public val availableModels: List<ModelInfo>

    /**
     * Returns the current model for the session. Check for [modelsSupported] before calling this method.
     *
     * @throws IllegalStateException if the model changing is not supported.
     */
    public val currentModel: StateFlow<ModelId>

    /**
     * Changes the session model to the specified model. The real change will be reported by an agent via [currentModel] and [ClientSessionOperations.notify].
     */
    public suspend fun setModel(modelId: ModelId, _meta: JsonElement? = null): SetSessionModelResponse
}