@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.ForkSessionResponse
import com.agentclientprotocol.model.v2.InitializeResponse
import com.agentclientprotocol.model.v2.NewSessionResponse
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.ResumeSessionResponse
import com.agentclientprotocol.model.v2.SessionCapabilities
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClientSessionTest {

    @Test
    fun `cancel releases pending permission when notification send fails`() = withV2Client { client, agent, scope ->
        val permissionStarted = CompletableDeferred<Unit>()
        val permissionCleanedUp = CompletableDeferred<Unit>()
        val sessionId = SessionId("session")
        agent.onNewSession(sessionId) { }
        val session = client.newSession(
            cwd = ".",
            operations = object : ClientSessionOperations {
                override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
                    permissionStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        permissionCleanedUp.complete(Unit)
                    }
                }
            },
        )
        val permission = scope.async {
            session.handlePermissionRequest(RequestPermissionRequest(sessionId, "Allow?", emptyList()))
        }
        permissionStarted.await()
        agent.sendFailure = ClosedSendChannelException("Writer queue closed")

        assertFailsWith<ClosedSendChannelException> { session.cancel() }

        assertEquals(RequestPermissionOutcome.Cancelled, permission.await().outcome)
        assertTrue(permissionCleanedUp.isCompleted)
    }

    /** Delivers updates sent before `session/new` responds to the returned session in order. */
    @Test
    fun `updates sent before the session new response reach the session`() = withV2Client { client, agent, scope ->
        agent.onNewSession(SessionId("session-1")) {
            sendUpdate(SessionId("session-1"), "first")
            sendUpdate(SessionId("session-1"), "second")
        }

        val session = client.newSession(cwd = ".")

        assertEquals(listOf("first", "second"), scope.read(session).takeTexts(2))
    }

    /** Delivers replay updates sent before `session/resume` responds to the resumed session. */
    @Test
    fun `history replayed during session resume reaches the session`() = withV2Client { client, agent, scope ->
        agent.onResumeSession {
            sendUpdate(SessionId("old-session"), "replay-1")
            sendUpdate(SessionId("old-session"), "replay-2")
        }

        val session = client.resumeSession(sessionId = SessionId("old-session"), cwd = ".")

        assertEquals(listOf("replay-1", "replay-2"), scope.read(session).takeTexts(2))
    }

    @Test
    fun `delivers tool call updates in order with payloads and metadata intact`() = withV2Client { client, agent, scope ->
        val sessionId = SessionId("session-1")
        val notificationMeta = buildJsonObject { put("scope", "notification") }
        val updates = listOf(
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1"}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","title":"Read config","_meta":{"scope":"tool"}}""",
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"first"}},"_meta":{"chunk":1}}""",
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"second"}},"_meta":{"chunk":2}}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","content":[{"type":"content","content":{"type":"text","text":"replacement"}}]}""",
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"after replacement"}}}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","content":null,"_meta":null}""",
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"after null"}}}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","content":[]}""",
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"after empty array"}}}""",
            """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","status":"cancelled"}""",
        ).map(ACPJson::parseToJsonElement)
        agent.onNewSession(sessionId) {
            updates.take(3).forEach { sendRawUpdate(sessionId, it, notificationMeta) }
        }

        val session = client.newSession(cwd = ".")
        val reader = scope.read(session)
        updates.drop(3).forEach { agent.sendRawUpdate(sessionId, it, notificationMeta) }

        reader.take(updates.size).forEachIndexed { index, actual ->
            assertEquals(updates[index], ACPJson.encodeToJsonElement(SessionUpdate.serializer(), actual.update), "update $index")
            assertEquals(notificationMeta, actual._meta, "notification metadata for update $index")
        }
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

            assertEquals(listOf("after resume"), scope.read(ghost).takeTexts(1))
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

            assertEquals(listOf("after the second resume"), scope.read(second).takeTexts(1))
            assertEquals(emptyList(), firstUpdates.rest())
        }

    /**
     * Hands the replay of a session resumed while it is still open to the session that comes back.
     */
    @Test
    fun `resuming an open session delivers the replay to the resumed session`() =
        withV2Client { client, agent, scope ->
            agent.onResumeSession { }
            val open = client.resumeSession(sessionId = SessionId("session-1"), cwd = "/work")
            val openUpdates = scope.read(open)
            agent.onResumeSession {
                sendUpdate(SessionId("session-1"), "replay-1")
                sendUpdate(SessionId("session-1"), "replay-2")
            }

            val resumed = client.resumeSession(
                sessionId = SessionId("session-1"),
                cwd = "/work",
                replayFrom = ReplayFrom.Start(),
            )

            assertEquals(listOf("replay-1", "replay-2"), scope.read(resumed).takeTexts(2))
            assertEquals(emptyList(), openUpdates.rest())
        }

    /**
     * Keeps updates that arrived during a failed resume for the session that stays open.
     */
    @Test
    fun `a failed resume leaves the open session its updates`() = withV2Client { client, agent, scope ->
        agent.onResumeSession { }
        val open = client.resumeSession(sessionId = SessionId("session-1"), cwd = "/work")
        val openUpdates = scope.read(open)
        agent.onResumeSessionFailing { sendUpdate(SessionId("session-1"), "while resuming") }

        assertFails { client.resumeSession(sessionId = SessionId("session-1"), cwd = "/work") }
        agent.sendUpdate(SessionId("session-1"), "after the failure")

        assertEquals(listOf("while resuming", "after the failure"), openUpdates.takeTexts(2))
        assertEquals(open, client.getSession(SessionId("session-1")))
    }

    /**
     * Refuses additional directories locally when the agent did not advertise support for them.
     */
    @Test
    fun `additional directories require the capability without sending a request`() =
        withV2Client { client, agent, _ ->
            val calls: List<suspend () -> ClientSession> = listOf(
                { client.newSession(cwd = "/work", additionalDirectories = listOf("/lib")) },
                { client.resumeSession(SessionId("saved"), cwd = "/work", additionalDirectories = listOf("/lib")) },
                { client.forkSession(SessionId("saved"), cwd = "/work", additionalDirectories = listOf("/lib")) },
            )

            for (call in calls) {
                val error = assertFailsWith<AcpExpectedError> { call() }
                assertEquals(
                    "Cannot send additionalDirectories: the agent did not advertise session.additionalDirectories",
                    error.message,
                )
            }

            assertTrue(agent.requests.tryReceive().isFailure)
        }

    /**
     * Fails instead of waiting on an initialization that may never happen.
     */
    @Test
    fun `additional directories fail before initialization instead of waiting`() =
        withV2Client(initialize = false) { client, agent, _ ->
            val error = assertFailsWith<AcpExpectedError> {
                client.newSession(cwd = "/work", additionalDirectories = listOf("/lib"))
            }

            assertEquals("Cannot send additionalDirectories before initialization completes", error.message)
            assertTrue(agent.requests.tryReceive().isFailure)
        }

    /**
     * The control for the capability check: an empty list needs no capability and is left off the wire.
     */
    @Test
    fun `setup omits empty additional directories without the capability`() = withV2Client { client, agent, _ ->
        agent.onNewSession(SessionId("opened")) { }
        agent.onResumeSession { }
        agent.onForkSession(SessionId("forked")) { }

        client.setUpSessions(roots = emptyList())

        repeat(3) { assertEquals(null, agent.requests.receive().params!!.jsonObject["additionalDirectories"]) }
    }

    /**
     * Forwards additional directories as given once the agent has advertised support for them.
     */
    @Test
    fun `setup forwards additional directories to an agent that supports them`() =
        withV2Client(capabilities = additionalDirectoriesCapabilities) { client, agent, _ ->
            agent.onNewSession(SessionId("opened")) { }
            agent.onResumeSession { }
            agent.onForkSession(SessionId("forked")) { }

            client.setUpSessions(roots = listOf("/lib", "/shared"))
            client.setUpSessions(roots = emptyList())

            val expected = JsonArray(listOf(JsonPrimitive("/lib"), JsonPrimitive("/shared")))
            repeat(3) { assertEquals(expected, agent.requests.receive().params!!.jsonObject["additionalDirectories"]) }
            repeat(3) { assertEquals(null, agent.requests.receive().params!!.jsonObject["additionalDirectories"]) }
        }
}

private val additionalDirectoriesCapabilities = AgentCapabilities(
    session = SessionCapabilities(additionalDirectories = SessionAdditionalDirectoriesCapabilities()),
)

/**
 * Opens a session with each of the three setup calls in turn, passing [roots] as the additional directories.
 */
private suspend fun Client.setUpSessions(roots: List<String>) {
    newSession(cwd = "/work", additionalDirectories = roots)
    resumeSession(SessionId("saved"), cwd = "/work", additionalDirectories = roots)
    forkSession(SessionId("saved"), cwd = "/work", additionalDirectories = roots)
}

/**
 * Runs [block] against a v2 client whose agent is the raw-JSON [ScriptedAgent], handing it the scope the
 * connection lives in so it can read sessions with [read].
 *
 * The agent advertises [capabilities] in its `initialize` response. With [initialize] off the handshake is left
 * to the test.
 */
private fun withV2Client(
    capabilities: AgentCapabilities = AgentCapabilities(),
    initialize: Boolean = true,
    block: suspend (Client, ScriptedAgent, CoroutineScope) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob())
    try {
        val agent = ScriptedAgent(capabilities)
        val protocol = Protocol(scope, agent)
        protocol.start()
        agent.start()
        val client = Client(protocol)
        runBlocking {
            withTimeout(10.seconds) {
                if (initialize) {
                    client.initialize(
                        ClientInfo(protocolVersion = PROTOCOL_VERSION_V2, implementation = Implementation("test", "1.0.0"))
                    )
                }
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
 * Reads one session's updates in the background, retaining notification metadata.
 *
 * Collecting the whole flow rather than `take`-ing from it: `take` aborts the collection with an exception
 * that the channel behind [ClientSession.updates] does not always keep to itself, which showed up as a rare
 * `AbortFlowException` escaping a test.
 */
private class SessionReader(scope: CoroutineScope, session: ClientSession) {
    private val updates = Channel<ClientSession.UpdateWithMeta>(Channel.UNLIMITED)

    init {
        scope.launch {
            session.updates.collect { updates.send(it) }
            // The session's buffer was closed, so nothing more can arrive.
            updates.close()
        }
    }

    /** The next [count] updates, waiting for them if they have not arrived yet. */
    suspend fun take(count: Int): List<ClientSession.UpdateWithMeta> =
        withTimeout(10.seconds) { List(count) { updates.receive() } }

    /** The next [count] agent message chunks, as their texts. */
    suspend fun takeTexts(count: Int): List<String> = take(count).map {
        ((it.update as SessionUpdate.AgentMessageChunk).chunk.content as ContentBlock.Text).text
    }

    /** Everything up to the end of the session's updates, which is empty when it has already ended. */
    suspend fun rest(): List<ClientSession.UpdateWithMeta> =
        withTimeout(10.seconds) { buildList { for (update in updates) add(update) } }
}

/**
 * An agent scripted at the JSON-RPC level: it answers `initialize` on its own, and answers the call that
 * opens a session only after sending the updates the test asked for.
 */
private class ScriptedAgent(private val capabilities: AgentCapabilities) : BaseTransport() {
    var sendFailure: Throwable? = null

    /**
     * Every request received after `initialize`, in arrival order.
     */
    val requests = Channel<JsonRpcRequest>(Channel.UNLIMITED)
    private var newSession: (JsonRpcRequest) -> Unit = { error("no answer scripted for session/new") }
    private var resumeSession: (JsonRpcRequest) -> Unit = { error("no answer scripted for session/resume") }
    private var forkSession: (JsonRpcRequest) -> Unit = { error("no answer scripted for session/fork") }

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

    fun onResumeSessionFailing(beforeResponse: ScriptedAgent.() -> Unit) {
        resumeSession = { request ->
            beforeResponse()
            fireMessage(
                JsonRpcErrorResponse(request.id, JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, "resume failed"))
            )
        }
    }

    fun onForkSession(sessionId: SessionId, beforeResponse: ScriptedAgent.() -> Unit) {
        forkSession = { request ->
            beforeResponse()
            respond(request, AcpMethod.AgentMethods.V2.SessionFork.responseSerializer, ForkSessionResponse(sessionId))
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

    fun sendRawUpdate(sessionId: SessionId, update: JsonElement, meta: JsonElement) {
        fireMessage(
            JsonRpcNotification(
                method = AcpMethod.ClientMethods.V2.SessionUpdate.methodName,
                params = buildJsonObject {
                    put("sessionId", ACPJson.encodeToJsonElement(SessionId.serializer(), sessionId))
                    put("update", update)
                    put("_meta", meta)
                },
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

    private fun fireMessage(message: JsonRpcMessage) = fireFrame(TransportFrame.Single(message))

    override fun send(frame: TransportFrame) {
        sendFailure?.let { throw it }
        if (state.value == Transport.State.CLOSING || state.value == Transport.State.CLOSED) {
            throw ClosedSendChannelException("Transport is closed")
        }
        val message = (frame as TransportFrame.Single).message
        if (message !is JsonRpcRequest) return
        if (message.method != AcpMethod.AgentMethods.V2.Initialize.methodName) requests.trySend(message)
        when (message.method) {
            AcpMethod.AgentMethods.V2.Initialize.methodName -> respond(
                message,
                AcpMethod.AgentMethods.V2.Initialize.responseSerializer,
                InitializeResponse(PROTOCOL_VERSION_V2, Implementation("scripted-agent", "1.0.0"), capabilities),
            )
            AcpMethod.AgentMethods.V2.SessionNew.methodName -> newSession(message)
            AcpMethod.AgentMethods.V2.SessionResume.methodName -> resumeSession(message)
            AcpMethod.AgentMethods.V2.SessionFork.methodName -> forkSession(message)
            else -> {}
        }
    }

    private fun <T> respond(request: JsonRpcRequest, serializer: kotlinx.serialization.KSerializer<T>, response: T) {
        fireMessage(JsonRpcSuccessResponse(id = request.id, result = ACPJson.encodeToJsonElement(serializer, response)))
    }
}
