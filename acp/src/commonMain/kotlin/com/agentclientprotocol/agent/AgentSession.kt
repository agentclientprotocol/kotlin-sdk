package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

public interface AgentSession {
    public val sessionId: SessionId

    /**
     * Executes after the session is created and send to the client. Can be used to send additional notifications like Commands.
     *
     * To access client operations use:
     * ```
     * currentCoroutineContext().client
     * ```
     *
     * This method shouldn't throw exceptions.
     */
    public suspend fun postInitialize() {}
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

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Return a list of available configuration options for the session. If the session doesn't support config options, return an empty list.
     */
    @UnstableApi
    public val configOptions: List<SessionConfigOption>
        get() = emptyList()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Called when a client asks to change a configuration option. If the option is changed [SessionUpdate.ConfigOptionUpdate] must be sent to the client.
     */
    @UnstableApi
    public suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement?): SetSessionConfigOptionResponse {
        throw NotImplementedError("Must be implemented when providing non-empty ${::configOptions.name}")
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

@UnstableApi
internal fun AgentSession.asConfigOptionsState(): List<SessionConfigOption>? {
    val options = configOptions
    if (options.isEmpty()) return null
    return options
}