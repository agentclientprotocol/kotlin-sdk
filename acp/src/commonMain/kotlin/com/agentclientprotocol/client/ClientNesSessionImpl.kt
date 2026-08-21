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
        return AcpMethod.AgentMethods.V1.NesSuggest(protocol, SuggestNesRequest(nesSessionId, uri, version, position, selection, triggerKind, context, _meta))
    }

    override suspend fun accept(id: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.NesAccept(protocol, AcceptNesNotification(nesSessionId, id, _meta))
    }

    override suspend fun reject(id: String, reason: NesRejectReason?, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.NesReject(protocol, RejectNesNotification(nesSessionId, id, reason, _meta))
    }

    override suspend fun close(_meta: kotlinx.serialization.json.JsonElement?): CloseNesResponse {
        val response = AcpMethod.AgentMethods.V1.NesClose(protocol, CloseNesRequest(nesSessionId, _meta))
        client.removeNesSession(nesSessionId)
        return response
    }

    override suspend fun didOpen(uri: String, languageId: String, version: Long, text: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.DocumentDidOpen(protocol, DidOpenDocumentNotification(nesSessionId, uri, languageId, version, text, _meta))
    }

    override suspend fun didChange(uri: String, version: Long, contentChanges: List<TextDocumentContentChangeEvent>, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.DocumentDidChange(protocol, DidChangeDocumentNotification(nesSessionId, uri, version, contentChanges, _meta))
    }

    override suspend fun didClose(uri: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.DocumentDidClose(protocol, DidCloseDocumentNotification(nesSessionId, uri, _meta))
    }

    override suspend fun didSave(uri: String, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.DocumentDidSave(protocol, DidSaveDocumentNotification(nesSessionId, uri, _meta))
    }

    override suspend fun didFocus(uri: String, version: Long, position: NesPosition, visibleRange: NesRange, _meta: kotlinx.serialization.json.JsonElement?) {
        AcpMethod.AgentMethods.V1.DocumentDidFocus(protocol, DidFocusDocumentNotification(nesSessionId, uri, version, position, visibleRange, _meta))
    }
}
