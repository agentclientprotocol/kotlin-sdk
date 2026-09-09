package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.rpc.JsonRpcJson
import com.agentclientprotocol.rpc.parseTransportFrame
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

public const val ACP_PATH: String = "acp"

public class WebSocketTransport(private val parentScope: CoroutineScope, private val wss: WebSocketSession) : BaseTransport() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val sendChannel = Channel<String>(Channel.UNLIMITED)

    override fun start() {
        check(_state.compareAndSet(Transport.State.CREATED, Transport.State.STARTING)) { "Transport has already started or closed" }
        scope.launch {
            try {
                for (encoded in sendChannel) {
                    wss.send(Frame.Text(encoded))
                    wss.flush()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                fireError(e)
            } finally {
                close()
            }
        }
        scope.launch {
            try {
                for (frame in wss.incoming) {
                    if (frame is Frame.Text) fireFrame(parseTransportFrame(frame.readText()))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                fireError(e)
            } finally {
                close()
            }
        }
        _state.compareAndSet(Transport.State.STARTING, Transport.State.STARTED)
    }

    override fun send(frame: TransportFrame) {
        val encoded = JsonRpcJson.encodeToString(TransportFrame.serializer(), frame)
        sendChannel.trySend(encoded).getOrThrow()
    }

    override fun close() {
        if (!sendChannel.close()) return
        _state.value = Transport.State.CLOSED
        scope.cancel()
        // Also release a writer blocked by network backpressure.
        wss.cancel()
        fireClose()
    }
}
