package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.client.*
import com.agentclientprotocol.common.*
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.*
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



    @Test
    fun `authenticate returns null when agent returns null`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)

        val agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse? {
                // Return null to simulate {"jsonrpc":"2.0","id":3,"result":null} response
                return null
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                return TestAgentSession()
            }

            override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
                return TestAgentSession()
            }
        }
        Agent(agentProtocol, agentSupport)

        client.initialize(ClientInfo())

        // This should correctly handle the null response without throwing an exception
        val result = client.authenticate(AuthMethodId("test-auth"))
        assertNull(result, "authenticate should return null when agent returns null")
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