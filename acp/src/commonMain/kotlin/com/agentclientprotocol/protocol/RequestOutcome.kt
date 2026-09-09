package com.agentclientprotocol.protocol

/**
 * Separates a handler's reply from work that must follow it, such as streaming v2 prompt updates.
 * This lets the protocol queue the entire response batch before starting that work, without
 * waiting for streams to finish. The dispatcher takes ownership when the handler returns.
 *
 * @property response The handler's reply payload, mapped to JSON before it is queued.
 * @property afterResponse Optional work started after the response frame (including any batch)
 * is accepted by the transport's ordered queue, not after network flush or peer acknowledgement.
 * @property onCompletion Optional non-suspending cleanup, called exactly once when follow-up work
 * finishes, fails, or is discarded—even if cancellation or a send failure prevents it from starting.
 */
public data class RequestOutcome<out T>(
    val response: T,
    val afterResponse: (suspend () -> Unit)? = null,
    val onCompletion: (() -> Unit)? = null,
) {
    public inline fun <R> mapResponse(transform: (T) -> R): RequestOutcome<R> = try {
        RequestOutcome(transform(response), afterResponse, onCompletion)
    } catch (t: Throwable) {
        onCompletion?.invoke()
        throw t
    }
}
