@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Application-owned queue installed before session setup, retaining full notifications for assertions.
 */
internal class SessionUpdates {
    private val received = Channel<UpdateSessionNotification>(Channel.UNLIMITED)

    fun accept(notification: UpdateSessionNotification) {
        received.trySend(notification).getOrThrow()
    }

    suspend fun next(): UpdateSessionNotification = withTimeout(10.seconds) { received.receive() }

    suspend fun take(count: Int): List<UpdateSessionNotification> =
        withTimeout(10.seconds) { List(count) { received.receive() } }
}
