package com.agentclientprotocol.client

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Client operations that record the session events they get.
 *
 * @param beforeEach runs before an update is recorded; it may suspend or throw
 * @param beforeOpened runs before the marker is recorded; it may suspend or throw
 */
internal class RecordingOperations(
    private val beforeEach: suspend (SessionUpdate) -> Unit = {},
    private val beforeOpened: suspend () -> Unit = {},
) : ClientSessionOperations {
    /**
     * Every event in delivery order, the marker as [OPENED].
     */
    val events = MutableStateFlow<List<Any>>(emptyList())

    /**
     * The updates in delivery order, without the marker.
     */
    val updates = MutableStateFlow<List<SessionUpdate>>(emptyList())

    /**
     * The responses of the markers in delivery order.
     */
    val openedResponses = MutableStateFlow<List<AcpCreatedSessionResponse?>>(emptyList())

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = error("not expected in this test")

    override suspend fun notify(event: SessionEvent) {
        when (event) {
            is SessionEvent.Update -> {
                beforeEach(event.update)
                updates.update { it + event.update }
                events.update { it + event.update }
            }
            is SessionEvent.Opened -> {
                beforeOpened()
                openedResponses.update { it + event.response }
                events.update { it + OPENED }
            }
        }
    }

    suspend fun awaitCount(count: Int) {
        withTimeout(TIMEOUT) { updates.first { it.size >= count } }
    }

    suspend fun awaitUpdate(update: SessionUpdate) {
        withTimeout(TIMEOUT) { updates.first { update in it } }
    }

    suspend fun awaitEvents(count: Int) {
        withTimeout(TIMEOUT) { events.first { it.size >= count } }
    }

    companion object {
        /**
         * Stands for the marker in [events].
         */
        const val OPENED = "Opened"

        private val TIMEOUT = 5.seconds
    }
}

/**
 * The agent side of a connection, driven by the test: it records the requests of the client, and sends the updates
 * and the answers the test asks for, in exactly that order.
 */
internal class ScriptedAgentTransport : BaseTransport() {
    private val requests = Channel<JsonRpcRequest>(capacity = Channel.UNLIMITED)
    private val responses = MutableStateFlow<Map<RequestId, JsonRpcResponse>>(emptyMap())
    private val notifications = Channel<JsonRpcNotification>(capacity = Channel.UNLIMITED)
    private val barrierIds = atomic(0)

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    override fun send(frame: TransportFrame) {
        when (val message = (frame as TransportFrame.Single).message) {
            is JsonRpcRequest -> requests.trySend(message)
            is JsonRpcResponse -> responses.update { it + (message.id to message) }
            is JsonRpcNotification -> notifications.trySend(message)
        }
    }

    suspend fun receiveRequest(method: MethodName): JsonRpcRequest {
        val request = withTimeout(5.seconds) { requests.receive() }
        assertEquals(method, request.method)
        return request
    }

    suspend fun receiveNotification(method: MethodName): JsonRpcNotification {
        while (true) {
            val notification = withTimeout(5.seconds) { notifications.receive() }
            if (notification.method == method) return notification
        }
    }

    fun assertNoRequest() {
        assertNull(requests.tryReceive().getOrNull(), "the client must not send a request")
    }

    fun sendUpdate(sessionId: SessionId, update: SessionUpdate) {
        sendNotification(AcpMethod.ClientMethods.V1.SessionUpdate, SessionNotification(sessionId, update))
    }

    fun <P : AcpNotification> sendNotification(method: AcpMethod.AcpNotificationMethod<P>, params: P) {
        val encoded = ACPJson.encodeToJsonElement(method.serializer, params)
        fireFrame(TransportFrame.Single(JsonRpcNotification(method = method.methodName, params = encoded)))
    }

    /**
     * Sends a request of the agent to the client, and returns its id for [awaitResponse].
     */
    fun <P : AcpRequest> sendRequest(method: AcpMethod.AcpRequestResponseMethod<P, *>, params: P): RequestId {
        val id = RequestId.create("agent-${barrierIds.incrementAndGet()}")
        val encoded = ACPJson.encodeToJsonElement(method.requestSerializer, params)
        fireFrame(TransportFrame.Single(JsonRpcRequest(id, method.methodName, encoded)))
        return id
    }

    /**
     * Waits for the client's answer to the agent's request [id].
     */
    suspend fun awaitResponse(id: RequestId): JsonRpcResponse =
        withTimeout(5.seconds) { responses.first { id in it }.getValue(id) }

    /**
     * Returns the client's answer to the agent's request [id], or `null` if it hasn't answered yet.
     */
    fun responseOrNull(id: RequestId): JsonRpcResponse? = responses.value[id]

    fun <T> answer(request: JsonRpcRequest, serializer: KSerializer<T>, response: T) {
        answerRaw(request, ACPJson.encodeToJsonElement(serializer, response))
    }

    fun answerRaw(request: JsonRpcRequest, result: JsonElement) {
        fireFrame(TransportFrame.Single(JsonRpcSuccessResponse(request.id, result)))
    }

    fun fail(request: JsonRpcRequest, code: JsonRpcErrorCode = JsonRpcErrorCode.INTERNAL_ERROR) {
        val error = JsonRpcError(code.code, "load failed")
        fireFrame(TransportFrame.Single(JsonRpcErrorResponse(request.id, error)))
    }

    /**
     * Returns after the client has handled every message sent before this call.
     *
     * The client reads messages in wire order and lets each notification handler run before it reads on, so its
     * answer to a later request proves that the earlier notifications have been handled.
     */
    suspend fun awaitHandled() {
        val id = RequestId.create("barrier-${barrierIds.incrementAndGet()}")
        fireFrame(TransportFrame.Single(JsonRpcRequest(id, MethodName("test/barrier"), null)))
        awaitResponse(id)
    }
}
