package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSupport
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.common.client
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

abstract class SimpleAgentTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    @Test
    fun initialization() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentInitialized = CompletableDeferred<ClientInfo>()
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        TODO("Not yet implemented")
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                agentInitialized.complete(clientInfo)
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val testVersion = 10
        val clientInfo = ClientInfo(protocolVersion = testVersion)
        val agentInfo = client.initialize(clientInfo)
        assertEquals(testVersion, agentInfo.protocolVersion)
    }

    @Test
    fun `simple prompt`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        TODO("Not yet implemented")
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(sessionParameters.cwd))))
                        delay(100)
                        for (block in content.filterIsInstance<ContentBlock.Text>()) {
                            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(block)))
                            delay(100)
                        }
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val testVersion = 10
        val clientInfo = ClientInfo(protocolVersion = testVersion)
        val agentInfo = client.initialize(clientInfo)
        val cwd = "/test/path"
        val newSession = client.newSession(SessionParameters(cwd, emptyList()))
        val responses = mutableListOf<String>()
        var result: PromptResponse? = null
        withTimeout(1000) {
            newSession.prompt(listOf(ContentBlock.Text("Message 1"), ContentBlock.Text("Message 2"))).collect { event ->
                when (event) {
                    is Event.PromptResponseEvent -> result = event.response
                    is Event.SessionUpdateEvent -> responses.add(((event.update as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text)
                }
            }
        }
        assertContentEquals(listOf("/test/path", "Message 1", "Message 2"), responses)
        assertEquals(result!!.stopReason, StopReason.END_TURN)
    }

    @Test
    fun `cancel simple prompt from client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        TODO("Not yet implemented")
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })

        val agentSideCeDeferred = CompletableDeferred<CancellationException>()
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> {
                        // forever wait
                        try {
                            awaitCancellation()
                        }
                        catch (ce: CancellationException) {
                            agentSideCeDeferred.complete(ce)
                            throw ce
                        }
                        catch (e: Exception) {
                            agentSideCeDeferred.completeExceptionally(e)
                            throw e
                        }
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val promptJob = launch {
            session.prompt(listOf(ContentBlock.Text("Test message"))).collect()
        }
        delay(500)
        val cancellationMessage = "Test cancellation"
        promptJob.cancel(CancellationException(cancellationMessage))
        val agentSideCe = withTimeout(1000) { agentSideCeDeferred.await() }
        assertEquals(cancellationMessage, agentSideCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `cancel simple prompt from client (in flow)`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        TODO("Not yet implemented")
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })

        val agentSideCeDeferred = CompletableDeferred<CancellationException>()
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        // forever wait
                        try {
                            awaitCancellation()
                        }
                        catch (ce: CancellationException) {
                            agentSideCeDeferred.complete(ce)
                            throw ce
                        }
                        catch (e: Exception) {
                            agentSideCeDeferred.completeExceptionally(e)
                            throw e
                        }
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        val promptJob = launch {
            session.prompt(listOf(ContentBlock.Text("Test message"))).collect()
        }
        delay(500)
        val cancellationMessage = "Test cancellation"
        promptJob.cancel(CancellationException(cancellationMessage))
        val agentSideCe = withTimeout(1000) { agentSideCeDeferred.await() }
        assertEquals(cancellationMessage, agentSideCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `permission request in prompt`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Selected(permissions.first().optionId), _meta)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        val permissionResponse = currentCoroutineContext().client.requestPermissions(
                            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("tool-id")), listOf(
                                PermissionOption(
                                    optionId = PermissionOptionId("approve"),
                                    name = "Approve",
                                    kind = PermissionOptionKind.ALLOW_ONCE
                                ),
                                PermissionOption(
                                    optionId = PermissionOptionId("reject"),
                                    name = "Reject",
                                    kind = PermissionOptionKind.REJECT_ONCE
                                )
                            )
                        )
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Permission response: ${permissionResponse.outcome}"))))
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val responses = mutableListOf<String>()
        val session = client.newSession(SessionParameters("/test/path", emptyList()))
        session.prompt(listOf(ContentBlock.Text("Test message"))).collect { event ->
            when (event) {
                is Event.PromptResponseEvent -> {
                }
                is Event.SessionUpdateEvent -> responses.add(((event.update as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text)
            }

        }

        assertContentEquals(listOf("Permission response: Selected(optionId=approve)"), responses)
    }

    @Test
    fun `permission request should be cancelled by prompt cancellation on client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val permissionResponseCeDeferred = CompletableDeferred<CancellationException>()
        val client = Client(protocol = clientProtocol, clientSupport = object : ClientSupport {
            override suspend fun createClientSession(
                session: ClientSession,
                _sessionResponseMeta: JsonElement?,
            ): ClientSessionOperations {
                return object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        try {
                            // wait forever
                            awaitCancellation()
                        }
                        catch (ce: CancellationException) {
                            permissionResponseCeDeferred.complete(ce)
                            throw ce
                        }
                        catch (e: Exception) {
                            permissionResponseCeDeferred.completeExceptionally(e)
                            throw e
                        }
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        TODO("Not yet implemented")
                    }
                }
            }
        })

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        try {
                            val permissionResponse = currentCoroutineContext().client.requestPermissions(
                                SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("tool-id")), listOf(
                                    PermissionOption(
                                        optionId = PermissionOptionId("approve"),
                                        name = "Approve",
                                        kind = PermissionOptionKind.ALLOW_ONCE
                                    ),
                                    PermissionOption(
                                        optionId = PermissionOptionId("reject"),
                                        name = "Reject",
                                        kind = PermissionOptionKind.REJECT_ONCE
                                    )
                                )
                            )
                            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Permission response: ${permissionResponse.outcome}"))))
                        }
                        catch (ce: CancellationException) {
                            println("Client cancellation exception caught")
                            throw ce
                        }
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val responses = mutableListOf<String>()
        val session = client.newSession(SessionParameters("/test/path", emptyList()))

        val promptJob = launch {
            session.prompt(listOf(ContentBlock.Text("Test message"))).collect()
        }

        delay(500)
        promptJob.cancel(CancellationException("Test cancellation"))
//        val permissionResponseCe = withTimeout(100000) { permissionResponseCeDeferred.await() }
        val permissionResponseCe = permissionResponseCeDeferred.await()
        assertEquals("Test cancellation", permissionResponseCe.message, "Cancellation exception should be propagated to agent")
    }


}