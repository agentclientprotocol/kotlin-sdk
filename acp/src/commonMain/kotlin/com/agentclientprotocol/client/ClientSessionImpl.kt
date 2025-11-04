package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

internal class ClientSessionImpl(
    override val client: Client,
    override val sessionId: SessionId,
    override val parameters: SessionCreationParameters,
    override val operations: ClientSessionOperations,
    private val protocol: Protocol,
//    private val modeState: SessionModeState?,
//    private val modelState: SessionModelState?,
) : ClientSession {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)

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

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        return AcpMethod.AgentMethods.SessionSetMode(protocol, SetSessionModeRequest(sessionId, modeId, _meta))
    }

    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        return AcpMethod.AgentMethods.SessionSetModel(protocol, SetSessionModelRequest(sessionId, modelId, _meta))
    }

    internal suspend fun <T> executeWithSession(block: suspend () -> T): T {
        return withContext(this.asContextElement()) {
            block()
        }
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
            operations.notify(notification, _meta)
        }
    }

    internal suspend fun handlePermissionResponse(toolCall: SessionUpdate.ToolCallUpdate,
                                                  permissions: List<PermissionOption>,
                                                  _meta: JsonElement?,): RequestPermissionResponse {
        return operations.requestPermissions(toolCall,  permissions, _meta)
    }
}

internal class ClientSessionContextElement(val session: ClientSessionImpl) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientSessionContextElement>
}

internal fun ClientSessionImpl.asContextElement() = ClientSessionContextElement(this)

public val CoroutineContext.clientSession: ClientSession
    get() = this[ClientSessionContextElement.Key]?.session ?: error("No client session data found in context")