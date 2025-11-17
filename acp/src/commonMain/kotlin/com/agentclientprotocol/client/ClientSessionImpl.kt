package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val createdResponse: AcpCreatedSessionResponse,
    private val protocol: Protocol,
) : ClientSession {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)

    private val _currentMode by lazy {
        val modes = createdResponse.modes ?: error("Modes are not provided by the agent")
        MutableStateFlow(modes.currentModeId)
    }

    private val _currentModel by lazy {
        val models = createdResponse.models ?: error("Models are not provided by the agent")
        MutableStateFlow(models.currentModelId)
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

            // after receiving prompt response we immediately close the current prompt channel
            // and then waiting for draining all the updates that were sent during prompt execution
            // only after that we emit the PromptResponseEvent to the outbound flow
            logger.trace { "Closing prompt channel" }
            activePrompt.getAndSet(null)?.updateChannel?.close()
            logger.trace { "Waiting for prompt channel to close" }
            channelJob.join()

            send(Event.PromptResponseEvent(promptResponse))
            close()
        } finally {
            activePrompt.getAndSet(null)?.updateChannel?.close()
            channelJob.cancel()
        }
    }

    override suspend fun cancel() {
        AcpMethod.AgentMethods.SessionCancel(protocol, CancelNotification(sessionId))
    }

    override val modesSupported: Boolean
        get() = createdResponse.modes != null

    override val availableModes: List<SessionMode>
        get() = createdResponse.modes?.availableModes ?: emptyList()

    override val currentMode: StateFlow<SessionModeId>
        get() = _currentMode


    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        return AcpMethod.AgentMethods.SessionSetMode(protocol, SetSessionModeRequest(sessionId, modeId, _meta))
    }

    override val modelsSupported: Boolean
        get() = createdResponse.models != null

    override val availableModels: List<ModelInfo>
        get() = createdResponse.models?.availableModels ?: emptyList()

    override val currentModel: StateFlow<ModelId>
        get() = _currentModel

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
        if (notification is SessionUpdate.CurrentModeUpdate) {
            _currentMode.value = notification.currentModeId
        }
        // TODO: add support for model updates, there is no type for it
//        if (notification is SessionUpdate.CurrentModelUpdate) {
//            _currentModel.value = notification.currentModelId
//        }

        val promptSession = activePrompt.value
        @OptIn(DelicateCoroutinesApi::class)
        // check for isClosedForSend because the prompt may exist, but the code is waiting for the updates drain
        if (promptSession != null && !promptSession.updateChannel.isClosedForSend) {
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