package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.PendingPermissionCancellation
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CancelSessionNotification
import com.agentclientprotocol.model.v2.CloseSessionRequest
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.PromptRequest
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SetSessionConfigOptionRequest
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.invoke
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * A v2 session as seen from the client.
 *
 * Separate from [com.agentclientprotocol.client.ClientSession] because a v2 turn is shaped differently:
 * `session/prompt` answers only with a receipt for the inserted user message, and everything else the
 * client wants to know — the content, the tool calls, and how the turn ended — arrives as [updates].
 * The turn is over when an update carrying [com.agentclientprotocol.model.v2.StateUpdate.Idle] shows
 * up, and its `stopReason` says why
 * ([prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle)).
 */
@UnstableApi
public class ClientSession internal constructor(
    public val sessionId: SessionId,
    public val configOptions: List<SessionConfigOption>,
    private val protocol: Protocol,
    private val operations: ClientSessionOperations?,
    private val updatesFlow: Flow<UpdateWithMeta>,
    private val onClosed: () -> Unit = {},
) {
    private val pendingPermissions = PendingPermissionCancellation()

    /**
     * One `session/update` notification: the update and the metadata it came with.
     */
    @UnstableApi
    public class UpdateWithMeta(public val update: SessionUpdate, public val _meta: JsonElement? = null)

    /**
     * A single-consumer stream of this session's `session/update` notifications, in arrival order.
     *
     * It includes updates buffered before this object was created. Collect it once: the stream completes
     * when the session is closed or deleted.
     */
    public val updates: Flow<UpdateWithMeta>
        get() = updatesFlow

    /**
     * Sends a prompt and returns once the agent has inserted it into the conversation.
     *
     * Returning does **not** mean the turn is done: v2 reports completion through [updates], not here.
     *
     * @return the id of the inserted user message. The `user_message` or `user_message_chunk` update
     * with this id can arrive on [updates] before or after this call returns
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): MessageId {
        pendingPermissions.startTurn()
        return AcpMethod.AgentMethods.V2.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta)).messageId
    }

    /**
     * Answers an incoming `session/request_permission` for this session.
     *
     * Races the handler against a cancel of the turn or a close of the session, because a client that
     * cancels MUST answer every pending permission request with [RequestPermissionOutcome.Cancelled] rather
     * than leave the agent waiting
     * ([prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation)).
     */
    internal suspend fun handlePermissionRequest(request: RequestPermissionRequest): RequestPermissionResponse {
        val handler = operations
            ?: acpFail(
                "This client has no v2 session operations, so it cannot answer session/request_permission. " +
                    "Pass operations to Client.v2.newSession to handle permissions"
            )
        return pendingPermissions.answerUnlessCancelled(
            cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
        ) {
            handler.requestPermission(request)
        }
    }

    /**
     * Sets a configuration option with `session/set_config_option`, returning the options as they now stand.
     *
     * v2 folded v1's `session/set_mode` into this: a mode is one option among others. The agent answers with
     * the complete option list rather than the one that changed, because one choice can affect the others,
     * so this supersedes [configOptions] — as does every [SessionUpdate.ConfigOptionUpdate] on [updates].
     */
    public suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement? = null,
    ): List<SessionConfigOption> = AcpMethod.AgentMethods.V2.SessionSetConfigOption(
        protocol,
        SetSessionConfigOptionRequest(sessionId, configId, value, _meta)
    ).configOptions

    /**
     * Ends this session with `session/close`.
     *
     * Closing makes the agent cancel the session's ongoing work, including background work, and free its
     * resources. When the close starts, every pending permission request of this session is answered with
     * [RequestPermissionOutcome.Cancelled] and its handler is cancelled, without waiting for the response and
     * even if the close then fails. Requests that arrive later are answered the same way without reaching
     * the operations.
     *
     * After a successful response the session is forgotten locally, so its [updates] stop being routed
     * anywhere.
     */
    public suspend fun close(_meta: JsonElement? = null): CloseSessionResponse {
        /*
         * Answer before waiting for the response, so an agent that does not cancel its own permission requests
         * first is not left waiting for a session the client is dropping.
         */
        pendingPermissions.close()
        val response = AcpMethod.AgentMethods.V2.SessionClose(protocol, CloseSessionRequest(sessionId, _meta))
        onClosed()
        return response
    }

    /**
     * Asks the agent to stop the active work.
     *
     * The agent keeps reporting afterwards and finishes with an idle update carrying the `cancelled`
     * stop reason, so keep collecting [updates] after calling this.
     *
     * Encoding and transport failures throw synchronously. Pending local permission handlers are
     * cancelled even if the cancellation notification cannot be sent.
     */
    public fun cancel(_meta: JsonElement? = null) {
        try {
            AcpMethod.AgentMethods.V2.SessionCancel(protocol, CancelSessionNotification(sessionId, _meta))
        } finally {
            // Answers whatever permission request is in flight even if sending fails.
            pendingPermissions.cancelPending()
        }
    }
}
