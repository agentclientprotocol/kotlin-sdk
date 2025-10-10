package com.agentclientprotocol.framework

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentInstance
import com.agentclientprotocol.agent.AgentSessionBase
import com.agentclientprotocol.common.Session
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

open class TestAgent(protocol: Protocol, agentInfo: AgentInfo) : AgentInstance(protocol, agentInfo) {
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createSessionImpl(sessionParameters: SessionParameters): Session {
        return TestAgentSession(SessionId("test-session-id"), protocol, false)
    }

    override suspend fun loadSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
    ): Session {
        return TestAgentSession(sessionId, protocol, true)
    }
}

open class TestAgentSession(sessionId: SessionId, protocol: Protocol, public val loaded: Boolean) : AgentSessionBase(sessionId, protocol) {
    val defaultMode = SessionMode(id = SessionModeId("Default"), "Default", "Default mode")
    val planMode = SessionMode(id = SessionModeId("Plan"), "Plan", "Plan mode")

    val gpt5 = ModelInfo(modelId = ModelId("gpt-5"), "GPT-5", "GPT-5 model")
    val sonnet45 = ModelInfo(modelId = ModelId("sonnet-4.5"), "Sonnet 4.5", "Sonnet 4.5 model")

    private val modes = listOf(
        defaultMode,
        planMode,
    )

    private val models = listOf(
        gpt5,
        sonnet45,
    )

    private val _mode = MutableStateFlow<SessionModeId?>(defaultMode.id)
    private val _model = MutableStateFlow<ModelId?>(gpt5.modelId)

    override val availableModes: List<SessionMode>
        get() = modes

    override val currentMode: StateFlow<SessionModeId?> = _mode.asStateFlow()

    override suspend fun changeMode(modeId: SessionModeId) {
        delay(100)
        _mode.emit(modeId)
    }

    override val availableModels: List<ModelInfo>
        get() = models
    override val currentModel: StateFlow<ModelId?> = _model.asStateFlow()

    override suspend fun changeModel(modelId: ModelId) {
        delay(100)
        _model.emit(modelId)
    }

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): PromptResponse {
        return PromptResponse(stopReason = StopReason.END_TURN)
    }

    override suspend fun cancel() {
        TODO("Not yet implemented")
    }
}