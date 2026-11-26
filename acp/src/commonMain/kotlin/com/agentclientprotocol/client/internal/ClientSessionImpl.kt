package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.setConfigOption
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * The root session of the client.
 *
 * @param isOpen whether [holder] still owns its id
 * @param onClosed removes the closed root's tree after a successful close
 */
internal class ClientSessionImpl(
    // Kept only to implement the deprecated getter.
    @Suppress("OVERRIDE_DEPRECATION")
    override val client: Client,
    override val sessionId: SessionId,
    override val parameters: SessionCreationParameters,
    override val operations: ClientSessionOperations,
    private val createdResponse: AcpCreatedSessionResponse,
    private val protocol: Protocol,
    private val isOpen: () -> Boolean,
    private val onClosed: suspend () -> Unit,
    private val holder: RootSessionHolder,
) : ClientSession {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)
    private val pendingPermissions = RootPendingPermissions()

    // TODO remove together with the deprecated ClientSession.currentMode.
    private val _currentMode by lazy {
        val modes = createdResponse.modes ?: error("Modes are not provided by the agent")
        MutableStateFlow(modes.currentModeId)
    }

    @UnstableApi
    private val _currentModel by lazy {
        val models = createdResponse.models ?: error("Models are not provided by the agent")
        MutableStateFlow(models.currentModelId)
    }

    // TODO remove together with the deprecated ClientSession.configOptions.
    @UnstableApi
    private val _configOptions by lazy {
        val configOptions = createdResponse.configOptions ?: error("Config options are not provided by the agent")
        MutableStateFlow(configOptions)
    }

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        val promptSession = PromptSession(Channel(Channel.UNLIMITED))
        if (!activePrompt.compareAndSet(null, promptSession)) error("There is active prompt execution")
        pendingPermissions.clearCancellation()
        logger.trace { "Starting channel collection for prompt" }
        val channelJob = launch {
            for (update in promptSession.updateChannel) {
                logger.trace { "Received update for prompt: $update" }
                send(Event.SessionUpdateEvent(update))
            }
        }
        try {
            logger.trace { "Sending prompt request: $content" }
            val promptResponse = AcpMethod.AgentMethods.V1.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta = _meta))
            logger.trace { "Received prompt response: $promptResponse" }

            // after receiving prompt response we immediately close the current prompt channel
            // and then waiting for draining all the updates that were sent during prompt execution
            // only after that we emit the PromptResponseEvent to the outbound flow
            logger.trace { "Closing prompt channel" }
            endTurn(promptSession)
            logger.trace { "Waiting for prompt channel to close" }
            channelJob.join()

            send(Event.PromptResponseEvent(promptResponse))
            close()
        } finally {
            endTurn(promptSession)
            channelJob.cancel()
        }
    }

    // Clears only this turn: a later turn may have registered while this one drained its updates.
    private fun endTurn(promptSession: PromptSession) {
        activePrompt.compareAndSet(promptSession, null)
        promptSession.updateChannel.close()
    }

    override suspend fun cancel() {
        try {
            AcpMethod.AgentMethods.V1.SessionCancel(protocol, CancelNotification(sessionId))
        } finally {
            pendingPermissions.cancelPending()
        }
    }

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        /*
         * The request carries only the id, so only the holder that still owns it may send it. A live root's holder
         * leaves the map only through a successful close, so a holder that is gone and hasn't failed was closed.
         */
        if (!isOpen()) {
            check(!holder.isFailed) { "Session $sessionId failed to open and can't be closed" }
            return CloseSessionResponse()
        }

        /*
         * Answer before waiting for the response: an agent whose close waits for its work to end, a subagent's
         * included, would otherwise wait for the permission answer while the client waits for the close response.
         */
        pendingPermissions.close()
        holder.startClosing()

        val response = try {
            AcpMethod.AgentMethods.V1.SessionClose(protocol, CloseSessionRequest(sessionId, _meta))
        } catch (e: JsonRpcException) {
            /*
             * ACP asks the client to treat `-32002` as a successful close:
             * https://agentclientprotocol.com/protocol/v1/session-setup#closing-active-sessions
             */
            if (e.code != JsonRpcErrorCode.RESOURCE_NOT_FOUND.code) throw e
            CloseSessionResponse()
        }
        onClosed()
        return response
    }

    @UnstableApi
    override val subagents: List<ClientSubagentSession>
        get() = holder.subagents

    @UnstableApi
    override val cwd: String
        get() = parameters.cwd

    @UnstableApi
    override val additionalDirectories: List<String>
        get() = parameters.additionalDirectories ?: emptyList()

    override val modesSupported: Boolean
        get() = createdResponse.modes != null

    override val availableModes: List<SessionMode>
        get() = createdResponse.modes?.availableModes ?: emptyList()

    // TODO remove together with the deprecated ClientSession.currentMode.
    @Suppress("OVERRIDE_DEPRECATION")
    override val currentMode: StateFlow<SessionModeId>
        get() = _currentMode


    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        return AcpMethod.AgentMethods.V1.SessionSetMode(protocol, SetSessionModeRequest(sessionId, modeId, _meta))
    }

    @UnstableApi
    override val modelsSupported: Boolean
        get() = createdResponse.models != null

    @UnstableApi
    override val availableModels: List<ModelInfo>
        get() = createdResponse.models?.availableModels ?: emptyList()

    @UnstableApi
    override val currentModel: StateFlow<ModelId>
        get() = _currentModel

    @UnstableApi
    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        return AcpMethod.AgentMethods.V1.SessionSetModel(protocol, SetSessionModelRequest(sessionId, modelId, _meta))
    }

    @UnstableApi
    override val configOptionsSupported: Boolean
        get() = createdResponse.configOptions != null

    // TODO remove together with the deprecated ClientSession.configOptions.
    @UnstableApi
    @Suppress("OVERRIDE_DEPRECATION")
    override val configOptions: StateFlow<List<SessionConfigOption>>
        get() = _configOptions

    @UnstableApi
    override suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement?): SetSessionConfigOptionResponse {
        val response = AcpMethod.AgentMethods.V1.SessionSetConfigOption(protocol, SetSessionConfigOptionRequest(sessionId, configId, value, _meta))
        // TODO remove together with the deprecated ClientSession.configOptions.
        _configOptions.value = response.configOptions
        return response
    }

    /**
     * Routes notification to either active prompt or global notification channel
     */
    internal suspend fun handleNotification(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        trackState(notification)

        // The channel is unlimited, so this fails only once the turn has closed it to drain its updates.
        val deliveredToTurn = activePrompt.value?.updateChannel?.trySend(notification)?.isSuccess == true
        if (deliveredToTurn) {
            logger.trace { "Sent update to active prompt: $notification" }
        } else {
            logger.trace { "Notifying globally: $notification" }
            operations.notify(notification, _meta)
        }
    }

    // TODO remove together with the deprecated ClientSession.currentMode and ClientSession.configOptions.
    /**
     * Applies the session state that [notification] carries to [currentMode] and [configOptions].
     */
    internal fun trackState(notification: SessionUpdate) {
        // TODO remove together with the deprecated ClientSession.currentMode.
        if (notification is SessionUpdate.CurrentModeUpdate) {
            _currentMode.value = notification.currentModeId
        }
        // TODO: add support for model updates, there is no type for it
//        if (notification is SessionUpdate.CurrentModelUpdate) {
//            _currentModel.value = notification.currentModelId
//        }
        // TODO remove together with the deprecated ClientSession.configOptions.
        @OptIn(UnstableApi::class)
        if (notification is SessionUpdate.ConfigOptionUpdate && configOptionsSupported) {
            _configOptions.value = notification.configOptions
        }
    }

    internal suspend fun answerPermissionRequest(toolCall: SessionUpdate.ToolCallUpdate,
                                                 permissions: List<PermissionOption>,
                                                 _meta: JsonElement?,): RequestPermissionResponse {
        return pendingPermissions.answerUnlessCancelled(
            cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
        ) {
            operations.requestPermissions(toolCall, permissions, _meta)
        }
    }
}
