@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.AvailableCommand
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
import com.agentclientprotocol.model.v2.SessionConfigKind
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ClientSessionTest {
    @Test
    fun `new updates reach the callback before setup responds`() = withV2Client { client, agent, scope, received ->
        val opening = scope.async { client.newSession("/work") }
        val request = agent.requests.receive()
        agent.sendUpdate(SessionId("new"), "early")

        assertEquals(listOf("early"), received.takeTexts(1))
        assertFalse(opening.isCompleted)
        agent.complete(request, """{"sessionId":"new"}""")
        assertEquals(SessionId("new"), opening.await().sessionId)
    }

    @Test
    fun `replay reaches the callback before resume responds without a handle`() = withV2Client { client, agent, scope, received ->
        val resuming = scope.async { client.resumeSession(SessionId("saved"), "/work", replayFrom = ReplayFrom.Start()) }
        val request = agent.requests.receive()
        agent.sendUpdate(SessionId("saved"), "history")

        assertEquals(listOf("history"), received.takeTexts(1))
        assertFalse(resuming.isCompleted)
        agent.complete(request)
        resuming.await()
    }

    @Test
    fun `failed resume keeps partial replay and later notifications`() = withV2Client { client, agent, scope, received ->
        val resuming = scope.async {
            runCatching { client.resumeSession(SessionId("saved"), "/work", replayFrom = ReplayFrom.Start()) }
        }
        val request = agent.requests.receive()
        agent.sendUpdate(SessionId("saved"), "partial replay")
        assertEquals(listOf("partial replay"), received.takeTexts(1))
        agent.fail(request)
        assertTrue(resuming.await().isFailure)

        agent.sendUpdate(SessionId("saved"), "after failure")
        assertEquals(listOf("after failure"), received.takeTexts(1))
    }

    @Test
    fun `failure before replay permits an explicit retry`() = withV2Client { client, agent, scope, received ->
        val failed = scope.async { runCatching { client.resumeSession(SessionId("saved"), "/work") } }
        agent.fail(agent.requests.receive())
        assertTrue(failed.await().isFailure)
        val retry = scope.async { client.resumeSession(SessionId("saved"), "/work", replayFrom = ReplayFrom.Start()) }
        val request = agent.requests.receive()
        agent.sendUpdate(SessionId("saved"), "retry history")
        assertEquals(listOf("retry history"), received.takeTexts(1))
        agent.complete(request)
        retry.await()
    }

    @Test
    fun `failed resume without replay still delivers live events`() = withV2Client { client, agent, scope, received ->
        val resuming = scope.async { runCatching { client.resumeSession(SessionId("saved"), "/work") } }
        val request = agent.requests.receive()
        assertFalse(request.params!!.jsonObject.containsKey("replayFrom"))
        agent.sendUpdate(SessionId("saved"), "live during resume")
        agent.fail(request)
        assertTrue(resuming.await().isFailure)
        assertEquals(listOf("live during resume"), received.takeTexts(1))
    }

    @Test
    fun `cancelled resume leaves updates available for an explicit retry`() = withV2Client { client, agent, scope, received ->
        val resuming = scope.async { client.resumeSession(SessionId("saved"), "/work", replayFrom = ReplayFrom.Start()) }
        agent.requests.receive()
        agent.sendUpdate(SessionId("saved"), "partial")
        assertEquals(listOf("partial"), received.takeTexts(1))
        resuming.cancelAndJoin()
        agent.sendUpdate(SessionId("saved"), "after cancellation")
        val retry = scope.async { client.resumeSession(SessionId("saved"), "/work", replayFrom = ReplayFrom.Start()) }
        val request = agent.requests.receive()
        agent.sendUpdate(SessionId("saved"), "replayed again")
        agent.complete(request)
        retry.await()
        assertEquals(listOf("after cancellation", "replayed again"), received.takeTexts(2))
    }

    @Test
    fun `overlapping and repeated resumes share the connection callback`() = withV2Client { client, agent, scope, received ->
        val id = SessionId("saved")
        val first = scope.async { client.resumeSession(id, "/work", replayFrom = ReplayFrom.Start()) }
        val firstRequest = agent.requests.receive()
        val second = scope.async { client.resumeSession(id, "/work", replayFrom = ReplayFrom.Start()) }
        val secondRequest = agent.requests.receive()
        agent.sendUpdate(id, "first replay")
        agent.complete(firstRequest)
        first.await()
        agent.sendUpdate(SessionId("other"), "other session")
        agent.sendUpdate(id, "second replay")
        agent.complete(secondRequest)
        second.await()
        val third = scope.async { client.resumeSession(id, "/work") }
        agent.complete(agent.requests.receive())
        third.await()
        agent.sendUpdate(id, "live")

        val events = received.take(4)
        assertEquals(listOf(id, SessionId("other"), id, id), events.map { it.sessionId })
        assertEquals(listOf("first replay", "other session", "second replay", "live"), events.map { it.text() })
    }

    @Test
    fun `all setup methods preserve complete responses`() = withV2Client { client, agent, scope, _ ->
        val options = listOf(SessionConfigOption(SessionConfigId("verbose"), "Verbose", kind = SessionConfigKind.Boolean(true)))
        val commands = listOf(AvailableCommand("test", "Run project tests"))
        val meta = buildJsonObject { put("trace", "setup") }
        val new = NewSessionResponse(SessionId("new"), configOptions = options, availableCommands = commands, _meta = meta)
        val opening = scope.async { client.newSession("/work") }
        agent.complete(agent.requests.receive(), ACPJson.encodeToString(NewSessionResponse.serializer(), new))
        assertEquals(new, opening.await())

        val resumed = ResumeSessionResponse(configOptions = options, availableCommands = commands, _meta = meta)
        val resuming = scope.async { client.resumeSession(SessionId("saved"), "/work") }
        agent.complete(agent.requests.receive(), ACPJson.encodeToString(ResumeSessionResponse.serializer(), resumed))
        assertEquals(resumed, resuming.await())

        val forked = ForkSessionResponse(SessionId("fork"), configOptions = options, availableCommands = commands, _meta = meta)
        val forking = scope.async { client.forkSession(SessionId("saved"), "/work") }
        agent.complete(agent.requests.receive(), ACPJson.encodeToString(ForkSessionResponse.serializer(), forked))
        assertEquals(forked, forking.await())
    }

    @Test
    fun `null resume result reads as an empty response`() = withV2Client { client, agent, scope, _ ->
        val resuming = scope.async { client.resumeSession(SessionId("saved"), "/work") }
        agent.complete(agent.requests.receive(), "null")
        assertEquals(ResumeSessionResponse(), resuming.await())
    }

    @Test
    fun `callback failure does not prevent later updates`() {
        val received = SessionReader()
        withV2Client(onUpdate = {
            if (it.text() == "bad") error("callback failed")
            received.accept(it)
        }) { _, agent, _, _ ->
            agent.sendUpdate(SessionId("unregistered"), "bad")
            agent.sendUpdate(SessionId("unregistered"), "good")
            assertEquals(listOf("good"), received.takeTexts(1))
        }
    }

    @Test
    fun `cancel releases pending permission when notification send fails`() {
        val operations = WaitingPermissions()
        withV2Client(operations = operations) { client, agent, scope, _ ->
            val id = SessionId("session")
            val permission = scope.async { client.handlePermissionRequest(RequestPermissionRequest(id, "Allow?", emptyList())) }
            operations.started.receive()
            agent.sendFailure = ClosedSendChannelException("Writer queue closed")

            assertFailsWith<ClosedSendChannelException> { client.session(id).cancel() }
            assertEquals(RequestPermissionOutcome.Cancelled, permission.await().outcome)
            assertEquals(id, operations.cleaned.receive())
        }
    }

    @Test
    fun `permission cancellation spans handles but isolates sessions and future requests`() {
        val operations = WaitingPermissions()
        withV2Client(operations = operations) { client, _, scope, _ ->
            val id = SessionId("session")
            val other = SessionId("other")
            val first = scope.async { client.handlePermissionRequest(RequestPermissionRequest(id, "First", emptyList())) }
            assertEquals(id, operations.started.receive())
            val second = scope.async { client.handlePermissionRequest(RequestPermissionRequest(id, "Second", emptyList())) }
            assertEquals(id, operations.started.receive())
            val isolated = scope.async { client.handlePermissionRequest(RequestPermissionRequest(other, "Other", emptyList())) }
            assertEquals(other, operations.started.receive())
            val handle = client.session(id)
            val duplicate = client.session(id)
            handle.cancel()
            assertEquals(RequestPermissionOutcome.Cancelled, first.await().outcome)
            assertEquals(RequestPermissionOutcome.Cancelled, second.await().outcome)
            assertFalse(isolated.isCompleted)

            val future = scope.async { client.handlePermissionRequest(RequestPermissionRequest(id, "Future", emptyList())) }
            assertEquals(id, operations.started.receive())
            assertFalse(future.isCompleted)
            duplicate.cancel()
            assertEquals(RequestPermissionOutcome.Cancelled, future.await().outcome)
            client.session(other).cancel()
            assertEquals(RequestPermissionOutcome.Cancelled, isolated.await().outcome)
        }
    }

    @Test
    fun `additional directories require the capability without sending a request`() = withV2Client { client, agent, _, _ ->
        assertFailsWith<AcpExpectedError> { client.newSession("/work", additionalDirectories = listOf("/lib")) }
        assertFailsWith<AcpExpectedError> { client.resumeSession(SessionId("saved"), "/work", additionalDirectories = listOf("/lib")) }
        assertFailsWith<AcpExpectedError> { client.forkSession(SessionId("saved"), "/work", additionalDirectories = listOf("/lib")) }
        assertTrue(agent.requests.tryReceive().isFailure)
    }

    @Test
    fun `additional directories fail before initialization instead of waiting`() = withV2Client(initialize = false) { client, agent, _, _ ->
        assertFailsWith<AcpExpectedError> { client.newSession("/work", additionalDirectories = listOf("/lib")) }
        assertTrue(agent.requests.tryReceive().isFailure)
    }

    @Test
    fun `setup omits empty roots and forwards supported roots`() {
        for (supported in listOf(false, true)) {
            val capabilities = if (supported) AgentCapabilities(
                session = SessionCapabilities(additionalDirectories = SessionAdditionalDirectoriesCapabilities())
            ) else AgentCapabilities()
            withV2Client(capabilities = capabilities) { client, agent, scope, _ ->
                for (roots in if (supported) listOf(emptyList(), listOf("/lib")) else listOf(emptyList())) {
                    val calls: List<suspend () -> Any> = listOf(
                        { client.newSession("/work", additionalDirectories = roots) },
                        { client.resumeSession(SessionId("saved"), "/work", additionalDirectories = roots) },
                        { client.forkSession(SessionId("saved"), "/work", additionalDirectories = roots) },
                    )
                    for (call in calls) {
                        val pending = scope.async { call() }
                        val request = agent.requests.receive()
                        val field = request.params!!.jsonObject["additionalDirectories"]
                        assertEquals(roots.takeIf { it.isNotEmpty() }?.map(::JsonPrimitive), field?.jsonArray?.toList())
                        agent.complete(request, """{"sessionId":"opened"}""")
                        pending.await()
                    }
                }
            }
        }
    }

    @Test
    fun `delivers tool call updates in order with payloads and metadata intact`() = withV2Client { client, agent, _, received ->
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
        client.newSession(cwd = "/work")
        updates.drop(3).forEach { agent.sendRawUpdate(sessionId, it, notificationMeta) }

        received.take(updates.size).forEachIndexed { index, actual ->
            assertEquals(sessionId, actual.sessionId)
            assertEquals(updates[index], ACPJson.encodeToJsonElement(SessionUpdate.serializer(), actual.update), "update $index")
            assertEquals(notificationMeta, actual._meta, "notification metadata for update $index")
        }
    }
}

private class WaitingPermissions : ClientSessionOperations {
    val started = Channel<SessionId>(Channel.UNLIMITED)
    val cleaned = Channel<SessionId>(Channel.UNLIMITED)

    override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        started.send(request.sessionId)
        try {
            awaitCancellation()
        } finally {
            cleaned.trySend(request.sessionId).getOrThrow()
        }
    }
}

private fun withV2Client(
    operations: ClientSessionOperations? = null,
    onUpdate: ((UpdateSessionNotification) -> Unit)? = null,
    capabilities: AgentCapabilities = AgentCapabilities(),
    initialize: Boolean = true,
    block: suspend (Client, ScriptedAgent, CoroutineScope, SessionReader) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob())
    try {
        val agent = ScriptedAgent(capabilities)
        val protocol = Protocol(scope, agent)
        val received = SessionReader()
        val client = Client(protocol, operations = operations, onSessionUpdate = onUpdate ?: received::accept)
        protocol.start()
        agent.start()
        runBlocking {
            withTimeout(10.seconds) {
                if (initialize) client.initialize(
                    ClientInfo(protocolVersion = PROTOCOL_VERSION_V2, implementation = Implementation("test", "1.0.0"))
                )
                block(client, agent, scope, received)
            }
        }
    } finally {
        scope.cancel()
    }
}

private class SessionReader {
    private val updates = Channel<UpdateSessionNotification>(Channel.UNLIMITED)

    fun accept(notification: UpdateSessionNotification) {
        updates.trySend(notification).getOrThrow()
    }

    suspend fun take(count: Int): List<UpdateSessionNotification> = List(count) { updates.receive() }

    suspend fun takeTexts(count: Int): List<String> = take(count).map { it.text() }
}

private fun UpdateSessionNotification.text(): String =
    ((update as SessionUpdate.AgentMessageChunk).chunk.content as ContentBlock.Text).text

/**
 * Holds setup responses until the test completes them, so early updates never depend on timing.
 */
private class ScriptedAgent(private val capabilities: AgentCapabilities) : BaseTransport() {
    var sendFailure: Throwable? = null
    val requests = Channel<JsonRpcRequest>(Channel.UNLIMITED)
    private var newSession: ((JsonRpcRequest) -> Unit)? = null

    fun onNewSession(sessionId: SessionId, beforeResponse: ScriptedAgent.() -> Unit) {
        newSession = { request ->
            beforeResponse()
            respond(request, NewSessionResponse.serializer(), NewSessionResponse(sessionId))
        }
    }

    fun complete(request: JsonRpcRequest, result: String = "{}") {
        fireMessage(JsonRpcSuccessResponse(request.id, ACPJson.parseToJsonElement(result)))
    }

    fun fail(request: JsonRpcRequest) {
        fireMessage(JsonRpcErrorResponse(request.id, JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, "resume failed")))
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
        when (message.method) {
            AcpMethod.AgentMethods.V2.Initialize.methodName -> respond(
                message,
                AcpMethod.AgentMethods.V2.Initialize.responseSerializer,
                InitializeResponse(PROTOCOL_VERSION_V2, Implementation("scripted-agent", "1.0.0"), capabilities = capabilities),
            )
            AcpMethod.AgentMethods.V2.SessionNew.methodName -> {
                val handler = newSession
                if (handler == null) requests.trySend(message).getOrThrow() else handler(message)
            }
            AcpMethod.AgentMethods.V2.SessionResume.methodName,
            AcpMethod.AgentMethods.V2.SessionFork.methodName -> requests.trySend(message).getOrThrow()
            else -> {}
        }
    }

    private fun <T> respond(request: JsonRpcRequest, serializer: kotlinx.serialization.KSerializer<T>, response: T) {
        fireMessage(JsonRpcSuccessResponse(id = request.id, result = ACPJson.encodeToJsonElement(serializer, response)))
    }
}
