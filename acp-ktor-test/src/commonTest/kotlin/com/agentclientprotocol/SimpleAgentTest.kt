package com.agentclientprotocol

import com.agentclientprotocol.agent.*
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.invoke
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

abstract class SimpleAgentTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    @Test
    fun initialization() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentInitialized = CompletableDeferred<ClientInfo>()
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                agentInitialized.complete(clientInfo)
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
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
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val testVersion = 10
        val clientInfo = ClientInfo(protocolVersion = testVersion)
        val agentInfo = client.initialize(clientInfo)
        val cwd = "/test/path"
        val newSession = client.newSession(SessionCreationParameters(cwd, emptyList())) { _, _ ->
            object : ClientSessionOperations {
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
    fun `prompt response and update have proper order`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("test-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(sessionParameters.cwd))))
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("text 1"))))
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("text 2"))))
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("text 3"))))
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        val testVersion = 10
        val clientInfo = ClientInfo(protocolVersion = testVersion)
        val agentInfo = client.initialize(clientInfo)
        val cwd = "/test/path"
        val newSession = client.newSession(SessionCreationParameters(cwd, emptyList())) { _, _ ->
            object : ClientSessionOperations {
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
        val responses = mutableListOf<String>()
        var result: PromptResponse? = null
        withTimeout(1000) {
            newSession.prompt(listOf()).collect { event ->
                when (event) {
                    is Event.PromptResponseEvent -> {
                        println( "Received prompt response: ${event.response}" )
                        result = event.response
                        responses.add(event.response.stopReason.toString())
                    }
                    is Event.SessionUpdateEvent -> {
                        println( "Received session update: ${(event.update as SessionUpdate.AgentMessageChunk).content}" )
                        responses.add(((event.update as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text)
                    }
                }
            }
        }
        assertContentEquals(listOf("/test/path", "text 1", "text 2", "text 3", "END_TURN"), responses)
        assertEquals(result!!.stopReason, StopReason.END_TURN)
    }

    @Test
    fun `cancel simple prompt from client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)

        val agentSideCeDeferred = CompletableDeferred<CancellationException>()
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : ClientSessionOperations {
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
        val client = Client(protocol = clientProtocol)

        val agentSideCeDeferred = CompletableDeferred<CancellationException>()
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ -> object : ClientSessionOperations {
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
        val client = Client(protocol = clientProtocol)

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
        val responses = mutableListOf<String>()
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCall: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    _meta: JsonElement?,
                ): RequestPermissionResponse {
                    return RequestPermissionResponse(
                        RequestPermissionOutcome.Selected(permissions.first().optionId),
                        _meta
                    )
                }

                override suspend fun notify(
                    notification: SessionUpdate,
                    _meta: JsonElement?,
                ) {
                    TODO("Not yet implemented")
                }
            }
        }
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
        val client = Client(protocol = clientProtocol)

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
        val responses = mutableListOf<String>()
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCall: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    _meta: JsonElement?,
                ): RequestPermissionResponse {
                    try {
                        // wait forever
                        awaitCancellation()
                    } catch (ce: CancellationException) {
                        permissionResponseCeDeferred.complete(ce)
                        throw ce
                    } catch (e: Exception) {
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

        val promptJob = launch {
            session.prompt(listOf(ContentBlock.Text("Test message"))).collect()
        }

        delay(500)
        promptJob.cancel(CancellationException("Test cancellation"))
//        val permissionResponseCe = withTimeout(100000) { permissionResponseCeDeferred.await() }
        val permissionResponseCe = permissionResponseCeDeferred.await()
        assertEquals("Test cancellation", permissionResponseCe.message, "Cancellation exception should be propagated to agent")
    }


    @Test
    fun `permission request should be cancelled by prompt cancellation on client and wait for graceful cancellation`() = testWithProtocols { clientProtocol, agentProtocol ->
        val permissionResponseCeDeferred = CompletableDeferred<CancellationException>()
        val client = Client(protocol = clientProtocol)

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
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
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
        val responses = mutableListOf<String>()
        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCall: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    _meta: JsonElement?,
                ): RequestPermissionResponse {
                    try {
                        // wait forever
                        awaitCancellation()
                    } catch (ce: CancellationException) {
                        permissionResponseCeDeferred.complete(ce)
                        throw ce
                    } catch (e: Exception) {
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

        val promptJob = launch {
            session.prompt(listOf(ContentBlock.Text("Test message"))).collect()
        }

        delay(500)
        promptJob.cancel(CancellationException("Test cancellation"))
//        val permissionResponseCe = withTimeout(100000) { permissionResponseCeDeferred.await() }
        val permissionResponseCe = permissionResponseCeDeferred.await()
        assertEquals("Test cancellation", permissionResponseCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `long session init on client and consequent session update should be properly handler`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationDeferred = CompletableDeferred<SessionUpdate>()

        val client = Client(protocol = clientProtocol)

        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                val id = SessionId("test-session-id")
                this@testWithProtocols.launch {
                    delay(200.milliseconds)
                    AcpMethod.ClientMethods.SessionUpdate(agentProtocol, SessionNotification(id, SessionUpdate.AvailableCommandsUpdate(listOf())))
                }

                return object : AgentSession {
                    override val sessionId: SessionId = id

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = flow {
                        TODO()
                    }
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })
        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val session = client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            // long session init
            delay(1000.milliseconds)
            return@newSession object : ClientSessionOperations {
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
                    notificationDeferred.complete(notification)
                }
            }
        }

        val notification = withTimeout(5000.milliseconds) {
            notificationDeferred.await()
        }
        assertTrue(notification is SessionUpdate.AvailableCommandsUpdate)
    }

    @Test
    fun `load session should process updates that arrive before load response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val sessionId = SessionId("loaded-session-id")
        val replayText = "replay-before-load-response"
        val notificationDeferred = CompletableDeferred<SessionUpdate>()

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = sessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(replayText))
                    )
                )

                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val loadedSession = withTimeout(2000.milliseconds) {
            client.loadSession(sessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        notificationDeferred.complete(notification)
                    }
                }
            }
        }

        assertEquals(sessionId, loadedSession.sessionId)

        val notification = withTimeout(2000.milliseconds) { notificationDeferred.await() }
        assertTrue(notification is SessionUpdate.AgentMessageChunk)
        val text = ((notification as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text
        assertEquals(replayText, text)
    }

    @Test
    fun `unknown session replay should not leak into later load after init cleanup`() = testWithProtocols { clientProtocol, agentProtocol ->
        val createdSessionId = SessionId("created-session-id")
        val unknownSessionId = SessionId("unknown-session-id")
        val staleReplay = "stale-replay-from-unknown-session"
        val freshReplay = "fresh-replay-from-load-session"
        val received = mutableListOf<String>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = unknownSessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(staleReplay))
                    )
                )

                return object : AgentSession {
                    override val sessionId: SessionId = createdSessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = sessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(freshReplay))
                    )
                )

                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        withTimeout(2000.milliseconds) {
            client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                    }
                }
            }
        }

        val loadedSession = withTimeout(2000.milliseconds) {
            client.loadSession(unknownSessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        val text = (notification as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text ?: return
                        received.add(text.text)
                    }
                }
            }
        }

        assertEquals(unknownSessionId, loadedSession.sessionId)
        assertContentEquals(listOf(freshReplay), received)
    }

    @Test
    fun `unknown session request during init should fail fast with not found`() = testWithProtocols { clientProtocol, agentProtocol ->
        val unknownSessionId = SessionId("unknown-request-session-id")
        val requestFailure = CompletableDeferred<Throwable>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                this@testWithProtocols.launch {
                    try {
                        AcpMethod.ClientMethods.FsReadTextFile(
                            agentProtocol,
                            ReadTextFileRequest(unknownSessionId, "/test/path", null, null, null)
                        )
                        requestFailure.complete(AssertionError("Unknown session request should fail"))
                    } catch (t: Throwable) {
                        requestFailure.complete(t)
                    }
                }

                delay(200.milliseconds)
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("known-session-id")

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        withTimeout(2000.milliseconds) {
            client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                    }
                }
            }
        }

        val failure = withTimeout(2000.milliseconds) { requestFailure.await() }
        val message = failure.message ?: failure.toString()
        assertTrue(
            message.contains("Session $unknownSessionId not found"),
            "Unexpected failure message: $message"
        )
    }

    @Test
    fun `unknown session replay should survive while another session still initializing`() = testWithProtocols { clientProtocol, agentProtocol ->
        val newSessionId = SessionId("parallel-new-session-id")
        val delayedLoadSessionId = SessionId("parallel-load-session-id")
        val replayText = "replay-during-parallel-init"
        val loadStarted = CompletableDeferred<Unit>()
        val allowLoadToComplete = CompletableDeferred<Unit>()
        val replayReceived = CompletableDeferred<String>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                loadStarted.await()
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = delayedLoadSessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(replayText))
                    )
                )
                return object : AgentSession {
                    override val sessionId: SessionId = newSessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                loadStarted.complete(Unit)
                allowLoadToComplete.await()
                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val loadedSessionDeferred = async {
            client.loadSession(delayedLoadSessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        val text = (notification as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text ?: return
                        if (!replayReceived.isCompleted) {
                            replayReceived.complete(text.text)
                        }
                    }
                }
            }
        }

        withTimeout(2000.milliseconds) { loadStarted.await() }

        val createdSession = withTimeout(2000.milliseconds) {
            client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                    }
                }
            }
        }
        assertEquals(newSessionId, createdSession.sessionId)

        allowLoadToComplete.complete(Unit)
        val loadedSession = withTimeout(2000.milliseconds) { loadedSessionDeferred.await() }

        assertEquals(delayedLoadSessionId, loadedSession.sessionId)
        assertEquals(replayText, withTimeout(2000.milliseconds) { replayReceived.await() })
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `fork session should process updates that arrive before fork response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val sourceSessionId = SessionId("source-session-id")
        val forkedSessionId = SessionId("forked-session-id")
        val replayText = "replay-before-fork-response"
        val notificationDeferred = CompletableDeferred<SessionUpdate>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(fork = SessionForkCapabilities())
                    )
                )
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun forkSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                assertEquals(sourceSessionId, sessionId)
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = forkedSessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(replayText))
                    )
                )
                return object : AgentSession {
                    override val sessionId: SessionId = forkedSessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val forkedSession = withTimeout(2000.milliseconds) {
            client.forkSession(sourceSessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        notificationDeferred.complete(notification)
                    }
                }
            }
        }

        assertEquals(forkedSessionId, forkedSession.sessionId)
        val notification = withTimeout(2000.milliseconds) { notificationDeferred.await() }
        assertTrue(notification is SessionUpdate.AgentMessageChunk)
        val text = ((notification as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text
        assertEquals(replayText, text)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `resume session should process updates that arrive before resume response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val resumedSessionId = SessionId("resumed-session-id")
        val replayText = "replay-before-resume-response"
        val notificationDeferred = CompletableDeferred<SessionUpdate>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities())
                    )
                )
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun resumeSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                assertEquals(resumedSessionId, sessionId)
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = sessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(replayText))
                    )
                )
                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val resumedSession = withTimeout(2000.milliseconds) {
            client.resumeSession(resumedSessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        notificationDeferred.complete(notification)
                    }
                }
            }
        }

        assertEquals(resumedSessionId, resumedSession.sessionId)
        val notification = withTimeout(2000.milliseconds) { notificationDeferred.await() }
        assertTrue(notification is SessionUpdate.AgentMessageChunk)
        val text = ((notification as SessionUpdate.AgentMessageChunk).content as ContentBlock.Text).text
        assertEquals(replayText, text)
    }

    @Test
    fun `failed session creation should cleanup holder and allow retry`() = testWithProtocols { clientProtocol, agentProtocol ->
        val sessionId = SessionId("retry-session-id")
        var loadAttempt = 0
        val firstNotification = CompletableDeferred<String>()
        val secondNotification = CompletableDeferred<String>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                loadAttempt += 1
                AcpMethod.ClientMethods.SessionUpdate(
                    agentProtocol,
                    SessionNotification(
                        sessionId = sessionId,
                        update = SessionUpdate.AgentMessageChunk(ContentBlock.Text("replay-attempt-$loadAttempt"))
                    )
                )
                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val firstFailure = runCatching {
            withTimeout(2000.milliseconds) {
                client.loadSession(sessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                    throw IllegalStateException("operations factory failed")
                }
            }
        }.exceptionOrNull()

        assertNotNull(firstFailure, "First attempt should fail")
        assertTrue(
            (firstFailure.message ?: "").contains("operations factory failed"),
            "Unexpected first failure: $firstFailure"
        )

        val loadedSession = withTimeout(2000.milliseconds) {
            client.loadSession(sessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        val text = (notification as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text ?: return
                        if (!firstNotification.isCompleted) {
                            firstNotification.complete(text.text)
                        } else if (!secondNotification.isCompleted) {
                            secondNotification.complete(text.text)
                        }
                    }
                }
            }
        }

        assertEquals(sessionId, loadedSession.sessionId)
        assertEquals("replay-attempt-2", withTimeout(2000.milliseconds) { firstNotification.await() })
        assertNull(withTimeoutOrNull(300) { secondNotification.await() }, "Retry should not receive stale replay from failed attempt")
    }

    @Test
    fun `new session should process updates that arrive before new response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val sessionId = SessionId("new-session-id")
        val replayTexts = listOf("replay-before-new-response-1", "replay-before-new-response-2")
        val notificationsDeferred = CompletableDeferred<List<String>>()
        val received = mutableListOf<String>()

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                for (text in replayTexts) {
                    AcpMethod.ClientMethods.SessionUpdate(
                        agentProtocol,
                        SessionNotification(
                            sessionId = sessionId,
                            update = SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))
                        )
                    )
                }

                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val createdSession = withTimeout(2000.milliseconds) {
            client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        if (notification !is SessionUpdate.AgentMessageChunk) return
                        val text = (notification.content as ContentBlock.Text).text
                        received.add(text)
                        if (received.size == replayTexts.size) {
                            notificationsDeferred.complete(received.toList())
                        }
                    }
                }
            }
        }

        assertEquals(sessionId, createdSession.sessionId)
        assertContentEquals(replayTexts, withTimeout(2000.milliseconds) { notificationsDeferred.await() })
    }

    @Test
    fun `create session should not lose events client slower agent (new session)`() = `create session should not lose events`(agentEmitDelay = 100.milliseconds, clientNotifyDelay = 200.milliseconds, isLoad = false)
    @Test
    fun `create session should not lose events agent slower client (new session)`() = `create session should not lose events`(agentEmitDelay = 200.milliseconds, clientNotifyDelay = 100.milliseconds, isLoad = false)
    @Test
    fun `create session should not lose events client slower agent (load session)`() = `create session should not lose events`(agentEmitDelay = 100.milliseconds, clientNotifyDelay = 200.milliseconds, isLoad = true)
    @Test
    fun `create session should not lose events agent slower client (load session)`() = `create session should not lose events`(agentEmitDelay = 200.milliseconds, clientNotifyDelay = 100.milliseconds, isLoad = true)
    @Test
    fun `load session should handle huge replay updates when client notify is slow`() = testWithProtocols { clientProtocol, agentProtocol ->
        val sessionId = SessionId("load-session-capacity-stress-id")
        val updatesCount = 1025
        val updates = (1..updatesCount).map { index ->
            "msg-$index"
        }
        val receivedMessages = mutableListOf<String>()
        val allReceived = CompletableDeferred<List<String>>()

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                for (message in updates) {
                    AcpMethod.ClientMethods.SessionUpdate(
                        agentProtocol,
                        SessionNotification(
                            sessionId,
                            SessionUpdate.AgentMessageChunk(ContentBlock.Text(message))
                        )
                    )
                }

                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val loadedSession = withTimeout(30000.milliseconds) {
            client.loadSession(sessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                object : ClientSessionOperations {
                    override suspend fun requestPermissions(
                        toolCall: SessionUpdate.ToolCallUpdate,
                        permissions: List<PermissionOption>,
                        _meta: JsonElement?,
                    ): RequestPermissionResponse {
                        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                    }

                    override suspend fun notify(
                        notification: SessionUpdate,
                        _meta: JsonElement?,
                    ) {
                        delay(2.milliseconds)
                        val text = (notification as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text ?: return
                        receivedMessages.add(text.text)
                        if (receivedMessages.size == updatesCount && !allReceived.isCompleted) {
                            allReceived.complete(receivedMessages.toList())
                        }
                    }
                }
            }
        }

        assertEquals(sessionId, loadedSession.sessionId)
        assertContentEquals(updates, withTimeout(30000.milliseconds) { allReceived.await() })
    }

    private fun `create session should not lose events`(agentEmitDelay: Duration, clientNotifyDelay: Duration, isLoad: Boolean = false) = testWithProtocols { clientProtocol, agentProtocol ->
        val sessionId = SessionId("slow-notify-load-session-id")
        val replayUpdates = (1..10).map { index ->
            if (index % 2 == 0) {
                SessionUpdate.AgentMessageChunk(ContentBlock.Text("agent-$index"))
            } else {
                SessionUpdate.UserMessageChunk(ContentBlock.Text("user-$index"))
            }
        }
        val postInitializeText = "post-initialize-agent"
        val expectedMessages = replayUpdates.mapNotNull { update ->
            when (update) {
                is SessionUpdate.AgentMessageChunk -> "agent:${(update.content as ContentBlock.Text).text}"
                is SessionUpdate.UserMessageChunk -> "user:${(update.content as ContentBlock.Text).text}"
                else -> null
            }
        }

        val receivedMessages = mutableListOf<String>()

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                return newSession(sessionId)
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                return newSession(sessionId)
            }

            private suspend fun newSession(sessionId: SessionId): AgentSession {
                for (update in replayUpdates) {
                    AcpMethod.ClientMethods.SessionUpdate(
                        agentProtocol,
                        SessionNotification(sessionId, update)
                    )
                    delay(agentEmitDelay)
                }

                return object : AgentSession {
                    override val sessionId: SessionId = sessionId

                    override suspend fun postInitialize() {
                        delay(agentEmitDelay)
                        currentCoroutineContext().client.notify(
                            SessionUpdate.AgentMessageChunk(ContentBlock.Text(postInitializeText))
                        )
                    }

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val postInitializeDeferred = CompletableDeferred<String>()
        withTimeout(8000.milliseconds) {
            fun createOperations(
                clientNotifyDelay: Duration,
                postInitializeText: String,
                postInitializeDeferred: CompletableDeferred<String>,
                receivedMessages: MutableList<String>,
            ): ClientSessionOperations = object : ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCall: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    _meta: JsonElement?,
                ): RequestPermissionResponse {
                    return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                }

                override suspend fun notify(
                    notification: SessionUpdate,
                    _meta: JsonElement?,
                ) {
                    delay(clientNotifyDelay)
                    val message = when (notification) {
                        is SessionUpdate.AgentMessageChunk -> "agent:${(notification.content as ContentBlock.Text).text}"
                        is SessionUpdate.UserMessageChunk -> "user:${(notification.content as ContentBlock.Text).text}"
                        else -> return
                    }
                    if (message.contains(postInitializeText)) {
                        postInitializeDeferred.complete(message)
                    } else {
                        receivedMessages.add(message)
                    }
                }
            }

            if (isLoad) {
                client.loadSession(sessionId, SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                    createOperations(clientNotifyDelay, postInitializeText, postInitializeDeferred, receivedMessages)
                }
            } else {
                client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
                    createOperations(clientNotifyDelay, postInitializeText, postInitializeDeferred, receivedMessages)
                }
            }
        }

        withTimeout(5000.milliseconds) {
            postInitializeDeferred.await()
        }
        assertContentEquals(expectedMessages, receivedMessages)
    }


    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions returns paginated results`() = testWithProtocols { clientProtocol, agentProtocol ->
        val testSessions = (1..25).map { i ->
            SessionInfo(
                sessionId = SessionId("session-$i"),
                cwd = "/test/path/$i",
                title = "Session $i"
            )
        }

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: kotlinx.serialization.json.JsonElement?): Sequence<SessionInfo> {
                return if (cwd != null) {
                    testSessions.filter { it.cwd.contains(cwd) }.asSequence()
                } else {
                    testSessions.asSequence()
                }
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val flow = client.listSessions()
        val allSessions = flow.toList()

        assertEquals(25, allSessions.size)
        assertEquals("session-1", allSessions.first().sessionId.value)
        assertEquals("session-25", allSessions.last().sessionId.value)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions with cwd filter`() = testWithProtocols { clientProtocol, agentProtocol ->
        val testSessions = listOf(
            SessionInfo(sessionId = SessionId("session-1"), cwd = "/project/a"),
            SessionInfo(sessionId = SessionId("session-2"), cwd = "/project/b"),
            SessionInfo(sessionId = SessionId("session-3"), cwd = "/project/a/subdir"),
            SessionInfo(sessionId = SessionId("session-4"), cwd = "/other/path"),
        )

        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: kotlinx.serialization.json.JsonElement?): Sequence<SessionInfo> {
                return if (cwd != null) {
                    testSessions.filter { it.cwd.contains(cwd) }.asSequence()
                } else {
                    testSessions.asSequence()
                }
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        val flow = client.listSessions(cwd = "/project/a")
        val filteredSessions = flow.toList()

        assertEquals(2, filteredSessions.size)
        assertTrue(filteredSessions.all { it.cwd.contains("/project/a") })
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions with partial iteration works correctly`() = testWithProtocols { clientProtocol, agentProtocol ->
        // Create 30 test sessions
        val allSessions = (1..30).map { i ->
            SessionInfo(sessionId = SessionId("session-$i"), cwd = "/project")
        }
        
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: JsonElement?): Sequence<SessionInfo> {
                // Return all sessions - the adapter will handle pagination
                return allSessions.asSequence()
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        // Take only 12 items from 30 available
        val flow = client.listSessions()
        val partialSessions = flow.take(12).toList()

        // Verify we got exactly 12 items even though 30 were available
        assertEquals(12, partialSessions.size)
        assertEquals("session-1", partialSessions.first().sessionId.value)
        assertEquals("session-12", partialSessions[11].sessionId.value)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions stops cleanly on early termination`() = testWithProtocols { clientProtocol, agentProtocol ->
        var resourcesAcquired = 0
        var resourcesReleased = 0
        
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: JsonElement?): Sequence<SessionInfo> {
                resourcesAcquired++
                
                return sequence {
                    try {
                        // Simulate paginated data
                        repeat(10) { i ->
                            yield(SessionInfo(sessionId = SessionId("session-$i"), cwd = "/project"))
                        }
                    } finally {
                        // Cleanup should happen even on early termination
                        resourcesReleased++
                    }
                }
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        // Take only 3 items and stop
        val flow = client.listSessions()
        val limitedSessions = flow.take(3).toList()

        assertEquals(3, limitedSessions.size)
        assertEquals("session-0", limitedSessions.first().sessionId.value)
        assertEquals("session-2", limitedSessions.last().sessionId.value)
        
        // Resources should be properly cleaned up
        assertEquals(resourcesAcquired, resourcesReleased)
        assertTrue(resourcesReleased > 0)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions does not fetch second page when only first is consumed`() = testWithProtocols { clientProtocol, agentProtocol ->
        var itemsGenerated = 0
        
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: JsonElement?): Sequence<SessionInfo> {
                // The Agent's setPaginatedRequestHandler with batchSize=10 will consume
                // items from this sequence in batches of 10
                return sequence {
                    for (i in 1..20) {
                        itemsGenerated++
                        yield(SessionInfo(sessionId = SessionId("session-$i"), cwd = "/project"))
                    }
                }
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        // Take only 5 items - the flow should lazily fetch data
        val flow = client.listSessions()
        val limitedSessions = flow.take(5).toList()

        assertEquals(5, limitedSessions.size)
        assertEquals("session-1", limitedSessions.first().sessionId.value)
        assertEquals("session-5", limitedSessions.last().sessionId.value)
        
        // Due to batchSize=10 in Agent.setPaginatedRequestHandler, 
        // the sequence should generate approximately 10 items for the first batch
        // (might be 11 due to iterator checking for next)
        assertTrue(itemsGenerated <= 11, "Should have generated only first batch (~10 items), but generated $itemsGenerated")
        assertTrue(itemsGenerated < 15, "Should not have fetched second batch")
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `list sessions is truly lazy and cold`() = testWithProtocols { clientProtocol, agentProtocol ->
        var fetchStarted = false
        
        val client = Client(protocol = clientProtocol)
        val agent = Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())
                    )
                )
            }

            override suspend fun listSessions(cwd: String?, _meta: JsonElement?): Sequence<SessionInfo> {
                fetchStarted = true
                return sequenceOf(
                    SessionInfo(sessionId = SessionId("session-1"), cwd = "/project")
                )
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not yet implemented")
            }

            override suspend fun loadSession(
                sessionId: SessionId,
                sessionParameters: SessionCreationParameters,
            ): AgentSession {
                TODO("Not yet implemented")
            }
        })

        client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))

        // Create flow but don't collect yet
        val flow = client.listSessions()
        
        // Flow is cold - should not start fetching until collection begins
        assertFalse(fetchStarted, "Flow should not start fetching before collection")
        
        // Now start collecting
        val sessions = flow.toList()
        
        // Now fetching should have started
        assertTrue(fetchStarted, "Flow should start fetching when collection begins")
        assertEquals(1, sessions.size)
    }

    @Test
    @OptIn(UnstableApi::class)
    fun `session ready notifies available commands`() = testWithProtocols { clientProtocol, agentProtocol ->
        val readyUpdate = CompletableDeferred<SessionUpdate>()
        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion)
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                return object : AgentSession {
                    override val sessionId: SessionId = SessionId("ready-session-id")

                    override suspend fun postInitialize() {
                        currentCoroutineContext().client.notify(
                            SessionUpdate.AvailableCommandsUpdate(
                                listOf(AvailableCommand("help", "Show available commands", AvailableCommandInput.Unstructured("topic")))
                            )
                        )
                    }

                    override suspend fun prompt(
                        content: List<ContentBlock>,
                        _meta: JsonElement?,
                    ): Flow<Event> = emptyFlow()
                }
            }
        })

        client.initialize(ClientInfo(protocolVersion = 10))
        client.newSession(SessionCreationParameters("/test/path", emptyList())) { _, _ ->
            object : ClientSessionOperations {
                override suspend fun requestPermissions(
                    toolCall: SessionUpdate.ToolCallUpdate,
                    permissions: List<PermissionOption>,
                    _meta: JsonElement?,
                ): RequestPermissionResponse {
                    return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                }

                override suspend fun notify(
                    notification: SessionUpdate,
                    _meta: JsonElement?,
                ) {
                    readyUpdate.complete(notification)
                }
            }
        }

        val update = withTimeout(1000) { readyUpdate.await() }
        assertTrue(update is SessionUpdate.AvailableCommandsUpdate)
        val command = (update as SessionUpdate.AvailableCommandsUpdate).availableCommands.single()
        assertEquals("help", command.name)
    }

}
