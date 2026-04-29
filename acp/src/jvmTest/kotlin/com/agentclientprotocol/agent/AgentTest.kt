package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class AgentTest {

    @Test
    fun `initialize agent`() {
        withTestAgent { testAgent ->
            val (response) = testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))
            assertNotNull(response)
            assertTrue(testAgent.agentSupport.isInitialized)
        }
    }

    @Test
    fun `create new session`() {
        withInitializedTestAgent { testAgent ->
            val (response) = testAgent.testNewSession(NewSessionRequest(cwd = ".", mcpServers = emptyList()))
            assertNotNull(response)
            assertTrue(response.sessionId in testAgent.agentSupport.createdSessions)
        }
    }

    @Test
    fun `simple prompt turn`() {
        withTestAgentSession(promptHandler = echoPromptHandler) { testAgent, _ ->
            testAgent.simplePrompt("hello").let { (response, updates) ->
                assertEquals(StopReason.END_TURN, response.stopReason)
                assertEquals(1, updates.size)

                val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .map { (it.content as? ContentBlock.Text)?.text }
                    .firstOrNull()
                assertEquals("hello", message)
            }

            testAgent.simplePrompt("world").let { (response, updates) ->
                assertEquals(StopReason.END_TURN, response.stopReason)
                assertEquals(1, updates.size)

                val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .map { (it.content as? ContentBlock.Text)?.text }
                    .firstOrNull()
                assertEquals("world", message)
            }
        }
    }

    @Test
    fun `prompt cancellation`() {
        withTestAgentSession(promptHandler = delayEchoPromptHandler(2.seconds)) { testAgent, session ->
            val deferredResponse = async { testAgent.simplePrompt("hello").first }
            delay(1.seconds)
            testAgent.testCancel(CancelNotification(session.sessionId))

            val response = deferredResponse.await()
            assertEquals(StopReason.CANCELLED, response.stopReason)
        }
    }
}
