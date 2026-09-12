package com.agentclientprotocol.transport

import kotlinx.coroutines.flow.Flow

/**
 * Framework-neutral WebSocket connection used by ACP remote transports.
 *
 * Framework adapters own conversion from their native WebSocket/session APIs
 * into this contract. The protocol transport owns JSON-RPC serialization and
 * lifecycle behavior on top of the text-frame stream.
 */
public interface AcpWebSocketConnection : AutoCloseable {
    public val incomingTextFrames: Flow<String>

    public suspend fun sendText(text: String)

    /**
     * Closes the native connection.
     *
     * Implementations should be idempotent and should suppress native close
     * exceptions caused by normal close races or already-closed sessions.
     */
    override fun close()
}
