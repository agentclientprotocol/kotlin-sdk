package com.agentclientprotocol.common

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse

@UnstableApi
public interface ElicitationOperations {
    public suspend fun createElicitation(
        request: CreateElicitationRequest
    ): CreateElicitationResponse {
        throw NotImplementedError("Must be implemented by client when advertising elicitation capability")
    }

    public suspend fun completeElicitation(
        notification: CompleteElicitationNotification
    ) {
        throw NotImplementedError("Must be implemented by client when advertising elicitation capability")
    }
}
