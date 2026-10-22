package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.*
import com.agentclientprotocol.common.*
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.jsonRpcRequest
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlin.test.*

open class TestClientSessionOperations(): ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        TODO()
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
    }
}

open class TestAgentSession(override val sessionId: SessionId = SessionId("test-session-id")) : AgentSession {
    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> {
        TODO()
    }
}

class TestAgentSupport(val capabilities: AgentCapabilities = AgentCapabilities(), val createSessionFunc: suspend (SessionCreationParameters) -> AgentSession) : AgentSupport {
    val agentInitialized = kotlinx.coroutines.CompletableDeferred<ClientInfo>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        agentInitialized.complete(clientInfo)
        return AgentInfo(clientInfo.protocolVersion, capabilities)
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        return createSessionFunc(sessionParameters)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
    ): AgentSession {
        return createSessionFunc(sessionParameters)
    }
}


abstract class FeaturesTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    @Test
    fun `call file system from agent`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)


        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val fileSystemOperations =
                        currentCoroutineContext().client
                    val readTextFileResponse = fileSystemOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(
                        readTextFile = true,
                        writeTextFile = true
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), FileSystemOperations {
                override suspend fun fsReadTextFile(
                    path: String,
                    line: UInt?,
                    limit: UInt?,
                    _meta: JsonElement?,
                ): ReadTextFileResponse {
                    return ReadTextFileResponse("test")
                }

                override suspend fun fsWriteTextFile(
                    path: String,
                    content: String,
                    _meta: JsonElement?,
                ): WriteTextFileResponse {
                    return WriteTextFileResponse()
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Hello", "File content: test", "END_TURN"), result)
    }

    @Test
    fun `change mode from client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)

        val modes = listOf(SessionMode(SessionModeId("ask"), "Ask mode", "Only conversations"), SessionMode(SessionModeId("Code"), "Coding mode", "Writes code"))

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                private val mode = MutableStateFlow(modes[0].id)

                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val clientSessionOperations =
                        currentCoroutineContext().client
                    val readTextFileResponse = clientSessionOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }

                override val availableModes: List<SessionMode>
                    get() = modes
                override val defaultMode: SessionModeId
                    get() = mode.value

                override suspend fun setMode(
                    modeId: SessionModeId,
                    _meta: JsonElement?,
                ): SetSessionModeResponse {
                    delay(500)
                    mode.value = modeId
                    currentCoroutineContext().client.notify(SessionUpdate.CurrentModeUpdate(modeId))
                    return SetSessionModeResponse()
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(
                        readTextFile = true,
                        writeTextFile = true
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), FileSystemOperations {
                override suspend fun fsReadTextFile(
                    path: String,
                    line: UInt?,
                    limit: UInt?,
                    _meta: JsonElement?,
                ): ReadTextFileResponse {
                    return ReadTextFileResponse("test")
                }

                override suspend fun fsWriteTextFile(
                    path: String,
                    content: String,
                    _meta: JsonElement?,
                ): WriteTextFileResponse {
                    return WriteTextFileResponse()
                }
            }
        }
        assertTrue(session.modesSupported, "Modes should be supported")
        assertContentEquals(modes, session.availableModes)
        session.setMode(modes[1].id)
        delay(100)
        assertEquals(modes[1].id, session.currentMode.value, "Current mode should be changed")
    }



    @OptIn(UnstableApi::class)
    @Test
    fun `form elicitation from agent returns accepted response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val elicitationResult = CompletableDeferred<String>()

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Before elicitation")))
                    val client = currentCoroutineContext().client
                    val schema = ElicitationSchema(
                        properties = mapOf(
                            "name" to ElicitationPropertySchema.StringProperty(title = "Your name")
                        ),
                        required = listOf("name")
                    )
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Form(requestedSchema = schema),
                        message = "Please provide your name"
                    )
                    val response = client.createElicitation(request)
                    val action = response.action
                    if (action is ElicitationAction.Accept) {
                        val nameValue = action.content?.get("name")
                        if (nameValue is ElicitationContentValue.StringValue) {
                            elicitationResult.complete(nameValue.value)
                            emit(Event.SessionUpdateEvent(agentTextChunk("Got name: ${nameValue.value}")))
                        }
                    }
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    assertEquals("Please provide your name", request.message)
                    assertTrue(request.mode is ElicitationMode.Form)
                    return CreateElicitationResponse(
                        action = ElicitationAction.Accept(
                            content = mapOf("name" to ElicitationContentValue.StringValue("Alice"))
                        )
                    )
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Before elicitation", "Got name: Alice", "END_TURN"), result)
        assertEquals("Alice", elicitationResult.await())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `form elicitation declined by user`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val client = currentCoroutineContext().client
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Form(
                            requestedSchema = ElicitationSchema(
                                properties = mapOf(
                                    "confirm" to ElicitationPropertySchema.BooleanProperty(title = "Confirm?")
                                )
                            )
                        ),
                        message = "Please confirm"
                    )
                    val response = client.createElicitation(request)
                    when (response.action) {
                        is ElicitationAction.Decline -> emit(Event.SessionUpdateEvent(agentTextChunk("User declined")))
                        is ElicitationAction.Cancel -> emit(Event.SessionUpdateEvent(agentTextChunk("User cancelled")))
                        is ElicitationAction.Accept -> emit(Event.SessionUpdateEvent(agentTextChunk("User accepted")))
                    }
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    return CreateElicitationResponse(action = ElicitationAction.Decline)
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("User declined", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `url elicitation from agent returns accepted response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val completionReceived = CompletableDeferred<ElicitationId>()

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val client = currentCoroutineContext().client
                    val elicitationId = ElicitationId("url-elicit-1")
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Url(
                            elicitationId = elicitationId,
                            url = "https://example.com/auth"
                        ),
                        message = "Please authenticate"
                    )
                    val response = client.createElicitation(request)
                    assertTrue(response.action is ElicitationAction.Accept, "Expected Accept action for URL elicitation")
                    emit(Event.SessionUpdateEvent(agentTextChunk("URL elicitation accepted")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        url = ElicitationUrlCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    assertEquals("Please authenticate", request.message)
                    val mode = request.mode
                    assertTrue(mode is ElicitationMode.Url)
                    assertEquals("https://example.com/auth", mode.url)
                    assertEquals(ElicitationId("url-elicit-1"), mode.elicitationId)
                    return CreateElicitationResponse(action = ElicitationAction.Accept())
                }

                override suspend fun completeElicitation(
                    notification: CompleteElicitationNotification
                ) {
                    completionReceived.complete(notification.elicitationId)
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("URL elicitation accepted", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `form elicitation with complex schema`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val client = currentCoroutineContext().client
                    val schema = ElicitationSchema(
                        title = "User Profile",
                        properties = mapOf(
                            "name" to ElicitationPropertySchema.StringProperty(
                                title = "Name",
                                minLength = 1,
                                maxLength = 100
                            ),
                            "age" to ElicitationPropertySchema.IntegerProperty(
                                title = "Age",
                                minimum = 0,
                                maximum = 150
                            ),
                            "score" to ElicitationPropertySchema.NumberProperty(
                                title = "Score",
                                minimum = 0.0,
                                maximum = 100.0
                            ),
                            "active" to ElicitationPropertySchema.BooleanProperty(
                                title = "Active?",
                                default = true
                            )
                        ),
                        required = listOf("name", "age")
                    )
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Form(requestedSchema = schema),
                        message = "Fill in your profile"
                    )
                    val response = client.createElicitation(request)
                    val action = response.action as ElicitationAction.Accept
                    val content = action.content!!
                    val name = (content["name"] as ElicitationContentValue.StringValue).value
                    val age = (content["age"] as ElicitationContentValue.IntegerValue).value
                    val score = (content["score"] as ElicitationContentValue.NumberValue).value
                    val active = (content["active"] as ElicitationContentValue.BooleanValue).value
                    emit(Event.SessionUpdateEvent(agentTextChunk("$name,$age,$score,$active")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    val mode = request.mode as ElicitationMode.Form
                    assertEquals("User Profile", mode.requestedSchema.title)
                    assertEquals(4, mode.requestedSchema.properties.size)
                    assertEquals(listOf("name", "age"), mode.requestedSchema.required)
                    return CreateElicitationResponse(
                        action = ElicitationAction.Accept(
                            content = mapOf(
                                "name" to ElicitationContentValue.StringValue("Bob"),
                                "age" to ElicitationContentValue.IntegerValue(30),
                                "score" to ElicitationContentValue.NumberValue(95.5),
                                "active" to ElicitationContentValue.BooleanValue(true)
                            )
                        )
                    )
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Bob,30,95.5,true", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `elicitation cancelled by user`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val client = currentCoroutineContext().client
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Form(
                            requestedSchema = ElicitationSchema(
                                properties = mapOf(
                                    "input" to ElicitationPropertySchema.StringProperty(title = "Input")
                                )
                            )
                        ),
                        message = "Provide input"
                    )
                    val response = client.createElicitation(request)
                    assertTrue(response.action is ElicitationAction.Cancel)
                    emit(Event.SessionUpdateEvent(agentTextChunk("Cancelled")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    return CreateElicitationResponse(action = ElicitationAction.Cancel)
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Cancelled", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `url elicitation complete notification is delivered to session`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val completionReceived = CompletableDeferred<ElicitationId>()

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val clientOps = currentCoroutineContext().client
                    val elicitationId = ElicitationId("url-complete-1")
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Session(SessionId("test-session-id")),
                        mode = ElicitationMode.Url(
                            elicitationId = elicitationId,
                            url = "https://example.com/oauth"
                        ),
                        message = "Please authenticate via URL"
                    )
                    val response = clientOps.createElicitation(request)
                    assertTrue(response.action is ElicitationAction.Accept)

                    // Now send the completion notification
                    clientOps.completeElicitation(CompleteElicitationNotification(elicitationId))
                    // Small delay to let the notification propagate
                    delay(200)

                    emit(Event.SessionUpdateEvent(agentTextChunk("Completed")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        url = ElicitationUrlCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    return CreateElicitationResponse(action = ElicitationAction.Accept())
                }

                override suspend fun completeElicitation(
                    notification: CompleteElicitationNotification
                ) {
                    completionReceived.complete(notification.elicitationId)
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Completed", "END_TURN"), result)
        assertEquals(ElicitationId("url-complete-1"), completionReceived.await())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `request-scoped elicitation routed to session via in-flight request`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val clientOps = currentCoroutineContext().client
                    // Get the current incoming request ID — this is the prompt request from the client
                    val incomingRequestId = currentCoroutineContext().jsonRpcRequest.id
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Request(incomingRequestId),
                        mode = ElicitationMode.Form(
                            requestedSchema = ElicitationSchema(
                                properties = mapOf(
                                    "answer" to ElicitationPropertySchema.StringProperty(title = "Answer")
                                )
                            )
                        ),
                        message = "Request-scoped elicitation"
                    )
                    val response = clientOps.createElicitation(request)
                    val action = response.action as ElicitationAction.Accept
                    val answer = (action.content!!["answer"] as ElicitationContentValue.StringValue).value
                    emit(Event.SessionUpdateEvent(agentTextChunk("Answer: $answer")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse {
                    assertTrue(request.scope is ElicitationScope.Request, "Expected Request scope")
                    assertEquals("Request-scoped elicitation", request.message)
                    return CreateElicitationResponse(
                        action = ElicitationAction.Accept(
                            content = mapOf("answer" to ElicitationContentValue.StringValue("via request scope"))
                        )
                    )
                }
            }
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Answer: via request scope", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `request-scoped elicitation falls back to global handler`() = testWithProtocols { clientProtocol, agentProtocol ->
        val globalHandlerCalled = CompletableDeferred<CreateElicitationRequest>()

        val client = Client(
            protocol = clientProtocol,
            globalElicitationHandler = GlobalElicitationHandler { request ->
                globalHandlerCalled.complete(request)
                CreateElicitationResponse(
                    action = ElicitationAction.Accept(
                        content = mapOf("key" to ElicitationContentValue.StringValue("from global"))
                    )
                )
            }
        )

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val clientOps = currentCoroutineContext().client
                    // Use a request ID that doesn't correspond to any in-flight outgoing request
                    val request = CreateElicitationRequest(
                        scope = ElicitationScope.Request(RequestId.create(999999)),
                        mode = ElicitationMode.Form(
                            requestedSchema = ElicitationSchema(
                                properties = mapOf(
                                    "key" to ElicitationPropertySchema.StringProperty(title = "Key")
                                )
                            )
                        ),
                        message = "Global handler elicitation"
                    )
                    val response = clientOps.createElicitation(request)
                    val action = response.action as ElicitationAction.Accept
                    val value = (action.content!!["key"] as ElicitationContentValue.StringValue).value
                    emit(Event.SessionUpdateEvent(agentTextChunk("Got: $value")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(
                        form = ElicitationFormCapabilities()
                    )
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations(), ElicitationOperations {}
        }
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Got: from global", "END_TURN"), result)

        val receivedRequest = globalHandlerCalled.await()
        assertEquals("Global handler elicitation", receivedRequest.message)
        assertTrue(receivedRequest.scope is ElicitationScope.Request)
    }

//    @Test
//    fun `call agent extension from client`(): TestResult = testWithProtocols { clientProtocol, agentProtocol ->
//
//        val client = Client(
//            protocol = clientProtocol        )
//
//        val agentSupport = TestAgentSupport {
//            object : TestAgentSession(), TestAgentInterface {
//                override suspend fun readTextFile(path: String): String = "test content"
//            }
//        }
//        val agent = Agent(agentProtocol, agentSupport, handlerSideExtensions = listOf(TestAgentInterface))
//
//        client.initialize(
//            ClientInfo(
//                capabilities = ClientCapabilities(
//                    fs = FileSystemCapability(
//                        readTextFile = true,
//                        writeTextFile = true
//                    )
//                )
//            )
//        )
//        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { id,_ -> object : TestClientSessionOperations() }
//        val fileSystemOperations = session.remoteOperations(TestAgentInterface)
//        val fileResponse = fileSystemOperations.readTextFile("/test/file/path")
//        assertEquals("test content", fileResponse)
//    }
}