package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * A v2 session as seen from the client.
 *
 * Separate from [com.agentclientprotocol.client.ClientSession] because a v2 turn is shaped differently:
 * `session/prompt` answers with nothing, and everything the client wants to know — the content, the tool
 * calls, and how the turn ended — arrives as [updates]. The turn is over when an update carrying
 * [com.agentclientprotocol.model.v2.StateUpdate.Idle] shows up, and its `stopReason` says why
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
    // Completed when the client cancels the turn, so pending permission requests can be answered.
    private val _cancelled = atomic(CompletableDeferred<Unit>())

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
     * Sends a prompt and returns once the agent has accepted it.
     *
     * Returning does **not** mean the turn is done: v2 reports completion through [updates], not here.
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null) {
        // A fresh signal per turn: a cancel belongs to the turn it interrupted.
        _cancelled.value = CompletableDeferred()
        AcpMethod.AgentMethods.V2.SessionPrompt(protocol, PromptRequest(sessionId, content, _meta))
    }

    /**
     * Answers an incoming `session/request_permission` for this session.
     *
     * Races the handler against a cancel of the turn, because a client that cancels MUST answer every
     * pending permission request with [RequestPermissionOutcome.Cancelled] rather than leave the agent
     * waiting
     * ([prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation)).
     */
    internal suspend fun handlePermissionRequest(request: RequestPermissionRequest): RequestPermissionResponse {
        val handler = operations
            ?: acpFail(
                "This client has no v2 session operations, so it cannot answer session/request_permission. " +
                    "Pass operations to Client.v2.newSession to handle permissions"
            )
        val cancelled = _cancelled.value
        return coroutineScope {
            val answer = async { handler.requestPermission(request) }
            select {
                answer.onAwait { it }
                cancelled.onAwait {
                    answer.cancel()
                    RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                }
            }
        }
    }

    /**
     * Sets a configuration option with `session/set_config_option`, returning the options as they now stand.
     *
     * v2 folded v1's `session/set_mode` into this: a mode is one option among others.
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
     * The session is forgotten locally afterwards, so its [updates] stop being routed anywhere.
     */
    public suspend fun close(_meta: JsonElement? = null): CloseSessionResponse {
        val response = AcpMethod.AgentMethods.V2.SessionClose(protocol, CloseSessionRequest(sessionId, _meta))
        onClosed()
        return response
    }

    /**
     * Asks the agent to stop the active work.
     *
     * The agent keeps reporting afterwards and finishes with an idle update carrying the `cancelled`
     * stop reason, so keep collecting [updates] after calling this.
     */
    public fun cancel(_meta: JsonElement? = null) {
        AcpMethod.AgentMethods.V2.SessionCancel(protocol, CancelSessionNotification(sessionId, _meta))
        // Answers whatever permission request is in flight; see [handlePermissionRequest].
        _cancelled.value.complete(Unit)
    }
}
