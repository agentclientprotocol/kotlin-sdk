package com.agentclientprotocol.common

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * Operations that the client side must provide to the agent. In the case of advertising capabilities like fs or terminal,
 * the client must override the corresponding methods.
 *
 * @see FileSystemOperations
 * @see TerminalOperations
 */
public interface ClientSessionOperations : FileSystemOperations, TerminalOperations {
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
    public suspend fun notify(notification: SessionUpdate, _meta: JsonElement? = null)

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Requests structured user input from the client.
     *
     * This method corresponds to the `session/elicitation` request.
     * Implement only when the client advertises [ElicitationCapabilities].
     */
    @UnstableApi
    public suspend fun requestElicitation(
        mode: ElicitationMode,
        message: String,
        _meta: JsonElement? = null
    ): ElicitationResponse {
        throw NotImplementedError("Must be implemented by client when advertising elicitation capability")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Handles optional completion notifications for URL-mode elicitation.
     *
     * This method corresponds to the `session/elicitation/complete` notification.
     * It is usually sent after a URL flow was accepted with [ElicitationAction.Accept].
     */
    @UnstableApi
    public suspend fun notifyElicitationComplete(
        elicitationId: ElicitationId,
        _meta: JsonElement? = null
    ) {
    }
}
