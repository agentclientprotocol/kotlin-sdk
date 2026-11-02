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

    /*
     * testy reports each callback as `<method>: ok <response>` or `<method>: error <error>` and still completes
     * the turn. `<response>` is the Rust `Debug` output of the response the agent decoded, whose layout changes
     * when upstream adds fields, so only the fields that show our answers arrived intact are checked. serde
     * decodes a misnamed optional field as `None` without an error, which only these values reveal. The terminal
     * output depends on whether `printf` has finished when the agent asks, so that step only has to succeed.
     */
    private fun assertCallbacksSucceeded(turn: Turn) {
        val expectedFields = listOf(
            "session/request_permission" to listOf("option_id: PermissionOptionId(\"allow_once\")"),
            "fs/write_text_file" to emptyList(),
            "fs/read_text_file" to listOf("content: \"written by testy\\n\""),
            "terminal/create" to listOf("terminal_id: TerminalId(\"terminal-1\")"),
            "terminal/output" to emptyList(),
            "terminal/wait_for_exit" to listOf("exit_code: Some(0)"),
            "terminal/kill" to emptyList(),
            "terminal/release" to emptyList(),
        )
        val callbackLines = turn.reply.lines().filter {
            it.startsWith("session/request_permission:") || it.startsWith("fs/") || it.startsWith("terminal/")
        }
        assertEquals(
            expectedFields.map { it.first },
            callbackLines.map { it.substringBefore(':') },
            "reply: ${turn.reply}",
        )

        callbackLines.zip(expectedFields).forEach { (line, expected) ->
            val (method, fields) = expected
            assertTrue(line.startsWith("$method: ok"), "expected `$method` to succeed: $line")
            fields.forEach { assertTrue(it in line, "expected `$it` in: $line") }
        }
        assertTrue(turn.reply.lines().none { it.contains(": error") }, "reply: ${turn.reply}")
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
                assertCallbacksSucceeded(turn)
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
