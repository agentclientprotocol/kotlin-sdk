@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.e2e.rustagent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class PromptTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(2)
    }

    @TempDir
    lateinit var cwd: Path

    /*
     * Testy v2 reports a turn as the inserted user message, a running state, the reply chunk, and an idle
     * state carrying the stop reason, in that order.
     */
    @Test
    fun greetRepliesHelloWorld() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val text = prompt("greet")
                val updates = client.newSession(cwd).promptAndCollect(text)

                assertEquals(4, updates.size, "updates: $updates")
                val user = assertIs<SessionUpdate.UserMessage>(updates[0])
                assertEquals(MaybeUndefined.Value(listOf<ContentBlock>(ContentBlock.Text(text))), user.message.content)
                assertIs<StateUpdate.Running>(assertIs<SessionUpdate.StateUpdate>(updates[1]).state)
                val reply = assertIs<SessionUpdate.AgentMessageChunk>(updates[2])
                assertEquals(ContentBlock.Text("Hello, world!"), reply.chunk.content)
                assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[3]).state)
                assertEquals(StopReason.EndTurn, updates.stopReason)
            }
        }
    }

    @Test
    fun echoRepliesMessage() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val updates = client.newSession(cwd).promptAndCollect(prompt("echo", "message" to "echo from the Kotlin client"))

                assertEquals("echo from the Kotlin client", updates.reply)
                assertEquals(StopReason.EndTurn, updates.stopReason)
            }
        }
    }
}
