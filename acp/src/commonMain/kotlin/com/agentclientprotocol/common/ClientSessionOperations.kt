package com.agentclientprotocol.common

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * Operations that the client side must provide to the agent. In the case of advertising capabilities like fs or terminal,
 * the client must override the corresponding methods.
 *
 * @see FileSystemOperations
 * @see TerminalOperations
 * @see ElicitationOperations
 */
@OptIn(UnstableApi::class)
public interface ClientSessionOperations : FileSystemOperations, TerminalOperations, ElicitationOperations {
    /**
     * Asks the user whether the agent may perform [toolCall], offering [permissions], and returns the outcome.
     *
     * This can simply wait for the user: when the client cancels or closes the session, the SDK answers the
     * request with [RequestPermissionOutcome.Cancelled] on its own and cancels this coroutine. Returning
     * `Cancelled` here is allowed but unnecessary.
     *
     * Respond to cancellation promptly, for example by dismissing the UI in `finally`: the `cancelled` reply goes
     * out only after this coroutine finishes, so blocking a thread or suspending in `NonCancellable` delays it.
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
}
