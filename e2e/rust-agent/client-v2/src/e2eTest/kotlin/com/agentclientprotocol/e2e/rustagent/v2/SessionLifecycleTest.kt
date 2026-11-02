@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.e2e.rustagent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.StopReason
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionLifecycleTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(2)
    }

    @TempDir
    lateinit var cwd: Path

    @Test
    fun newSessionIsListed() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                assertTrue(session.sessionId.value.startsWith("testy-v2-session-"), "session id: ${session.sessionId}")
                val info = assertNotNull(client.listed(session.sessionId))
                assertEquals(cwd.toString(), info.cwd)
            }
        }
    }

    /*
     * The client forgets a session once it is closed, while testy keeps it and marks it inactive, so
     * `session/resume` brings the same id back on this connection.
     */
    @Test
    fun resumeSessionReopensSession() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val sessionId = client.newSession(cwd).sessionId
                assertNotNull(client.getSession(sessionId)).close()

                val resumed = client.resumeSession(sessionId, cwd.toString())

                assertEquals(sessionId, resumed.sessionId)
                assertSame(resumed, client.getSession(sessionId))
                assertNotNull(client.listed(sessionId))
                assertEquals(StopReason.EndTurn, resumed.promptAndCollect(prompt("greet")).stopReason)
            }
        }
    }

    @Test
    fun closeSessionSucceeds() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                session.close()

                assertNull(client.getSession(session.sessionId))
                // Testy answers a prompt for a closed session with Invalid params, which the SDK raises as AcpExpectedError.
                assertFailsWith<AcpExpectedError> { session.prompt(listOf(ContentBlock.Text(prompt("greet")))) }
            }
        }
    }
}
