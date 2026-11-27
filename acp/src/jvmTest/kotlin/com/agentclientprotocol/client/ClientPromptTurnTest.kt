package com.agentclientprotocol.client

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * A v1 client routes a root session's updates to the running turn's flow, and to the session's operations otherwise.
 * No update is lost while a turn ends, and a finished turn never unregisters the next one.
 */
class ClientPromptTurnTest {
    private val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())

    @Test
    fun `an update sent right behind the prompt response reaches the turn or the operations`() = withClient { client, agent ->
        /*
         * The turn ends on another thread while the read loop routes the update, so each round can take either side.
         * This is a stress test: correct code never fails it.
         */
        val operations = RecordingOperations()
        val session = openSession(client, agent, operations)
        withContext(Dispatchers.Default) {
            repeat(ROUNDS) { round ->
                val update = text("after-$round")
                val turn = async { session.prompt(listOf(ContentBlock.Text("$round"))).toList() }
                val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionPrompt.methodName)
                answerPrompt(agent, request)
                agent.sendUpdate(session.sessionId, update)

                val inTurn = turn.await().filterIsInstance<Event.SessionUpdateEvent>().any { it.update == update }
                if (inTurn) {
                    agent.awaitHandled()
                    assertFalse(update in operations.updates.value, "round $round: the update was delivered twice")
                } else {
                    operations.awaitUpdate(update)
                }
            }
        }
    }

    @Test
    fun `a finished turn's cleanup leaves the next turn registered`() = withClient { client, agent ->
        val operations = RecordingOperations()
        val session = openSession(client, agent, operations)

        // Without a buffer, the first turn can't send its response event while the test holds its first update.
        val firstHoldsUpdate = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstTurn = async {
            session.prompt(listOf(ContentBlock.Text("first"))).buffer(Channel.RENDEZVOUS).collect { event ->
                if (event is Event.SessionUpdateEvent) {
                    firstHoldsUpdate.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        val firstRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionPrompt.methodName)
        agent.sendUpdate(session.sessionId, text("first"))
        firstHoldsUpdate.await()
        answerPrompt(agent, firstRequest)
        /*
         * The answer resumes the first turn on this thread before the barrier's answer resumes the test, so the first
         * turn has ended its registration and waits to send its response event.
         */
        agent.awaitHandled()

        val secondTurn = async { session.prompt(listOf(ContentBlock.Text("second"))).toList() }
        val secondRequest = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionPrompt.methodName)
        releaseFirst.complete(Unit)
        firstTurn.await()

        agent.sendUpdate(session.sessionId, text("second"))
        agent.awaitHandled()
        answerPrompt(agent, secondRequest)

        val secondUpdates = secondTurn.await().filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
        assertEquals(listOf<SessionUpdate>(text("second")), secondUpdates, "the second turn's update reaches its flow")
        assertEquals(emptyList(), operations.updates.value)
    }

    private suspend fun openSession(client: Client, agent: ScriptedAgentTransport, operations: RecordingOperations): ClientSession =
        withContext(Dispatchers.Default) {
            val opening = async { client.newSession(parameters) { _, _ -> operations } }
            val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionNew.methodName)
            agent.answer(request, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(SessionId("session")))
            opening.await()
        }

    private fun answerPrompt(agent: ScriptedAgentTransport, request: JsonRpcRequest) {
        agent.answer(request, AcpMethod.AgentMethods.V1.SessionPrompt.responseSerializer, PromptResponse(StopReason.END_TURN))
    }

    /*
     * The test body runs on the single thread of runBlocking, and the read loop on Dispatchers.Default.
     */
    private fun withClient(block: suspend CoroutineScope.(Client, ScriptedAgentTransport) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = ScriptedAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val client = Client(protocol)
            runBlocking {
                withTimeout(60.seconds) { block(client, transport) }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        private const val ROUNDS = 20_000

        private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))
    }
}
