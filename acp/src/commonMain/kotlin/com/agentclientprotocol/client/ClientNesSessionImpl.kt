package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.invoke

@UnstableApi
internal class ClientNesSessionImpl(
    private val client: Client,
    override val nesSessionId: SessionId,
    private val protocol: Protocol,
) : ClientNesSession {

    override suspend fun suggest(
        uri: String,
        version: Long,
        position: NesPosition,
        selection: NesRange?,
        triggerKind: NesTriggerKind,
        context: NesSuggestContext?,
        _meta: kotlinx.serialization.json.JsonElement?
    ): SuggestNesResponse {
        return AcpMethod.AgentMethods.NesSuggest(protocol, SuggestNesRequest(nesSessionId, uri, version, position, selection, triggerKind, context, _meta))
    }

    override suspend fun accept(id: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.NesAccept(protocol, AcceptNesNotification(nesSessionId, id, _meta))
    }

    override suspend fun reject(id: String, reason: NesRejectReason?, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.NesReject(protocol, RejectNesNotification(nesSessionId, id, reason, _meta))
    }

    override suspend fun close(_meta: kotlinx.serialization.json.JsonElement?): CloseNesResponse {
        val response = AcpMethod.AgentMethods.NesClose(protocol, CloseNesRequest(nesSessionId, _meta))
        client.removeNesSession(nesSessionId)
        return response
    }

    override suspend fun didOpen(uri: String, languageId: String, version: Long, text: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.DocumentDidOpen(protocol, DidOpenDocumentNotification(nesSessionId, uri, languageId, version, text, _meta))
    }

    override suspend fun didChange(uri: String, version: Long, contentChanges: List<TextDocumentContentChangeEvent>, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.DocumentDidChange(protocol, DidChangeDocumentNotification(nesSessionId, uri, version, contentChanges, _meta))
    }

    override suspend fun didClose(uri: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.DocumentDidClose(protocol, DidCloseDocumentNotification(nesSessionId, uri, _meta))
    }

    override suspend fun didSave(uri: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.DocumentDidSave(protocol, DidSaveDocumentNotification(nesSessionId, uri, _meta))
    }

    override suspend fun didFocus(uri: String, version: Long, position: NesPosition, visibleRange: NesRange, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.DocumentDidFocus(protocol, DidFocusDocumentNotification(nesSessionId, uri, version, position, visibleRange, _meta))
    }
}
