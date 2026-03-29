package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import kotlinx.serialization.json.JsonElement

@UnstableApi
public interface ClientNesSession {
    public val nesSessionId: SessionId

    public suspend fun suggest(
        uri: String,
        version: Long,
        position: NesPosition,
        selection: NesRange? = null,
        triggerKind: NesTriggerKind,
        context: NesSuggestContext? = null,
        _meta: JsonElement? = null
    ): SuggestNesResponse

    public suspend fun accept(id: String, _meta: JsonElement? = null)

    public suspend fun reject(id: String, reason: NesRejectReason? = null, _meta: JsonElement? = null)

    public suspend fun close(_meta: JsonElement? = null): CloseNesResponse

    public suspend fun didOpen(uri: String, languageId: String, version: Long, text: String, _meta: JsonElement? = null)

    public suspend fun didChange(uri: String, version: Long, contentChanges: List<TextDocumentContentChangeEvent>, _meta: JsonElement? = null)

    public suspend fun didClose(uri: String, _meta: JsonElement? = null)

    public suspend fun didSave(uri: String, _meta: JsonElement? = null)

    public suspend fun didFocus(uri: String, version: Long, position: NesPosition, visibleRange: NesRange, _meta: JsonElement? = null)
}
