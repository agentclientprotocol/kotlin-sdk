@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.e2e.rustagent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class InitializationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(2)
    }

    /*
     * The v2 agent info carries no protocol version: `Client.initialize` throws
     * `UnsupportedProtocolVersionException` unless the agent answers with version 2, so reaching the block
     * means version 2 was negotiated.
     */
    @Test
    fun initializeNegotiatesV2() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                assertEquals("test-agent", client.agentInfo.await().implementation.name)
            }
        }
    }
}
