package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

internal class ClientSessionImpl(
    override val sessionId: SessionId,
    override val parameters: SessionParameters,
    private val protocol: Protocol,
//    private val modeState: SessionModeState?,
//    private val modelState: SessionModelState?,
) : ClientSession {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)

    private lateinit var _clientApi: ClientSessionOperations

    internal fun setApi(api: ClientSessionOperations) {
        if (::_clientApi.isInitialized) error("Api already initialized")
        _clientApi = api
    }

    override val operations: ClientSessionOperations
        get() = _clientApi
//    private val _currentMode = MutableStateFlow(modeState?.currentModeId)
//    private val _currentModel = MutableStateFlow(modelState?.currentModelId)

//    override val availableModes: List<SessionMode> = modeState?.availableModes ?: emptyList()
//    override val currentMode: StateFlow<SessionModeId?> = _currentMode.asStateFlow()
//
//    override suspend fun changeMode(modeId: SessionModeId) {
//        AcpMethod.AgentMethods.SessionSetMode(protocol, SetSessionModeRequest(sessionId, modeId))
//    }
//
//    override val availableModels: List<ModelInfo> = modelState?.availableModels ?: emptyList()
//    override val currentModel: StateFlow<ModelId?> = _currentModel.asStateFlow()
//
//    override suspend fun changeModel(modelId: ModelId) {
//        AcpMethod.AgentMethods.SessionSetModel(protocol, SetSessionModelRequest(sessionId, modelId))
//    }

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        val promptSession = PromptSession(Channel(Channel.UNLIMITED))
        if (!activePrompt.compareAndSet(null, promptSession)) error("There is active prompt execution")
        logger.trace { "Starting channel collection for prompt" }
        val channelJob = launch {
            for (update in promptSession.updateChannel) {
                logger.trace { "Received update for prompt: $update" }
                send(Event.SessionUpdateEvent(update))
            }
        }
        try {
            logger.trace { "Sending prompt request: $content" }
            val promptResponse = AcpMethod.AgentMethods.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta))
            logger.trace { "Received prompt response: $promptResponse" }
            send(Event.PromptResponseEvent(promptResponse))
        } finally {
            logger.trace { "Closing prompt channel" }
            activePrompt.getAndSet(null)?.updateChannel?.close()
            logger.trace { "Waiting for prompt channel to close" }
            channelJob.join()
        }
    }

    override suspend fun cancel() {
        AcpMethod.AgentMethods.SessionCancel(protocol, CancelNotification(sessionId))
    }


    /**
     * Routes notification to either active prompt or global notification channel
     */
    internal suspend fun handleNotification(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        val promptSession = activePrompt.value
        if (promptSession != null) {
            logger.trace { "Sending update to active prompt: $notification" }
            promptSession.updateChannel.send(notification)
        }
        else {
            logger.trace { "Notifying globally: $notification" }
            _clientApi.notify(notification, _meta)
        }
    }

    internal suspend fun handlePermissionResponse(toolCall: SessionUpdate.ToolCallUpdate,
                                                  permissions: List<PermissionOption>,
                                                  _meta: JsonElement?,): RequestPermissionResponse {
        return _clientApi.requestPermissions(toolCall,  permissions, _meta)
    }
}