package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.TransportFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

public abstract class BaseTransport : Transport {
    @Suppress("PropertyName")
    protected val _state: MutableStateFlow<Transport.State> = MutableStateFlow(Transport.State.CREATED)
    private val frameHandlers = atomic<FrameListener>({})
    private val errorHandlers = atomic<ErrorListener>({})
    private val closeHandlers = atomic<CloseListener>({})

    override fun onFrame(handler: FrameListener) {
        frameHandlers.update { old ->
            {
                old(it)
                runCatching { handler(it) }.onFailure { e -> logger.error(e) { "Error in frame handler" } }
            }
        }
    }

    override val state: StateFlow<Transport.State>
        get() = _state.asStateFlow()

    protected fun fireFrame(frame: TransportFrame) {
        frameHandlers.value(frame)
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