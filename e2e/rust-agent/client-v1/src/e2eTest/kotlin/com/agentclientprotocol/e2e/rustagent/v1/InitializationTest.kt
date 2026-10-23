package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V1
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InitializationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @Test
    @OptIn(UnstableApi::class)
    fun initializeNegotiatesV1AndReportsCapabilities() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val info = client.agentInfo
                assertEquals(PROTOCOL_VERSION_V1, info.protocolVersion)

                val capabilities = info.capabilities
                assertTrue(capabilities.loadSession, "capabilities.loadSession")
                assertTrue(capabilities.promptCapabilities.image, "capabilities.promptCapabilities.image")
                assertTrue(capabilities.promptCapabilities.audio, "capabilities.promptCapabilities.audio")
                assertTrue(capabilities.promptCapabilities.embeddedContext, "capabilities.promptCapabilities.embeddedContext")

                val sessions = capabilities.sessionCapabilities
                assertNotNull(sessions.list, "session.list")
                assertNotNull(sessions.delete, "session.delete")
                assertNotNull(sessions.resume, "session.resume")
                assertNotNull(sessions.close, "session.close")
                assertNotNull(sessions.additionalDirectories, "session.additionalDirectories")
                assertNotNull(capabilities.auth.logout, "auth.logout")

                assertTrue(info.authMethods.any { it.id == AuthMethodId("testy-agent-auth") }, "authMethods: ${info.authMethods}")
            }
        }
    }

    @Test
    fun authenticateAndLogout() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                client.authenticate(AuthMethodId("testy-agent-auth"))
                @OptIn(UnstableApi::class)
                client.logout()
            }
        }
    }
}
