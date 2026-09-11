package com.agentclientprotocol.protocol

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.util.PaginatedResponseToFlowAdapter
import com.agentclientprotocol.util.SequenceToPaginatedResponseAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


/**
 *  Send a request and wait for the response.
 */
public suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?
): TResponse {
    val params = request?.let { ACPJson.encodeToJsonElement(method.requestSerializer, request) }
    val sessionId = (request as? AcpWithSessionId)?.sessionId
    // if we've got null, we can interpret it as {}
    val responseJson = this.sendRequestRaw(method.methodName, params, sessionId).takeIf { it != JsonNull } ?: buildJsonObject {  }
    return ACPJson.decodeFromJsonElement(method.responseSerializer, responseJson)
}

/**
 * Fetch paginated results (not a JSON-RPC batch) as a cold [Flow].
 * The flow is cold - it won't start fetching until collection begins.
 */
@UnstableApi
public fun <TRequest : AcpPaginatedRequest, TResponse : AcpPaginatedResponse<TItem>, TItem> RpcMethodsOperations.sendBatchedRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    requestFactory: (cursor: String?) -> TRequest
): Flow<TItem> {
    return PaginatedResponseToFlowAdapter.asFlow { cursor ->
        sendRequest(method, requestFactory(cursor))
    }
}

/**
 * Send a notification (no response expected).
 */
public fun <TNotification: AcpNotification> RpcMethodsOperations.sendNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification? = null,
) {
    val params = notification?.let { ACPJson.encodeToJsonElement(method.serializer, notification) }
    this.sendNotificationRaw(method, params)
}

/**
 * Register a handler for incoming requests.
 */
public fun<TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.setRequestHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend (TRequest) -> TResponse
) {
    this.setRequestHandlerRaw(method, additionalContext) { request ->
        val requestParams = ACPJson.decodeFromJsonElement(method.requestSerializer, request.params ?: JsonNull)
        val responseObject = handler(requestParams)
        ACPJson.encodeToJsonElement(method.responseSerializer, responseObject)
    }
}

@UnstableApi
public fun <TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.setRequestOutcomeHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend (TRequest) -> RequestOutcome<TResponse>,
) {
    setRequestOutcomeHandlerRaw(method, additionalContext) { request ->
        val params = ACPJson.decodeFromJsonElement(method.requestSerializer, request.params ?: JsonNull)
        handler(params).mapResponse { ACPJson.encodeToJsonElement(method.responseSerializer, it) }
    }
}

@OptIn(UnstableApi::class)
public fun<TRequest : AcpPaginatedRequest, TResponse : AcpPaginatedResponse<TItem>, TItem> RpcMethodsOperations.setPaginatedRequestHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    batchSize: Int = 10,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    batchedResultFactory: (request: TRequest, batch: List<TItem>, newCursor: String?) -> TResponse,
    sequenceFactory: suspend (request: TRequest) -> Sequence<TItem>
) {
    val paginatedResponseAdapter = SequenceToPaginatedResponseAdapter<TItem, TRequest, TResponse>(batchSize = batchSize)
    this.setRequestHandler(method, additionalContext) { params ->
        return@setRequestHandler paginatedResponseAdapter.next(
            params = params,
            sequenceFactory = sequenceFactory,
            resultFactory = { _, batch, newCursor -> batchedResultFactory(params, batch, newCursor) }
        )
    }
}
/**
 * Register a handler for incoming notifications.
 */
public fun<TNotification : AcpNotification> RpcMethodsOperations.setNotificationHandler(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend (TNotification) -> Unit
) {
    this.setNotificationHandlerRaw(method, additionalContext) { notification ->
        val notificationParams = ACPJson.decodeFromJsonElement(method.serializer, notification.params ?: JsonNull)
        handler(notificationParams)
    }
}

public suspend operator fun <TRequest: AcpRequest, TResponse: AcpResponse> AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>.invoke(rpc: RpcMethodsOperations, request: TRequest): TResponse {
    return rpc.sendRequest(this, request)
}

/**
 * Send a typed notification via [sendNotification], propagating encoding and transport failures
 * synchronously to the caller.
 */
public operator fun <TNotification : AcpNotification> AcpMethod.AcpNotificationMethod<TNotification>.invoke(rpc: RpcMethodsOperations, notification: TNotification) {
    return rpc.sendNotification(this, notification)
}

internal class JsonRpcRequestContextElement(val request: JsonRpcRequest) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<JsonRpcRequestContextElement>
}

public val CoroutineContext.jsonRpcRequest: JsonRpcRequest
    get() = this[JsonRpcRequestContextElement.Key]?.request ?: error("There is no active incoming request in this context")

