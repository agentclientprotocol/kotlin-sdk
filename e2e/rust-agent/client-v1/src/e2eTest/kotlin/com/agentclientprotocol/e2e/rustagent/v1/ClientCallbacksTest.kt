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

    @Test
    fun callbacksScenarioExercisesEveryClientMethod() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
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
                assertContains(turn.reply, "elicitations: completed")
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    fun elicitationsScenarioCompletes() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(scenario("elicitations"))

                assertContains(turn.reply, "elicitations: completed")
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }
}
