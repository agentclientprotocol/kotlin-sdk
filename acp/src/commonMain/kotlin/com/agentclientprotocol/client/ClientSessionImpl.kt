package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
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
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

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
    private val onClosing: () -> Unit,
    private val onClosed: suspend () -> Unit,
    private val holder: RootSessionHolder,
) : ClientSession {

    private class PromptSession(
        val updateChannel: Channel<SessionUpdate>
    )
    private val activePrompt = atomic<PromptSession?>(null)
    private val pendingPermissions = PendingPermissionCancellation()

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
        try {
            AcpMethod.AgentMethods.V1.SessionCancel(protocol, CancelNotification(sessionId))
        } finally {
            pendingPermissions.cancelPending()
        }
    }

    @UnstableApi
    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        /*
         * Only the registered, live holder of the id may close it: the request carries only the id, so the close of a
         * session that is gone would close whatever the id is open as now. A root's holder leaves the map while it
         * blocks its id only through a successful close, because the reaping and a replacement take only holders that
         * haven't completed or have failed. So a holder that is gone and hasn't failed was closed.
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
        onClosing()

        val response = AcpMethod.AgentMethods.V1.SessionClose(protocol, CloseSessionRequest(sessionId, _meta))
        onClosed()
        return response
    }

    @UnstableApi
    override val subagents: List<ClientSubagentSession>
        get() = holder.subagents.snapshot

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

    /**
     * Delivers the marker of the opening call's [response] to the operations. No prompt can be active yet, so it
     * always goes to [ClientSessionOperations.notify].
     */
    internal suspend fun deliverOpened(response: AcpCreatedSessionResponse) {
        operations.notify(SessionEvent.Opened(response))
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

    internal suspend fun handlePermissionResponse(toolCall: SessionUpdate.ToolCallUpdate,
                                                  permissions: List<PermissionOption>,
                                                  _meta: JsonElement?,): RequestPermissionResponse {
        return pendingPermissions.answerUnlessCancelled(
            cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
        ) {
            operations.requestPermissions(toolCall, permissions, _meta)
        }
    }
}

/**
 * The root session that the current call of a session's operations belongs to.
 *
 * @throws IllegalStateException if the context belongs to no session, or to a subagent session. Use
 * [baseClientSession] where the operations may serve subagents.
 */
public val CoroutineContext.clientSession: ClientSession
    get() = this[ClientSessionContextElement.Key]?.session as? ClientSession ?: error("No client session data found in context")
