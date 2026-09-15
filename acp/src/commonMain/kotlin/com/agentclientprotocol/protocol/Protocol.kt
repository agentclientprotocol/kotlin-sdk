@file:Suppress("unused")

package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.model.ProtocolVersion
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.asFrameChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * Communication protocol implementation, implementing [RpcMethodsOperations] and handling JSON-RPC communication over a transport.
 *
 * This class manages request/response correlation, notifications, and error handling.
 */
public class Protocol(
    parentScope: CoroutineScope,
    private val transport: Transport,
    public val options: ProtocolOptions = ProtocolOptions()
) : RpcMethodsOperations {
    public companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val protocolJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + protocolJob + CoroutineName(options.protocolDebugName))
    /**
     * Serializes handler execution, which is what preserves wire order between handlers.
     *
     * `parallelism = 1` is load-bearing beyond ordering: tests use it as a barrier, on the reasoning
     * that a handler queued later cannot run before one queued earlier, so awaiting a marker sent after
     * a message proves that message's handler has already run. That is the only way to order a test
     * against a handler with no observable effect, such as a `$/cancel_request` that is correctly
     * ignored. Raising this parallelism would not fail those tests — it would quietly turn them into
     * races that pass most of the time. See the notes in `BatchProtocolTest` and `AgentTest`.
     */
    private val handlerDispatcher = Dispatchers.Default.limitedParallelism(parallelism = 1)
    // a scope and dispatcher that executes handlers to avoid blocking of message processing
    private val handlerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(protocolJob)
            + handlerDispatcher + CoroutineName(options.protocolDebugName))
    // now the incoming and outgoing requests can clash by ids, but it should not be a problem
    private val requestIdCounter: AtomicInt = atomic(0)
    private val pendingOutgoingRequests: AtomicRef<PersistentMap<OutgoingRequestId, OutgoingRequest>> =
        atomic(persistentMapOf())
    private val pendingIncomingRequests: AtomicRef<PersistentMap<IncomingRequestId, IncomingRequest>> =
        atomic(persistentMapOf())

    /**
     * Request handlers for incoming requests.
     */
    private val requestHandlers: AtomicRef<PersistentMap<MethodName, suspend (JsonRpcRequest) -> RequestOutcome<JsonElement?>>> =
        atomic(persistentMapOf())

    /**
     * Notification handlers for incoming notifications.
     */
    private val notificationHandlers: AtomicRef<PersistentMap<MethodName, suspend (JsonRpcNotification) -> Unit>> =
        atomic(persistentMapOf())

    private val _negotiatedProtocolVersion: AtomicRef<ProtocolVersion?> = atomic(null)

    init {
        // Also release resources if the parent is cancelled before the reader starts.
        protocolJob.invokeOnCompletion { close() }
    }

    /**
     * The protocol version negotiated for this connection, or `null` before `initialize` resolves.
     *
     * Lives here because it is the wire format's version: dispatch and serialization need it, and a
     * connection speaks exactly one version.
     */
    internal val negotiatedProtocolVersion: ProtocolVersion?
        get() = _negotiatedProtocolVersion.value

    /**
     * Records [version] as the version of this connection and returns the version the connection
     * actually speaks — the first recorded value wins, so a repeated `initialize` cannot move it.
     */
    internal fun recordNegotiatedProtocolVersion(version: ProtocolVersion): ProtocolVersion =
        if (_negotiatedProtocolVersion.compareAndSet(null, version))
            version
        else
            checkNotNull(_negotiatedProtocolVersion.value)

    /**
     * Connect to a transport and start processing messages.
     */
    public fun start() {
        protocolJob.ensureActive()
        setNotificationHandler(AcpMethod.MetaMethods.CancelRequest) { request ->
            val incomingRequestId = IncomingRequestId(request.requestId)
            val incoming = pendingIncomingRequests.value[incomingRequestId]
            if (incoming == null) {
                logger.warn { "Received CancelRequest for unknown request: ${request.requestId}" }
                return@setNotificationHandler
            }
            val cancelled = incoming.cancelByCounterpart(
                IncomingRequestCancelledException(
                    "Cancelled by the counterpart",
                    incomingRequestId,
                )
            )
            if (!cancelled) {
                // A reply for this request has already been collected, so the counterpart is getting its
                // answer either way and this cancellation is redundant. Acting on it would only discard
                // after-response work, which for a v2 prompt turn means losing the idle update that ends it.
                logger.debug {
                    "Ignoring CancelRequest for request ${request.requestId}: it has already been answered"
                }
            }
        }

        // Start processing incoming frames
        val frameChannel = transport.asFrameChannel()
        scope.launch(CoroutineName("${Protocol::class.simpleName!!}.read-frames")) {
            try {
                for (frame in frameChannel) {
                    handleIncomingFrame(frame)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                logger.error(e) { "Error processing incoming frames" }
            } finally {
                close()
            }
        }
        transport.start()
    }

    override suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement?,
        sessionId: SessionId?
    ): JsonElement {
        currentCoroutineContext().ensureActive()
        val requestId = OutgoingRequestId(RequestId.create(requestIdCounter.incrementAndGet()))
        val response = CompletableDeferred<JsonElement>()
        val outgoingRequest = OutgoingRequest(response, sessionId)

        pendingOutgoingRequests.update { it.put(requestId, outgoingRequest) }

        try {
            val request = JsonRpcRequest(
                id = requestId.id,
                method = method,
                params = params
            )
            sendFrame(TransportFrame.Single(request))

            return response.await()
        } catch (jsonRpcException: JsonRpcException) {
            throw jsonRpcException.toProtocolException()
        } catch (ce: CancellationException) {
            logger.trace(ce) { "Request cancelled on this side. Sending CancelRequest notification." }
            withContext(NonCancellable) {
                if (!protocolJob.isActive) return@withContext

                val cancellationSent = runCatching {
                    AcpMethod.MetaMethods.CancelRequest(this@Protocol, CancelRequestNotification(requestId.id))
                }.isSuccess
                if (!cancellationSent) return@withContext

                // here we have to try waiting for graceful CANCELLED response from the other side, do it with timeout
                if (!response.isCancelled) {
                    try {
                        withTimeout(options.gracefulRequestCancellationTimeout) {
                            response.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.trace(e) { "Timed out waiting for graceful cancellation response for request: $requestId" }
                    } catch (ce: CancellationException) {
                        // actually should not happen
                        logger.trace(ce) { "Graceful cancellation response received for request: $requestId" }
                    } catch (e: JsonRpcException) {
                        val convertedException = e.toProtocolException()
                        if (convertedException is AcpRequestCancelledException) {
                            logger.trace(convertedException) { "Graceful cancellation response received for request: $requestId" }
                        } else {
                            logger.warn(convertedException) { "Unexpected error while waiting for graceful cancellation response for request: $requestId" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Unexpected error while waiting for graceful cancellation response for request: $requestId" }
                    }
                    response.cancel()
                }
            }

            throw ce
        }
        finally {
            pendingOutgoingRequests.update { if (it[requestId] === outgoingRequest) it.remove(requestId) else it }
        }
    }

     override suspend fun sendBatchRequestRaw(
        calls: List<JsonRpcCall>,
        sessionId: SessionId?,
    ): List<Result<JsonElement>> {
        require(calls.isNotEmpty()) { "A JSON-RPC batch must not be empty" }

        val outgoingBuilder = mutableListOf<Pair<OutgoingRequestId, OutgoingRequest>>()
        val frame = TransportFrame.Batch(calls.map { call ->
            when (call) {
                is JsonRpcCall.Request -> {
                    val id = RequestId.create(requestIdCounter.incrementAndGet())
                    outgoingBuilder += OutgoingRequestId(id) to OutgoingRequest(CompletableDeferred(), sessionId)

                    TransportFrame.Single(JsonRpcRequest(id = id, method = call.method, params = call.params))
                }

                is JsonRpcCall.Notification ->
                    TransportFrame.Single(JsonRpcNotification(method = call.method, params = call.params))
            }
        })
        val outgoing = outgoingBuilder.toMap()

        currentCoroutineContext().ensureActive()

        pendingOutgoingRequests.update { it.putAll(outgoing) }

        var sent = false
        try {
            sendFrame(frame)
            sent = true

            return outgoing.map { (_, request) ->
                try {
                    Result.success(request.response.await())
                } catch (e: JsonRpcException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(e.toProtocolException())
                }
            }
        } catch (ce: CancellationException) {
            // If the scope is still active when cancellation is encountered, cancel other pending requests in this batch
            if (sent && protocolJob.isActive) {
                for ((id, request) in outgoing) {
                    if (!request.response.isCompleted) runCatching {
                        AcpMethod.MetaMethods.CancelRequest(this, CancelRequestNotification(id.id))
                    }
                }
            }

            throw ce
        } finally {
            // Cancel all outgoing requests and remove them from the global pending requests map
            pendingOutgoingRequests.update { pendingOutgoing ->
                pendingOutgoing.mutate { pendingOutgoing ->
                    outgoing.entries.forEach { (id, request) ->
                        request.response.cancel()
                        if (pendingOutgoing[id] === request) pendingOutgoing.remove(id)
                    }
                }
            }
        }
    }

    override fun sendNotificationRaw(method: AcpMethod.AcpNotificationMethod<*>, params: JsonElement?) {
        val notification = JsonRpcNotification(
            method = method.methodName,
            params = params
        )
        sendFrame(TransportFrame.Single(notification))
    }

    private fun sendFrame(frame: TransportFrame) {
        protocolJob.ensureActive()
        transport.send(frame)
    }

    override fun setRequestOutcomeHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcRequest) -> RequestOutcome<JsonElement?>,
    ) {
        val wrapped: suspend (JsonRpcRequest) -> RequestOutcome<JsonElement?> = { request ->
            var outcome: RequestOutcome<JsonElement?>? = null
            try {
                withContext(additionalContext) {
                    handler(request).also { outcome = it }
                }
            } catch (e: CancellationException) {
                // withContext can throw cancellation exceptions at the dispatcher boundary, clean up in this case
                outcome?.onCompletion?.invoke()
                throw e
            }
        }

        requestHandlers.update { it.put(method.methodName, wrapped) }
    }

    override fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    ) {
        val wrapped: suspend (JsonRpcNotification) -> Unit = { params ->
            withContext(additionalContext) {
                handler(params)
            }
        }
        notificationHandlers.update { it.put(method.methodName, wrapped) }
    }

    /**
     * Looks up the session ID associated with an outgoing request that is currently in-flight.
     *
     * Returns null if the request is not found or was not associated with a session.
     */
    public fun getOutgoingRequestSessionId(requestId: RequestId): SessionId? {
        return pendingOutgoingRequests.value[OutgoingRequestId(requestId)]?.sessionId
    }

    /**
     * Close the protocol and cleanup resources.
     */
    public fun close() {
        val message = "Protocol closed"
        try {
            protocolJob.cancel(CancellationException(message))
            cancelPendingIncomingRequests(CancellationException(message))
            cancelPendingOutgoingRequests(CancellationException(message))
        } finally {
            transport.close()
        }
    }

    /**
     * Cancels all requests that are currently being executed by this side.
     *
     * The message of [ce] will be rethrown as a [CancellationException] on the counterpart side.
     *
     * Unlike a counterpart's `$/cancel_request`, this also reaches a request whose reply has already been
     * collected, and so **discards its after-response work** — for a v2 prompt, the rest of the turn's
     * update stream. The reply itself is never withheld. To end a v2 turn while letting it report its own
     * outcome, use `session/cancel` / `AgentSession.cancel()` instead.
     */
    public fun cancelPendingIncomingRequests(ce: CancellationException? = null) {
        val requests = pendingIncomingRequests.getAndUpdate { it.clear() }
        for (incoming in requests.values) {
            logger.trace { "Canceling pending incoming request: ${incoming.handlerJob.key}" }
            incoming.handlerJob.cancel(ce)
        }
    }

    /**
     * Cancels one request that is currently being executed by this side.
     *
     * The message of [ce] will be rethrown as a [CancellationException] on the counterpart side.
     *
     * Carries the same caveat as [cancelPendingIncomingRequests]: it reaches a request whose reply has
     * already been collected and discards its after-response work.
     */
    public fun cancelPendingIncomingRequest(requestId: RequestId, ce: CancellationException? = null) {
        var incoming: IncomingRequest? = null
        val incomingRequestId = IncomingRequestId(requestId)
        pendingIncomingRequests.getAndUpdate {
            incoming = it[incomingRequestId]
            it.remove(incomingRequestId)
        }
        if (incoming != null) {
            logger.trace { "Canceling pending incoming request: $requestId" }
            incoming.handlerJob.cancel(ce)
        }
    }

    /**
     * Cancels all requests that are currently awaited for a response from the counterpart.
     *
     * Methods that await for a response will throw [ce]
     */
    public fun cancelPendingOutgoingRequests(ce: CancellationException? = null) {
        val requests = pendingOutgoingRequests.getAndUpdate { it.clear() }
        for ((requestId, outgoing) in requests) {
            logger.trace { "Canceling pending outgoing request: $requestId" }
            outgoing.response.cancel(ce)
        }
    }

    private suspend fun handleIncomingFrame(frame: TransportFrame) {
        val entries = when (frame) {
            is TransportFrame.Batch -> frame.entries
            is TransportFrame.Entry -> listOf(frame)
        }
        val responseFrameQueued = CompletableDeferred<Unit>(protocolJob)
        val slots = mutableListOf<ResponseSlot>()

        for (entry in entries) {
            when (entry) {
                is TransportFrame.Malformed -> if (!entry.isResponse) {
                    slots += ResponseSlot(responseFrameQueued).also {
                        it.response.complete(JsonRpcErrorResponse(RequestId.Null, entry.error))
                    }
                }

                is TransportFrame.Single -> when (val message = entry.message) {
                    is JsonRpcRequest -> {
                        val slot = ResponseSlot(responseFrameQueued)
                        slots += slot
                        dispatchRequest(message, slot)
                    }

                    is JsonRpcNotification -> {
                        handlerScope.launch { handleNotification(message) }
                        // Give the notification handler a turn before processing later messages, without waiting
                        // for it to finish if it suspends. This is to preserve wire order for notifications.
                        withContext(handlerDispatcher) {}
                    }

                    is JsonRpcResponse -> handleResponse(message)
                }
            }
        }

        if (slots.isEmpty()) {
            responseFrameQueued.complete(Unit)
            return
        }

        // Dispatch is now complete. This job never joins handler jobs or their continuations.
        scope.launch {
            try {
                val replies = slots.map { TransportFrame.Single(it.response.await()) }
                currentCoroutineContext().ensureActive()
                sendFrame(
                    if (frame is TransportFrame.Batch) TransportFrame.Batch(replies)
                    else replies.single()
                )
                responseFrameQueued.complete(Unit)
            } catch (ce: CancellationException) {
                responseFrameQueued.cancel(ce)
                logger.trace(ce) { "Response queueing cancelled" }
            } catch (t: Throwable) {
                if (protocolJob.isActive) {
                    responseFrameQueued.completeExceptionally(t)
                    logger.error(t) { "Unable to queue response frame" }
                } else {
                    responseFrameQueued.cancel(CancellationException("Response queueing cancelled", t))
                    logger.trace(t) { "Response queueing cancelled" }
                }
            }
        }
    }

    private fun dispatchRequest(request: JsonRpcRequest, slot: ResponseSlot) {
        val requestId = IncomingRequestId(request.id)
        // Register before execution or cancellation can complete this job.
        val handlerJob = handlerScope.launch(start = CoroutineStart.LAZY) { handleRequest(request, slot) }
        val incoming = IncomingRequest(handlerJob)

        val previous = pendingIncomingRequests.getAndUpdate {
            if (requestId in it) it else it.put(requestId, incoming)
        }
        if (requestId in previous) {
            handlerJob.cancel()
            slot.response.complete(JsonRpcErrorResponse(
                request.id,
                JsonRpcError(JsonRpcErrorCode.INVALID_REQUEST.code, "Request ID is already in use"),
            ))
            return
        }

        // Collecting a reply settles the request for the counterpart, so its `$/cancel_request` stops
        // applying from here on. Registered for every completion path rather than at each `complete` call.
        slot.response.invokeOnCompletion { incoming.settle() }

        handlerJob.invokeOnCompletion { cause ->
            // Also resolves a slot if cancellation prevented the coroutine body from starting.
            if (!slot.response.isCompleted) {
                val error = (cause ?: IllegalStateException("Request completed without a response")).toJsonRpcError()
                slot.response.complete(JsonRpcErrorResponse(request.id, error))
            }
            pendingIncomingRequests.update { if (it[requestId] === incoming) it.remove(requestId) else it }
        }

        handlerJob.start()
    }

    private suspend fun handleRequest(request: JsonRpcRequest, slot: ResponseSlot) {
        var outcome: RequestOutcome<JsonElement?>? = null

        try {
            try {
                val handler = requestHandlers.value[request.method]
                    ?: jsonRpcMethodNotFound("Method not supported: ${request.method}")

                withContext(JsonRpcRequestContextElement(request)) {
                    outcome = handler(request)
                }

                currentCoroutineContext().ensureActive()
                slot.response.complete(JsonRpcSuccessResponse(request.id, outcome!!.response ?: JsonNull))
            } catch (t: Throwable) {
                slot.response.complete(JsonRpcErrorResponse(request.id, t.toJsonRpcError()))
                return
            }

            /*
            Follow-up work must wait until the reply frame is queued, otherwise updates could reach the
            peer before the response that announced them. This wait still runs during cancellation;
            `responseFrameQueued` is completed or canceled by the protocol's normal shutdown paths.
             */
            val queued = runCatching { withContext(NonCancellable) { slot.responseFrameQueued.await() } }
            if (queued.isFailure && currentCoroutineContext().isActive) {
                // The reply never reached the transport and nothing cancelled this job: there is no
                // follow-up work to do, because nothing announced it.
                throw queued.exceptionOrNull()!!
            }

            /*
            Run follow-up work even if this job was already canceled, so it can observe cancellation and
            finish whatever the sent reply promised. Counterpart cancellation no longer reaches a settled
            request; local cancellation does, and is handled cooperatively inside afterResponse.
             */
            outcome.afterResponse?.invoke()
        } catch (t: Throwable) {
            // The response is already finalized: a continuation must never send a second response.
            if (t !is CancellationException) {
                logger.error(t) { "After-response work failed for ${request.method}" }
            }
        } finally {
            try {
                outcome?.onCompletion?.invoke()
            } catch (t: Throwable) {
                logger.error(t) { "Outcome cleanup failed for ${request.method}" }
            }
        }
    }

    private suspend fun handleNotification(notification: JsonRpcNotification) {
        val handler = notificationHandlers.value[notification.method]
        if (handler != null) {
            runCatching {
                handler(notification)
            }.onFailure { t ->
                if (t is CancellationException) {
                    logger.trace(t) { "Notification handler for '${notification.method}' cancelled" }
                } else {
                    logger.error(t) { "Error handling notification ${notification.method}" }
                }
            }
        } else {
            logger.debug { "No handler for notification: ${notification.method}" }
        }
    }

    private fun handleResponse(response: JsonRpcResponse) {
        val outgoingRequestId = OutgoingRequestId(response.id)
        var outgoing: OutgoingRequest? = null
        pendingOutgoingRequests.update { currentRequests ->
            outgoing = currentRequests[outgoingRequestId]
            currentRequests.remove(outgoingRequestId)
        }

        val result = outgoing?.response
        if (result != null) {
            when (response) {
                is JsonRpcSuccessResponse -> result.complete(response.result)

                is JsonRpcErrorResponse -> result.completeExceptionally(
                    JsonRpcException(
                        code = response.error.code,
                        message = response.error.message,
                        data = response.error.data,
                    )
                )
            }
        } else {
            logger.warn { "Received response for unknown request ID: ${response.id}" }
        }
    }


    override fun toString(): String {
        return "Protocol(${options.protocolDebugName})"
    }
}

// these types added to distinct request and response ids and not to clash between them
@JvmInline
internal value class IncomingRequestId(val id: RequestId)
@JvmInline
internal value class OutgoingRequestId(val id: RequestId)

internal data class OutgoingRequest(
    val response: CompletableDeferred<JsonElement>,
    val sessionId: SessionId? = null
)

/**
 * An incoming request being served, and whether a reply for it has been collected yet.
 *
 * The distinction exists for `$/cancel_request`. Once a reply has been collected the request is settled
 * from the counterpart's perspective, so its cancellation can only be redundant, while honoring it
 * would still discard after-response work — for a v2 prompt turn, the idle update that MUST end the
 * turn. Local cancellation ([Protocol.cancelPendingIncomingRequests], [Protocol.close]) deliberately
 * still reaches the job, because shutdown does have to discard that work.
 */
internal class IncomingRequest(val handlerJob: Job) {
    private val cancellableByCounterpart = atomic(true)

    /** Records that a reply was collected, after which [cancelByCounterpart] does nothing. */
    fun settle() {
        cancellableByCounterpart.value = false
    }

    /**
     * Cancels this request on the counterpart's behalf, unless a reply has already been collected.
     *
     * @return whether the request was cancelled.
     */
    fun cancelByCounterpart(ce: CancellationException): Boolean {
        if (!cancellableByCounterpart.compareAndSet(expect = true, update = false)) return false
        handlerJob.cancel(ce)
        return true
    }
}

/**
 * Coordinates one incoming entry's reply with the response frame that contains it.
 * Each request or invalid entry gets its own slot, even when request IDs repeat in a batch.
 * Separating reply readiness from enqueueing lets the protocol collect all batch replies
 * without waiting for after-response work, such as streaming, to finish.
 *
 * Every incoming request is answered, a cancelled one included: the counterpart that asked to cancel
 * MUST get back either a valid response or `-32800`, so it is never left waiting for a reply that
 * cannot come ([cancellation](https://agentclientprotocol.com/protocol/v2/draft/cancellation)).
 *
 * @property response Completes with this entry's reply. Every incoming request is answered, so the
 * frame collector waits for every slot and each one contributes a reply to the response.
 * @property responseFrameQueued Shared by all slots in the incoming frame. Completes after the response
 * frame is accepted by the transport queue (or no replies remain), allowing after-response
 * work to start. Failure or cancellation prevents that work from starting; success does not
 * imply a network flush or peer acknowledgement.
 */
internal class ResponseSlot(val responseFrameQueued: CompletableDeferred<Unit>) {
    val response = CompletableDeferred<JsonRpcResponse>()
}
