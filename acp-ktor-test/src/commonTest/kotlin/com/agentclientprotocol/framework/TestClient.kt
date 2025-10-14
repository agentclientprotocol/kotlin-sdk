package com.agentclientprotocol.framework

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientInstance
import com.agentclientprotocol.client.ClientSessionBase
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeState
import com.agentclientprotocol.model.SessionModelState
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import kotlinx.serialization.json.JsonElement

open class TestClient(protocol: Protocol, clientInfo: ClientInfo) : ClientInstance(protocol, clientInfo,) {
    override suspend fun createSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?,
    ): ClientSessionBase {
        TODO("Not yet implemented")
    }

    override suspend fun loadSessionImpl(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
        modeState: SessionModeState?,
        modelState: SessionModelState?,
    ): ClientSessionBase {
        TODO("Not yet implemented")
    }
}

open class TestClientSession(sessionId: SessionId, protocol: Protocol, modeState: SessionModeState?,
                             modelState: SessionModelState?
) : ClientSessionBase(sessionId, protocol,
    modeState, modelState
) {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        TODO("Not yet implemented")
    }

    override suspend fun updateImpl(
        params: SessionUpdate,
        _meta: JsonElement?,
    ) {
        TODO("Not yet implemented")
    }
}