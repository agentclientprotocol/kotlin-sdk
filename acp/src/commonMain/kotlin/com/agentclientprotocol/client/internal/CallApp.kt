package com.agentclientprotocol.client.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runs app code, [what], in the caller's coroutine. A [CancellationException] it throws while the caller is active,
 * such as its own timeout, becomes an [IllegalStateException] with the cause kept, so it fails the operation instead of
 * passing for the caller's cancellation. The caller's own cancellation propagates.
 */
internal suspend inline fun <T> callApp(what: String, block: () -> T): T =
    try {
        block()
    } catch (ce: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("$what was cancelled", ce)
    }
