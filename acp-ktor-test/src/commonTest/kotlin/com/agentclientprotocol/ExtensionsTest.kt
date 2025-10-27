package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSupport
import com.agentclientprotocol.client.FileSystemOperations
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.common.remoteSessionOperations
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        TODO()
    }
}

class TestClientSupport(val createSessionFunc: suspend (ClientSession, JsonElement?) -> ClientSessionOperations) : ClientSupport {
    override suspend fun createClientSession(
        session: ClientSession,
        _sessionResponseMeta: JsonElement?,
    ): ClientSessionOperations {
        return createSessionFunc(session, _sessionResponseMeta)
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

class TestAgentSupport(val capabilities: AgentCapabilities = AgentCapabilities(), val createSessionFunc: suspend (SessionParameters) -> AgentSession) : AgentSupport {
    val agentInitialized = kotlinx.coroutines.CompletableDeferred<ClientInfo>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        agentInitialized.complete(clientInfo)
        return AgentInfo(clientInfo.protocolVersion, capabilities)
    }

    override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
        return createSessionFunc(sessionParameters)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionParameters,
    ): AgentSession {
        return createSessionFunc(sessionParameters)
    }
}


abstract class ExtensionsTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    @Test
    fun `exception when extension interface not supported on client`() = testWithProtocols { clientProtocol, agentProtocol ->

        val clientSupport = TestClientSupport { session, _sessionResponseMeta ->
            return@TestClientSupport TestClientSessionOperations()
        }
        val client = Client(protocol = clientProtocol, clientSupport = clientSupport, handlerSideExtensions = listOf(
            FileSystemOperations))

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val fileSystemOperations =
                        currentCoroutineContext().remoteSessionOperations(FileSystemOperations)
                    val readTextFileResponse = fileSystemOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }
            }
        }
        val agent = Agent(agentProtocol, agentSupport, remoteSideExtensions = listOf(FileSystemOperations))

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
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val exception = runCatching { session.prompt(textBlocks("Test")).collect { } }.exceptionOrNull()
        assertNotNull(exception, "Exception should be thrown")
        assertContains(exception.message!!, "does not implement extension type")
    }

    @Test
    fun `exception when extension not registered on client`() = testWithProtocols { clientProtocol, agentProtocol ->

        val clientSupport = TestClientSupport { session, _sessionResponseMeta ->
            return@TestClientSupport TestClientSessionOperations()
        }
        val client = Client(protocol = clientProtocol, clientSupport = clientSupport)

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val fileSystemOperations =
                        currentCoroutineContext().remoteSessionOperations(FileSystemOperations)
                    val readTextFileResponse = fileSystemOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }
            }
        }
        val agent = Agent(agentProtocol, agentSupport, remoteSideExtensions = listOf(FileSystemOperations))

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
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val exception = runCatching { session.prompt(textBlocks("Test")).collect { } }.exceptionOrNull()
        assertNotNull(exception, "Exception should be thrown")
        assertContains(exception.message!!, "Method not supported")
    }

    @Test
    fun `exception when extension not registered on agent`() = testWithProtocols { clientProtocol, agentProtocol ->

        val clientSupport = TestClientSupport { session, _sessionResponseMeta ->
            return@TestClientSupport object : TestClientSessionOperations(), FileSystemOperations {
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
        val client = Client(protocol = clientProtocol, clientSupport = clientSupport, handlerSideExtensions = listOf(
            FileSystemOperations)
        )

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val fileSystemOperations =
                        currentCoroutineContext().remoteSessionOperations(FileSystemOperations)
                    val readTextFileResponse = fileSystemOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }
            }
        }
        val agent = Agent(agentProtocol, agentSupport, /*remoteSideExtensions = listOf(FileSystemOperations)*/)

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
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val exception = runCatching { session.prompt(textBlocks("Test")).collect { } }.exceptionOrNull()
        assertNotNull(exception, "Exception should be thrown")
        assertContains(exception.message!!, "is either not registered")
    }

    @Test
    fun `call client extension from agent`() = testWithProtocols { clientProtocol, agentProtocol ->

        val clientSupport = TestClientSupport { session, _sessionResponseMeta ->
            return@TestClientSupport object : TestClientSessionOperations(), FileSystemOperations {
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
        val client = Client(protocol = clientProtocol, clientSupport = clientSupport, handlerSideExtensions = listOf(
            FileSystemOperations)
        )

        val agentSupport = TestAgentSupport {
            object : TestAgentSession() {
                override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
                    emit(Event.SessionUpdateEvent(agentTextChunk("Hello")))
                    val fileSystemOperations =
                        currentCoroutineContext().remoteSessionOperations(FileSystemOperations)
                    val readTextFileResponse = fileSystemOperations.fsReadTextFile("/test/path")
                    emit(Event.SessionUpdateEvent(agentTextChunk("File content: ${readTextFileResponse.content}")))
                }
            }
        }
        val agent = Agent(agentProtocol, agentSupport, remoteSideExtensions = listOf(FileSystemOperations))

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
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val result = session.promptToList("Test")
        assertContentEquals(listOf("Hello", "File content: test", "END_TURN"), result)
    }


}