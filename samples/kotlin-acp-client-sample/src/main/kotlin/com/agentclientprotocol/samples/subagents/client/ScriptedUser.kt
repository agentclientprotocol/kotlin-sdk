@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

const val PERMISSION_ANSWERED_CANCELLED = "permission request answered cancelled by the SDK"
const val USER_CANCELS = "user cancels this subagent instead of answering"

/**
 * The user of the sample's client: it answers the permission requests that the sessions' operations hand it.
 *
 * Scenarios in this file, numbered as its section comments:
 * - 5. a subagent's permission request: answered by the user, or answered `cancelled` when the subagent is cancelled;
 * - 6. cancelling a subagent.
 */
internal class ScriptedUser(private val view: ClientTranscript) {
    private class PermissionPrompt(
        val session: BaseClientSession,
        val toolCall: SessionUpdate.ToolCallUpdate,
        val options: List<PermissionOption>,
    ) {
        val answer = CompletableDeferred<RequestPermissionOutcome>()
    }

    private val prompts = Channel<PermissionPrompt>(Channel.UNLIMITED)

    /**
     * Asks the user whether [session] may run [toolCall], and waits for the answer.
     */
    suspend fun askPermission(
        session: BaseClientSession,
        toolCall: SessionUpdate.ToolCallUpdate,
        options: List<PermissionOption>,
    ): RequestPermissionResponse {
        val prompt = PermissionPrompt(session, toolCall, options)
        view.show(session.sessionId, "asks for permission: ${toolCall.title}")
        prompts.send(prompt)

        /*
         * 5. A pending permission request
         *
         * This may simply wait for the user. When the client cancels the session or closes its root, the SDK answers
         * the request `cancelled` on its own and cancels this call.
         */
        try {
            return RequestPermissionResponse(prompt.answer.await())
        } catch (e: CancellationException) {
            view.show(session.sessionId, PERMISSION_ANSWERED_CANCELLED)
            throw e
        }
    }

    /*
     * 5. Answering a subagent's permission request, and 6. cancelling a subagent
     *
     * The user allows everything except deleting files. When a subagent asks to delete, the user cancels the subagent
     * instead of answering. `canCancel` follows the agent's latest grant, and `cancel()` checks it again. `cancel()`
     * sends `session/cancel` for the subagent and answers its pending permission requests `cancelled`.
     */
    suspend fun run() {
        for (prompt in prompts) {
            val subagent = prompt.session as? ClientSubagentSession
            if (prompt.toolCall.kind == ToolKind.DELETE && subagent != null && subagent.canCancel) {
                view.show(subagent.sessionId, USER_CANCELS)
                subagent.cancel()
            } else {
                val allow = prompt.options.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
                view.show(prompt.session.sessionId, "user allows: ${prompt.toolCall.title}")
                prompt.answer.complete(RequestPermissionOutcome.Selected(allow.optionId))
            }
        }
    }
}
