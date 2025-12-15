package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
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

    private val updateChannel: Channel<SessionUpdate> = Channel(Channel.UNLIMITED)
    private val activePrompt = atomic(false)

    private val _currentMode by lazy {
        val modes = createdResponse.modes ?: error("Modes are not provided by the agent")
        MutableStateFlow(modes.currentModeId)
    }

    @UnstableApi
    private val _currentModel by lazy {
        val models = createdResponse.models ?: error("Models are not provided by the agent")
        MutableStateFlow(models.currentModelId)
    }

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        if (!activePrompt.compareAndSet(false, true)) error("There is active prompt execution")
        logger.trace { "Starting channel collection for prompt" }
        val channelJob = launch {
            for (update in updateChannel) {
                logger.trace { "Received update for prompt: $update" }
                send(Event.SessionUpdateEvent(update))
            }
        }
        try {
            logger.trace { "Sending prompt request: $content" }
            val promptResponse = AcpMethod.AgentMethods.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta))
            logger.trace { "Received prompt response: $promptResponse" }

            // after receiving prompt response we mark the prompt as inactive
            // and cancel the channel job to stop collecting updates
            logger.trace { "Marking prompt as inactive" }
            activePrompt.getAndSet(false)
            logger.trace { "Cancelling channel job" }
            channelJob.cancel()
            channelJob.join()

            send(Event.PromptResponseEvent(promptResponse))
            close()
        } finally {
            activePrompt.getAndSet(false)
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

        @OptIn(DelicateCoroutinesApi::class)
        // check if there's an active prompt and the channel is open
        if (activePrompt.value && !updateChannel.isClosedForSend) {
            logger.trace { "Sending update to active prompt: $notification" }
            updateChannel.send(notification)
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