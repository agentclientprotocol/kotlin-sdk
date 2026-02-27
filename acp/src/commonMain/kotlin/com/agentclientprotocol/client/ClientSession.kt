package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
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
    @UnstableApi
    public val modelsSupported: Boolean

    /**
     * Returns a list of available models. Returns an empty list if the model changing is not supported.
     */
    @UnstableApi
    public val availableModels: List<ModelInfo>

    /**
     * Returns the current model for the session. Check for [modelsSupported] before calling this method.
     *
     * @throws IllegalStateException if the model changing is not supported.
     */
    @UnstableApi
    public val currentModel: StateFlow<ModelId>

    /**
     * Changes the session model to the specified model. The real change will be reported by an agent via [currentModel] and [ClientSessionOperations.notify].
     */
    @UnstableApi
    public suspend fun setModel(modelId: ModelId, _meta: JsonElement? = null): SetSessionModelResponse

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * The flag indicates whether the agent supports session configuration options.
     */
    @UnstableApi
    public val configOptionsSupported: Boolean

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Returns the current configuration options for the session.
     * Check for [configOptionsSupported] before calling this method.
     *
     * @throws IllegalStateException if configuration options are not supported.
     */
    @UnstableApi
    public val configOptions: StateFlow<List<SessionConfigOption>>

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Sets a configuration option for the session.
     *
     * @param configId the id of the configuration option to set
     * @param value the value to set for the configuration option
     * @param _meta optional metadata
     * @return a [SetSessionConfigOptionResponse] containing the updated configuration options
     */
    @UnstableApi
    public suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement? = null): SetSessionConfigOptionResponse
}

/**
 * Sets a configuration option using a [SessionConfigValueId] (string value).
 *
 * @deprecated Use [ClientSession.setConfigOption] with [SessionConfigOptionValue] instead.
 */
@UnstableApi
@Deprecated(
    "Use setConfigOption with SessionConfigOptionValue instead",
    ReplaceWith("setConfigOption(configId, SessionConfigOptionValue.StringValue(value.value), _meta)")
)
public suspend fun ClientSession.setConfigOption(configId: SessionConfigId, value: SessionConfigValueId, _meta: JsonElement? = null): SetSessionConfigOptionResponse =
    setConfigOption(configId, SessionConfigOptionValue.StringValue(value.value), _meta)