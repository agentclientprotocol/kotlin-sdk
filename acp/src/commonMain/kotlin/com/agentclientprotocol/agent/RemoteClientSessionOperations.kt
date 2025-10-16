package com.agentclientprotocol.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

internal class RemoteClientSessionOperations(private val protocol: Protocol, private val sessionId: SessionId) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return AcpMethod.ClientMethods.SessionRequestPermission(protocol, RequestPermissionRequest(sessionId, toolCall, permissions, _meta))
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        return AcpMethod.ClientMethods.SessionUpdate(protocol, SessionNotification(sessionId, notification, _meta))
    }
}