package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import com.agentclientprotocol.model.v2.ElicitationAction
import com.agentclientprotocol.model.v2.ElicitationMode
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * How a client answers `elicitation/create`, and hears that a URL elicitation finished.
 *
 * Registered for the whole connection rather than per session, because a v2 elicitation carries its own
 * scope: it may belong to a session, to a tool call in one, or to a request outside any session — during
 * authentication, for instance, when no session exists yet.
 */
@UnstableApi
public interface ElicitationHandler {
    /**
     * Asks the user for the input described by [request] and returns what they did.
     *
     * Answer with [ElicitationAction.Decline] or [ElicitationAction.Cancel] when the user does not fill it
     * in; the agent must not read either as acceptance.
     */
    public suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse

    /**
     * A URL-based elicitation identified by [elicitationId] has finished on the agent's side.
     *
     * Only [ElicitationMode.Url] flows produce this, and a client that shows no URL elicitations can ignore
     * it — the default does.
     */
    public fun elicitationCompleted(elicitationId: ElicitationId, _meta: JsonElement? = null) {}
}
