package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallLocation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PromptTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @TempDir
    lateinit var cwd: Path

    @Test
    fun greetRepliesHelloWorld() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(prompt("greet"))

                assertEquals("Hello, world!", turn.reply)
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    fun echoRepliesMessage() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(prompt("echo", "message" to "echo from the Kotlin client"))

                assertEquals("echo from the Kotlin client", turn.reply)
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    @OptIn(UnstableApi::class)
    fun sessionUpdatesScenarioEmitsEveryUpdateKind() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(scenario("session_updates"))

                val expected = setOf(
                    SessionUpdate.SessionInfoUpdate::class,
                    SessionUpdate.CurrentModeUpdate::class,
                    SessionUpdate.ConfigOptionUpdate::class,
                    SessionUpdate.AvailableCommandsUpdate::class,
                    SessionUpdate.UsageUpdate::class,
                    SessionUpdate.UserMessageChunk::class,
                    SessionUpdate.AgentThoughtChunk::class,
                    SessionUpdate.AgentMessageChunk::class,
                    SessionUpdate.ToolCall::class,
                    SessionUpdate.ToolCallUpdate::class,
                    SessionUpdate.PlanUpdate::class,
                )
                val received = turn.updates.map { it::class }.toSet()
                assertTrue(received.containsAll(expected), "missing ${expected - received}; received $received")
                assertContains(turn.reply, "session_updates: sent")
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    fun contentScenarioCountsBlocks() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(
                    scenario("content"),
                    ContentBlock.Image(PIXEL_PNG, "image/png"),
                    ContentBlock.Audio(SILENT_WAV, "audio/wav"),
                    ContentBlock.Resource(
                        EmbeddedResourceResource.TextResourceContents(
                            text = "embedded by the Kotlin client",
                            uri = cwd.resolve("embedded.txt").toUri().toString(),
                            mimeType = "text/plain",
                        )
                    ),
                )

                assertContains(turn.reply, "content_blocks: 4")
                assertEquals(StopReason.END_TURN, turn.stopReason)
            }
        }
    }

    @Test
    fun toolCallsScenarioEmitsToolCallAndUpdates() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val turn = client.newSession(cwd).promptTurn(scenario("tool_calls"))

                val toolCalls = turn.updates.filterIsInstance<SessionUpdate.ToolCall>()
                val read = toolCalls.first()
                assertEquals("Read deterministic fixture", read.title)
                assertEquals(listOf(ToolCallLocation(cwd.resolve("testy-input.txt").toString(), 1u)), read.locations)

                val firstCall = turn.updates.indexOf(read)
                val updates = turn.updates.drop(firstCall + 1).filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(updates.any { it.toolCallId == read.toolCallId }, "no update for ${read.toolCallId}")
                val diff = updates.flatMap { it.content.orEmpty() }.filterIsInstance<ToolCallContent.Diff>().single()
                assertEquals(cwd.resolve("testy-output.txt").toString(), diff.path)
                assertEquals("before\n", diff.oldText)
                assertEquals("after\n", diff.newText)

                assertContains(turn.reply, "tool_calls: sent")
                assertIs<SessionUpdate.PlanUpdate>(turn.updates.last { it !is SessionUpdate.AgentMessageChunk })
            }
        }
    }
}

private const val PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
private const val SILENT_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA="
