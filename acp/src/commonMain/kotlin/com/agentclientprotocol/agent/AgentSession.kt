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
     * Replays the conversation history to the client while serving `session/load`.
     *
     * Runs after the session is registered and before the `session/load` response is sent, so every update sent here
     * reaches the client ahead of the response, as the protocol requires. It is not called for other ways to open
     * a session. To access client operations use:
     * ```
     * currentCoroutineContext().client
     * ```
     *
     * If it throws, `session/load` fails with that error and the session is not registered.
     *
     * See protocol docs: [Loading Sessions](https://agentclientprotocol.com/protocol/session-setup#loading-sessions)
     */
    public suspend fun replayHistory() {}

    /**
     * Sends a message to the agent for execution and waits for the whole turn to be completed.
     * During execution, the agent can send notifications or requests to the client.
     *
     * The SDK runs at most one turn per session: this method is not called again until the previous turn's
     * `session/prompt` has been answered, including a cancelled turn that is still winding down. A `session/prompt`
     * that arrives earlier is rejected with an internal error (`-32603`). The protocol does not define overlapping
     * prompts; this is an SDK guarantee.
     *
     * Corresponds to the [AcpMethod.AgentMethods.V1.SessionPrompt]
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): Flow<Event>

    /**
     * Cancels the current agent turn and returns after the agent canceled all activities of the current turn.
     *
     * The cancelled turn's `session/prompt` is answered only after this method returns, even if the [prompt] flow
     * ends earlier, so updates sent from here through `currentCoroutineContext().client` reach the client before the
     * response. This method must therefore not wait for that response. A turn that starts after this call began is
     * not cancelled by it.
     *
     * The cancelled turn is answered with [StopReason.CANCELLED] unless its [prompt] flow emitted a
     * [Event.PromptResponseEvent], even if this method stops the flow cooperatively and it completes normally.
     *
     * Each `session/cancel` calls this method, so a call may start while a previous one is still running.
     *
     * Corresponds to the [AcpMethod.AgentMethods.V1.SessionCancel]
     */
    public suspend fun cancel() {}

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Closes the session and frees up any resources associated with it.
     * The agent must cancel any ongoing work (as if `cancel` was called)
     * and then free up any resources associated with the session.
     *
     * Only called if the agent advertises the `session.close` capability.
     *
     * Corresponds to the [AcpMethod.AgentMethods.V1.SessionClose]
     */
    @UnstableApi
    public suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        throw NotImplementedError("close is not implemented. The capability is declared in AgentCapabilities.sessionCapabilities.close")
    }

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

/**
 * Sets a configuration option using a [SessionConfigValueId] (string value).
 *
 * @deprecated Use [AgentSession.setConfigOption] with [SessionConfigOptionValue] instead.
 */
@UnstableApi
@Deprecated(
    "Use setConfigOption with SessionConfigOptionValue instead",
    ReplaceWith("setConfigOption(configId, SessionConfigOptionValue.StringValue(value.value), _meta)")
)
public suspend fun AgentSession.setConfigOption(configId: SessionConfigId, value: SessionConfigValueId, _meta: JsonElement? = null): SetSessionConfigOptionResponse =
    setConfigOption(configId, SessionConfigOptionValue.StringValue(value.value), _meta)

@UnstableApi
internal fun AgentSession.asConfigOptionsState(): List<SessionConfigOption>? {
    val options = configOptions
    if (options.isEmpty()) return null
    return options
}