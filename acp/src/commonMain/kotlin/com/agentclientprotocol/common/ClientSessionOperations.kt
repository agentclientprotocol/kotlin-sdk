package com.agentclientprotocol.common

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.clientSubagentSession
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * This can simply wait for the user: when the client cancels or closes the session, and, for a subagent, when the
     * agent reports its work cancelled or its root is closed, the SDK answers the request with
     * [RequestPermissionOutcome.Cancelled] on its own and cancels this coroutine. Returning `Cancelled` here is
     * allowed but unnecessary.
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
     * On the client, this receives the session's updates as [SessionEvent.Update], and once the [SessionEvent.Opened]
     * marker, which separates the updates that came before the answer of the opening call, such as the history that
     * `session/load` replays, from the live ones. [SessionEvent.Opened] says when it is and isn't delivered.
     *
     * Until the opening call returns, the call delivers these events itself: cancelling the caller cancels `notify`,
     * and a cancellation of the caller or a failure of `notify` fails the opening. After the call has returned, a
     * failure of `notify` is logged, as for any live update. A subagent session has no opening call, see
     * [com.agentclientprotocol.client.ClientSubagentSession].
     *
     * On the agent, this sends a [SessionEvent.Update] to the client, and throws [IllegalStateException] for
     * [SessionEvent.Opened], which only the client runtime delivers. Agent code usually calls the `notify` extension
     * that takes a [SessionUpdate].
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
