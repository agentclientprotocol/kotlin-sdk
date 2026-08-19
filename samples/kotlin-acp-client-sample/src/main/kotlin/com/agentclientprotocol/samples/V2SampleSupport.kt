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
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.SessionCapabilities
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import com.agentclientprotocol.model.v2.UserMessage
import kotlinx.coroutines.flow.Flow
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
    ): AgentSession = EchoV2Session(SessionId(UUID.randomUUID().toString()))

    override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?) = CloseSessionResponse()
}

private class EchoV2Session(override val sessionId: SessionId) : AgentSession {
    private var turn = 0

    override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate> = flow {
        turn += 1
        val text = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }

        emit(
            SessionUpdate.UserMessage(
                UserMessage(MessageId("user-$turn"), MaybeUndefined.Value(content))
            )
        )
        emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
        emit(
            SessionUpdate.AgentMessageChunk(
                ContentChunk(MessageId("agent-$turn"), ContentBlock.Text("Echo: $text"))
            )
        )
        emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
    }
}
