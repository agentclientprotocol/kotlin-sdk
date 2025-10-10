package com.agentclientprotocol.client

import com.agentclientprotocol.common.Session
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

public abstract class ClientSessionBase(
    override val sessionId: SessionId,
    private val protocol: Protocol,
    private val modeState: SessionModeState?,
    private val modelState: SessionModelState?
) : Session {

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
    ): PromptResponse {
        return AcpMethod.AgentMethods.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta))
    }

    override suspend fun cancel() {
        AcpMethod.AgentMethods.SessionCancel(protocol, CancelNotification(sessionId))
    }

    abstract override suspend fun requestPermissions(
        toolCall: ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse

    abstract override suspend fun update(
        params: SessionUpdate,
        _meta: JsonElement?,
    )
}