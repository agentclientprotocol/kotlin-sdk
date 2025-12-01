package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
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
     * Corresponds to the [AcpMethod.AgentMethods.SessionPrompt]
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): Flow<Event>

    /**
     * Cancels the current agent turn and returns after the agent canceled all activities of the current turn.
     *
     * Corresponds to the [AcpMethod.AgentMethods.SessionCancel]
     */
    public suspend fun cancel() {}

    /**
     * Return a set of available modes for the session. If the session doesn't support modes, return an empty list.
     */
    public val availableModes: List<SessionMode>
        get() = emptyList()

    /**
     * Return the default mode for the session. The method is called only if [availableModes] returns a non-empty list.
     */
    public val defaultMode: SessionModeId
        get() = throw NotImplementedError("Must be implemented when providing non-empty ${::availableModes.name}")

    /**
     * Called when a client asks to change mode. If the mode is changed [SessionUpdate.CurrentModeUpdate] must be sent to the client.
     */
    public suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        throw NotImplementedError("Must be implemented when providing non-empty ${::availableModes.name}")
    }

    /**
     * Return a set of available models for the session. If the session doesn't support models, return an empty list.
     */
    @UnstableApi
    public val availableModels: List<ModelInfo>
        get() = emptyList()

    /**
     * Return the default model for the session. The method is called only if [availableModels] returns a non-empty list.
     */
    @UnstableApi
    public val defaultModel: ModelId
        get() = throw NotImplementedError("Must be implemented when providing non-empty ${::availableModels.name}")

    /**
     * Called when a client asks to change model. If the model is changed [SessionUpdate.CurrentModelUpdate] must be sent to the client.
     */
    @UnstableApi
    public suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        throw NotImplementedError("Must be implemented when providing non-empty ${::availableModels.name}")
    }
}

@UnstableApi
internal fun AgentSession.asModelState(): SessionModelState? {
    val models = availableModels
    if (models.isEmpty()) return null
    return SessionModelState(defaultModel, models)
}

internal fun AgentSession.asModeState(): SessionModeState? {
    val modes = availableModes
    if (modes.isEmpty()) return null
    return SessionModeState(defaultMode, modes)
}