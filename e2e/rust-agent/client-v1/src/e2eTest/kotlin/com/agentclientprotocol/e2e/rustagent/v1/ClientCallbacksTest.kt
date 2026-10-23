package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClientCallbacksTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @TempDir
    lateinit var cwd: Path

    /*
     * testy reports `elicitations: completed` even when steps fail, so every step's report line is checked, as
     * well as the calls that reached the session operations and the global handler.
     */
    private fun assertElicitationsSucceeded(
        turn: Turn,
        sessionCalls: List<RecordedCall>,
        globalHandler: RecordingElicitationHandler,
    ) {
        val reportLines = turn.reply.lines()
        val elicitationLines = reportLines.filter { it.startsWith("elicitation/") || it.startsWith("elicitations:") }
        assertEquals(
            listOf(
                "elicitation/form_session_accept: ok accept content_fields=10",
                "elicitation/form_session_decline: ok accept content_fields=1",
                "elicitation/form_request_cancel: ok accept content_fields=1",
                "elicitation/url_session_accept: ok accept content_fields=0",
                "elicitation/complete_session_url: sent",
                "elicitation/url_request_decline: ok accept content_fields=0",
                "elicitations: completed",
            ),
            elicitationLines,
            "reply: ${turn.reply}",
        )
        assertTrue(elicitationLines.none { it.contains(": error") }, "reply: ${turn.reply}")

        assertEquals(
            listOf(
                RecordedCall("createElicitation", listOf("Accept the Testy session-scoped form elicitation")),
                RecordedCall("createElicitation", listOf("Decline the Testy session-scoped form elicitation")),
                RecordedCall("createElicitation", listOf("Accept the Testy session-scoped URL elicitation")),
                RecordedCall("completeElicitation", listOf("testy-url-session")),
            ),
            sessionCalls,
        )
        assertEquals(
            listOf(
                RecordedCall("createElicitation", listOf("Cancel the Testy request-scoped form elicitation")),
                RecordedCall("createElicitation", listOf("Decline the Testy request-scoped URL elicitation")),
            ),
            globalHandler.calls,
        )
    }

    @Test
    fun callbacksScenarioExercisesEveryClientMethod() = runBlocking {
        withTimeout(30.seconds) {
            val globalHandler = RecordingElicitationHandler()
            agent.client(globalHandler) { client ->
                val operations = RecordingClientOperations()
                val turn = client.newSession(cwd, operations).promptTurn(scenario("callbacks"))

                val writePath = cwd.resolve("testy-write.txt")
                val calls = operations.calls
                assertEquals(
                    listOf(
                        "requestPermissions",
                        "fsWriteTextFile",
                        "fsReadTextFile",
                        "terminalCreate",
                        "terminalOutput",
                        "terminalWaitForExit",
                        "terminalKill",
                        "terminalRelease",
                    ),
                    calls.take(8).map { it.method },
                    "calls: $calls",
                )
                assertEquals(writePath.toString(), calls[1].args.first())
                assertEquals(writePath.toString(), calls[2].args.first())
                assertEquals(listOf("printf", listOf("testy terminal\n").toString()), calls[3].args)

                assertTrue(writePath.exists())
                assertEquals("written by testy\n", writePath.readText())
                assertContains(turn.reply, "terminal/create: ok")
                assertElicitationsSucceeded(turn, calls.drop(8), globalHandler)
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    fun elicitationsScenarioCompletes() = runBlocking {
        withTimeout(30.seconds) {
            val globalHandler = RecordingElicitationHandler()
            agent.client(globalHandler) { client ->
                val operations = RecordingClientOperations()
                val turn = client.newSession(cwd, operations).promptTurn(scenario("elicitations"))

                assertElicitationsSucceeded(turn, operations.calls, globalHandler)
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }
}
