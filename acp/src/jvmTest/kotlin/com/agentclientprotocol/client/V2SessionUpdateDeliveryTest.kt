@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.Client
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.InitializeResponse
import com.agentclientprotocol.model.v2.NewSessionResponse
import com.agentclientprotocol.model.v2.ResumeSessionResponse
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class V2SessionUpdateDeliveryTest {

    /** Delivers updates sent before `session/new` responds to the returned session in order. */
    @Test
    fun `updates sent before the session new response reach the session`() = withV2Client { client, agent, scope ->
        agent.onNewSession(SessionId("session-1")) {
            sendUpdate(SessionId("session-1"), "first")
            sendUpdate(SessionId("session-1"), "second")
        }

        val session = client.newSession(cwd = ".")

        assertEquals(listOf("first", "second"), scope.read(session).take(2))
    }

    /** Delivers replay updates sent before `session/resume` responds to the resumed session. */
    @Test
    fun `history replayed during session resume reaches the session`() = withV2Client { client, agent, scope ->
        agent.onResumeSession {
            sendUpdate(SessionId("old-session"), "replay-1")
            sendUpdate(SessionId("old-session"), "replay-2")
        }

        val session = client.resumeSession(sessionId = SessionId("old-session"), cwd = ".")

        assertEquals(listOf("replay-1", "replay-2"), scope.read(session).take(2))
    }

    /** Discards early updates whose session id is not claimed by the opening call. */
    @Test
    fun `buffered updates that no session claims do not reach a later session with that id`() =
        withV2Client { client, agent, scope ->
            agent.onNewSession(SessionId("session-1")) { sendUpdate(SessionId("ghost-session"), "ghost") }
            client.newSession(cwd = ".")

            agent.onResumeSession { }
            val ghost = client.resumeSession(sessionId = SessionId("ghost-session"), cwd = ".")
            agent.sendUpdate(SessionId("ghost-session"), "after resume")

            assertEquals(listOf("after resume"), scope.read(ghost).take(1))
        }

    /** Routes updates only to the latest session object after the same session is resumed again. */
    @Test
    fun `resuming a session again hands the updates to the session that came back last`() =
        withV2Client { client, agent, scope ->
            agent.onResumeSession { }
            val first = client.resumeSession(sessionId = SessionId("session-1"), cwd = ".")
            val firstUpdates = scope.read(first)
            val second = client.resumeSession(sessionId = SessionId("session-1"), cwd = ".")

            agent.sendUpdate(SessionId("session-1"), "after the second resume")

            assertEquals(listOf("after the second resume"), scope.read(second).take(1))
            assertEquals(emptyList(), firstUpdates.rest())
        }
}

/**
 * Runs [block] against a v2 client whose agent is the raw-JSON [ScriptedAgent], handing it the scope the
 * connection lives in so it can read sessions with [read].
 */
private fun withV2Client(block: suspend (Client, ScriptedAgent, CoroutineScope) -> Unit) {
    val scope = CoroutineScope(SupervisorJob())
    try {
        val agent = ScriptedAgent()
        val protocol = Protocol(scope, agent)
        protocol.start()
        agent.start()
        val client = Client(protocol)
        runBlocking {
            withTimeout(10.seconds) {
                client.initialize(
                    ClientInfo(protocolVersion = PROTOCOL_VERSION_V2, implementation = Implementation("test", "1.0.0"))
                )
                block(client, agent, scope)
            }
        }
    } finally {
        scope.cancel()
    }
}

/** Starts reading [session], from the buffered updates onwards. */
private fun CoroutineScope.read(session: ClientSession) = SessionReader(this, session)

/**
 * Reads one session's updates in the background, as their texts.
 *
 * Collecting the whole flow rather than `take`-ing from it: `take` aborts the collection with an exception
 * that the channel behind [ClientSession.updates] does not always keep to itself, which showed up as a rare
 * `AbortFlowException` escaping a test.
 */
private class SessionReader(scope: CoroutineScope, session: ClientSession) {
    private val texts = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            session.updates.collect {
                texts.send(((it.update as SessionUpdate.AgentMessageChunk).chunk.content as ContentBlock.Text).text)
            }
            // The session's buffer was closed, so nothing more can arrive.
            texts.close()
        }
    }

    /** The next [count] updates, waiting for them if they have not arrived yet. */
    suspend fun take(count: Int): List<String> = withTimeout(10.seconds) { List(count) { texts.receive() } }

    /** Everything up to the end of the session's updates, which is empty when it has already ended. */
    suspend fun rest(): List<String> = withTimeout(10.seconds) { buildList { for (text in texts) add(text) } }
}

/**
 * An agent scripted at the JSON-RPC level: it answers `initialize` on its own, and answers the call that
 * opens a session only after sending the updates the test asked for.
 */
private class ScriptedAgent : BaseTransport() {
    private var newSession: (JsonRpcRequest) -> Unit = { error("no answer scripted for session/new") }
    private var resumeSession: (JsonRpcRequest) -> Unit = { error("no answer scripted for session/resume") }

    fun onNewSession(sessionId: SessionId, beforeResponse: ScriptedAgent.() -> Unit) {
        newSession = { request ->
            beforeResponse()
            respond(request, AcpMethod.AgentMethods.V2.SessionNew.responseSerializer, NewSessionResponse(sessionId))
        }
    }

    fun onResumeSession(beforeResponse: ScriptedAgent.() -> Unit) {
        resumeSession = { request ->
            beforeResponse()
            respond(request, AcpMethod.AgentMethods.V2.SessionResume.responseSerializer, ResumeSessionResponse())
        }
    }

    fun sendUpdate(sessionId: SessionId, text: String) {
        fireMessage(
            JsonRpcNotification(
                method = AcpMethod.ClientMethods.V2.SessionUpdate.methodName,
                params = ACPJson.encodeToJsonElement(
                    AcpMethod.ClientMethods.V2.SessionUpdate.serializer,
                    UpdateSessionNotification(
                        sessionId,
                        SessionUpdate.AgentMessageChunk(ContentChunk(MessageId("m-$text"), ContentBlock.Text(text))),
                    ),
                ),
            )
        )
    }

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSING
        fireClose()
        _state.value = Transport.State.CLOSED
    }

    override fun send(message: JsonRpcMessage) {
        if (message !is JsonRpcRequest) return
        when (message.method) {
            AcpMethod.AgentMethods.V2.Initialize.methodName -> respond(
                message,
                AcpMethod.AgentMethods.V2.Initialize.responseSerializer,
                InitializeResponse(PROTOCOL_VERSION_V2, Implementation("scripted-agent", "1.0.0")),
            )
            AcpMethod.AgentMethods.V2.SessionNew.methodName -> newSession(message)
            AcpMethod.AgentMethods.V2.SessionResume.methodName -> resumeSession(message)
            else -> {}
        }
    }

    private fun <T> respond(request: JsonRpcRequest, serializer: kotlinx.serialization.KSerializer<T>, response: T) {
        fireMessage(JsonRpcResponse(id = request.id, result = ACPJson.encodeToJsonElement(serializer, response)))
    }
}
