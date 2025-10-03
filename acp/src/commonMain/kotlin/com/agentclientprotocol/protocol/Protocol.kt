@file:Suppress("unused")

package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.asMessageChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.AtomicLong
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Exception thrown when a request times out.
 */
public class RequestTimeoutException(message: String) : Exception(message)

/**
 * Exception thrown for JSON-RPC protocol errors.
 */
public class JsonRpcException(
    public val code: Int,
    message: String,
    public val data: JsonElement? = null
) : Exception(message)

/**
 * Configuration options for the protocol.
 */
public open class ProtocolOptions(
    /**
     * Default timeout for requests.
     */
    public val requestTimeout: Duration = 60.seconds
)

/**
 * Base protocol implementation handling JSON-RPC communication over a transport.
 *
 * This class manages request/response correlation, notifications, and error handling.
 */
public class Protocol(
    parentScope: CoroutineScope,
    private val transport: Transport,
    public val options: ProtocolOptions = ProtocolOptions(),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val requestIdCounter: AtomicLong = atomic(0L)
    private val pendingRequests: AtomicRef<PersistentMap<RequestId, CompletableDeferred<JsonElement>>> =
        atomic(persistentMapOf())

    /**
     * Request handlers for incoming requests.
     */
    private val requestHandlers: AtomicRef<PersistentMap<MethodName, suspend (JsonRpcRequest) -> JsonElement?>> =
        atomic(persistentMapOf())

    /**
     * Notification handlers for incoming notifications.
     */
    private val notificationHandlers: AtomicRef<PersistentMap<MethodName, suspend (JsonRpcNotification) -> Unit>> =
        atomic(persistentMapOf())

    /**
     * Connect to a transport and start processing messages.
     */
    public fun start() {
        // Start processing incoming messages
        scope.launch(CoroutineName("${Protocol::class.simpleName!!}.read-messages")) {
            try {
                for (message in transport.asMessageChannel()) {
                    try {
                        handleIncomingMessage(message)
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing incoming message: $message" }
                    }
                }
            }
            catch (e: Exception) {
                logger.error(e) { "Error processing incoming messages" }
            }
        }
        transport.start()
    }

    /**
     * Send a request and wait for the response.
     */
    public suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null,
        timeout: Duration = options.requestTimeout
    ): JsonElement {
        val requestId = RequestId(requestIdCounter.incrementAndGet().toString())
        val deferred = CompletableDeferred<JsonElement>()

        pendingRequests.update { it.put(requestId, deferred) }

        try {
            val request = JsonRpcRequest(
                id = requestId,
                method = method,
                params = params
            )

            transport.send(request)

            return withTimeout(timeout) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw RequestTimeoutException("Request timed out after $timeout: ${method.name}")
        } catch (e: Exception) {
            throw e
        }
        finally {
            pendingRequests.update { it.remove(requestId) }
        }
    }

    /**
     * Send a notification (no response expected).
     */
    public fun sendNotificationRaw(method: AcpMethod.AcpNotificationMethod<*>, params: JsonElement? = null) {
        val notification = JsonRpcNotification(
            method = method.methodName,
            params = params
        )
        transport.send(notification)
    }

    /**
     * Register a handler for incoming requests.
     */
    public fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        requestHandlers.update { it.put(method.methodName, handler) }
    }

    /**
     * Register a handler for incoming notifications.
     */
    public fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        handler: suspend (JsonRpcNotification) -> Unit
    ) {
        notificationHandlers.update { it.put(method.methodName, handler) }
    }

    /**
     * Close the protocol and cleanup resources.
     */
    public fun close() {
        transport.close()
        scope.cancel()
    }

    private suspend fun handleIncomingMessage(message: JsonRpcMessage) {
        try {
            when (message) {
                is JsonRpcNotification -> {
                    handleNotification(message)
                }
                is JsonRpcRequest -> {
                    handleRequest(message)
                }
                is JsonRpcResponse -> {
                    handleResponse(message)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse message: $message" }
        }
    }

    private suspend fun handleRequest(request: JsonRpcRequest) {
        val handler = requestHandlers.value[request.method]
        if (handler != null) {
            try {
                val result = handler(request)
                sendResponse(request.id, result, null)
            } catch (e: Exception) {
                logger.error(e) { "Error handling request ${request.method}" }
                val error = JsonRpcError(
                    code = JsonRpcErrorCode.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
                sendResponse(request.id, null, error)
            }
        } else {
            val error = JsonRpcError(
                code = JsonRpcErrorCode.METHOD_NOT_FOUND,
                message = "Method not supported: ${request.method}"
            )
            sendResponse(request.id, null, error)
        }
    }

    private suspend fun handleNotification(notification: JsonRpcNotification) {
        val handler = notificationHandlers.value[notification.method]
        if (handler != null) {
            try {
                handler(notification)
            } catch (e: Exception) {
                logger.error(e) { "Error handling notification ${notification.method}" }
            }
        } else {
            logger.debug { "No handler for notification: ${notification.method}" }
        }
    }

    private fun handleResponse(response: JsonRpcResponse) {
        var deferred: CompletableDeferred<JsonElement>? = null
        pendingRequests.update { currentRequests ->
            deferred = currentRequests[response.id]
            currentRequests.remove(response.id)
        }
        if (deferred != null) {
            val responseError = response.error
            if (responseError != null) {
                val exception = JsonRpcException(
                    code = responseError.code,
                    message = responseError.message,
                    data = responseError.data
                )
                deferred.completeExceptionally(exception)
            } else {
                deferred.complete(response.result ?: JsonNull)
            }
        } else {
            logger.warn { "Received response for unknown request ID: ${response.id}" }
        }
    }

    private fun sendResponse(
        requestId: RequestId,
        result: JsonElement?,
        error: JsonRpcError?
    ) {
        val transport = checkNotNull(this.transport) { "Transport not connected" }
        
        val response = JsonRpcResponse(
            id = requestId,
            result = result,
            error = error
        )
        transport.send(response)
    }
}

public suspend inline fun <reified TRequest : AcpRequest, reified TResponse : AcpResponse> Protocol.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?,
    timeout: Duration = options.requestTimeout
): TResponse {
    val params = request?.let { ACPJson.encodeToJsonElement(request) }
    val responseJson = this.sendRequestRaw(method.methodName, params)
    return ACPJson.decodeFromJsonElement(responseJson)
}

public inline fun <reified TNotification: AcpNotification> Protocol.sendNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification? = null,
) {
    val params = notification?.let { ACPJson.encodeToJsonElement(notification) }
    this.sendNotificationRaw(method, params)
}

public inline fun<reified TRequest : AcpRequest, reified TResponse : AcpResponse> Protocol.setRequestHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    noinline handler: suspend (TRequest) -> TResponse
) {
    this.setRequestHandlerRaw(method) { request ->
        val requestParams = ACPJson.decodeFromJsonElement<TRequest>(request.params ?: JsonNull)
        val responseObject = handler(requestParams)
        ACPJson.encodeToJsonElement(responseObject)
    }
}

public inline fun<reified TNotification : AcpNotification> Protocol.setNotificationHandler(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    noinline handler: suspend (TNotification) -> Unit
) {
    this.setNotificationHandlerRaw(method) { notification ->
        val notificationParams = ACPJson.decodeFromJsonElement<TNotification>(notification.params ?: JsonNull)
        handler(notificationParams)
    }
}