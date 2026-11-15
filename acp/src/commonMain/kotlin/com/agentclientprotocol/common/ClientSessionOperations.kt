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
     * Handles an event of the session's stream that is not bound to any prompt.
     *
     * On the client, this receives the session's updates as [SessionEvent.Update] and, exactly once, the
     * [SessionEvent.Opened] marker with the answer of the opening call. Updates before the marker came before that
     * answer, for example the history that `session/load` replays; updates after it are live. If the opening call
     * fails, the marker never arrives.
     *
     * On the agent, this sends a [SessionEvent.Update] to the client. Only the client runtime delivers
     * [SessionEvent.Opened], so the agent's implementation throws [IllegalStateException] for it. Agent code usually
     * calls the [notify] extension that takes a [SessionUpdate].
     */
    public suspend fun notify(event: SessionEvent)
}

/**
 * Sends [notification] as a [SessionEvent.Update] through [ClientSessionOperations.notify].
 *
 * This is an extension rather than an overridable member, so a client implementation can't override it instead of
 * the member and miss [SessionEvent.Opened].
 */
public suspend fun ClientSessionOperations.notify(notification: SessionUpdate, _meta: JsonElement? = null) {
    notify(SessionEvent.Update(notification, _meta))
}
