package com.agentclientprotocol.common

import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * An event of a session's stream, delivered to [ClientSessionOperations.notify].
 *
 * The stream carries the session's updates and, once, the [Opened] marker that separates the updates that came
 * before the answer of the opening call from the live ones.
 */
public sealed class SessionEvent {
    /**
     * A `session/update` of the session.
     *
     * @property _meta the notification's metadata
     */
    public class Update(public val update: SessionUpdate, public val _meta: JsonElement? = null) : SessionEvent()

    /**
     * Marks the point from which this stream is live: updates before it came before the answer of the call that
     * opened the root session, and updates after it are current.
     *
     * ### Delivery
     *
     * - A successful opening delivers exactly one [Opened] to its operations, before any live update.
     * - An opening call that fails before the agent's answer arrives delivers none.
     * - Once the answer has arrived, the call can still fail: if its caller is cancelled, or `notify` throws, while the
     *   call delivers [Opened] and the updates that arrived before the session took over. [Opened] may already have
     *   been delivered then, so treat a failed opening call as a failed opening, whatever events the operations got.
     *
     * After a load, the session's [com.agentclientprotocol.client.BaseClientSession.subagents] lists every subagent
     * that its history announced. The subagents' own lists are complete only at their own [Opened], which nothing
     * orders against this one.
     *
     * Only the client runtime delivers it; agent code can't create one.
     *
     * @property response the answer of the call that opened this session, with its state at this point; `null` for a
     * subagent, which no call opens: its state starts unconfirmed and comes from later updates
     */
    public class Opened internal constructor(public val response: AcpCreatedSessionResponse?) : SessionEvent()
}
