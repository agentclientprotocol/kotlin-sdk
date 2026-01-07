package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.json.JsonElement

public interface AgentSupport {
    public suspend fun initialize(clientInfo: ClientInfo): AgentInfo
    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse = AuthenticateResponse()
    public suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Resumes an existing session without returning previous messages.
     *
     * This method is only available if the agent advertises the `session.resume` capability.
     */
    @UnstableApi
    public suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        throw NotImplementedError("Must be implemented by agent when advertising session.resume capability")
    }
}