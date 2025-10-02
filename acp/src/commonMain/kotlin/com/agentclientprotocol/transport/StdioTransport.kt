package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.io.*
import kotlinx.serialization.encodeToString

private val logger = KotlinLogging.logger {}

/**
 * STDIO transport implementation for ACP.
 *
 * This transport communicates over standard input/output streams,
 * which is commonly used for command-line agents.
 */
public class StdioTransport(
    private val parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val input: Source,
    private val output: Sink,
) : Transport {
    private val childScope = CoroutineScope(parentScope.coroutineContext + CoroutineName(::StdioTransport.name) + SupervisorJob(parentScope.coroutineContext[Job]))
    private val errorHandlers = atomic<ErrorListener>({})
    private val closeHandlers = atomic<CloseListener>({})

    override fun onClose(handler: CloseListener) {
        closeHandlers.update { old ->
            {
                // old runCatching is made in the previous subscription
                old()
                runCatching { handler() }.onFailure { e -> logger.error(e) { "Error in close handler" } }
            }
        }
    }

    private fun fireClose() {
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

    private fun fireError(throwable: Throwable) {
        errorHandlers.value(throwable)
    }

    private val receiveChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)
    private val sendChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)
    
    private val _isConnected = atomic(false)
    override val isConnected: Boolean get() = _isConnected.value
    
    override val messages: ReceiveChannel<JsonRpcMessage> = receiveChannel
    
    override fun start() {
        // TODO handle state properly
        _isConnected.value = true
        // Start reading messages from input
        childScope.launch(CoroutineName("${::StdioTransport.name}.join-jobs")) {
            val readJob = launch(ioDispatcher + CoroutineName("${::StdioTransport.name}.read-from-input")) {
                try {
                    while (_isConnected.value) {
                        // ACP assumes working with ND Json (new line delimited Json) when working over stdio
                        val line = try {
                            input.readLine()
                        } catch (e: IllegalStateException) {
                            logger.trace(e) { "Input stream closed" }
                            break
                        } catch (e: IOException) {
                            logger.trace(e) { "Input stream likely closed" }
                            break
                        }
                        if (line == null) {
                            // End of stream
                            logger.trace { "End of stream" }
                            break
                        }

                        val jsonRpcMessage = try {
                            decodeJsonRpcMessage(line)
                        } catch (t: Throwable) {
                            logger.trace(t) { "Failed to decode JSON message: $line" }
                            fireError(t)
                            continue
                        }
                        logger.trace { "Sending message to channel: $jsonRpcMessage" }
                        receiveChannel.send(jsonRpcMessage)
                    }
                } catch (ce: CancellationException) {
                    logger.trace(ce) { "Read job cancelled" }
                    // don't throw as error
                } catch (e: Exception) {
                    logger.trace(e) { "Failed to read from input stream" }
                    fireError(e)
                } finally {
                    withContext(NonCancellable) {
                        logger.trace { "Closing channels..." }
                        closeChannels()
                        logger.trace { "Closing input..." }
                        input.close()
                    }
                }
                logger.trace { "Exiting read job..." }
            }
            val writeJob = launch(ioDispatcher + CoroutineName("${::StdioTransport.name}.write-to-output")) {
                try {
                    for (message in sendChannel) {
                        val encoded = ACPJson.encodeToString(message)
                        try {
                            output.writeString(encoded)
                            output.writeString("\n")
                            output.flush()
                        } catch (e: IllegalStateException) {
                            logger.trace(e) { "Output stream closed" }
                            break
                        }
                    }
                } catch (ce: CancellationException) {
                    logger.trace(ce) { "Write job cancelled" }
                    // don't throw as error
                } catch (e: Throwable) {
                    logger.trace(e) { "Failed to write to output stream" }
                    fireError(e)
                } finally {
                    withContext(NonCancellable) {
                        logger.trace { "Closing channels..." }
                        closeChannels()
                        logger.trace { "Closing output..." }
                        output.close()
                    }
                }
                logger.trace { "Exiting write job..." }
            }
            try {
                logger.trace { "Joining read/write jobs..." }
                joinAll(readJob, writeJob)
            }
            catch (e: Exception) {
                logger.trace(e) { "Exception while waiting read/write jobs" }
                fireError(e)
            }
            finally {
                _isConnected.value = false
                fireClose()
                logger.trace { "Transport closed" }
            }
        }
    }
    
    override fun send(message: JsonRpcMessage) {
        logger.trace { "Sending message: $message" }
        val channelResult = sendChannel.trySend(message)
        logger.trace { "Send result: $channelResult" }
    }

    override fun close() {
        if (!_isConnected.getAndSet(false)) {
            logger.trace { "Transport is already closed" }
            return
        }

        closeChannels()

        try {
            input.close()
            output.close()
        } catch (e: Throwable) {
            logger.trace(e) { "Exception when closing input/output streams" }
        }
    }

    private fun closeChannels(t: Throwable? = null) {
        if (sendChannel.close(t)) logger.trace(t) { "Send channel closed" }
        if (receiveChannel.close(t)) logger.trace(t) { "Receive channel closed" }
    }
}