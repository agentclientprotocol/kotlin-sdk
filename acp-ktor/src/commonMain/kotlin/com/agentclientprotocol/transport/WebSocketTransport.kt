package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.JsonRpcMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

public const val ACP_PATH: String = "acp"

internal class KtorAcpWebSocketConnection(
    private val parentScope: CoroutineScope,
    private val wss: WebSocketSession,
) : AcpWebSocketConnection {
    override val incomingTextFrames: Flow<String> = flow {
        for (message in wss.incoming) {
            when (message) {
                is Frame.Text -> emit(message.readText())
                else -> logger.trace { "Received unexpected message from channel: '$message'" }
            }
        }
    }

    override suspend fun sendText(text: String) {
        logger.trace { "Sending message to channel: '$text'" }
        wss.send(Frame.Text(text))
        wss.flush()
    }

    override fun close() {
        parentScope.launch {
            wss.close()
            wss.flush()
        }
    }
}

public class WebSocketTransport(private val parentScope: CoroutineScope, private val wss: WebSocketSession) : BaseTransport() {
    private val delegate = RemoteWebSocketTransport(
        parentScope = parentScope,
        connection = KtorAcpWebSocketConnection(parentScope, wss),
        name = WebSocketTransport::class.simpleName!!,
    )

    init {
        delegate.onMessage { fireMessage(it) }
        delegate.onError { fireError(it) }
        delegate.onClose { fireClose() }
        parentScope.launch {
            delegate.state.collect {
                _state.value = it
            }
        }
    }

    override fun start() {
        delegate.start()
        _state.value = delegate.state.value
    }

    override fun send(message: JsonRpcMessage) {
        delegate.send(message)
    }

    override fun close() {
        delegate.close()
        _state.value = delegate.state.value
    }
}
