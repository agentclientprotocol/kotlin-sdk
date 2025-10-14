@file:Suppress("unused")

package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.asMessageChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * An exception that gracefully handled and passed to the counterpart.
 */
public class AcpExpectedError(message: String) : Exception(message)

/**
 * Throws [AcpExpectedError] that gracefully handled and passed to the counterpart.
 */
public fun acpFail(message: String): Nothing = throw AcpExpectedError(message)

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
 * Exception thrown when a request is cancelled.
 */
public class JsonRpcCanceledException(
    message: String,
    public val data: JsonElement? = null
) : CancellationException(message)

/**
 * Configuration options for the protocol.
 */
public open class ProtocolOptions(
    /**
     * Default timeout for requests.
     */
    @Deprecated("Use coroutine timeouts")
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
    // a scope and dispatcher that executes requests to avoid blocking of message processing
    private val requestsScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
            + Dispatchers.Default.limitedParallelism(parallelism = 1, name = "MessageProcessor"))
    private val requestIdCounter: AtomicInt = atomic(0)
    private val pendingOutgoingRequests: AtomicRef<PersistentMap<RequestId, CompletableDeferred<JsonElement>>> =
        atomic(persistentMapOf())
    private val pendingIncomingRequests: AtomicRef<PersistentMap<RequestId, Job>> =
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
        setNotificationHandler(AcpMethod.MetaMethods.CancelRequest) { request ->
            var requestJob: Job? = null
            pendingIncomingRequests.update { map ->
                requestJob = map[request.requestId]
                map.remove(request.requestId)
            }
            if (requestJob == null) {
                logger.warn { "Received CancelRequest for unknown request: ${request.requestId}" }
                return@setNotificationHandler
            }
            requestJob.cancel(JsonRpcCanceledException(request.message ?: "Cancelled by the counterpart"))
        }

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
     *
     * Prefer typed [sendRequest] over this method.
     */
    public suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null
    ): JsonElement {
        val requestId = RequestId(requestIdCounter.incrementAndGet())
        val deferred = CompletableDeferred<JsonElement>()

        pendingOutgoingRequests.update { it.put(requestId, deferred) }

        try {
            val request = JsonRpcRequest(
                id = requestId,
                method = method,
                params = params
            )

            transport.send(request)
            return deferred.await()
        }
        catch (ce: CancellationException) {
            // when ce is JsonRpcCanceledException it means that the cancellation is done on the counterpart side
            // no need to send CancelRequest notification in this case
            if (ce !is JsonRpcCanceledException) {
                logger.trace(ce) { "Request cancelled on this side. Sending CancelRequest notification." }
                withContext(NonCancellable) {
                    AcpMethod.MetaMethods.CancelRequest(this@Protocol, CancelRequestNotification(requestId, ce.message))
                    deferred.cancel()
                }
            }
            throw ce
        }
        finally {
            pendingOutgoingRequests.update { it.remove(requestId) }
        }
    }

    /**
     * Send a notification (no response expected).
     *
     * Prefer typed [sendNotification] over this method.
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
     *
     * Prefer typed [setRequestHandler] over this method.
     */
    public fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        requestHandlers.update { it.put(method.methodName, handler) }
    }

    /**
     * Register a handler for incoming notifications.
     *
     * Prefer typed [setNotificationHandler] over this method.
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
        val message = "Protocol closed"
        cancelPendingIncomingRequests(CancellationException(message))
        cancelPendingOutgoingRequests(CancellationException(message))
        scope.cancel(message)
    }

    /**
     * Cancels all requests that are currently being executed by this side.
     *
     * The message of [ce] will be rethrown as a [CancellationException] on the counterpart side.
     */
    public fun cancelPendingIncomingRequests(ce: CancellationException? = null) {
        val requests = pendingIncomingRequests.getAndUpdate { it.clear() }
        for (job in requests.values) {
            logger.trace { "Canceling pending incoming request: ${job.key}" }
            job.cancel(ce)
        }
    }

    /**
     * Cancels all requests that are currently awaited for a response from the counterpart.
     *
     * Methods that await for a response will throw [ce]
     */
    public fun cancelPendingOutgoingRequests(ce: CancellationException? = null) {
        val requests = pendingOutgoingRequests.getAndUpdate { it.clear() }
        for (deferred in requests.values) {
            logger.trace { "Canceling pending outgoing request: ${deferred.key}" }
            deferred.cancel(ce)
        }
    }

    private suspend fun handleIncomingMessage(message: JsonRpcMessage) {
        try {
            when (message) {
                is JsonRpcNotification -> {
                    handleNotification(message)
                }
                is JsonRpcRequest -> {
                    requestsScope.launch {
                        handleRequest(message)
                    }.also { job ->
                        pendingIncomingRequests.update { map -> map.put(message.id, job) }
                    }.invokeOnCompletion {
                        pendingIncomingRequests.update { it.remove(message.id) }
                    }
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
            } catch (e: AcpExpectedError) {
                logger.trace(e) { "Expected error on '${request.method}'" }
                sendResponse(
                    request.id, null, JsonRpcError(
                        code = JsonRpcErrorCode.INVALID_PARAMS, message = e.message ?: "Invalid params"
                    )
                )
            } catch (e: SerializationException) {
                logger.trace(e) { "Serialization error on ${request.method}" }
                sendResponse(
                    request.id, null, JsonRpcError(
                        code = JsonRpcErrorCode.PARSE_ERROR, message = e.message ?: "Serialization error"
                    )
                )
            } catch (ce: CancellationException) {
                logger.trace(ce) { "Incoming request cancelled: ${request.method}" }
                if (ce !is JsonRpcCanceledException) { // JsonRpcCanceledException already means that the request was cancelled on the counterpart side
                    sendResponse(
                        request.id, null,
                        JsonRpcError(
                            code = JsonRpcErrorCode.CANCELLED,
                            message = ce.message ?: "Cancelled"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Exception on ${request.method}" }
                sendResponse(
                    request.id, null, JsonRpcError(
                        code = JsonRpcErrorCode.INTERNAL_ERROR, message = e.message ?: "Internal error"
                    )
                )
            }
        } else {
            val error = JsonRpcError(
                code = JsonRpcErrorCode.METHOD_NOT_FOUND, message = "Method not supported: ${request.method}"
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
        pendingOutgoingRequests.update { currentRequests ->
            deferred = currentRequests[response.id]
            currentRequests.remove(response.id)
        }
        if (deferred != null) {
            val responseError = response.error
            if (responseError != null) {
                if (responseError.code == JsonRpcErrorCode.CANCELLED) {
                    deferred.cancel(JsonRpcCanceledException(responseError.message, responseError.data))
                }
                else {
                    val exception = JsonRpcException(
                        code = responseError.code,
                        message = responseError.message,
                        data = responseError.data
                    )
                    deferred.completeExceptionally(exception)
                }
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
        val response = JsonRpcResponse(
            id = requestId,
            result = result,
            error = error
        )
        transport.send(response)
    }
}