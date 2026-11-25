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

        /**
         * Set before the user cancels the asking subagent, so the request's handler knows why it was cancelled.
         */
        @Volatile
        var subagentCancelled = false
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
            /*
             * Only the user's cancel of the subagent is named; any other cancellation, such as the root's close or the
             * connection's end, shows as dismissed.
             */
            val text = if (prompt.subagentCancelled) PERMISSION_ANSWERED_CANCELLED else "permission request dismissed"
            view.show(session.sessionId, text)
            throw e
        }
    }

    /*
     * 5. Answering a subagent's permission request, and 6. cancelling a subagent
     *
     * The user allows everything except deleting files. When a subagent asks to delete, the user cancels the subagent
     * instead of answering, if the agent granted cancel for it, and rejects the request otherwise. `canCancel` follows
     * the agent's latest grant, and `cancel()` checks it again. `cancel()` sends `session/cancel` for the subagent and
     * answers its pending permission requests `cancelled`.
     */
    suspend fun run() {
        for (prompt in prompts) {
            val subagent = prompt.session as? ClientSubagentSession
            when {
                prompt.toolCall.kind != ToolKind.DELETE -> answer(prompt, PermissionOptionKind.ALLOW_ONCE, "allows")

                subagent != null && subagent.canCancel -> {
                    prompt.subagentCancelled = true
                    view.show(subagent.sessionId, USER_CANCELS)
                    subagent.cancel()
                }

                else -> answer(prompt, PermissionOptionKind.REJECT_ONCE, "rejects")
            }
        }
    }

    // The sample's agent offers both an allow and a reject option with every request.
    private fun answer(prompt: PermissionPrompt, kind: PermissionOptionKind, verb: String) {
        val option = prompt.options.first { it.kind == kind }
        view.show(prompt.session.sessionId, "user $verb: ${prompt.toolCall.title}")
        prompt.answer.complete(RequestPermissionOutcome.Selected(option.optionId))
    }
}
