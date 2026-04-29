@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TestAgent(val agent: Agent, val agentSupport: TestAgentSupport, val transport: TestTransport) {
    suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> testRequest(
        method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
        request: TRequest
    ): Pair<TResponse?, List<JsonRpcNotification>> {
        val received = transport.fireTestRequest(
            methodName = method.methodName,
            params = ACPJson.encodeToJsonElement(method.requestSerializer, request)
        )
        val response = (received.lastOrNull() as? JsonRpcResponse)?.result?.let {
            ACPJson.decodeFromJsonElement(method.responseSerializer, it)
        }
        val notifications = received.filterIsInstance<JsonRpcNotification>()
        return response to notifications
    }

    fun <TNotification : AcpNotification> testNotification(
        method: AcpMethod.AcpNotificationMethod<TNotification>,
        notification: TNotification
    ) {
        transport.fireTestNotification(method.methodName, ACPJson.encodeToJsonElement(method.serializer, notification))
    }

    fun close() {
        agent.protocol.close()
    }

    suspend fun testInitialize(request: InitializeRequest) = testRequest(AcpMethod.AgentMethods.Initialize, request)
    suspend fun testNewSession(request: NewSessionRequest) = testRequest(AcpMethod.AgentMethods.SessionNew, request)
    suspend fun testPrompt(request: PromptRequest) = testRequest(AcpMethod.AgentMethods.SessionPrompt, request)

    fun testCancel(notification: CancelNotification) = testNotification(AcpMethod.AgentMethods.SessionCancel, notification)
}

suspend fun TestAgent.simplePrompt(prompt: String): Pair<PromptResponse, List<SessionUpdate>> {
    val session = agentSupport.createdSessions.values.single()
    val (resp, notifications) = testPrompt(PromptRequest(session.sessionId, listOf(ContentBlock.Text(prompt))))
    checkNotNull(resp)

    return resp to notifications
        .filter { it.method == AcpMethod.ClientMethods.SessionUpdate.methodName }
        .mapNotNull { it.params }
        .map { ACPJson.decodeFromJsonElement(AcpMethod.ClientMethods.SessionUpdate.serializer, it).update }
}

class TestAgentSupport(val promptHandler: PromptHandler) : AgentSupport {
    var isInitialized = false
    val createdSessions = mutableMapOf<SessionId, TestAgentSession>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        isInitialized = true
        return AgentInfo()
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val sessionId = SessionId("test-agent-session-${sessionId.incrementAndGet()}")
        val session = TestAgentSession(sessionId, promptHandler)
        createdSessions[sessionId] = session
        return session
    }

    companion object {
        private val sessionId = atomic(0)
    }
}

typealias PromptHandler = suspend FlowCollector<Event>.(List<ContentBlock>) -> Unit

class TestAgentSession(
    override val sessionId: SessionId,
    val promptHandler: PromptHandler
) : AgentSession {
    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        promptHandler(content)
    }
}

fun withTestAgent(
    timeout: Duration = 5.seconds,
    promptHandler: PromptHandler = echoPromptHandler,
    block: suspend CoroutineScope.(TestAgent) -> Unit
) = runBlocking {
    val transport = TestTransport(timeout)
    val protocol = Protocol(this, transport)
    val agentSupport = TestAgentSupport(promptHandler)
    val agent = Agent(protocol, agentSupport)
    protocol.start()

    // wait a little after protocol start, if messages get sent right away they can get lost
    delay(100.milliseconds)

    val testAgent = TestAgent(agent, agentSupport, transport)
    block(testAgent)
    testAgent.close()
}

fun withInitializedTestAgent(
    timeout: Duration = 5.seconds,
    promptHandler: PromptHandler = echoPromptHandler,
    block: suspend CoroutineScope.(TestAgent) -> Unit
) = withTestAgent(
    timeout = timeout,
    promptHandler = promptHandler,
) { testAgent ->
    testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))
    check(testAgent.agentSupport.isInitialized)
    block(testAgent)
}

fun withTestAgentSession(
    timeout: Duration = 5.seconds,
    promptHandler: PromptHandler = echoPromptHandler,
    cwd: String = ".",
    mcpServers: List<McpServer> = emptyList(),
    block: suspend CoroutineScope.(TestAgent, TestAgentSession) -> Unit
) = withInitializedTestAgent(
    timeout = timeout,
    promptHandler = promptHandler,
) { testAgent ->
    val (newSessionResponse) = testAgent.testNewSession(NewSessionRequest(cwd, mcpServers))
    checkNotNull(newSessionResponse)
    val session = testAgent.agentSupport.createdSessions[newSessionResponse.sessionId]
    checkNotNull(session)
    block(testAgent, session)
}

val echoPromptHandler: PromptHandler = { prompt ->
    prompt.filterIsInstance<ContentBlock.Text>().forEach {
        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(it)))
    }
    emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
}

fun delayEchoPromptHandler(delay: Duration): PromptHandler = { prompt ->
    delay(delay)
    prompt.filterIsInstance<ContentBlock.Text>().forEach {
        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(it)))
    }
    emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
}
