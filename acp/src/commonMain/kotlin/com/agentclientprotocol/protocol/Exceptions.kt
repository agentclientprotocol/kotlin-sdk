package com.agentclientprotocol.protocol

import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * An exception that gracefully handled and passed to the counterpart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class AcpExpectedError(override val message: String) : Exception(message), CopyableThrowable<AcpExpectedError> {
    override fun createCopy(): AcpExpectedError = AcpExpectedError(message).also { it.addSuppressed(this) }
}

/**
 * Throws [AcpExpectedError] that gracefully handled and passed to the counterpart.
 */
public fun acpFail(message: String): Nothing = throw AcpExpectedError(message)

public fun jsonRpcMethodNotFound(message: String): Nothing =
    throw JsonRpcException(JsonRpcErrorCode.METHOD_NOT_FOUND.code, message)

public fun jsonRpcInvalidParams(message: String): Nothing =
    throw JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS.code, message)

/**
 * Exception thrown for JSON-RPC protocol errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class JsonRpcException(
    public val code: Int,
    public override val message: String,
    public val data: JsonElement? = null
) : Exception(message), CopyableThrowable<JsonRpcException> {
    override fun createCopy(): JsonRpcException = JsonRpcException(code, message, data).also { it.addSuppressed(this) }
}

/**
 * Exception thrown when a request is cancelled explicitly by invoking [AcpMethod.MetaMethods.CancelRequest] from the calling site
 */
internal class JsonRpcIncomingRequestCanceledException(
    message: String,
    internal val requestId: IncomingRequestId,
    val data: JsonElement? = null
) : CancellationException(message)


/**
 * Try to convert an exception to a [JsonRpcError] wire representation.
 */
internal fun Throwable.toJsonRpcError(): JsonRpcError? {
    if (this is JsonRpcIncomingRequestCanceledException) return null

    return when (this) {
        is AcpExpectedError -> JsonRpcError(JsonRpcErrorCode.INVALID_PARAMS.code, this.message)
        is JsonRpcException -> JsonRpcError(this.code, this.message, this.data)
        is SerializationException -> JsonRpcError(JsonRpcErrorCode.PARSE_ERROR.code, this.message ?: "Serialization error")
        is CancellationException -> JsonRpcError(JsonRpcErrorCode.CANCELLED.code, this.message ?: "Cancelled")
        else -> JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, this.message ?: "Internal error")
    }
}

/**
 * Try to convert a generic [JsonRpcException] to a concrete protocol exception, when possible.
 * Return the original exception otherwise.
 */
internal fun JsonRpcException.toProtocolException(): Exception {
    return when (this.code) {
        JsonRpcErrorCode.PARSE_ERROR.code -> SerializationException(this.message, this)
        JsonRpcErrorCode.INVALID_PARAMS.code -> AcpExpectedError(this.message)
        JsonRpcErrorCode.CANCELLED.code -> CancellationException(this.message, this)
        else -> this
    }
}