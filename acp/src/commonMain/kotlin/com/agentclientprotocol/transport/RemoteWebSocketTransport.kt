package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

private val remoteWebSocketLogger = KotlinLogging.logger {}

/**
 * Framework-neutral ACP transport for the WebSocket remote profile.
 *
 * This class owns JSON-RPC framing over text messages. Framework modules should
 * adapt their native WebSocket/session APIs to [AcpWebSocketConnection] and then
 * compose this transport instead of duplicating protocol behavior.
 */
public class RemoteWebSocketTransport(
    parentScope: CoroutineScope,
    private val connection: AcpWebSocketConnection,
    private val name: String = RemoteWebSocketTransport::class.simpleName!!,
) : BaseTransport() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name))
    private val sendChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)
    private val closeFired = atomic(false)

    override fun start() {
        if (!_state.compareAndSet(Transport.State.CREATED, Transport.State.STARTING)) {
            error("Transport is not in ${Transport.State.CREATED.name} state")
        }
        _state.value = Transport.State.STARTED

        scope.launch(CoroutineName("$name.write-to-websocket")) {
            try {
                for (message in sendChannel) {
                    val jsonText = try {
                        ACPJson.encodeToString(message)
                    } catch (e: SerializationException) {
                        remoteWebSocketLogger.trace(e) { "Failed to serialize message: $message" }
                        fireError(e)
                        continue
                    }
                    remoteWebSocketLogger.trace { "Sending message to WebSocket: '$jsonText'" }
                    connection.sendText(jsonText)
                }
                remoteWebSocketLogger.trace { "No more messages in send channel, closing WebSocket connection" }
                closeConnection()
            } catch (ce: CancellationException) {
                remoteWebSocketLogger.trace(ce) { "WebSocket send job cancelled" }
                closeConnection()
            } catch (e: Throwable) {
                remoteWebSocketLogger.trace(e) { "Failed to send message to WebSocket" }
                fireError(e)
                closeConnection()
            } finally {
                close()
            }
        }

        scope.launch(CoroutineName("$name.read-from-websocket")) {
            try {
                connection.incomingTextFrames.collect { text ->
                    val decodedMessage = try {
                        decodeJsonRpcMessage(text)
                    } catch (e: SerializationException) {
                        remoteWebSocketLogger.trace(e) { "Failed to deserialize message: '$text'" }
                        fireError(e)
                        return@collect
                    }
                    remoteWebSocketLogger.trace { "Received message from WebSocket: '$text'" }
                    fireMessage(decodedMessage)
                }
            } catch (ce: CancellationException) {
                remoteWebSocketLogger.trace(ce) { "WebSocket receive job cancelled" }
                closeConnection()
            } catch (e: Throwable) {
                remoteWebSocketLogger.trace(e) { "Failed to receive message from WebSocket" }
                fireError(e)
            } finally {
                close()
            }
            remoteWebSocketLogger.trace { "Exiting WebSocket read job" }
        }
    }

    override fun send(message: JsonRpcMessage) {
        sendChannel.trySend(message)
    }

    override fun close() {
        while (true) {
            val old = _state.value
            if (old == Transport.State.CLOSED || old == Transport.State.CLOSING) return
            if (_state.compareAndSet(old, Transport.State.CLOSING)) break
        }

        sendChannel.close()
        closeConnection()
        scope.cancel()
        _state.value = Transport.State.CLOSED

        if (closeFired.compareAndSet(false, true)) {
            fireClose()
        }
    }

    private fun closeConnection() {
        runCatching { connection.close() }.onFailure { e ->
            fireError(e)
        }
    }
}
