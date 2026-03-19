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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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
    fun `request form elicitation from agent`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val response = currentCoroutineContext().client.requestElicitation(
                        mode = ElicitationMode.Form(
                            ElicitationSchema(
                                properties = mapOf("name" to ElicitationPropertySchema.StringValue()),
                                required = listOf("name")
                            )
                        ),
                        message = "Please enter your name"
                    )

                    val name = ((response.action as? ElicitationAction.Accept)
                        ?.content?.get("name") as? ElicitationContentValue.StringValue)?.value ?: "none"
                    emit(Event.SessionUpdateEvent(agentTextChunk("Name: $name")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(form = ElicitationFormCapabilities())
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations() {
                override suspend fun requestElicitation(
                    mode: ElicitationMode,
                    message: String,
                    _meta: JsonElement?
                ): ElicitationResponse {
                    assertTrue(mode is ElicitationMode.Form)
                    assertEquals("Please enter your name", message)
                    return ElicitationResponse(
                        ElicitationAction.Accept(
                            content = mapOf("name" to ElicitationContentValue.StringValue("Alice"))
                        )
                    )
                }
            }
        }

        val result = session.promptToList("Test")
        assertContentEquals(listOf("Name: Alice", "END_TURN"), result)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `url elicitation completion notification is routed to client session`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val completionDeferred = CompletableDeferred<ElicitationId>()

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    val response = currentCoroutineContext().client.requestElicitation(
                        mode = ElicitationMode.Url(
                            elicitationId = ElicitationId("elic_1"),
                            url = "https://example.com/auth"
                        ),
                        message = "Authenticate"
                    )

                    if (response.action is ElicitationAction.Accept) {
                        currentCoroutineContext().client.notifyElicitationComplete(ElicitationId("elic_1"))
                    }
                    emit(Event.SessionUpdateEvent(agentTextChunk("Prompt done")))
                }
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    elicitation = ElicitationCapabilities(url = ElicitationUrlCapabilities())
                )
            )
        )
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : TestClientSessionOperations() {
                override suspend fun requestElicitation(
                    mode: ElicitationMode,
                    message: String,
                    _meta: JsonElement?
                ): ElicitationResponse {
                    assertTrue(mode is ElicitationMode.Url)
                    assertEquals("Authenticate", message)
                    return ElicitationResponse(ElicitationAction.Accept())
                }

                override suspend fun notifyElicitationComplete(elicitationId: ElicitationId, _meta: JsonElement?) {
                    completionDeferred.complete(elicitationId)
                }
            }
        }

        val result = session.promptToList("Test")
        assertContentEquals(listOf("Prompt done", "END_TURN"), result)
        val completedId = withTimeout(1000) { completionDeferred.await() }
        assertEquals(ElicitationId("elic_1"), completedId)
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
