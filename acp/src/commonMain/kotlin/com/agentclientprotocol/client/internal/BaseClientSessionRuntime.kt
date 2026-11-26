@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.BaseClientSession
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.internal.ClientSessionImpl
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.baseClientSession
import com.agentclientprotocol.client.clientSession
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
 * The runtime behaviours that differ between the kinds of [BaseClientSession]. That type is an interface, because the
 * public [ClientSession] interface extends it, and an interface can't have internal members. So each behaviour is an
 * extension that switches over the sealed type.
 */

/**
 * Delivers [update] to this session: a root routes it to its active prompt or to `notify`, and a subagent to `notify`.
 */
internal suspend fun BaseClientSession.deliverUpdate(update: SessionUpdate, meta: JsonElement?) {
    when (this) {
        // The client registers only its own ClientSessionImpl for a root.
        is ClientSession -> (this as ClientSessionImpl).handleNotification(update, meta)
        is ClientSubagentSession -> operations.notify(update, meta)
    }
}

/**
 * Answers the agent's permission request for this subagent through its session's operations, unless its work is
 * cancelled or it is closed first.
 *
 * Call it when the request arrives, so the request joins the race before anything suspends, and a cancel answers it
 * even while the holder is incomplete. The receiver is the holder, because the session may not exist yet.
 */
internal suspend fun SubagentSessionHolder.answerPermissionRequest(
    toolCall: SessionUpdate.ToolCallUpdate,
    permissions: List<PermissionOption>,
    meta: JsonElement?,
): RequestPermissionResponse = this.permissions.answerUnlessCancelled(
    cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
) {
    val subagent = session.await()
    subagent.executeWithSession { subagent.operations.requestPermissions(toolCall, permissions, meta) }
}

/**
 * Runs [block] with this session in the coroutine context, so [baseClientSession] returns it, and [clientSession] or
 * [com.agentclientprotocol.client.clientSubagentSession] does by its kind.
 */
internal suspend fun <T> BaseClientSession.executeWithSession(block: suspend () -> T): T =
    withContext(asContextElement()) { block() }

internal class ClientSessionContextElement(val session: BaseClientSession) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientSessionContextElement>
}

internal fun BaseClientSession.asContextElement() = ClientSessionContextElement(this)
