package com.agentclientprotocol.common

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

public interface ClientSessionOperations {
    /**
     * Requests permissions
     */
    public suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement? = null,
    ): RequestPermissionResponse

    /**
     * Handles notification from an agent that is not bound to any prompt
     */
    public suspend fun notify(notification: SessionUpdate, _meta: JsonElement?)
}
