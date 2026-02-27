package com.agentclientprotocol.protocol

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
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
    // if we've got null, we can interpret it as {}
    val responseJson = this.sendRequestRaw(method.methodName, params).takeIf { it != JsonNull } ?: buildJsonObject {  }
    return ACPJson.decodeFromJsonElement(method.responseSerializer, responseJson)
}

/**
 * Send a batched request and return a cold [Flow] that automatically fetches subsequent pages.
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
public fun <TNotification : AcpNotification> RpcMethodsOperations.sendNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification? = null,
) {
    val params = notification?.let { ACPJson.encodeToJsonElement(method.serializer, notification) }
    this.sendNotificationRaw(method, params)
}

/**
 * Register a handler for incoming requests.
 */
public fun <TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.setRequestHandler(
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
public fun <TNotification : AcpNotification> RpcMethodsOperations.setNotificationHandler(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend (TNotification) -> Unit
) {
    this.setNotificationHandlerRaw(method, additionalContext) { notification ->
        val notificationParams = ACPJson.decodeFromJsonElement(method.serializer, notification.params ?: JsonNull)
        handler(notificationParams)
    }
}

public suspend operator fun <TRequest : AcpRequest, TResponse : AcpResponse> AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>.invoke(
    rpc: RpcMethodsOperations,
    request: TRequest
): TResponse {
    return rpc.sendRequest(this, request)
}

public operator fun <TNotification : AcpNotification> AcpMethod.AcpNotificationMethod<TNotification>.invoke(
    rpc: RpcMethodsOperations,
    notification: TNotification
) {
    return rpc.sendNotification(this, notification)
}

internal class RequestHolder(val jsonRpcRequest: JsonRpcRequest) {
    // probably make it thread safe
    internal val handlers = mutableListOf<suspend () -> Unit>()
    fun executeAfterCurrentRequest(block: suspend () -> Unit) {
        handlers.add(block)
    }
}

internal class JsonRpcRequestContextElement(val requestHolder: RequestHolder) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<JsonRpcRequestContextElement>
}

internal val CoroutineContext.requestHolder: RequestHolder
    get() = this[JsonRpcRequestContextElement.Key]?.requestHolder ?: error("There is no active incoming request in this context")

public val CoroutineContext.jsonRpcRequest: JsonRpcRequest
    get() = this.requestHolder.jsonRpcRequest


/**
 * Execute a block after the current request is processed and the response is sent back to the client.
 */
internal fun CoroutineContext.executeAfterCurrentRequest(block: suspend () -> Unit) {
    requestHolder.executeAfterCurrentRequest(block)
}