package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What a caller can send to a peer and which incoming requests and notifications it can handle.
 *
 * Implemented by [Protocol]; typed extension functions wrap these raw JSON operations for ACP methods.
 */
public interface RpcMethodsOperations {
    /**
     *
     * Set a handler for a request method, producing a [RequestOutcome] to allow for additional work
     * and cleanup to be scheduled after the initial response.
     * This will override any existing handler for the same method.
     * Prefer typed [setRequestOutcomeHandler] over this method.
     */
    public fun setRequestOutcomeHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcRequest) -> RequestOutcome<JsonElement?>,
    )

    /**
     * Set a simple handler for a request method that only produces [JsonElement] result without any additional work or cleanup.
     * This will override any existing handler for the same method.
     * Prefer typed [setRequestHandler] over this method.
     */
    public fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        setRequestOutcomeHandlerRaw(method, additionalContext) { RequestOutcome(handler(it)) }
    }

    /**
     * Set a handler for a notification method.
     * This will override any existing handler for the same method.
     * Prefer typed [setNotificationHandler] over this method.
     */
    public fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    )

    /**
     * Send a request and wait for the response.
     *
     * Throws synchronously if encoding fails or the transport cannot accept the frame,
     * including when it is closing or closed. A normal return acknowledges queue acceptance,
     * not a physical flush or peer receipt. Callers sending during cleanup should handle send
     * failures so that required local cleanup still runs.
     *
     * Prefer typed [sendRequest] over this method.
     */
    public suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null,
        sessionId: SessionId? = null
    ): JsonElement

    /**
     * Send a notification (no response expected).
     *
     * Throws synchronously if encoding fails or the transport cannot accept the frame,
     * including when it is closing or closed. A normal return acknowledges queue acceptance,
     * not a physical flush or peer receipt. Callers sending during cleanup should handle send
     * failures so that required local cleanup still runs.
     *
     * Prefer typed [sendNotification] over this method.
     */
    public fun sendNotificationRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        params: JsonElement? = null,
    )

    /**
     * Send one explicit JSON-RPC batch. Results correspond to requests in input order;
     * notifications have no result. Remote errors are individual failures, local cancellation throws.
     *
     * Request IDs are assigned by this protocol using the same counter as single requests.
     * [sessionId] optionally associates all requests in this batch with one session for local tracking;
     * it does not modify their wire params. Omit it for batches spanning multiple sessions.
     *
     * Throws if encoding fails or the transport cannot accept the frame, including when it is
     * closing or closed. Returns after every request has received a response. A notification-only
     * batch returns an empty list after queue acceptance, without acknowledging peer receipt.
     *
     * There is no built-in timeout. If the peer omits a response, this call keeps waiting until
     * cancelled or the protocol closes. Wrap the entire call in [kotlinx.coroutines.withTimeout]
     * when bounded completion is required. Uncorrelated errors with a null ID cannot complete
     * this batch because they cannot be attributed to its requests.
     *
     * Timeout or other local cancellation throws for the whole operation; partial results are not
     * returned. All of this batch's pending requests are cancelled and removed from local tracking
     * before the call exits. Cancellation notifications for unfinished requests are sent best effort;
     * failure to send them does not prevent local cleanup. Other calls are unaffected.
     *
     * The caller must know the peer accepts batches. Lifecycle operations such as initialize,
     * auth/login, session/new, session/resume and session/prompt SHOULD NOT be batched.
     * A batch is not transactional and does not establish dependencies between its entries.
     */
    public suspend fun sendBatchRequestRaw(
        calls: List<JsonRpcCall>,
        sessionId: SessionId? = null,
    ): List<Result<JsonElement>>
}

/**
 * Separates a handler's reply from work that must follow it, such as streaming v2 prompt updates.
 * This lets the protocol queue the entire response before starting that work, without
 * waiting for streams to finish. The dispatcher takes ownership when the handler returns.
 *
 * @property response The handler's reply payload, mapped to JSON before it is queued.
 * @property afterResponse Optional work started after the response frame (including any batch)
 * is accepted by the transport's ordered queue.
 * @property onCompletion Optional non-suspending cleanup, called exactly once when follow-up work
 * finishes, fails, or is discarded—even if cancellation or a send failure prevents it from starting.
 */
public data class RequestOutcome<out T>(
    val response: T,
    val afterResponse: (suspend () -> Unit)? = null,
    val onCompletion: (() -> Unit)? = null,
) {
    public inline fun <R> mapResponse(transform: (T) -> R): RequestOutcome<R> = try {
        RequestOutcome(transform(response), afterResponse, onCompletion)
    } catch (t: Throwable) {
        onCompletion?.invoke()
        throw t
    }
}

/**
 * A JSON-RPC method call to be sent in batch by [RpcMethodsOperations].
 *
 * Represents either a request, which expects a response, or a notification, which does not.
 * Unlike wire type [JsonRpcRequest], this type does not carry a [RequestId]; request IDs are assigned
 * by [RpcMethodsOperations] implementation, such as [Protocol], when the call is sent.
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
