package com.agentclientprotocol.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

internal class RemoteClientSessionOperations(private val rpc: RpcMethodsOperations, private val sessionId: SessionId) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return AcpMethod.ClientMethods.SessionRequestPermission(rpc, RequestPermissionRequest(sessionId, toolCall, permissions, _meta))
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        return AcpMethod.ClientMethods.SessionUpdate(rpc, SessionNotification(sessionId, notification, _meta))
    }
}