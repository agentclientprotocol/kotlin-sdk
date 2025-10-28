package com.agentclientprotocol.common

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.common.RemoteSideExtension
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
