package com.agentclientprotocol.agent

import com.agentclientprotocol.common.Session
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

public abstract class AgentSessionBase(
    override val sessionId: SessionId,
    private val protocol: Protocol,
): Session {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return AcpMethod.ClientMethods.SessionRequestPermission(protocol, RequestPermissionRequest(sessionId, toolCall, permissions, _meta))
    }

    override suspend fun update(
        params: SessionUpdate,
        _meta: JsonElement?,
    ) {
        AcpMethod.ClientMethods.SessionUpdate(protocol, SessionNotification(sessionId, params, _meta))
    }
}