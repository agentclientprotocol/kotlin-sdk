package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class SessionLifecycleTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @TempDir
    lateinit var cwd: Path

    private suspend fun Client.listed(sessionId: SessionId): SessionInfo? =
        listSessions().toList().find { it.sessionId == sessionId }

    @Test
    fun newSessionIsListed() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                val info = assertNotNull(client.listed(session.sessionId))
                assertEquals(cwd.toString(), info.cwd)
            }
        }
    }

    /*
     * A client keeps one session per id, so reopening an id means closing it here first. Testy forgets a closed
     * session and `session/load` (or `session/resume`) recreates it.
     */
    @Test
    fun loadSessionReopensSession() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val sessionId = client.newSession(cwd).sessionId
                client.getSession(sessionId).close()

                val loaded = client.loadSession(sessionId, sessionParameters(cwd)) { _, _ -> RecordingClientOperations() }

                assertEquals(sessionId, loaded.sessionId)
                assertEquals(SessionModeId("chat"), loaded.currentMode.value)
                assertNotNull(client.listed(sessionId))
                assertEquals(StopReason.END_TURN, loaded.promptTurn(prompt("greet")).stopReason)
            }
        }
    }

    @Test
    fun resumeSessionReopensSession() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val sessionId = client.newSession(cwd).sessionId
                client.getSession(sessionId).close()

                val resumed = client.resumeSession(sessionId, sessionParameters(cwd)) { _, _ -> RecordingClientOperations() }

                assertEquals(sessionId, resumed.sessionId)
                assertEquals(SessionModeId("chat"), resumed.currentMode.value)
                assertNotNull(client.listed(sessionId))
                assertEquals(StopReason.END_TURN, resumed.promptTurn(prompt("greet")).stopReason)
            }
        }
    }

    @Test
    fun closeSessionSucceeds() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                session.close()

                assertNull(client.listed(session.sessionId))
            }
        }
    }

    @Test
    fun deletedSessionIsNotListed() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)
                assertNotNull(client.listed(session.sessionId))

                client.deleteSession(session.sessionId)

                assertNull(client.listed(session.sessionId))
            }
        }
    }

    @Test
    fun additionalDirectoriesAreAccepted(@TempDir extraDir: Path) = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(sessionParameters(cwd, listOf(extraDir))) { _, _ -> RecordingClientOperations() }

                val info = assertNotNull(client.listed(session.sessionId))
                assertEquals(listOf(extraDir.toString()), info.additionalDirectories)
            }
        }
    }
}
