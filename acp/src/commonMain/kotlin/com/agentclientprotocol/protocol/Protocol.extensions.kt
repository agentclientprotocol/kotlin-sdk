package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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
    val responseJson = this.sendRequestRaw(method.methodName, params)
    return ACPJson.decodeFromJsonElement(method.responseSerializer, responseJson)
}

public suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.sendRequestNullable(
    method: AcpMethod.AcpRequestResponseNullableMethod<TRequest, TResponse>,
    request: TRequest?
): TResponse? {
    val params = request?.let { ACPJson.encodeToJsonElement(method.requestSerializer, request) }
    val responseJson = this.sendRequestRaw(method.methodName, params)
    if (responseJson is JsonNull) return null
    return ACPJson.decodeFromJsonElement(method.responseSerializer, responseJson)
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

/**
 * Register a handler for incoming requests.
 */
public fun<TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.setRequestHandler(
    method: AcpMethod.AcpRequestResponseNullableMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend (TRequest) -> TResponse?
) {
    this.setRequestHandlerRaw(method, additionalContext) { request ->
        val requestParams = ACPJson.decodeFromJsonElement(method.requestSerializer, request.params ?: JsonNull)
        val responseObject = handler(requestParams)
        responseObject?.let { ACPJson.encodeToJsonElement(method.responseSerializer, responseObject) } ?: JsonNull
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

public suspend operator fun <TRequest: AcpRequest, TResponse: AcpResponse> AcpMethod.AcpRequestResponseNullableMethod<TRequest, TResponse>.invoke(rpc: RpcMethodsOperations, request: TRequest): TResponse? {
    return rpc.sendRequestNullable(this, request)
}

public operator fun <TNotification : AcpNotification> AcpMethod.AcpNotificationMethod<TNotification>.invoke(rpc: RpcMethodsOperations, notification: TNotification) {
    return rpc.sendNotification(this, notification)
}

internal class JsonRpcRequestContextElement(val jsonRpcRequest: JsonRpcRequest) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<JsonRpcRequestContextElement>
}

public val CoroutineContext.jsonRpcRequest: JsonRpcRequest
    get() = this[JsonRpcRequestContextElement.Key]?.jsonRpcRequest ?: error("No JsonRpcRequest found in context")

internal fun JsonRpcRequest.asContextElement() = JsonRpcRequestContextElement(this)