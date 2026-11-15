@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * The behaviours of the runtime that differ between the kinds of [BaseClientSession]. They are extension functions,
 * because an interface can't have internal members, and each one switches exhaustively over the sealed type.
 */

/**
 * Delivers [update] to this session: a root routes it to its active prompt or to its operations' `notify`, and a
 * subagent has no prompt, so it always goes to `notify`.
 */
internal suspend fun BaseClientSession.deliverUpdate(update: SessionUpdate, meta: JsonElement?) {
    when (this) {
        // The client registers only its own ClientSessionImpl for a root.
        is ClientSession -> (this as ClientSessionImpl).handleNotification(update, meta)
        is ClientSubagentSession -> operations.notify(update, meta)
    }
}

/**
 * Answers the agent's permission request for this session through its operations, unless the client cancels or
 * closes the session first.
 */
internal suspend fun BaseClientSession.answerPermissionRequest(
    toolCall: SessionUpdate.ToolCallUpdate,
    permissions: List<PermissionOption>,
    meta: JsonElement?,
): RequestPermissionResponse = when (this) {
    is ClientSession -> (this as ClientSessionImpl).handlePermissionResponse(toolCall, permissions, meta)
    is ClientSubagentSession -> pendingPermissions.answerUnlessCancelled(
        cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
    ) {
        operations.requestPermissions(toolCall, permissions, meta)
    }
}

/**
 * Runs [block] with this session in the coroutine context, so [baseClientSession] and, for a root, [clientSession]
 * return it.
 */
internal suspend fun <T> BaseClientSession.executeWithSession(block: suspend () -> T): T =
    withContext(asContextElement()) { block() }

internal class ClientSessionContextElement(val session: BaseClientSession) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientSessionContextElement>
}

internal fun BaseClientSession.asContextElement() = ClientSessionContextElement(this)
