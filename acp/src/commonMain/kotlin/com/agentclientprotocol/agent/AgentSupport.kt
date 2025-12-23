package com.agentclientprotocol.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.json.JsonElement

public interface AgentSupport {
    public suspend fun initialize(clientInfo: ClientInfo): AgentInfo
    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse? = AuthenticateResponse()
    public suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession
}