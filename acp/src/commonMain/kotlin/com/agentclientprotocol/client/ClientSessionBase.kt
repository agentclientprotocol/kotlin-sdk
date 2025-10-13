package com.agentclientprotocol.client

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.Session
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

public abstract class ClientSessionBase(
    override val sessionId: SessionId,
    private val protocol: Protocol,
    private val modeState: SessionModeState?,
    private val modelState: SessionModelState?
) : Session {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)

    private val _currentMode = MutableStateFlow(modeState?.currentModeId)
    private val _currentModel = MutableStateFlow(modelState?.currentModelId)

    override val availableModes: List<SessionMode> = modeState?.availableModes ?: emptyList()
    override val currentMode: StateFlow<SessionModeId?> = _currentMode.asStateFlow()

    override suspend fun changeMode(modeId: SessionModeId) {
        AcpMethod.AgentMethods.SessionSetMode(protocol, SetSessionModeRequest(sessionId, modeId))
    }

    override val availableModels: List<ModelInfo> = modelState?.availableModels ?: emptyList()
    override val currentModel: StateFlow<ModelId?> = _currentModel.asStateFlow()

    override suspend fun changeModel(modelId: ModelId) {
        AcpMethod.AgentMethods.SessionSetModel(protocol, SetSessionModelRequest(sessionId, modelId))
    }

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

    abstract override suspend fun requestPermissions(
        toolCall: ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse

    override suspend fun update(
        params: SessionUpdate,
        _meta: JsonElement?,
    ) {
        val promptSession = activePrompt.value
        if (promptSession != null) {
            logger.trace { "Sending update to active prompt: $params" }
            promptSession.updateChannel.send(params)
        }
        else {
            logger.trace { "Notifying globally: $params" }
            updateImpl(params, _meta)
        }
    }

    protected abstract suspend fun updateImpl(params: SessionUpdate, _meta: JsonElement?)
}