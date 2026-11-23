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
 * The behaviours of the runtime that differ between the kinds of [BaseClientSession]. [BaseClientSession] has to be an
 * interface, because [ClientSession], an existing public interface, extends it: an interface can only extend
 * interfaces, and making [ClientSession] a class would break every compiled caller and implementer. An interface
 * can't have internal members. So each behaviour is an extension function that switches exhaustively over the
 * sealed type.
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
 * Answers the agent's permission request for the subagent of this holder through the operations of its session, unless
 * the subagent's work is cancelled or it is closed first.
 *
 * The request joins the race when this is called, before anything suspends, so call it when the request arrives:
 * a cancel that comes while the holder is still incomplete answers it too. The application sees the request only once
 * the holder's session has completed. The receiver is the holder, because the subagent's session doesn't exist yet
 * when the request arrives.
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
 * Runs [block] with this session in the coroutine context, so [baseClientSession] and, for a root, [clientSession]
 * return it.
 */
internal suspend fun <T> BaseClientSession.executeWithSession(block: suspend () -> T): T =
    withContext(asContextElement()) { block() }

internal class ClientSessionContextElement(val session: BaseClientSession) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientSessionContextElement>
}

internal fun BaseClientSession.asContextElement() = ClientSessionContextElement(this)
