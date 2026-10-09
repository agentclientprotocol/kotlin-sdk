package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AvailableCommand
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.protocol.acpFail
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * One v2 session.
 *
 */
@UnstableApi
public interface AgentSession {
    public val sessionId: SessionId

    /** Complete current configuration to report from `session/new`, `session/resume`, and `session/fork`. */
    public val configOptions: List<SessionConfigOption> get() = emptyList()

    /**
     * Commands the session starts with, reported from `session/new`, `session/resume`, and `session/fork`.
     *
     * Report later changes with [ClientOperations.notify] as [SessionUpdate.AvailableCommandsUpdate].
     */
    public val availableCommands: List<AvailableCommand> get() = emptyList()

    /**
     * Runs one turn as a flow of `session/update` notifications. The turn ends when the flow completes.
     *
     * What the [prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle) requires
     * of these updates, none of which the SDK can invent on an implementation's behalf:
     * - a `user_message` or `user_message_chunk` update reporting where the user message landed in
     *   history — it is the source of truth for the agent-owned `messageId`;
     * - [StateUpdate.Running] when work starts or resumes;
     * - a final [StateUpdate.Idle] carrying the `stopReason` when the turn's work ends.
     *
     * The first [SessionUpdate.UserMessage] or [SessionUpdate.UserMessageChunk] is the insertion point.
     * Its `messageId` supplies the `session/prompt` receipt. The SDK buffers flow updates until the response
     * frame is queued. The client may receive updates before or after its `prompt()` call returns.
     *
     * Before insertion, flow failure, cancellation, or completion rejects the request. A [StateUpdate.Idle]
     * with the `cancelled` stop reason also rejects the request with cancellation. In each case, the SDK
     * discards buffered updates and sends no idle update. To reject a prompt, throw before insertion.
     *
     * If the flow fails after insertion, the SDK closes the turn with a terminal [StateUpdate.Idle]
     * whose `stopReason` is `null`. This keeps the client from waiting on a failed turn, but does not replace
     * explicit reporting. None of the defined stop reasons describes a failure, so only the implementation
     * can say why its turn ended.
     */
    public fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): Flow<SessionUpdate>

    /**
     * Handles `session/set_config_option` for this session, returning the options as they now stand.
     *
     * v2 replaced v1's `session/set_mode` with this; a mode is just one option among others.
     * Return the complete option list, including changes to other options and their available values,
     * and keep [configOptions] consistent with it. An additional notification is not required for the
     * requesting client: the response contains the full state. Agent-initiated changes can be sent with
     * [ClientOperations.notify] as [SessionUpdate.ConfigOptionUpdate].
     */
    public suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement? = null,
    ): List<SessionConfigOption> = acpFail(
        "session/set_config_option is not implemented for session $sessionId"
    )

    /**
     * Called on `session/cancel`: stop language model requests and tool calls as soon as possible.
     *
     * The turn is **not** cancelled underneath — the flow from [prompt] keeps running, because v2 requires
     * the agent itself to finish reporting: further updates MAY follow, and an idle
     * [StateUpdate.Idle] with the `cancelled` stop reason MUST be the last one. An implementation whose
     * work throws on abort has to catch that and report `cancelled` rather than let it surface as an error.
     */
    public suspend fun cancel() {}
}
