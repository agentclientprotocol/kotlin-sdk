package com.agentclientprotocol.util

import kotlin.coroutines.cancellation.CancellationException

/** Use instead of [runCatching] for KT-55480 fix. */
public suspend fun <R> catching(body: suspend () -> R): Result<R> =
    try {
        Result.success(body())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }