package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import kotlinx.serialization.json.JsonElement

@UnstableApi
public interface NesAgentSession {
    public val nesSessionId: SessionId

    public suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse

    public suspend fun accept(id: String, _meta: JsonElement? = null) {}

    public suspend fun reject(id: String, reason: NesRejectReason? = null, _meta: JsonElement? = null) {}

    public suspend fun close(_meta: JsonElement? = null): CloseNesResponse = CloseNesResponse()

    public suspend fun didOpen(notification: DidOpenDocumentNotification) {}

    public suspend fun didChange(notification: DidChangeDocumentNotification) {}

    public suspend fun didClose(notification: DidCloseDocumentNotification) {}

    public suspend fun didSave(notification: DidSaveDocumentNotification) {}

    public suspend fun didFocus(notification: DidFocusDocumentNotification) {}
}
