@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples

import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ConfigOptionUpdate
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.SessionCapabilities
import com.agentclientprotocol.model.v2.SessionConfigKind
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionCategory
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionConfigSelectOptions
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import com.agentclientprotocol.model.v2.UserMessage
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** A minimal v2 agent that echoes every prompt. */
class SimpleV2AgentSupport : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        implementation = Implementation("v2-echo-agent", "1.0.0"),
        capabilities = AgentCapabilities(session = SessionCapabilities()),
    )

    override suspend fun createSession(
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession = EchoV2Session(SessionId(UUID.randomUUID().toString()), client)

    override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?) = CloseSessionResponse()
}

private class EchoV2Session(override val sessionId: SessionId, private val client: ClientOperations) : AgentSession {
    private var turn = 0
    private val responseMode = MutableStateFlow(SessionConfigValueId("echo"))

    override val configOptions: List<SessionConfigOption>
        get() = listOf(
            SessionConfigOption(
                configId = SessionConfigId("response_mode"),
                name = "Response mode",
                description = "Uppercase applies to one reply, then returns to Echo.",
                category = SessionConfigOptionCategory.Mode,
                kind = SessionConfigKind.Select(
                    currentValue = responseMode.value,
                    options = SessionConfigSelectOptions.Ungrouped(listOf(
                        SessionConfigSelectOption(SessionConfigValueId("echo"), "Echo"),
                        SessionConfigSelectOption(SessionConfigValueId("uppercase"), "Uppercase once"),
                    )),
                ),
            )
        )

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?,
    ): List<SessionConfigOption> {
        if (configId != SessionConfigId("response_mode")) jsonRpcInvalidParams("Unknown config option: $configId")
        val selected = (value as? SessionConfigOptionValue.Id)?.value
            ?: jsonRpcInvalidParams("Response mode requires an id value")
        if (selected.value !in listOf("echo", "uppercase")) jsonRpcInvalidParams("Unknown response mode: $selected")
        responseMode.value = selected
        return configOptions // Always return every option and its current value.
    }

    override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate> = flow {
        turn += 1
        val text = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        val mode = responseMode.value
        val reply = if (mode.value == "uppercase") text.uppercase() else text

        emit(
            SessionUpdate.UserMessage(
                UserMessage(MessageId("user-$turn"), MaybeUndefined.Value(content))
            )
        )
        emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
        emit(
            SessionUpdate.AgentMessageChunk(
                ContentChunk(MessageId("agent-$turn"), ContentBlock.Text("Echo: $reply"))
            )
        )
        if (mode.value == "uppercase" && responseMode.compareAndSet(mode, SessionConfigValueId("echo"))) {
            // Agent-initiated changes use the same full configuration state as setter responses.
            client.notify(SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(configOptions)))
        }
        emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
    }
}
