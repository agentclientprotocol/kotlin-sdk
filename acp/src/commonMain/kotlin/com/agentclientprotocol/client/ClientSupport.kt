package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import kotlinx.serialization.json.JsonElement

public fun interface ClientSupport {
    public suspend fun createClientSession(session: ClientSession, _sessionResponseMeta: JsonElement?): ClientSessionOperations
}