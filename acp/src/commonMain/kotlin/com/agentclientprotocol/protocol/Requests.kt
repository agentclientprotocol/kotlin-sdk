package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

// these types added to distinct request and response ids and not to clash between them
@JvmInline
internal value class IncomingRequestId(val id: RequestId)
@JvmInline
internal value class OutgoingRequestId(val id: RequestId)

internal data class OutgoingRequest(
    val deferred: CompletableDeferred<JsonElement>,
    val sessionId: SessionId? = null
)

/**
 * A JSON-RPC method call to be sent by [Protocol].
 *
 * Represents either a request, which expects a response, or a notification, which does not.
 * Unlike wire type [JsonRpcRequest], this type does not carry a [RequestId]; request IDs are assigned
 * by [Protocol] when the call is sent.
 */
public sealed interface JsonRpcCall {
    public val method: MethodName
    public val params: JsonElement?

    /**
     * Corresponds to the wire type [JsonRpcRequest].
     */
    public data class Request(
        override val method: MethodName,
        override val params: JsonElement? = null
    ) : JsonRpcCall

    /**
     * Corresponds to the wire type [JsonRpcNotification].
     */
    public data class Notification(
        override val method: MethodName,
        override val params: JsonElement? = null
    ) : JsonRpcCall
}

