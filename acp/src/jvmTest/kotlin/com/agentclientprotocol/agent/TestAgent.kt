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
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.model.v2.InitializeRequest as V2InitializeRequest
import com.agentclientprotocol.model.v2.InitializeResponse as V2InitializeResponse
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
import kotlin.time.Duration.Companion.seconds

suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> TestTransport.testRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest
): Pair<TResponse?, List<JsonRpcNotification>> {
    val received = fireTestRequest(
        methodName = method.methodName,
        params = ACPJson.encodeToJsonElement(method.requestSerializer, request)
    )
    val response = (received.lastOrNull() as? JsonRpcResponse)?.result?.let {
        ACPJson.decodeFromJsonElement(method.responseSerializer, it)
    }
    val notifications = received.filterIsInstance<JsonRpcNotification>()
    return response to notifications
}

fun <TNotification : AcpNotification> TestTransport.testNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification
) {
    fireTestNotification(method.methodName, ACPJson.encodeToJsonElement(method.serializer, notification))
}

class TestAgent(val agent: Agent, val agentSupport: TestAgentSupport, val transport: TestTransport) {
    suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> testRequest(
        method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
        request: TRequest
    ): Pair<TResponse?, List<JsonRpcNotification>> = transport.testRequest(method, request)

    fun <TNotification : AcpNotification> testNotification(
        method: AcpMethod.AcpNotificationMethod<TNotification>,
        notification: TNotification
    ) = transport.testNotification(method, notification)

    fun close() {
        agent.protocol.close()
    }

    suspend fun testInitialize(request: InitializeRequest) = testRequest(AcpMethod.AgentMethods.V1.Initialize, request)

    suspend fun testNewSession(request: NewSessionRequest) = testRequest(AcpMethod.AgentMethods.V1.SessionNew, request)
    suspend fun testPrompt(request: PromptRequest) = testRequest(AcpMethod.AgentMethods.V1.SessionPrompt, request)

    fun testCancel(notification: CancelNotification) = testNotification(AcpMethod.AgentMethods.V1.SessionCancel, notification)
}

suspend fun TestAgent.simplePrompt(prompt: String): Pair<PromptResponse, List<SessionUpdate>> {
    val session = agentSupport.createdSessions.values.single()
    val (resp, notifications) = testPrompt(PromptRequest(session.sessionId, listOf(ContentBlock.Text(prompt))))
    checkNotNull(resp)

    return resp to notifications
        .filter { it.method == AcpMethod.ClientMethods.V1.SessionUpdate.methodName }
        .mapNotNull { it.params }
        .map { ACPJson.decodeFromJsonElement(AcpMethod.ClientMethods.V1.SessionUpdate.serializer, it).update }
}

class TestAgentSupport(
    val promptHandler: PromptHandler,
) : AgentSupport {
    var isInitialized = false
    var initializedWith: ClientInfo? = null
    val createdSessions = mutableMapOf<SessionId, TestAgentSession>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        isInitialized = true
        initializedWith = clientInfo
        return AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))
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
    val testAgent = TestAgent(agent, agentSupport, transport)
    block(testAgent)
    testAgent.close()
}

/** The v2 counterpart of [TestAgent]: a v2 agent is its own object, on its own connection. */
class TestV2Agent(val agent: V2Agent, val transport: TestTransport) {
    suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> testRequest(
        method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
        request: TRequest
    ): Pair<TResponse?, List<JsonRpcNotification>> = transport.testRequest(method, request)

    fun <TNotification : AcpNotification> testNotification(
        method: AcpMethod.AcpNotificationMethod<TNotification>,
        notification: TNotification
    ) = transport.testNotification(method, notification)

    fun close() {
        agent.protocol.close()
    }

    suspend fun testInitialize(
        request: V2InitializeRequest
    ): Pair<V2InitializeResponse?, List<JsonRpcNotification>> =
        testRequest(AcpMethod.AgentMethods.V2.Initialize, request)
}

fun withTestV2Agent(
    agentSupport: V2AgentSupport,
    timeout: Duration = 5.seconds,
    block: suspend CoroutineScope.(TestV2Agent) -> Unit
) = runBlocking {
    val transport = TestTransport(timeout)
    val protocol = Protocol(this, transport)
    val agent = V2Agent(protocol, agentSupport)
    protocol.start()
    val testAgent = TestV2Agent(agent, transport)
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
