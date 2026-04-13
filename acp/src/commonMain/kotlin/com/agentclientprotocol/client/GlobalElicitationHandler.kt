package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse

/**
 * Handler for elicitation requests that are not associated with any session.
 *
 * This is used when an elicitation arrives with [com.agentclientprotocol.model.ElicitationScope.Request]
 * scope and the referenced request does not belong to any session.
 */
@UnstableApi
public fun interface GlobalElicitationHandler {
    public suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse
}
