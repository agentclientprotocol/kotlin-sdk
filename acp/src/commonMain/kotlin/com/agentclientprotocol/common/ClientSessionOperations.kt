package com.agentclientprotocol.common

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
        _meta: JsonElement?,
    ): RequestPermissionResponse

    /**
     * Handles notification from an agent that is not bound to any prompt
     */
    public suspend fun notify(notification: SessionUpdate, _meta: JsonElement?)
}

internal class ClientSessionOperationsContextElement(val client: ClientSessionOperations) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientSessionOperationsContextElement>
}

/**
 * Returns a remote client connected to the counterpart via the current protocol
 */
public val CoroutineContext.client: ClientSessionOperations
    get() = this[ClientSessionOperationsContextElement.Key]?.client ?: error("No remote client found in context")

internal fun ClientSessionOperations.asContextElement() = ClientSessionOperationsContextElement(this)