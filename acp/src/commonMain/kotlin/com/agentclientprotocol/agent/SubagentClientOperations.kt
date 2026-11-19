@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import kotlinx.serialization.json.JsonElement

/**
 * The client operations of [subagent]: requests for the child's session, gated until the announcement, and the
 * subagents routing rule for updates. The child's send state stays in [subagent], which does the sending.
 */
internal class SubagentClientOperations(
    protocol: Protocol,
    sessionId: SessionId,
    clientCapabilities: ClientCapabilities,
    private val subagent: SubagentSessionWrapper,
) : RemoteClientSessionOperations(protocol, sessionId, clientCapabilities) {
    override fun checkCanSend() = subagent.checkAnnounced()

    override suspend fun notify(event: SessionEvent) {
        val (notification, _meta) = event.requireUpdate()
        if (notification is SessionUpdate.SubagentUpdate) {
            require(notification.sessionId == sessionId) {
                "SubagentUpdate for ${notification.sessionId} cannot be sent through the client operations of " +
                    "subagent $sessionId: announce nested subagents through subagents.announce"
            }
            subagent.sendOwnUpdate(notification, _meta)
        } else {
            subagent.sendPlainUpdate(notification, _meta)
        }
    }

    /**
     * Sends [update] on the stream of [target], without any of the checks of [notify]: the wrapper makes them under
     * its lock.
     */
    fun sendOnStream(target: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        sendUpdate(target, update, _meta)
    }
}
