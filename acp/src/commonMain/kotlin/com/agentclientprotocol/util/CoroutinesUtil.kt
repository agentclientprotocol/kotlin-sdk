package com.agentclientprotocol.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Rethrows [CancellationException] if the [Result] execution was cancelled. Use on [runCatching] to avoid swallowing of [CancellationException]
 */
internal fun <T> Result<T>.checkCancelled(): Result<T> {
    val throwable = exceptionOrNull()
    if (throwable is CancellationException) throw throwable
    return this
}