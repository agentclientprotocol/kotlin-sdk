package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.rpc.JsonRpcJson
import com.agentclientprotocol.rpc.parseTransportFrame
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Default URL path segment used by the ACP WebSocket client and server helpers. */
public const val ACP_PATH: String = "acp"

/**
 * Exchanges JSON-RPC frames over an existing WebSocket session.
 *
 * Call [start] once to begin reading and writing. [send] accepts frames into an unbounded, ordered
 * queue, including before [start]. [close] rejects further sends and asynchronously drains that
 * queue before closing the session normally. Reader termination and writer completion also initiate
 * shutdown. I/O failures are reported to error listeners; close listeners run when shutdown finishes.
 *
 * @param parentScope Supplies the coroutine context and parent job for the transport's reader,
 * writer, and shutdown work. Cancelling it cancels transport work and aborts the session when the
 * transport has started.
 * @param wss An established WebSocket session whose incoming channel and outgoing writes are managed
 * by this transport. The transport closes the session on shutdown and cancels it on writer failure,
 * cancellation, or drain timeout.
 * @param drainTimeout Maximum time [close] waits for queued frames and the normal WebSocket Close
 * frame to be written and flushed. Defaults to five seconds.
 */
public class WebSocketTransport(
    private val parentScope: CoroutineScope,
    private val wss: WebSocketSession,
    private val drainTimeout: Duration = 5.seconds,
) : BaseTransport() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val sendChannel = Channel<String>(Channel.UNLIMITED)
    private val writer = scope.launch(start = CoroutineStart.LAZY) {
        try {
            for (encoded in sendChannel) {
                wss.send(Frame.Text(encoded))
                wss.flush()
            }
            wss.close(CloseReason(CloseReason.Codes.NORMAL, ""))
            wss.flush()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            fireError(e)
            wss.cancel()
        }
    }

    override fun start() {
        check(_state.compareAndSet(Transport.State.CREATED, Transport.State.STARTING)) { "Transport has already started or closed" }
        writer.invokeOnCompletion { close() }
        writer.start()

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

    /**
     * Rejects new sends and asynchronously drains accepted frames before sending a normal WebSocket Close frame.
     *
     * Returns without waiting for shutdown. The state becomes [Transport.State.CLOSING] immediately;
     * draining and flushing are limited by [drainTimeout]. Cancellation or timeout aborts the session
     * and discards remaining queued frames. The state becomes [Transport.State.CLOSED] and close
     * listeners run once shutdown finishes.
     *
     * Safe to call repeatedly or before [start]; closing before start also drains accepted frames.
     */
    override fun close() {
        if (!sendChannel.close()) return

        _state.value = Transport.State.CLOSING
        // Start undispatched so cleanup also runs when the parent scope is already cancelled.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var writerDrained = false
            try {
                writerDrained = withTimeoutOrNull(drainTimeout) {
                    writer.join()
                    !writer.isCancelled
                } == true
            } finally {
                if (!writerDrained) wss.cancel()
                scope.cancel()
                sendChannel.cancel()
                _state.value = Transport.State.CLOSED
                fireClose()
            }
        }
    }
}
