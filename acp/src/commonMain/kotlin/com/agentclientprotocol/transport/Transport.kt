@file:Suppress("unused")

package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.TransportFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow

public typealias FrameListener = (TransportFrame) -> Unit
public typealias ErrorListener = (Throwable) -> Unit
public typealias CloseListener = () -> Unit

/**
 * Base interface for ACP transport implementations.
 *
 * Transports handle the actual communication between clients and agents,
 * supporting various protocols like STDIO, WebSocket, and SSE.
 * Implementations must make [close] safe to call repeatedly, including from close listeners.
 */
public interface Transport : AutoCloseable {
    public enum class State { CREATED, STARTING, STARTED, CLOSING, CLOSED }
    public val state: StateFlow<State>

    /**
     * Start the transport and begin listening for messages.
     */
    public fun start()

    /**
     * Accept a complete frame into the ordered writer queue.
     * Throws synchronously if encoding fails or the queue cannot accept the frame,
     * including when the transport is closing or closed.
     * This does not acknowledge a physical flush or peer receipt.
     *
     * @throws kotlinx.coroutines.channels.ClosedSendChannelException if the writer queue is closed normally.
     * @throws kotlinx.coroutines.CancellationException if the transport was cancelled.
     * Use these exceptions for shutdown so the protocol can distinguish it from other failures.
     */
    public fun send(frame: TransportFrame)

    /**
     * Registers an additional handler for incoming frames.
     * Handlers are invoked in registration order.
     */
    public fun onFrame(handler: FrameListener)

    /**
     * Registers an additional handler for errors.
     * Handlers are invoked in registration order.
     */
    public fun onError(handler: ErrorListener)

    /**
     * Registers an additional handler for close events.
     * Handlers are invoked in registration order.
     */
    public fun onClose(handler: CloseListener)
}

public fun Transport.asFrameChannel(): Channel<TransportFrame> {
    val channel = Channel<TransportFrame>(capacity = Channel.UNLIMITED)
    onFrame { channel.trySend(it) }
    onError { channel.close(it) }
    onClose { channel.close() }
    return channel
}
