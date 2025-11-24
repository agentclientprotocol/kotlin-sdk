package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

public const val ACP_PATH: String = "acp"

public class WebSocketTransport(private val parentScope: CoroutineScope, private val wss: WebSocketSession) : BaseTransport() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val sendChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)

    override fun start() {
        scope.launch {
            try {
                for (message in sendChannel) {
                    val jsonText = try {
                        ACPJson.encodeToString(message)
                    } catch (e: SerializationException) {
                        logger.trace(e) { "Failed to serialize message: ${message}" }
                        fireError(e)
                        continue
                    }
                    val frame = Frame.Text(jsonText)
                    logger.trace { "Sending message to channel: '$jsonText'" }
                    wss.send(frame)
                    wss.flush()
                }
                logger.trace { "No more messages in channel, closing connection" }
                wss.close()
                wss.flush()
            }
            catch (ce: CancellationException) {
                logger.trace(ce) { "Send job cancelled" }
                wss.close(CloseReason(CloseReason.Codes.NORMAL, "Cancelled"))
            }
            catch (e: Throwable) {
                logger.trace(e) { "Failed to send message to channel" }
                fireError(e)
                wss.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, e.message ?: "Internal error"))
                wss.flush()
            }
        }
        scope.launch {
            try {
                for (message in wss.incoming) {
                    when (message) {
                        is Frame.Text -> {
                            val text = message.readText()
                            val decodedMessage = try {
                                decodeJsonRpcMessage(text)
                            } catch (e: SerializationException) {
                                logger.trace(e) { "Failed to deserialize message: '$text'" }
                                fireError(e)
                                continue
                            }
                            logger.trace { "Received message from channel: '$text'" }
                            fireMessage(decodedMessage)
                        }

                        else -> {
                            logger.trace { "Received unexpected message from channel: '$message'" }
                        }
                    }
                }
            }
            catch (ce: CancellationException) {
                logger.trace(ce) { "Receive job cancelled" }
                wss.close(CloseReason(CloseReason.Codes.NORMAL, "Cancelled"))
            }
            catch (e: Throwable) {
                logger.trace(e) { "Failed to receive message from channel" }
                fireError(e)
            }
            finally {
                close()
            }
            logger.trace { "Exiting read job..." }
        }
    }

    override fun send(message: JsonRpcMessage) {
        sendChannel.trySend(message)
    }

    override fun close() {
        sendChannel.close()
    }
}