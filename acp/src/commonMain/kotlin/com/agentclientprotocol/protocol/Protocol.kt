@file:Suppress("unused")

package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.asMessageChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

// these types added to distinct request and response ids and not to clash between them
@JvmInline
internal value class IncomingRequestId(val id: RequestId)
@JvmInline
internal value class OutgoingRequestId(val id: RequestId)

/**
 * An exception that gracefully handled and passed to the counterpart.
 */
public class AcpExpectedError(message: String) : Exception(message)

/**
 * Throws [AcpExpectedError] that gracefully handled and passed to the counterpart.
 */
public fun acpFail(message: String): Nothing = throw AcpExpectedError(message)

public fun jsonRpcMethodNotFound(message: String): Nothing =
    throw JsonRpcException(JsonRpcErrorCode.METHOD_NOT_FOUND, message)

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
 * Exception thrown when a request is cancelled explicitly by invoking [AcpMethod.MetaMethods.CancelRequest] from the calling site
 */
internal class JsonRpcIncomingRequestCanceledException(
    message: String,
    internal val requestId: IncomingRequestId,
    val data: JsonElement? = null
) : CancellationException(message)

/**
 * Configuration options for the protocol.
 */
public open class ProtocolOptions(
    /**
     * Default timeout for requests.
     */
    @Deprecated("Use coroutine timeouts")
    public val requestTimeout: Duration = 60.seconds,
    public val gracefulRequestCancellationTimeout: Duration = 1.seconds,
    public val protocolDebugName: String = Protocol::class.simpleName!!
)

public interface RpcMethodsOperations {
    public fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    )

    public fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    )

    /**
     * Send a request and wait for the response.
     *
     * Prefer typed [sendRequest] over this method.
     */
    public suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null
    ): JsonElement

    /**
     * Send a notification (no response expected).
     *
     * Prefer typed [sendNotification] over this method.
     */
    public fun sendNotificationRaw(method: AcpMethod.AcpNotificationMethod<*>, params: JsonElement? = null)
}

/**
 * Base protocol implementation handling JSON-RPC communication over a transport.
 *
 * This class manages request/response correlation, notifications, and error handling.
 */
public class Protocol(
    parentScope: CoroutineScope,
    private val transport: Transport,
    public val options: ProtocolOptions = ProtocolOptions()
) : RpcMethodsOperations {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(options.protocolDebugName))
    // a scope and dispatcher that executes requests to avoid blocking of message processing
    private val requestsScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
            + Dispatchers.Default.limitedParallelism(parallelism = 1, name = "MessageProcessor") + CoroutineName(options.protocolDebugName))
    // now the incoming and outgoing requests can clash by ids, but it should not be a problem
    private val requestIdCounter: AtomicInt = atomic(0)
    private val pendingOutgoingRequests: AtomicRef<PersistentMap<OutgoingRequestId, CompletableDeferred<JsonElement>>> =
        atomic(persistentMapOf())
    private val pendingIncomingRequests: AtomicRef<PersistentMap<IncomingRequestId, Job>> =
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
            val incomingRequestId = IncomingRequestId(request.requestId)
            pendingIncomingRequests.update { map ->
                requestJob = map[incomingRequestId]
                map.remove(incomingRequestId)
            }
            if (requestJob == null) {
                logger.warn { "Received CancelRequest for unknown request: ${request.requestId}" }
                return@setNotificationHandler
            }
            requestJob.cancel(JsonRpcIncomingRequestCanceledException(request.message ?: "Cancelled by the counterpart", incomingRequestId))
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
    public override suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement?
    ): JsonElement {
        val requestId = OutgoingRequestId(RequestId(requestIdCounter.incrementAndGet()))
        val deferred = CompletableDeferred<JsonElement>()

        pendingOutgoingRequests.update { it.put(requestId, deferred) }

        try {
            val request = JsonRpcRequest(
                id = requestId.id,
                method = method,
                params = params
            )

            transport.send(request)
            return deferred.await()
        }
        catch (jsonRpcException: JsonRpcException) {
            throw convertJsonRpcExceptionIfPossible(jsonRpcException)
        }
        catch (ce: CancellationException) {
            logger.trace(ce) { "Request cancelled on this side. Sending CancelRequest notification." }
            withContext(NonCancellable) {
                AcpMethod.MetaMethods.CancelRequest(this@Protocol, CancelRequestNotification(requestId.id, ce.message))
                // here we have to try waiting for graceful CANCELLED response from the other side, do it with timeout
                if (!deferred.isCancelled) {
                    try {
                        withTimeout(options.gracefulRequestCancellationTimeout) {
                            deferred.await()
                        }
                    }
                    catch (e: TimeoutCancellationException) {
                        logger.trace(e) { "Timed out waiting for graceful cancellation response for request: $requestId" }
                    }
                    catch (ce: CancellationException) {
                        // actually should not happen
                        logger.trace(ce) { "Graceful cancellation response received for request: $requestId" }
                    }
                    catch (e: JsonRpcException) {
                        val convertedException = convertJsonRpcExceptionIfPossible(e)
                        if (convertedException is CancellationException) {
                            logger.trace(convertedException) { "Graceful cancellation response received for request: $requestId" }
                        }
                        else {
                            logger.warn(convertedException) { "Unexpected error while waiting for graceful cancellation response for request: $requestId" }
                        }
                    }
                    catch (e: Exception) {
                        logger.warn(e) { "Unexpected error while waiting for graceful cancellation response for request: $requestId" }
                    }
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
    override fun sendNotificationRaw(method: AcpMethod.AcpNotificationMethod<*>, params: JsonElement?) {
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
    public override fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        requestHandlers.update {
            it.put(method.methodName) { params ->
                withContext(additionalContext) {
                    handler(params)
                }
            }
        }
    }

    /**
     * Register a handler for incoming notifications.
     *
     * Prefer typed [setNotificationHandler] over this method.
     */
    public override fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    ) {
        notificationHandlers.update {
            it.put(method.methodName) { params ->
                withContext(additionalContext) {
                    handler(params)
                }
            }
        }
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

    public fun cancelPendingIncomingRequest(requestId: RequestId, ce: CancellationException? = null) {
        var job: Job? = null
        val incomingRequestId = IncomingRequestId(requestId)
        pendingIncomingRequests.getAndUpdate {
            job = it[incomingRequestId]
            it.remove(incomingRequestId)
        }
        if (job != null) {
            logger.trace { "Canceling pending incoming request: $requestId" }
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
                    val requestId = IncomingRequestId(message.id)
                    requestsScope.launch {
                        handleRequest(message)
                    }.also { job ->
                        pendingIncomingRequests.update { map -> map.put(requestId, job) }
                    }.invokeOnCompletion {
                        pendingIncomingRequests.update { it.remove(requestId) }
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
                val result = withContext(request.asContextElement()) {
                    handler(request)
                }
                sendResponse(request.id, result, null)
            } catch (e: AcpExpectedError) {
                logger.trace(e) { "Expected error on '${request.method}'" }
                sendResponse(
                    request.id, null, JsonRpcError(
                        code = JsonRpcErrorCode.INVALID_PARAMS, message = e.message ?: "Invalid params"
                    )
                )
            } catch (e: JsonRpcException) {
                logger.trace(e) { "JsonRpcException on '${request.method}'" }
                sendResponse(request.id, null, JsonRpcError(code = e.code, message = e.message ?: "Internal error", data = e.data))
            } catch (e: SerializationException) {
                logger.trace(e) { "Serialization error on ${request.method}" }
                sendResponse(
                    request.id, null, JsonRpcError(
                        code = JsonRpcErrorCode.PARSE_ERROR, message = e.message ?: "Serialization error"
                    )
                )
            } catch (ce: CancellationException) {
                logger.trace(ce) { "Incoming request cancelled: ${request.method}" }
                if (ce !is JsonRpcIncomingRequestCanceledException) { // JsonRpcIncomingRequestCanceledException already means that the request was cancelled on the counterpart side
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
        val outgoingRequestId = OutgoingRequestId(response.id)
        var deferred: CompletableDeferred<JsonElement>? = null
        pendingOutgoingRequests.update { currentRequests ->
            deferred = currentRequests[outgoingRequestId]
            currentRequests.remove(outgoingRequestId)
        }
        if (deferred != null) {
            val responseError = response.error
            if (responseError != null) {
                // do not convert CANCELLED to CancellationException here, because it's done in sendRequestRaw
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
        val response = JsonRpcResponse(
            id = requestId,
            result = result,
            error = error
        )
        transport.send(response)
    }

    override fun toString(): String {
        return "Protocol(${options.protocolDebugName})"
    }
}

private fun convertJsonRpcExceptionIfPossible(jsonRpcException: JsonRpcException): Exception {
    when (jsonRpcException.code) {
        JsonRpcErrorCode.PARSE_ERROR -> {
            return SerializationException(jsonRpcException.message, jsonRpcException)
        }

        JsonRpcErrorCode.INVALID_PARAMS -> {
            return AcpExpectedError(jsonRpcException.message ?: "Invalid params")
        }

        JsonRpcErrorCode.CANCELLED -> {
            return CancellationException(
                jsonRpcException.message ?: "Cancelled on the counterpart side",
                jsonRpcException
            )
        }

        else -> {
            return IllegalStateException(jsonRpcException.message, jsonRpcException)
        }
    }
}