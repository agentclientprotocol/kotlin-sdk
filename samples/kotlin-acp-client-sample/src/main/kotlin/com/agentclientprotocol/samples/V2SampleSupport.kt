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
import com.agentclientprotocol.model.v2.AgentMessage
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ConfigOptionUpdate
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.ReplayFrom
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
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal v2 agent that echoes every prompt, and keeps each session's history so it can be resumed. */
class SimpleV2AgentSupport : AgentSupport {
    private val histories = ConcurrentHashMap<SessionId, MutableList<SessionUpdate>>()

    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        implementation = Implementation("v2-echo-agent", "1.0.0"),
        capabilities = AgentCapabilities(session = SessionCapabilities()),
    )

    override suspend fun createSession(
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession {
        val sessionId = SessionId(UUID.randomUUID().toString())
        return EchoV2Session(sessionId, client, histories.getOrPut(sessionId) { CopyOnWriteArrayList() })
    }

    // `session/resume` is part of the v2 session baseline, so an agent advertising sessions has to serve it.
    override suspend fun resumeSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replayFrom: ReplayFrom?,
        client: ClientOperations,
    ): AgentSession {
        val history = histories[sessionId] ?: acpFail("Session $sessionId not found")
        when (replayFrom) {
            null -> {}
            // Sent before this returns, so the whole replay reaches the client ahead of the resume response.
            is ReplayFrom.Start -> history.forEach { client.notify(it) }
            is ReplayFrom.Unknown -> acpFail("Unsupported replay cursor '${replayFrom.type}'")
        }
        return EchoV2Session(sessionId, client, history)
    }

    override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?) = CloseSessionResponse()
}

private class EchoV2Session(
    override val sessionId: SessionId,
    private val client: ClientOperations,
    private val history: MutableList<SessionUpdate>,
) : AgentSession {
    // Continues from the history, so message ids stay unique across a resume.
    private var turn = history.count { it is SessionUpdate.UserMessage }
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

        val userMessage = SessionUpdate.UserMessage(
            UserMessage(MessageId("user-$turn"), MaybeUndefined.Value(content))
        )
        history += userMessage
        emit(userMessage)
        emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
        val answer = ContentBlock.Text("Echo: $reply")
        emit(SessionUpdate.AgentMessageChunk(ContentChunk(MessageId("agent-$turn"), answer)))
        // Kept as one full message rather than chunks, so a replay needs no clearing update.
        history += SessionUpdate.AgentMessage(AgentMessage(MessageId("agent-$turn"), MaybeUndefined.Value(listOf(answer))))
        if (mode.value == "uppercase" && responseMode.compareAndSet(mode, SessionConfigValueId("echo"))) {
            // Agent-initiated changes use the same full configuration state as setter responses.
            client.notify(SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(configOptions)))
        }
        emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
    }
}
