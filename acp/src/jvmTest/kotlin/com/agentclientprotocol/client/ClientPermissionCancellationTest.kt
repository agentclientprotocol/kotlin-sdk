@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A v1 client answers its pending `session/request_permission` requests with `cancelled` when it cancels or
 * closes the session, without waiting for the agent to send `$/cancel_request`.
 */
class ClientPermissionCancellationTest {

    @Test
    fun `without cancel or close the app's answer is the response`() = withV1Client { client, agent, _ ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)

        val request = agent.requestPermission(session.sessionId, "tool")
        val user = operations.user("tool")
        user.started.await()
        user.answer.complete(ALLOWED)

        assertEquals(ALLOWED.outcome, agent.permissionOutcome(request))
    }

    @Test
    fun `cancel answers a pending permission request and cancels the app's handler`() =
        withV1Client { client, agent, _ ->
            val operations = UserOperations()
            val session = client.newSession(agent, operations)
            val request = agent.requestPermission(session.sessionId, "tool")
            val user = operations.user("tool")
            user.started.await()

            session.cancel()

            assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(request))
            assertTrue(user.cleanedUp.isCompleted, "the handler stopped without the user answering")
            assertEquals(listOf(session.sessionId), agent.cancels.toListNow())
        }

    @Test
    fun `one cancel answers every pending permission request of the session`() = withV1Client { client, agent, _ ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)
        val first = agent.requestPermission(session.sessionId, "first")
        val second = agent.requestPermission(session.sessionId, "second")
        operations.user("first").started.await()
        operations.user("second").started.await()

        session.cancel()

        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(first))
        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(second))
        assertTrue(operations.user("first").cleanedUp.isCompleted)
        assertTrue(operations.user("second").cleanedUp.isCompleted)
    }

    @Test
    fun `cancel answers a pending permission request even if sending session cancel fails`() =
        withV1Client { client, agent, _ ->
            val operations = UserOperations()
            val session = client.newSession(agent, operations)
            val request = agent.requestPermission(session.sessionId, "tool")
            val user = operations.user("tool")
            user.started.await()
            agent.notificationFailure = ClosedSendChannelException("Writer queue closed")

            assertFailsWith<ClosedSendChannelException> { session.cancel() }

            assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(request))
            assertTrue(user.cleanedUp.isCompleted)
        }

    @Test
    fun `a request after cancel is answered without the app until the next prompt starts`() =
        withV1Client { client, agent, scope ->
            val operations = UserOperations()
            val session = client.newSession(agent, operations)
            session.cancel()

            val late = agent.requestPermission(session.sessionId, "late")
            assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(late))
            assertEquals(emptyList(), operations.calls.toListNow())

            val turn = scope.async { session.prompt(listOf(ContentBlock.Text("again"))).toList() }
            val promptRequest = agent.prompts.receive()
            val next = agent.requestPermission(session.sessionId, "next")
            operations.user("next").started.await()
            operations.user("next").answer.complete(ALLOWED)

            assertEquals(ALLOWED.outcome, agent.permissionOutcome(next))
            assertEquals(listOf("next"), operations.calls.toListNow())

            agent.answerPrompt(promptRequest)
            assertIs<Event.PromptResponseEvent>(turn.await().last())
        }

    @Test
    fun `a prompt rejected for an active turn does not undo a cancel`() = withV1Client { client, agent, scope ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)
        val turn = scope.async { session.prompt(listOf(ContentBlock.Text("first"))).toList() }
        val promptRequest = agent.prompts.receive()
        session.cancel()

        assertFailsWith<IllegalStateException> { session.prompt(listOf(ContentBlock.Text("second"))).toList() }

        val late = agent.requestPermission(session.sessionId, "late")
        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(late))
        assertEquals(emptyList(), operations.calls.toListNow())

        agent.answerPrompt(promptRequest)
        turn.await()
    }

    @Test
    fun `cancelling one session leaves another session's permission requests pending`() =
        withV1Client { client, agent, _ ->
            val operationsA = UserOperations()
            val operationsB = UserOperations()
            val sessionA = client.newSession(agent, operationsA)
            val sessionB = client.newSession(agent, operationsB)
            val requestA = agent.requestPermission(sessionA.sessionId, "a")
            val requestB = agent.requestPermission(sessionB.sessionId, "b")
            operationsA.user("a").started.await()
            val userB = operationsB.user("b")
            userB.started.await()

            sessionA.cancel()

            assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(requestA))
            userB.answer.complete(ALLOWED)
            assertEquals(ALLOWED.outcome, agent.permissionOutcome(requestB))
        }

    @Test
    fun `an answer the app gave before cancel is the only response`() = withV1Client { client, agent, _ ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)
        val request = agent.requestPermission(session.sessionId, "tool")
        operations.user("tool").answer.complete(ALLOWED)
        assertEquals(ALLOWED.outcome, agent.permissionOutcome(request))

        session.cancel()

        // A later round trip through the same session gives a stray second response the chance to show up.
        val barrier = agent.requestPermission(session.sessionId, "barrier")
        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(barrier))
        assertTrue(agent.responsesTo(request).isEmpty, "a second response to $request")
    }

    @Test
    fun `close answers a pending permission request before the close response arrives`() =
        withV1Client { client, agent, scope ->
            val operations = UserOperations()
            val session = client.newSession(agent, operations)
            val request = agent.requestPermission(session.sessionId, "tool")
            val user = operations.user("tool")
            user.started.await()

            val closing = scope.async { session.close() }
            val closeRequest = agent.closes.receive()

            /*
             * This agent answers `session/close` only once its turn has its permission answer, as one whose close
             * waits for the turn to end would.
             */
            assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(request))
            assertTrue(user.cleanedUp.isCompleted)
            agent.respond(closeRequest, AcpMethod.AgentMethods.V1.SessionClose.responseSerializer, CloseSessionResponse())
            closing.await()
        }

    @Test
    fun `close answers a pending permission request even if the close fails`() = withV1Client { client, agent, scope ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)
        val request = agent.requestPermission(session.sessionId, "tool")
        operations.user("tool").started.await()

        val closing = scope.async { assertFailsWith<JsonRpcException> { session.close() } }
        agent.fail(agent.closes.receive())

        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(request))
        val failure = closing.await()
        assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, failure.code)
        assertEquals(ScriptedV1Agent.FAILURE_MESSAGE, failure.message)
    }

    @Test
    fun `a closed session stays cancelled when a prompt starts after the close`() = withV1Client { client, agent, scope ->
        val operations = UserOperations()
        val session = client.newSession(agent, operations)

        // A failed close keeps the session known, so its later requests reach it rather than failing as unknown.
        val closing = scope.async { runCatching { session.close() } }
        agent.fail(agent.closes.receive())
        closing.await()

        val turn = scope.async { session.prompt(listOf(ContentBlock.Text("after close"))).toList() }
        val promptRequest = agent.prompts.receive()
        val late = agent.requestPermission(session.sessionId, "late")

        assertEquals(RequestPermissionOutcome.Cancelled, agent.permissionOutcome(late))
        assertEquals(emptyList(), operations.calls.toListNow())

        agent.answerPrompt(promptRequest)
        turn.await()
    }
}

private val ALLOWED = RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))

private fun withV1Client(block: suspend (Client, ScriptedV1Agent, CoroutineScope) -> Unit) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
        val agent = ScriptedV1Agent()
        val protocol = Protocol(scope, agent)
        protocol.start()
        agent.start()
        val client = Client(protocol)
        runBlocking {
            withTimeout(10.seconds) { block(client, agent, scope) }
        }
    } finally {
        scope.cancel()
    }
}

private suspend fun Client.newSession(agent: ScriptedV1Agent, operations: ClientSessionOperations): ClientSession {
    agent.nextSessionId = SessionId("session-${agent.sessionCount.incrementAndGet()}")
    return newSession(SessionCreationParameters(cwd = ".", mcpServers = emptyList())) { _, _ -> operations }
}

private fun <T> Channel<T>.toListNow(): List<T> = buildList {
    while (true) add(tryReceive().getOrNull() ?: break)
}

/**
 * Stands in for a user who answers a permission question whenever the test says so.
 */
private class WaitingUser {
    val started = CompletableDeferred<Unit>()
    val answer = CompletableDeferred<RequestPermissionResponse>()
    val cleanedUp = CompletableDeferred<Unit>()

    suspend fun ask(): RequestPermissionResponse {
        started.complete(Unit)
        try {
            return answer.await()
        } finally {
            cleanedUp.complete(Unit)
        }
    }
}

/**
 * Asks one [WaitingUser] per tool call id and records which tool calls reached the app.
 */
private class UserOperations : ClientSessionOperations {
    private val users = ConcurrentHashMap<String, WaitingUser>()
    val calls = Channel<String>(Channel.UNLIMITED)

    fun user(toolCallId: String): WaitingUser = users.computeIfAbsent(toolCallId) { WaitingUser() }

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        calls.trySend(toolCall.toolCallId.value)
        return user(toolCall.toolCallId.value).ask()
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {}
}

/**
 * A v1 agent scripted at the JSON-RPC level. It answers `session/new` on its own and leaves `session/prompt` and
 * `session/close` to the test.
 */
private class ScriptedV1Agent : BaseTransport() {
    var notificationFailure: Throwable? = null
    var nextSessionId: SessionId = SessionId("session")
    val sessionCount = AtomicInteger()

    val cancels = Channel<SessionId>(Channel.UNLIMITED)
    val prompts = Channel<JsonRpcRequest>(Channel.UNLIMITED)
    val closes = Channel<JsonRpcRequest>(Channel.UNLIMITED)

    private val requestIds = AtomicInteger()
    private val responses = ConcurrentHashMap<RequestId, Channel<JsonRpcResponse>>()

    fun requestPermission(sessionId: SessionId, toolCallId: String): RequestId {
        val id = RequestId.create("agent-${requestIds.incrementAndGet()}")
        val method = AcpMethod.ClientMethods.V1.SessionRequestPermission
        val request = RequestPermissionRequest(
            sessionId,
            SessionUpdate.ToolCallUpdate(ToolCallId(toolCallId)),
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.ALLOW_ONCE)),
        )
        fireMessage(JsonRpcRequest(id, method.methodName, ACPJson.encodeToJsonElement(method.requestSerializer, request)))
        return id
    }

    suspend fun permissionOutcome(id: RequestId): RequestPermissionOutcome {
        val response = assertIs<JsonRpcSuccessResponse>(responsesTo(id).receive())
        val decoded = ACPJson.decodeFromJsonElement(
            AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer,
            response.result,
        )
        return decoded.outcome
    }

    fun answerPrompt(request: JsonRpcRequest) {
        respond(request, AcpMethod.AgentMethods.V1.SessionPrompt.responseSerializer, PromptResponse(StopReason.CANCELLED))
    }

    fun <T> respond(request: JsonRpcRequest, serializer: kotlinx.serialization.KSerializer<T>, response: T) {
        fireMessage(JsonRpcSuccessResponse(id = request.id, result = ACPJson.encodeToJsonElement(serializer, response)))
    }

    fun fail(request: JsonRpcRequest) {
        fireMessage(
            JsonRpcErrorResponse(request.id, JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, FAILURE_MESSAGE))
        )
    }

    /**
     * Every response the client sent to the agent's request [id], in send order.
     */
    fun responsesTo(id: RequestId): Channel<JsonRpcResponse> =
        responses.computeIfAbsent(id) { Channel(Channel.UNLIMITED) }

    companion object {
        const val FAILURE_MESSAGE = "failed"
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
        when (val message = (frame as TransportFrame.Single).message) {
            is JsonRpcResponse -> responsesTo(message.id).trySend(message)
            is JsonRpcNotification -> {
                if (message.method != AcpMethod.AgentMethods.V1.SessionCancel.methodName) return
                notificationFailure?.let { throw it }
                val cancel = ACPJson.decodeFromJsonElement(
                    AcpMethod.AgentMethods.V1.SessionCancel.serializer,
                    checkNotNull(message.params),
                )
                cancels.trySend(cancel.sessionId)
            }
            is JsonRpcRequest -> when (message.method) {
                AcpMethod.AgentMethods.V1.SessionNew.methodName -> respond(
                    message,
                    AcpMethod.AgentMethods.V1.SessionNew.responseSerializer,
                    NewSessionResponse(nextSessionId),
                )
                AcpMethod.AgentMethods.V1.SessionPrompt.methodName -> prompts.trySend(message)
                AcpMethod.AgentMethods.V1.SessionClose.methodName -> closes.trySend(message)
                else -> {}
            }
        }
    }
}
