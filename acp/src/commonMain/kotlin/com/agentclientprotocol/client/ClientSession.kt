package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

@OptIn(UnstableApi::class)
public interface ClientSession : BaseClientSession {
    override val sessionId: SessionId
    public val parameters: SessionCreationParameters

    /**
     * The [Client] this session was opened with.
     *
     * Deprecated: the application already has the [Client] it opened the session with, and keeping a reference from
     * the session back to it adds a cycle. v2's session has no such member.
     */
    @Deprecated(
        message = "Keep the Client you opened the session with instead of reading it from the session. " +
                "Will be removed.",
        level = DeprecationLevel.WARNING,
    )
    public val client: Client
    override val operations: ClientSessionOperations

    /**
     * Sends a message to the agent for execution and waits for the whole turn to be completed.
     * During execution, the agent can send notifications or requests to the client.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.AgentMethods.V1.SessionPrompt]
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): Flow<Event>

    /**
     * Asks the agent to cancel the current turn by sending `session/cancel`.
     *
     * The call returns once the notification is sent; the turn itself ends when the agent answers the pending
     * `session/prompt`. Every pending permission request of this session is answered with
     * [RequestPermissionOutcome.Cancelled] and the handlers for them are cancelled, even if the send fails.
     * Requests that arrive after this call and before the next [prompt] are answered `cancelled` too, without
     * reaching [operations].
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.AgentMethods.V1.SessionCancel]
     */
    public suspend fun cancel()

    /**
     * **UNSTABLE**
     *
     * This API may change at any point.
     *
     * Closes the session and frees up any resources associated with it.
     * The agent must cancel any ongoing work (as if `session/cancel` was called)
     * and then free up any resources associated with the session.
     *
     * When the close starts, every pending permission request of this session is answered with
     * [RequestPermissionOutcome.Cancelled] and its handler is cancelled, without waiting for the response and
     * even if the close then fails. Requests that arrive later are answered `cancelled` without reaching
     * [operations]. The same holds for every subagent session below this one, in [subagents] and their own
     * [ClientSubagentSession.subagents].
     *
     * Closing a session closes all its subagents with it. After a successful response, and before this function
     * returns, the client drops the whole tree: later updates for its session ids go to the
     * [GlobalSessionUpdateHandler], the agent's requests for them fail, and a subagent that hasn't finished opening
     * fails, so its waiting requests fail too. Each [ClientSubagentSession] keeps its last values and its
     * [ClientSubagentSession.operations]. Nothing that arrives after the response reaches them. A call to them in
     * progress when the response arrives, and the delivery of an update that arrived before the response and waits
     * behind such a call, can still run after this function returns, as for [operations]. Its
     * [ClientSubagentSession.canCancel] is `false`, and its [ClientSubagentSession.cancel] throws. The [subagents]
     * lists stay as they were. This function cancels the deliveries to the subagents that haven't finished opening
     * and waits for them to end, so it returns only once each [ClientSessionOperations.notify] or
     * [SubagentOperations.createSubagentOperations] call in progress for such a subagent reacts to the cancellation.
     * An answer with `-32002` (resource not found) means the agent no longer knows the session, so it counts as a
     * successful close, as ACP asks: the tree is removed and this function returns an empty [CloseSessionResponse].
     * If the request fails with any other error, nothing is removed.
     *
     * Such a call may close the root itself. Then this function doesn't wait for that subagent's delivery, which
     * would wait for itself, and cancels it before returning. It recognizes the call when this function runs in the
     * call's coroutine or in one below it, or in a coroutine that kept the call's context elements, as
     * `withContext(NonCancellable)` does, so a coroutine of another scope that copied them doesn't wait either. A
     * close in a coroutine of another scope with a context of its own, such as `scope.async { session.close() }`, waits
     * like any other: if the call waits for that close without reacting to cancellation, for example under
     * `NonCancellable`, neither of them ever finishes.
     *
     * Only this session object sends `session/close` for its id, and only while the session is open. After a
     * successful close, a later call returns an empty [CloseSessionResponse] at once, sends nothing and touches no
     * other session, even if the id has been opened again since: the request carries only the id, so it would close
     * the new session. A call after a failed close sends the request again, and calls made while a close is in flight
     * send their own requests. If the opening of this session failed, which a call of [operations] that reads this
     * session from its context during the opening can see, this function throws [IllegalStateException] and sends
     * nothing. The agent's session then stays open until the connection ends.
     *
     * Only available if the agent advertises the `session.close` capability.
     *
     * Corresponds to the [com.agentclientprotocol.model.AcpMethod.AgentMethods.V1.SessionClose]
     *
     * @throws IllegalStateException if the opening of this session failed
     */
    public suspend fun close(_meta: JsonElement? = null): CloseSessionResponse

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
     *
     * Unreliable after `session/load`, and therefore deprecated for removal. The agent replays the history before it
     * answers, so the mode in the response is newer than every replayed `current_mode_update`. This flow starts from
     * the response and then applies the replayed updates on top of it, so it can end up with an older mode than the
     * response reported.
     *
     * @throws IllegalStateException if the mode changing is not supported.
     */
    @Deprecated(
        message = "Unreliable after session/load: older replayed current_mode_update notifications overwrite the " +
                "mode from the response. Will be removed. Track current_mode_update in " +
                "ClientSessionOperations.notify instead.",
        level = DeprecationLevel.WARNING,
    )
    public val currentMode: StateFlow<SessionModeId>
    /**
     * Changes the session mode to the specified mode. The real change will be reported by an agent via [ClientSessionOperations.notify].
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
     * Unreliable after `session/load`, and therefore deprecated for removal. The agent replays the history before it
     * answers, so the options in the response are newer than every replayed `config_option_update`. This flow starts
     * from the response and then applies the replayed updates on top of it, so it can end up with older options than
     * the response reported.
     *
     * @throws IllegalStateException if configuration options are not supported.
     */
    @UnstableApi
    @Deprecated(
        message = "Unreliable after session/load: older replayed config_option_update notifications overwrite the " +
                "options from the response. Will be removed. Track config_option_update in " +
                "ClientSessionOperations.notify and the setConfigOption response instead.",
        level = DeprecationLevel.WARNING,
    )
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