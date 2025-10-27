package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


/**
 *  Send a request and wait for the response.
 */
public suspend inline fun <reified TRequest : AcpRequest, reified TResponse : AcpResponse> RpcMethodsOperations.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?
): TResponse {
    val params = request?.let { ACPJson.encodeToJsonElement(request) }
    val responseJson = this.sendRequestRaw(method.methodName, params)
    return ACPJson.decodeFromJsonElement(responseJson)
}

/**
 * Send a notification (no response expected).
 */
public inline fun <reified TNotification: AcpNotification> RpcMethodsOperations.sendNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification? = null,
) {
    val params = notification?.let { ACPJson.encodeToJsonElement(notification) }
    this.sendNotificationRaw(method, params)
}

/**
 * Register a handler for incoming requests.
 */
public inline fun<reified TRequest : AcpRequest, reified TResponse : AcpResponse> RpcMethodsOperations.setRequestHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (TRequest) -> TResponse
) {
    this.setRequestHandlerRaw(method, additionalContext) { request ->
        val requestParams = ACPJson.decodeFromJsonElement<TRequest>(request.params ?: JsonNull)
        val responseObject = handler(requestParams)
        ACPJson.encodeToJsonElement(responseObject)
    }
}
/**
 * Register a handler for incoming notifications.
 */
public inline fun<reified TNotification : AcpNotification> RpcMethodsOperations.setNotificationHandler(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (TNotification) -> Unit
) {
    this.setNotificationHandlerRaw(method, additionalContext) { notification ->
        val notificationParams = ACPJson.decodeFromJsonElement<TNotification>(notification.params ?: JsonNull)
        handler(notificationParams)
    }
}

public suspend inline operator fun <reified TRequest: AcpRequest, reified TResponse: AcpResponse> AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>.invoke(rpc: RpcMethodsOperations, request: TRequest): TResponse {
    return rpc.sendRequest(this, request)
}

public inline operator fun <reified TNotification : AcpNotification> AcpMethod.AcpNotificationMethod<TNotification>.invoke(rpc: RpcMethodsOperations, notification: TNotification) {
    return rpc.sendNotification(this, notification)
}

internal class JsonRpcRequestContextElement(val jsonRpcRequest: JsonRpcRequest) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<JsonRpcRequestContextElement>
}

public val CoroutineContext.jsonRpcRequest: JsonRpcRequest
    get() = this[JsonRpcRequestContextElement.Key]?.jsonRpcRequest ?: error("No JsonRpcRequest found in context")

internal fun JsonRpcRequest.asContextElement() = JsonRpcRequestContextElement(this)