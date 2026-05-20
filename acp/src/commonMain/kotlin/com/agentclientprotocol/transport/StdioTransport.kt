package com.agentclientprotocol.transport

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import com.agentclientprotocol.transport.Transport.State
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.io.*
import kotlinx.serialization.encodeToString

private val logger = KotlinLogging.logger {}

/**
 * STDIO transport implementation for ACP.
 *
 * This transport communicates over standard input/output streams,
 * which is commonly used for command-line agents.
 */
public class StdioTransport private constructor(
    private val parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val input: Flow<String>,
    private val output: suspend (String) -> Unit,
    private val name: String,
    private val closeHandler: () -> Unit,
) : BaseTransport() {

    /**
     * Primary [Flow]-based constructor.
     *
     * @param parentScope coroutine scope for the transport's lifecycle
     * @param ioDispatcher dispatcher used for the read and write coroutines
     * @param input cold flow of incoming NDJSON lines. Cancellation of the
     *   transport cancels collection; use [Flow.onCompletion] to react to
     *   that cancellation if you need to release upstream resources.
     * @param output suspending writer invoked once per outgoing line. The
     *   implementation owns framing (newline) and flushing semantics. To
     *   signal that the underlying transport has closed cleanly, throw
     *   [IllegalStateException] or [IOException]; the write loop will exit
     *   without firing an error. Any other exception is reported via
     *   [Transport.onError].
     * @param name optional name used in coroutine names and log messages
     */
    public constructor(
        parentScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        input: Flow<String>,
        output: suspend (String) -> Unit,
        name: String = StdioTransport::class.simpleName!!,
    ) : this(parentScope, ioDispatcher, input, output, name, closeHandler = {})

    /**
     * Back-compat constructor backed by blocking [Source] / [Sink].
     *
     * Deprecated because this variant blocks the dispatcher thread for the
     * duration of each [Source.readLine]. Under high agent concurrency this can
     * saturate the I/O dispatcher (e.g. `Dispatchers.IO`) and cascade into
     * freezes if other consumers schedule blocking work on the same pool.
     *
     * When this constructor is removed, the [Flow]-based constructor should be
     * promoted to the primary one and [closeHandler] dropped from its parameter
     * list (cancelling [childScope] in [close] is already enough to unwind the
     * Flow-based read path).
     *
     * Callers should adapt their blocking [Source] / [Sink] into a
     * [Flow]`<String>` and a `suspend (String) -> Unit` at the call site and
     * use the [Flow]-based constructor instead.
     */
    @Deprecated(
        message = "Blocking Source/Sink pins a dispatcher thread per read and forces an extra closeHandler. " +
                "Adapt your I/O into Flow<String> + suspend (String) -> Unit and use the Flow-based constructor.",
        level = DeprecationLevel.WARNING,
    )
    public constructor(
        parentScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        input: Source,
        output: Sink,
        name: String = StdioTransport::class.simpleName!!,
    ) : this(
        parentScope, ioDispatcher,
        sourceAsLineFlow(input), sinkAsLineWriter(output),
        name,
        closeHandler = makeSourceSinkCloseHandler(input, output),
    )

    private val childScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name))

    private val receiveChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)
    private val sendChannel = Channel<JsonRpcMessage>(Channel.UNLIMITED)

    override fun start() {
        if (_state.getAndUpdate { State.STARTING } != State.CREATED) error("Transport is not in ${State.CREATED.name} state")
        // Start reading messages from input
        childScope.launch(CoroutineName("$name.join-jobs")) {
            val readJob = launch(ioDispatcher + CoroutineName("$name.read-from-input")) {
                try {
                    // ACP assumes working with ND Json (new line delimited Json) when working over stdio
                    input.collect { line ->
                        currentCoroutineContext().ensureActive()

                        val jsonRpcMessage = try {
                            decodeJsonRpcMessage(line)
                        } catch (t: Throwable) {
                            logger.trace(t) { "Failed to decode JSON message: $line" }
                            return@collect
                        }
                        logger.trace { "Sending message to channel: $jsonRpcMessage" }
                        fireMessage(jsonRpcMessage)
                    }
                } catch (ce: CancellationException) {
                    logger.trace(ce) { "Read job cancelled" }
                    // don't throw as error
                } catch (e: Exception) {
                    logger.trace(e) { "Failed to read from input stream" }
                    fireError(e)
                } finally {
                    withContext(NonCancellable) {
                        close()
                    }
                }
                logger.trace { "Exiting read job..." }
            }
            val writeJob = launch(ioDispatcher + CoroutineName("$name.write-to-output")) {
                try {
                    for (message in sendChannel) {
                        val encoded = ACPJson.encodeToString(message)
                        try {
                            output(encoded)
                        } catch (e: IllegalStateException) {
                            logger.trace(e) { "Output stream closed" }
                            break
                        } catch (e: IOException) {
                            logger.trace(e) { "Output stream likely closed" }
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
                        close()
                    }
                }
                logger.trace { "Exiting write job..." }
            }
            try {
                logger.trace { "Joining read/write jobs..." }
                if (_state.getAndUpdate { State.STARTED } != State.STARTING) logger.warn { "Transport is not in ${State.STARTING.name} state" }
                joinAll(readJob, writeJob)
            }
            catch (ce: CancellationException) {
                logger.trace(ce) { "Join cancelled" }
                // don't throw as error
            }
            catch (e: Exception) {
                logger.trace(e) { "Exception while waiting read/write jobs" }
                fireError(e)
            }
            finally {
                childScope.cancel()
                if (_state.getAndUpdate { State.CLOSED } != State.CLOSING) logger.warn { "Transport is not in ${State.CLOSING.name} state" }
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
        val old = _state.value
        if (old == State.CLOSED || old == State.CLOSING) {
            logger.trace { "Transport is already closed or closing" }
            return
        }
        if (!_state.compareAndSet(old, State.CLOSING)) {
            logger.debug { "State changed concurrently. Do nothing" }
            return
        }

        if (sendChannel.close()) logger.trace { "Send channel closed" }
        if (receiveChannel.close()) logger.trace { "Receive channel closed" }

        runCatching { closeHandler() }.onFailure { logger.warn(it) { "Exception in close handler" } }

        // Unwind the read/write coroutines. The Source/Sink back-compat path relies
        // on [closeHandler] closing the underlying streams to unblock readLine, but
        // the Flow-based path has no equivalent unblock — without cancelling here
        // the read job would stay parked inside input.collect and the transport
        // would be stuck in CLOSING.
        childScope.cancel()
    }
}

private fun sourceAsLineFlow(source: Source): Flow<String> = flow {
    while (true) {
        val line = try {
            source.readLine()
        } catch (e: IllegalStateException) {
            logger.trace(e) { "Input stream closed" }
            break
        } catch (e: IOException) {
            logger.trace(e) { "Input stream likely closed" }
            break
        }
        if (line == null) {
            logger.trace { "End of stream" }
            break
        }
        emit(line)
    }
}

private fun sinkAsLineWriter(sink: Sink): suspend (String) -> Unit = { line ->
    sink.writeString(line)
    sink.writeString("\n")
    sink.flush()
}

private fun makeSourceSinkCloseHandler(source: Source, sink: Sink): () -> Unit = {
    runCatching { source.close() }.onFailure { logger.warn(it) { "Exception when closing input stream" } }
    runCatching { sink.close() }.onFailure { logger.warn(it) { "Exception when closing output stream" } }
}
