package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

public abstract class BaseTransport : Transport {
    protected val _state: MutableStateFlow<Transport.State> = MutableStateFlow(Transport.State.CREATED)
    private val messageHandlers = atomic<MessageListener> {}
    private val errorHandlers = atomic<ErrorListener> {}
    private val closeHandlers = atomic<CloseListener> {}

    override fun onMessage(handler: MessageListener) {
        messageHandlers.update { old ->
            {
                old(it)
                runCatching { handler(it) }.onFailure { e -> logger.error(e) { "Error in message handler" } }
            }
        }
    }

    override val state: StateFlow<Transport.State>
        get() = _state.asStateFlow()

    protected fun fireMessage(message: JsonRpcMessage) {
        messageHandlers.value(message)
    }

    override fun onClose(handler: CloseListener) {
        closeHandlers.update { old ->
            {
                // old runCatching is made in the previous subscription
                old()
                runCatching { handler() }.onFailure { e -> logger.error(e) { "Error in close handler" } }
            }
        }
    }

    protected fun fireClose() {
        closeHandlers.value()
    }

    override fun onError(handler: ErrorListener) {
        errorHandlers.update { old ->
            {
                old(it)
                runCatching { handler(it) }.onFailure { e -> logger.error(e) { "Error in error handler" } }
            }
        }
    }

    protected fun fireError(throwable: Throwable) {
        errorHandlers.value(throwable)
    }
}