package com.agentclientprotocol.agent

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

class TestTransport(val timeout: Duration) : BaseTransport() {
    private val requestId = atomic(0)

    private val responseFlow: MutableStateFlow<JsonRpcMessage?> = MutableStateFlow(null)

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun send(message: JsonRpcMessage) {
        responseFlow.value = message
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    suspend fun fireTestRequest(methodName: MethodName, params: JsonElement): List<JsonRpcMessage> {
        val reqId = RequestId.create(requestId.incrementAndGet())
        val jsonReq = JsonRpcRequest(reqId, methodName, params)

        return try {
            coroutineScope {
                val responses = async {
                    withTimeoutOrNull(timeout) {
                        responseFlow.filterNotNull()
                            .transformWhile {
                                emit(it)
                                it !is JsonRpcResponse
                            }
                            .toList()
                    }
                }
                fireMessage(jsonReq)
                responses.await() ?: emptyList()
            }
        } finally {
            responseFlow.value = null
        }
    }

    fun fireTestNotification(methodName: MethodName, params: JsonElement) {
        fireMessage(JsonRpcNotification(methodName, params))
    }
}