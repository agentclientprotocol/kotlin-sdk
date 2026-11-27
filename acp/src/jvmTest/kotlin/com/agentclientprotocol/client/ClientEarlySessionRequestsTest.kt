@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.ForkSessionResponse
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.ResumeSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A session request of the agent waits only for a session that an opening call has claimed: a `session/load` or
 * `session/resume` claims its id before the request goes out, a `session/new` or `session/fork` only in its answer.
 * Any other request fails at once with `-32002`. The tests play the agent on a scripted transport, so they control
 * the exact wire order of the requests and the answer.
 */
class ClientEarlySessionRequestsTest {
    private val parameters = SessionCreationParameters(cwd = ".", mcpServers = emptyList())

    /**
     * One way to open a root session: the call, its method, and the answer the scripted agent gives.
     */
    private class Opening(
        val name: String,
        val method: MethodName,
        val open: suspend (Client, SessionId, SessionCreationParameters, SubagentWorld) -> ClientSession,
        val answer: (ScriptedAgentTransport, JsonRpcRequest, SessionId) -> Unit,
    ) {
        override fun toString() = name
    }

    private val newWithFactory = Opening(
        "new", AcpMethod.AgentMethods.V1.SessionNew.methodName,
        open = { client, _, parameters, world -> client.newSession(parameters) { id, _ -> world.root(id) } },
        answer = { agent, request, id -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, NewSessionResponse(id)) },
    )
    private val forkWithFactory = Opening(
        "fork", AcpMethod.AgentMethods.V1.SessionFork.methodName,
        open = { client, _, parameters, world -> client.forkSession(SessionId("fork-source"), parameters) { id, _ -> world.root(id) } },
        answer = { agent, request, id -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionFork.responseSerializer, ForkSessionResponse(id)) },
    )
    private val loadWithFactory = Opening(
        "load with a factory", AcpMethod.AgentMethods.V1.SessionLoad.methodName,
        open = { client, id, parameters, world -> client.loadSession(id, parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, _ -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse()) },
    )
    private val loadWithOperations = Opening(
        "load with operations", AcpMethod.AgentMethods.V1.SessionLoad.methodName,
        open = { client, id, parameters, world -> client.loadSession(id, parameters, world.root(id)) },
        answer = { agent, request, _ -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse()) },
    )
    private val resumeWithFactory = Opening(
        "resume with a factory", AcpMethod.AgentMethods.V1.SessionResume.methodName,
        open = { client, id, parameters, world -> client.resumeSession(id, parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, _ -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionResume.responseSerializer, ResumeSessionResponse()) },
    )
    private val resumeWithOperations = Opening(
        "resume with operations", AcpMethod.AgentMethods.V1.SessionResume.methodName,
        open = { client, id, parameters, world -> client.resumeSession(id, parameters, world.root(id)) },
        answer = { agent, request, _ -> agent.answer(request, AcpMethod.AgentMethods.V1.SessionResume.responseSerializer, ResumeSessionResponse()) },
    )

    @Test
    fun `a request before the answer of a new or forked session fails at once and the session still opens`() =
        withClient { client, agent ->
            for (opening in listOf(newWithFactory, forkWithFactory)) {
                val world = SubagentWorld()
                val id = SessionId("early-$opening")
                val open = async { opening.open(client, id, parameters, world) }
                val request = agent.receiveRequest(opening.method)

                // Nothing is registered for the id yet.
                assertNotFound(id, agent.awaitResponse(agent.sendRequest(PERMISSION, permissionRequest(id, "before-update"))))

                // An early update gives the id a holder that no opening has claimed yet.
                agent.sendUpdate(id, text("early"))
                assertNotFound(id, agent.awaitResponse(agent.sendRequest(PERMISSION, permissionRequest(id, "after-update"))))
                assertNotFound(id, agent.awaitResponse(agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))))
                assertNotFound(id, agent.awaitResponse(agent.sendRequest(TERMINAL, CreateTerminalRequest(id, "ls"))))
                assertFalse(open.isCompleted, "$opening: the requests are answered before the opening call")

                opening.answer(agent, request, id)
                open.await()
                val operations = world.awaitOperations(id)
                assertEquals(listOf(text("early"), TreeOperations.OPENED), operations.awaitEvents(2), "$opening: the early update is kept")

                val later = agent.sendRequest(PERMISSION, permissionRequest(id, "after-answer"))
                assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(later), PERMISSION.responseSerializer), "$opening")
                val read = agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))
                assertEquals("$id in $id", decode(agent.awaitResponse(read), READ.responseSerializer).content, "$opening")
                assertEquals(listOf(ToolCallId("after-answer")), operations.permissionRequests.value.map { it.toolCallId }, "$opening")
            }
        }

    @Test
    fun `a request during a load or resume of its session waits and reaches the app after the opening`() =
        withClient { client, agent ->
            for (opening in listOf(loadWithFactory, loadWithOperations, resumeWithFactory, resumeWithOperations)) {
                val world = SubagentWorld()
                val id = SessionId("restored-$opening")
                val openedBeforeRequest = MutableStateFlow<List<Boolean>>(emptyList())
                world.configure = { operations ->
                    operations.answerPermission = {
                        openedBeforeRequest.update { it + (TreeOperations.OPENED in operations.events.value) }
                        TreeOperations.ALLOWED
                    }
                }
                val open = async { opening.open(client, id, parameters, world) }
                val request = agent.receiveRequest(opening.method)

                val permission = agent.sendRequest(PERMISSION, permissionRequest(id, "early"))
                val read = agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))
                agent.awaitHandled()
                assertNull(agent.responseOrNull(permission), "$opening: a request waits for the claimed session")
                assertNull(agent.responseOrNull(read), "$opening: a request waits for the claimed session")

                opening.answer(agent, request, id)
                open.await()
                assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(permission), PERMISSION.responseSerializer), "$opening")
                assertEquals("$id in $id", decode(agent.awaitResponse(read), READ.responseSerializer).content, "$opening")
                assertEquals(listOf(true), openedBeforeRequest.value, "$opening: the app sees the request after its Opened")
            }
        }

    @Test
    fun `a request waiting for a load or resume is answered cancelled by a cancel or close during the opening`() =
        withClient { client, agent ->
            for (opening in listOf(loadWithFactory, resumeWithFactory)) {
                for (close in listOf(false, true)) {
                    val world = SubagentWorld()
                    val id = SessionId("ended-$opening-$close")
                    val release = CompletableDeferred<Unit>()
                    world.configure = { operations ->
                        operations.onNotify = { event, session ->
                            if (event is SessionEvent.Opened) {
                                val root = session as ClientSession
                                if (close) root.close() else root.cancel()
                                release.await()
                            }
                        }
                    }
                    val open = async { opening.open(client, id, parameters, world) }
                    val request = agent.receiveRequest(opening.method)
                    val permission = agent.sendRequest(PERMISSION, permissionRequest(id, "early"))
                    agent.awaitHandled()
                    assertNull(agent.responseOrNull(permission), "$opening, close = $close: the request waits for the session")

                    opening.answer(agent, request, id)
                    val closeRequest = if (close) {
                        agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionClose.methodName)
                    } else {
                        agent.receiveNotification(AcpMethod.AgentMethods.V1.SessionCancel.methodName)
                        null
                    }

                    // The opening is still held in `notify`, so the request was in the race before the session existed.
                    assertEquals(CANCELLED, decode(agent.awaitResponse(permission), PERMISSION.responseSerializer), "$opening, close = $close")
                    assertFalse(open.isCompleted, "$opening, close = $close: answered during the opening")

                    closeRequest?.let { agent.answer(it, AcpMethod.AgentMethods.V1.SessionClose.responseSerializer, CloseSessionResponse()) }
                    release.complete(Unit)
                    open.await()
                    assertEquals(emptyList(), world.awaitOperations(id).permissionRequests.value, "$opening, close = $close: the app never saw it")
                }
            }
        }

    @Test
    fun `a request waiting for a load gets the error of the failed opening and not cancelled`() =
        withClient { client, agent ->
            for (notifyFails in listOf(false, true)) {
                val world = SubagentWorld()
                val id = SessionId("failed-$notifyFails")
                world.configure = { operations ->
                    operations.onNotify = { event, _ ->
                        if (notifyFails && event is SessionEvent.Opened) throw IllegalStateException("notify failed")
                    }
                }
                val open = async { runCatching { loadWithFactory.open(client, id, parameters, world) } }
                val request = agent.receiveRequest(loadWithFactory.method)
                val permission = agent.sendRequest(PERMISSION, permissionRequest(id, "early"))
                agent.awaitHandled()

                if (notifyFails) loadWithFactory.answer(agent, request, id) else agent.fail(request)

                assertTrue(open.await().isFailure, "notifyFails = $notifyFails: the opening fails")
                val error = assertIs<JsonRpcErrorResponse>(agent.awaitResponse(permission), "notifyFails = $notifyFails").error
                assertEquals("Failed to create session $id", error.message, "notifyFails = $notifyFails")
            }
        }

    @Test
    fun `a request for an unknown session with no opening in flight fails with resource not found`() = withClient { _, agent ->
        val id = SessionId("unknown")
        assertNotFound(id, agent.awaitResponse(agent.sendRequest(PERMISSION, permissionRequest(id, "tool"))))
        assertNotFound(id, agent.awaitResponse(agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))))
        assertNotFound(id, agent.awaitResponse(agent.sendRequest(TERMINAL, CreateTerminalRequest(id, "ls"))))
    }

    private fun assertNotFound(id: SessionId, response: JsonRpcResponse) {
        val error = assertIs<JsonRpcErrorResponse>(response, "an error, got $response").error
        assertEquals(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, error.code)
        assertEquals("Session $id not found", error.message)
    }

    private fun withClient(block: suspend CoroutineScope.(Client, ScriptedAgentTransport) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = ScriptedAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val client = Client(protocol)
            runBlocking {
                withContext(Dispatchers.Default) {
                    withTimeout(60.seconds) { block(client, transport) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val PERMISSION = AcpMethod.ClientMethods.V1.SessionRequestPermission
        val READ = AcpMethod.ClientMethods.V1.FsReadTextFile
        val TERMINAL = AcpMethod.ClientMethods.V1.TerminalCreate

        val CANCELLED = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        fun permissionRequest(id: SessionId, toolCall: String) =
            RequestPermissionRequest(id, SessionUpdate.ToolCallUpdate(ToolCallId(toolCall)), emptyList())

        fun <T> decode(response: JsonRpcResponse, serializer: KSerializer<T>): T =
            ACPJson.decodeFromJsonElement(serializer, assertIs<JsonRpcSuccessResponse>(response, "an answer, got $response").result)
    }
}
