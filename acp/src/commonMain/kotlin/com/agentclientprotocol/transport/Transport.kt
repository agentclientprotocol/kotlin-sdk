@file:Suppress("unused")

package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import kotlinx.coroutines.channels.Channel

public typealias MessageListener = (JsonRpcMessage) -> Unit
public typealias ErrorListener = (Throwable) -> Unit
public typealias CloseListener = () -> Unit

/**
 * Base interface for ACP transport implementations.
 *
 * Transports handle the actual communication between clients and agents,
 * supporting various protocols like STDIO, WebSocket, and SSE.
 */
public interface Transport : AutoCloseable {
    /**
     * Start the transport and begin listening for messages.
     */
    public fun start()

    /**
     * Send a message over the transport.
     * 
     * @param message The JSON-encoded message to send
     */
    public fun send(message: JsonRpcMessage)

    /**
     * Whether the transport is currently connected.
     */
    public val isConnected: Boolean

    public fun onMessage(handler: MessageListener)

    public fun onError(handler: ErrorListener)

    public fun onClose(handler: CloseListener)
}

public fun Transport.asMessageChannel(): Channel<JsonRpcMessage> {
    val channel = Channel<JsonRpcMessage>(capacity = Channel.UNLIMITED)
    onMessage { channel.trySend(it) }
    onError { channel.close(it) }
    onClose { channel.close() }
    return channel
}