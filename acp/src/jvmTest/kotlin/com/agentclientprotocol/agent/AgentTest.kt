package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.rpc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession as V2AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations as V2ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters as V2SessionCreationParameters
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.model.v2.AvailableCommand as V2AvailableCommand
import com.agentclientprotocol.model.v2.ContentBlock as V2ContentBlock
import com.agentclientprotocol.model.v2.NewSessionRequest as V2NewSessionRequest
import com.agentclientprotocol.model.v2.SessionUpdate as V2SessionUpdate
import com.agentclientprotocol.model.v2.StatusAuthRequest as V2StatusAuthRequest
import com.agentclientprotocol.model.v2.StatusAuthResponse as V2StatusAuthResponse
import com.agentclientprotocol.model.v2.UserMessage as V2UserMessage

@OptIn(UnstableApi::class)
class AgentTest {

    @Test
    fun `initialize agent`() {
        withTestAgent { testAgent ->
            val (response) = testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))
            assertNotNull(response)
            assertTrue(testAgent.agentSupport.isInitialized)
        }
    }

    /** A v2 implementation: v2 types only, nothing shared with the v1 one. */
    private open class TestV2Support(private val status: Boolean? = null) : V2AgentSupport {
        var initializedWith: V2ClientInfo? = null

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession =
            TestV2Session(SessionId("v2-session"))

        override suspend fun initialize(clientInfo: V2ClientInfo): V2AgentInfo {
            initializedWith = clientInfo
            return V2AgentInfo(
                implementation = Implementation(name = "test-agent-v2", version = "1.0.0"),
                capabilities = com.agentclientprotocol.model.v2.AgentCapabilities(
                    session = com.agentclientprotocol.model.v2.SessionCapabilities(
                        prompt = com.agentclientprotocol.model.v2.PromptCapabilities(
                            image = com.agentclientprotocol.model.v2.PromptImageCapabilities()
                        )
                    ),
                    auth = status?.let { com.agentclientprotocol.model.v2.AgentAuthCapabilities(status = it) },
                ),
            )
        }
    }

    private fun v2InitializeRequest() = com.agentclientprotocol.model.v2.InitializeRequest(
        protocolVersion = PROTOCOL_VERSION_V2,
        info = Implementation(name = "test-client", version = "1.0.0"),
    )

    private open class StatusV1Support : AgentSupport {
        override suspend fun initialize(clientInfo: com.agentclientprotocol.client.ClientInfo): AgentInfo = AgentInfo(
            capabilities = AgentCapabilities(auth = AgentAuthCapabilities(status = true)),
        )

        override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
            error("auth/status does not need a session")
    }

    @Test
    fun `v1 agent answers repeated status queries with request and response metadata`() = runBlocking {
        val requestMeta = buildJsonObject { put("request", "v1") }
        val responseMeta = buildJsonObject { put("response", "v1") }
        val seen = mutableListOf<JsonElement?>()
        val support = object : StatusV1Support() {
            override suspend fun authStatus(_meta: JsonElement?): AuthStatusResponse {
                seen += _meta
                return AuthStatusResponse(authenticated = seen.size > 1, message = "Configured", _meta = responseMeta)
            }
        }
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        Agent(protocol, support)
        protocol.start()
        try {
            val initialized = transport.testRequest(AcpMethod.AgentMethods.V1.Initialize, InitializeRequest(LATEST_PROTOCOL_VERSION))
            assertEquals(true, assertNotNull(initialized.first).agentCapabilities.auth.status)

            assertEquals(
                AuthStatusResponse(false, "Configured", responseMeta),
                transport.testRequest(AcpMethod.AgentMethods.V1.AuthStatus, AuthStatusRequest(_meta = requestMeta)).first,
            )
            assertEquals(
                AuthStatusResponse(true, "Configured", responseMeta),
                transport.testRequest(AcpMethod.AgentMethods.V1.AuthStatus, AuthStatusRequest()).first,
            )
            assertEquals(listOf<JsonElement?>(requestMeta, null), seen)
        } finally {
            protocol.close()
        }
    }

    @Test
    fun `v1 agent without status hook returns method not found`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        Agent(protocol, StatusV1Support())
        protocol.start()
        try {
            transport.testRequest(AcpMethod.AgentMethods.V1.Initialize, InitializeRequest(LATEST_PROTOCOL_VERSION))
            val received = transport.fireTestRequest(AcpMethod.AgentMethods.V1.AuthStatus.methodName, buildJsonObject { })
            val error = assertNotNull(assertIs<JsonRpcErrorResponse>(received.last()).error)
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, error.code)
            assertTrue(error.message.contains("auth/status"))
        } finally {
            protocol.close()
        }
    }

    @Test
    fun `v2 agent answers repeated status queries with request and response metadata`() {
        val requestMeta = buildJsonObject { put("request", "v2") }
        val responseMeta = buildJsonObject { put("response", "v2") }
        val seen = mutableListOf<JsonElement?>()
        val support = object : TestV2Support(status = true) {
            override suspend fun authStatus(_meta: JsonElement?): V2StatusAuthResponse {
                seen += _meta
                return V2StatusAuthResponse(authenticated = seen.size > 1, message = "Configured", _meta = responseMeta)
            }
        }
        withTestV2Agent(support) { testAgent ->
            val (initialized) = testAgent.testInitialize(v2InitializeRequest())
            assertEquals(true, assertNotNull(initialized).capabilities.auth?.status)

            assertEquals(
                V2StatusAuthResponse(false, "Configured", responseMeta),
                testAgent.testRequest(AcpMethod.AgentMethods.V2.AuthStatus, V2StatusAuthRequest(_meta = requestMeta)).first,
            )
            assertEquals(
                V2StatusAuthResponse(true, "Configured", responseMeta),
                testAgent.testRequest(AcpMethod.AgentMethods.V2.AuthStatus, V2StatusAuthRequest()).first,
            )
            assertEquals(listOf<JsonElement?>(requestMeta, null), seen)
        }
    }

    @Test
    fun `v2 agent without status hook returns method not found`() {
        withTestV2Agent(TestV2Support(status = true)) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())
            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V2.AuthStatus.methodName,
                buildJsonObject { },
            )
            val error = assertNotNull(assertIs<JsonRpcErrorResponse>(received.last()).error)
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, error.code)
            assertTrue(error.message.contains("auth/status"))
        }
    }

    /**
     * An inert notification used to order a test against a handler that has no observable effect.
     *
     * Handlers are queued in wire order and run one at a time, because [Protocol]'s handler dispatcher
     * has `parallelism = 1`, so a barrier fired after the message under test cannot be handled before
     * it. Awaiting the barrier therefore proves the earlier handler has already run — the only way to
     * order against something like a `$/cancel_request` that is correctly ignored and so changes
     * nothing a test can observe.
     *
     * That parallelism is load-bearing here: raising it would not fail these tests, it would silently
     * turn them into races.
     */
    @Serializable
    private data class BarrierNotification(override val _meta: JsonElement? = null) :
        AcpNotification

    private object BarrierMethod :
        AcpMethod.AcpNotificationMethod<BarrierNotification>("test/barrier", BarrierNotification.serializer())

    /** Reads the next message from the agent as a `session/update`. */
    private suspend fun TestV2Agent.receiveSessionUpdate(): com.agentclientprotocol.model.v2.UpdateSessionNotification =
        transport.receiveTestMessages(1).single().sessionUpdate()

    /**
     * Reads [count] updates of a v2 turn, starting with those that arrived ahead of its receipt.
     *
     * A v2 turn sends each update as it is made, so the receipt can land anywhere among them.
     */
    private suspend fun TestV2Agent.receiveTurnUpdates(
        beforeReceipt: List<JsonRpcMessage>,
        count: Int,
    ): List<V2SessionUpdate> =
        (beforeReceipt + transport.receiveTestMessages(count - beforeReceipt.size)).map { it.sessionUpdate().update }

    /**
     * Decodes this message from the agent as a `session/update`.
     */
    private fun JsonRpcMessage.sessionUpdate(): com.agentclientprotocol.model.v2.UpdateSessionNotification =
        ACPJson.decodeFromJsonElement(
            AcpMethod.ClientMethods.V2.SessionUpdate.serializer,
            assertNotNull(assertIs<JsonRpcNotification>(this).params),
        )

    /** Completes once every notification fired before this call has been handled. */
    private fun TestV2Agent.barrier(): CompletableDeferred<Unit> {
        val reached = CompletableDeferred<Unit>()
        agent.protocol.setNotificationHandler(BarrierMethod) { reached.complete(Unit) }
        transport.fireTestNotification(
            BarrierMethod.methodName,
            ACPJson.encodeToJsonElement(BarrierMethod.serializer, BarrierNotification()),
        )
        return reached
    }

    @Test
    fun `a v2 agent speaks v2`() {
        withTestV2Agent(TestV2Support()) { testAgent ->
            val (response) = testAgent.testInitialize(v2InitializeRequest())
            assertEquals(PROTOCOL_VERSION_V2, assertNotNull(response).protocolVersion)
        }
    }

    @Test
    fun `a v1 agent answers a v2 request with v1`() {
        // Negotiation, not a refusal: the payload is read with v1 types — the only shape this Agent has
        // ever accepted — and the answer names the version it does speak, which the client then acts on.
        withTestAgent { testAgent ->
            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V2.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
                    v2InitializeRequest()
                )
            )
            val response = received.last() as JsonRpcSuccessResponse
            assertIs<JsonRpcSuccessResponse>(response, "a v1 agent negotiates instead of refusing")
            assertEquals(LATEST_PROTOCOL_VERSION, assertNotNull(response.result).jsonObject["protocolVersion"]?.jsonPrimitive?.int)
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.protocol.negotiatedProtocolVersion)
            // The v2-only fields of the request are not something v1 types can carry.
            assertEquals(PROTOCOL_VERSION_V2, assertNotNull(testAgent.agentSupport.initializedWith).protocolVersion)
        }
    }

    @Test
    fun `a v2 agent answers a request for a later version with v2`() {
        for (requested in listOf(PROTOCOL_VERSION_V2 + 1, 65535)) {
            withTestV2Agent(TestV2Support()) { testAgent ->
                val (response) = testAgent.testInitialize(v2InitializeRequest().copy(protocolVersion = requested))
                assertEquals(PROTOCOL_VERSION_V2, assertNotNull(response).protocolVersion, "requested $requested")
                assertEquals(PROTOCOL_VERSION_V2, testAgent.agent.protocol.negotiatedProtocolVersion)
                assertEquals(requested, testAgent.agent.clientInfo.await().protocolVersion)
            }
        }
    }

    @Test
    fun `a v2 agent refuses a lower version as unsupported`() {
        val v1Shaped = ACPJson.encodeToJsonElement(
            AcpMethod.AgentMethods.V1.Initialize.requestSerializer,
            InitializeRequest(LATEST_PROTOCOL_VERSION),
        )
        val v2Shaped = ACPJson.encodeToJsonElement(
            AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
            v2InitializeRequest().copy(protocolVersion = 0),
        )
        for ((requested, params) in listOf(LATEST_PROTOCOL_VERSION to v1Shaped, 0 to v2Shaped)) {
            withTestV2Agent(TestV2Support()) { testAgent ->
                val received = testAgent.transport.fireTestRequest(AcpMethod.AgentMethods.V2.Initialize.methodName, params)

                val error = assertNotNull((received.last() as JsonRpcErrorResponse).error)
                assertEquals(-32022, error.code, "requested $requested")
                assertEquals(
                    buildJsonObject {
                        put("requested", requested)
                        putJsonArray("supported") { add(PROTOCOL_VERSION_V2) }
                    },
                    error.data,
                )
                assertFalse(testAgent.agent.clientInfo.isCompleted)
                assertNull(testAgent.agent.protocol.negotiatedProtocolVersion)
            }
        }
    }

    @Test
    fun `a v2 agent serves session methods only after a successful initialize`() {
        withTestV2Agent(TestV2Support()) { testAgent ->
            suspend fun newSession(): JsonRpcMessage = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V2.SessionNew.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V2.SessionNew.requestSerializer,
                    com.agentclientprotocol.model.v2.NewSessionRequest(cwd = "."),
                ),
            ).last()

            assertEquals(-32600, assertIs<JsonRpcErrorResponse>(newSession()).error.code, "before initialize")

            // A version mismatch does not initialize the connection.
            val (refused) = testAgent.testInitialize(v2InitializeRequest().copy(protocolVersion = 0))
            assertNull(refused)
            assertEquals(-32600, assertIs<JsonRpcErrorResponse>(newSession()).error.code, "after a -32022 refusal")

            testAgent.testInitialize(v2InitializeRequest())
            assertIs<JsonRpcSuccessResponse>(newSession(), "after initialize")
        }
    }

    @Test
    fun `a v2 agent refuses a malformed version as invalid params`() {
        val malformed = listOf(null, JsonPrimitive(-1), JsonPrimitive(65536), JsonPrimitive("2"), JsonPrimitive(2.5))
        for (protocolVersion in malformed) {
            withTestV2Agent(TestV2Support()) { testAgent ->
                val params = buildJsonObject {
                    ACPJson.encodeToJsonElement(
                        AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
                        v2InitializeRequest(),
                    ).jsonObject.forEach { (key, value) -> if (key != "protocolVersion") put(key, value) }
                    if (protocolVersion != null) put("protocolVersion", protocolVersion)
                }
                val received = testAgent.transport.fireTestRequest(AcpMethod.AgentMethods.V2.Initialize.methodName, params)

                val error = assertNotNull((received.last() as JsonRpcErrorResponse).error)
                assertEquals(-32602, error.code, "protocolVersion $protocolVersion")
                assertFalse(testAgent.agent.clientInfo.isCompleted)
            }
        }
    }

    @Test
    fun `an unknown requested version is answered as v1`() {
        withTestAgent { testAgent ->
            // A client from the future sends a shape nobody here knows; v1 is the most permissive one.
            val (response) = testAgent.testInitialize(InitializeRequest(protocolVersion = 10))
            assertEquals(LATEST_PROTOCOL_VERSION, assertNotNull(response).protocolVersion)
        }
    }

    @Test
    fun `a raw v2 client exchanges v2 shaped initialize payloads`() {
        val v2Support = TestV2Support()
        withTestV2Agent(v2Support) { testAgent ->
            val clientInfo = testAgent.agent.clientInfo
            assertFalse(clientInfo.isCompleted)

            // Hand-written v2 params, so the assertions cannot pass just because both sides of the SDK
            // agree on a wrong shape.
            val params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION_V2)
                putJsonObject("info") {
                    put("name", "raw-v2-client")
                    put("version", "1.0.0")
                }
                putJsonObject("capabilities") {
                    putJsonObject("elicitation") {}
                }
            }
            val received = testAgent.transport.fireTestRequest(AcpMethod.AgentMethods.V2.Initialize.methodName, params)
            val result = assertNotNull((received.last() as JsonRpcSuccessResponse).result).jsonObject

            assertEquals(PROTOCOL_VERSION_V2, result["protocolVersion"]?.jsonPrimitive?.int)
            // v2 names, and none of the v1 ones.
            assertNotNull(result["info"], "a v2 response must carry `info`")
            assertNull(result["agentInfo"], "`agentInfo` is the v1 name and must not appear")
            assertNull(result["agentCapabilities"], "`agentCapabilities` is the v1 name and must not appear")
            // Capabilities are presence objects in v2, never booleans.
            val capabilities = assertNotNull(result["capabilities"]).jsonObject
            val session = assertNotNull(capabilities["session"]).jsonObject
            assertNotNull(assertNotNull(session["prompt"]).jsonObject["image"], "`{}` means supported")
            assertNull(capabilities["loadSession"], "v2 has no `loadSession`")

            // The implementation got the v2 types, and the agent reports them back.
            val seen = assertNotNull(v2Support.initializedWith)
            assertEquals(PROTOCOL_VERSION_V2, seen.protocolVersion)
            assertNotNull(seen.capabilities.elicitation)
            assertEquals("raw-v2-client", seen.implementation.name)
            assertEquals(seen, clientInfo.await())
        }
    }

    @Test
    fun `a v2 agent does not serve the v1 session methods`() {
        withTestV2Agent(TestV2Support()) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())

            // `session/load` does not exist in v2 at all — v2 replaced it with `session/resume` — so it
            // must be refused rather than served with v1 types.
            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.SessionLoad.methodName,
                buildJsonObject {
                    put("sessionId", "some-session")
                    put("cwd", ".")
                    put("mcpServers", buildJsonArray { })
                }
            )
            val error = assertNotNull((received.last() as JsonRpcErrorResponse).error)
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, error.code, "unexpected error: $error")
        }
    }

    /** A v2 session whose turn holds at a gate before reporting itself idle. */
    private class GatedV2Session(override val sessionId: SessionId) : V2AgentSession {
        val turnStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override fun prompt(
            content: List<com.agentclientprotocol.model.v2.ContentBlock>,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) = flow {
            emit(v2UserMessage("v2-user-1"))
            emit(
                com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate(
                    com.agentclientprotocol.model.v2.StateUpdate.Running()
                )
            )
            turnStarted.complete(Unit)
            release.await()
            emit(
                com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate(
                    com.agentclientprotocol.model.v2.StateUpdate.Idle(
                        stopReason = com.agentclientprotocol.model.v2.StopReason.EndTurn
                    )
                )
            )
        }
    }

    private class GatedV2Support : V2AgentSupport {
        val sessions = mutableListOf<GatedV2Session>()

        override suspend fun initialize(clientInfo: V2ClientInfo) = V2AgentInfo(
            implementation = Implementation(name = "test-agent-v2", version = "1.0.0")
        )

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = GatedV2Session(SessionId("v2-gated-${sessions.size + 1}")).also { sessions += it }
    }

    /**
     * A v2 session that inserts the user message, streams one agent message, and reports the turn as idle.
     */
    private class TestV2Session(override val sessionId: SessionId) : V2AgentSession {
        val cancelled = CompletableDeferred<Unit>()
        var promptSeen: List<com.agentclientprotocol.model.v2.ContentBlock>? = null

        override fun prompt(
            content: List<com.agentclientprotocol.model.v2.ContentBlock>,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) = flow {
            promptSeen = content
            emit(v2UserMessage("v2-user-1"))
            emit(
                com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk(
                    com.agentclientprotocol.model.v2.ContentChunk(
                        messageId = com.agentclientprotocol.model.MessageId("v2-message-1"),
                        content = com.agentclientprotocol.model.v2.ContentBlock.Text("hello from v2"),
                    )
                )
            )
            // v2 has no stop reason in the prompt response: the turn's outcome is an update.
            emit(
                com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate(
                    com.agentclientprotocol.model.v2.StateUpdate.Idle(
                        stopReason = com.agentclientprotocol.model.v2.StopReason.EndTurn
                    )
                )
            )
        }

        override suspend fun cancel() {
            cancelled.complete(Unit)
        }
    }

    private class SessionV2Support : V2AgentSupport {
        val sessions = mutableListOf<TestV2Session>()

        override suspend fun initialize(clientInfo: V2ClientInfo) =
            V2AgentInfo(implementation = Implementation(name = "test-agent-v2", version = "1.0.0"))

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession =
            TestV2Session(SessionId("v2-session-${sessions.size + 1}")).also { sessions += it }
    }

    @Test
    fun `a v2 connection runs a full prompt turn`() {
        val support = SessionV2Support()
        withTestV2Agent(support) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())

            val (newSession) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionNew,
                com.agentclientprotocol.model.v2.NewSessionRequest(cwd = ".")
            )
            val sessionId = assertNotNull(newSession).sessionId
            assertEquals(SessionId("v2-session-1"), sessionId)

            val (promptResponse, notificationsBeforeResponse) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionPrompt,
                com.agentclientprotocol.model.v2.PromptRequest(
                    sessionId = sessionId,
                    prompt = listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("hi")),
                )
            )
            assertEquals(MessageId("v2-user-1"), assertNotNull(promptResponse).messageId)

            // The updates went out as v2 `session/update` notifications, in order.
            val updates = testAgent.receiveTurnUpdates(notificationsBeforeResponse, 3)
            assertEquals(3, updates.size, "expected the user message, a message chunk and the idle state, got $updates")
            assertIs<V2SessionUpdate.UserMessage>(updates[0])
            val chunk = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk>(updates[1])
            assertEquals(
                "hello from v2",
                (chunk.chunk.content as com.agentclientprotocol.model.v2.ContentBlock.Text).text,
            )
            val state = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(updates[2])
            val idle = assertIs<com.agentclientprotocol.model.v2.StateUpdate.Idle>(state.state)
            assertEquals(com.agentclientprotocol.model.v2.StopReason.EndTurn, idle.stopReason)

            assertEquals(1, assertNotNull(support.sessions.single().promptSeen).size)
        }
    }

    @Test
    fun `a cancelled turn is reported by the implementation, not cut short by the SDK`() {
        // v2 requires the agent to keep reporting after `session/cancel`: updates MAY follow, and an idle
        // update with the `cancelled` stop reason MUST be the last one. So the SDK must not kill the turn.
        // https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation
        val cancelRequested = CompletableDeferred<Unit>()
        val turnStarted = CompletableDeferred<Unit>()
        val session = object : V2AgentSession {
            override val sessionId = SessionId("v2-session-1")

            override fun prompt(
                content: List<com.agentclientprotocol.model.v2.ContentBlock>,
                _meta: kotlinx.serialization.json.JsonElement?,
            ) = flow {
                emit(v2UserMessage("v2-user-1"))
                emit(
                    com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate(
                        com.agentclientprotocol.model.v2.StateUpdate.Running()
                    )
                )
                turnStarted.complete(Unit)
                cancelRequested.await()
                // Allowed after the cancel, as long as it comes before the idle report.
                emit(
                    com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk(
                        com.agentclientprotocol.model.v2.ContentChunk(
                            messageId = com.agentclientprotocol.model.MessageId("v2-message-1"),
                            content = com.agentclientprotocol.model.v2.ContentBlock.Text("winding down"),
                        )
                    )
                )
                emit(
                    com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate(
                        com.agentclientprotocol.model.v2.StateUpdate.Idle(
                            stopReason = com.agentclientprotocol.model.v2.StopReason.Cancelled
                        )
                    )
                )
            }

            override suspend fun cancel() {
                cancelRequested.complete(Unit)
            }
        }
        val support = object : V2AgentSupport {
            override suspend fun initialize(clientInfo: V2ClientInfo) =
                V2AgentInfo(implementation = Implementation(name = "test-agent-v2", version = "1.0.0"))

            override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = session
        }

        withTestV2Agent(support) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())
            val (newSession) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionNew,
                com.agentclientprotocol.model.v2.NewSessionRequest(cwd = ".")
            )
            val sessionId = assertNotNull(newSession).sessionId

            val (promptResponse, notificationsBeforeResponse) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionPrompt,
                com.agentclientprotocol.model.v2.PromptRequest(
                    sessionId = sessionId,
                    prompt = listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("hi")),
                )
            )
            assertNotNull(promptResponse, "the agent must accept the prompt before the turn finishes")
            val opening = testAgent.receiveTurnUpdates(notificationsBeforeResponse, 2)
            assertIs<V2SessionUpdate.UserMessage>(opening[0])
            assertIs<com.agentclientprotocol.model.v2.StateUpdate.Running>(
                assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(opening[1]).state
            )

            // Without waiting the cancel can arrive before the turn registers, and then the test would
            // pass whether or not the SDK cuts the turn short.
            withTimeout(5.seconds) { turnStarted.await() }
            testAgent.testNotification(
                AcpMethod.AgentMethods.V2.SessionCancel,
                com.agentclientprotocol.model.v2.CancelSessionNotification(sessionId)
            )

            val updates = testAgent.receiveTurnUpdates(emptyList(), 2)
            // The post-cancel chunk -> idle(cancelled), in that order.
            assertEquals(2, updates.size, "expected a chunk and the idle report, got $updates")
            assertIs<com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk>(updates[0])
            val last = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(updates[1])
            val idle = assertIs<com.agentclientprotocol.model.v2.StateUpdate.Idle>(last.state)
            assertEquals(com.agentclientprotocol.model.v2.StopReason.Cancelled, idle.stopReason)
        }
    }

    @Test
    fun `a v2 connection does not affect another connection speaking v1`() {
        // A `Protocol` is one connection: the ktor server builds it inside `webSocket { }`
        // (AcpKtorServerExtensions.kt:18-21), stdio builds one per pipe pair. Each connection gets the
        // agent for the version it speaks, while the support objects behind them are shared — that is the
        // realistic setup — and still one client's v2 must not touch another client's v1.
        val sharedV1Support = TestAgentSupport(echoPromptHandler)
        val sharedV2Support = SessionV2Support()

        runBlocking {
            val v2Transport = TestTransport(5.seconds)
            val v2Protocol = Protocol(this, v2Transport)
            V2Agent(v2Protocol, sharedV2Support)
            v2Protocol.start()

            val v1Transport = TestTransport(5.seconds)
            val v1Protocol = Protocol(this, v1Transport)
            val v1Agent = Agent(v1Protocol, sharedV1Support)
            v1Protocol.start()

            val v2Initialize = v2Transport.fireTestRequest(
                AcpMethod.AgentMethods.V2.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
                    v2InitializeRequest()
                )
            )
            assertNotNull((v2Initialize.last() as JsonRpcSuccessResponse).result)

            // The second connection is untouched: v1 initialize and a v1 session still work.
            val v1Initialize = v1Transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.Initialize.requestSerializer,
                    InitializeRequest(LATEST_PROTOCOL_VERSION)
                )
            )
            val v1Response = ACPJson.decodeFromJsonElement(
                AcpMethod.AgentMethods.V1.Initialize.responseSerializer,
                assertNotNull((v1Initialize.last() as JsonRpcSuccessResponse).result)
            )
            assertEquals(LATEST_PROTOCOL_VERSION, v1Response.protocolVersion)
            assertEquals(LATEST_PROTOCOL_VERSION, v1Agent.protocol.negotiatedProtocolVersion)

            val v1Session = v1Transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.SessionNew.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.SessionNew.requestSerializer,
                    NewSessionRequest(cwd = ".", mcpServers = emptyList())
                )
            )
            assertIs<JsonRpcSuccessResponse>(
                v1Session.last(),
                "a v1 session must still be servable while another connection speaks v2",
            )

            v2Protocol.close()
            v1Protocol.close()
        }
    }

    @Test
    fun `request cancellation works on a v2 connection`() {
        // `$/cancelRequest` belongs to the protocol, not to a version, so it is registered by
        // `Protocol.start()` and has to reach a request a v2 agent is still serving.
        val v2Support = object : V2AgentSupport {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()

            override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession =
                TestV2Session(SessionId("v2-session"))

            override suspend fun initialize(clientInfo: V2ClientInfo): V2AgentInfo {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
        }
        withTestV2Agent(v2Support) { testAgent ->
            val requestId = RequestId.create(1)
            val inFlight = async {
                testAgent.transport.fireTestRequest(
                    AcpMethod.AgentMethods.V2.Initialize.methodName,
                    ACPJson.encodeToJsonElement(
                        AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
                        v2InitializeRequest()
                    ),
                    requestId,
                )
            }
            v2Support.started.await()

            testAgent.transport.fireTestNotification(
                AcpMethod.MetaMethods.CancelRequest.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.MetaMethods.CancelRequest.serializer,
                    CancelRequestNotification(requestId)
                )
            )

            // Fails by timeout if the cancel notification was swallowed by the version switch.
            withTimeout(5.seconds) { v2Support.cancelled.await() }
            inFlight.cancel()
        }
    }

    @Test
    fun `a cancel for an already answered prompt leaves the turn running`() {
        /*
         * v2 answers session/prompt once the user message is inserted, so by the time the rest of the turn
         * flows the request is settled and a $/cancel_request for its id can only be redundant. Acting on it
         * would discard the rest of the turn, including the idle update that MUST end it.
         * https://agentclientprotocol.com/protocol/v2/draft/cancellation
         */
        val support = GatedV2Support()
        withTestV2Agent(support) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())
            val (newSession) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionNew,
                com.agentclientprotocol.model.v2.NewSessionRequest(cwd = "."),
            )
            val sessionId = assertNotNull(newSession).sessionId
            val session = support.sessions.single()

            val promptId = RequestId.create(99)
            val prompt = AcpMethod.AgentMethods.V2.SessionPrompt
            val promptReplies = testAgent.transport.fireTestRequest(
                prompt.methodName,
                ACPJson.encodeToJsonElement(
                    prompt.requestSerializer,
                    com.agentclientprotocol.model.v2.PromptRequest(
                        sessionId,
                        listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("hi")),
                    ),
                ),
                promptId,
            )
            assertIs<JsonRpcSuccessResponse>(promptReplies.last())
            withTimeout(5.seconds) { session.turnStarted.await() }
            // The turn's opening updates, consumed here so the tail read after the cancel is unambiguous.
            val opening = testAgent.receiveTurnUpdates(promptReplies.dropLast(1), 2)
            assertIs<V2SessionUpdate.UserMessage>(opening[0])
            assertIs<com.agentclientprotocol.model.v2.StateUpdate.Running>(
                assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(opening[1]).state
            )

            testAgent.transport.fireTestNotification(
                AcpMethod.MetaMethods.CancelRequest.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.MetaMethods.CancelRequest.serializer,
                    CancelRequestNotification(promptId),
                ),
            )
            // Firing a notification says nothing about when its handler runs, and an ignored cancel has no
            // observable effect to wait on, waiting for the barrier explicitly.
            withTimeout(5.seconds) { testAgent.barrier().await() }

            session.release.complete(Unit)
            val update = testAgent.receiveSessionUpdate()
            assertEquals(sessionId, update.sessionId)
            val idle = assertIs<com.agentclientprotocol.model.v2.StateUpdate.Idle>(
                assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(update.update).state
            )
            assertEquals(com.agentclientprotocol.model.v2.StopReason.EndTurn, idle.stopReason)
        }
    }

    @Test
    fun `session new reports the commands the session starts with`() {
        val commands = listOf(V2AvailableCommand("plan", "Make a plan"))
        val support = object : V2AgentSupport {
            override suspend fun initialize(clientInfo: V2ClientInfo) =
                V2AgentInfo(implementation = Implementation(name = "test-agent-v2", version = "1.0.0"))

            override suspend fun createSession(
                parameters: V2SessionCreationParameters,
                client: V2ClientOperations,
            ): V2AgentSession = object : V2AgentSession {
                override val sessionId = SessionId("v2-commands")
                override val availableCommands = commands

                override fun prompt(content: List<V2ContentBlock>, _meta: JsonElement?) = emptyFlow<V2SessionUpdate>()
            }
        }
        withTestV2Agent(support) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())
            val (newSession) = testAgent.testRequest(AcpMethod.AgentMethods.V2.SessionNew, V2NewSessionRequest(cwd = "."))
            assertEquals(commands, assertNotNull(newSession).availableCommands)
        }
    }

    @Test
    fun `negotiated version is retained for the connection`() {
        withTestAgent { testAgent ->
            assertNull(testAgent.agent.protocol.negotiatedProtocolVersion)

            testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.protocol.negotiatedProtocolVersion)
        }
    }

    @Test
    fun `a v2 agent refuses a v1 initialize and keeps serving v2`() {
        val v2Support = TestV2Support()
        withTestV2Agent(v2Support) { testAgent ->
            testAgent.testInitialize(v2InitializeRequest())

            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.Initialize.requestSerializer,
                    InitializeRequest(LATEST_PROTOCOL_VERSION),
                ),
            )

            assertEquals(-32022, assertNotNull((received.last() as JsonRpcErrorResponse).error).code)

            val (newSession) = testAgent.testRequest(
                AcpMethod.AgentMethods.V2.SessionNew,
                com.agentclientprotocol.model.v2.NewSessionRequest(cwd = "."),
            )
            assertNotNull(newSession, "the v2 handlers must remain installed")
        }
    }

    @Test
    fun `a repeated initialize keeps the negotiated version`() {
        withTestAgent { testAgent ->
            testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))

            // Another handshake, this time asking for v2: still answered as v1, and the connection does
            // not move.
            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V2.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V2.Initialize.requestSerializer,
                    v2InitializeRequest(),
                ),
            )
            val response = received.last() as JsonRpcSuccessResponse
            assertIs<JsonRpcSuccessResponse>(response)
            assertEquals(LATEST_PROTOCOL_VERSION, assertNotNull(response.result).jsonObject["protocolVersion"]?.jsonPrimitive?.int)
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.protocol.negotiatedProtocolVersion)

            val (newSession) = testAgent.testNewSession(NewSessionRequest(cwd = ".", mcpServers = emptyList()))
            assertNotNull(newSession, "the v1 handlers must remain installed")
        }
    }

    @Test
    fun `create new session`() {
        withInitializedTestAgent { testAgent ->
            val (response) = testAgent.testNewSession(NewSessionRequest(cwd = ".", mcpServers = emptyList()))
            assertNotNull(response)
            assertTrue(response.sessionId in testAgent.agentSupport.createdSessions)
        }
    }

    @Test
    fun `simple prompt turn`() {
        withTestAgentSession(promptHandler = echoPromptHandler) { testAgent, _ ->
            testAgent.simplePrompt("hello").let { (response, updates) ->
                assertEquals(StopReason.END_TURN, response.stopReason)
                assertEquals(1, updates.size)

                val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .map { (it.content as? ContentBlock.Text)?.text }
                    .firstOrNull()
                assertEquals("hello", message)
            }

            testAgent.simplePrompt("world").let { (response, updates) ->
                assertEquals(StopReason.END_TURN, response.stopReason)
                assertEquals(1, updates.size)

                val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .map { (it.content as? ContentBlock.Text)?.text }
                    .firstOrNull()
                assertEquals("world", message)
            }
        }
    }

    @Test
    fun `prompt cancellation`() {
        withTestAgentSession(promptHandler = delayEchoPromptHandler(2.seconds)) { testAgent, session ->
            val deferredResponse = async { testAgent.simplePrompt("hello").first }
            delay(1.seconds)
            testAgent.testCancel(CancelNotification(session.sessionId))

            val response = deferredResponse.await()
            assertEquals(StopReason.CANCELLED, response.stopReason)
        }
    }

    @Test
    fun `a cancel stops a prompt sent right after the previous turn was cancelled`() {
        /*
         * A client may send the next prompt as soon as it has cancelled the previous one, so the cancelled
         * turn can still be winding down while the next one runs. Ending the old turn must not unregister
         * the new one: a cancelled prompt MUST answer with the `cancelled` stop reason.
         * https://agentclientprotocol.com/protocol/prompt-turn#cancellation
         */
        val started = Channel<String>(Channel.UNLIMITED)
        val firstCancelled = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val promptHandler: PromptHandler = { content ->
            val text = assertIs<ContentBlock.Text>(content.single()).text
            started.send(text)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (text == "first") {
                    // Holds the cancelled turn open until the next prompt has been registered.
                    firstCancelled.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                }
                throw e
            }
        }
        withTestAgentSession(promptHandler = promptHandler) { testAgent, session ->
            testAgent.firePrompt(session.sessionId, "first", RequestId.create(101))
            assertEquals("first", withTimeout(5.seconds) { started.receive() })
            testAgent.testCancel(CancelNotification(session.sessionId))
            withTimeout(5.seconds) { firstCancelled.await() }

            testAgent.firePrompt(session.sessionId, "second", RequestId.create(102))
            assertEquals("second", withTimeout(5.seconds) { started.receive() })
            releaseFirst.complete(Unit)
            assertEquals(StopReason.CANCELLED, testAgent.receivePromptResponse(RequestId.create(101)).stopReason)

            testAgent.testCancel(CancelNotification(session.sessionId))
            assertEquals(StopReason.CANCELLED, testAgent.receivePromptResponse(RequestId.create(102)).stopReason)
        }
    }

    @Test
    fun `a rejected overlapping prompt leaves the running turn cancellable`() {
        val started = CompletableDeferred<Unit>()
        val promptHandler: PromptHandler = {
            started.complete(Unit)
            awaitCancellation()
        }
        withTestAgentSession(promptHandler = promptHandler) { testAgent, session ->
            testAgent.firePrompt(session.sessionId, "first", RequestId.create(201))
            withTimeout(5.seconds) { started.await() }

            testAgent.firePrompt(session.sessionId, "overlapping", RequestId.create(202))
            val message = assertNotNull(testAgent.transport.receiveTestMessages(1).singleOrNull(), "No reply to prompt 202")
            val rejection = assertIs<JsonRpcErrorResponse>(message)
            assertEquals(RequestId.create(202), rejection.id)
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, rejection.error.code)
            assertEquals("There is already active prompt execution", rejection.error.message)

            testAgent.testCancel(CancelNotification(session.sessionId))
            assertEquals(StopReason.CANCELLED, testAgent.receivePromptResponse(RequestId.create(201)).stopReason)
        }
    }

    @Test
    fun `a slow session cancel leaves the next prompt running`() {
        /*
         * AgentSession.cancel() may suspend until the turn's activities are cancelled, and the cancelled turn
         * can answer meanwhile. The client may then start the next turn before the session's cancel returns,
         * and that turn was never asked to stop: it must not be cancelled.
         */
        val started = Channel<String>(Channel.UNLIMITED)
        val stopFirst = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        val cancelReturned = CompletableDeferred<Unit>()
        val promptHandler: PromptHandler = { content ->
            val text = assertIs<ContentBlock.Text>(content.single()).text
            started.send(text)
            if (text == "first") {
                stopFirst.await()
                emit(Event.PromptResponseEvent(PromptResponse(StopReason.CANCELLED)))
            } else {
                /*
                 * Completed by the session's cancel() on the single handler thread, so this turn resumes only
                 * after the SDK's cancel() has finished with the active-prompt slot.
                 */
                cancelReturned.await()
                emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
            }
        }
        val cancelHandler: CancelHandler = {
            stopFirst.complete(Unit)
            releaseCancel.await()
            cancelReturned.complete(Unit)
        }
        withTestAgentSession(promptHandler = promptHandler, cancelHandler = cancelHandler) { testAgent, session ->
            testAgent.firePrompt(session.sessionId, "first", RequestId.create(301))
            assertEquals("first", withTimeout(5.seconds) { started.receive() })
            testAgent.testCancel(CancelNotification(session.sessionId))
            assertEquals(StopReason.CANCELLED, testAgent.receivePromptResponse(RequestId.create(301)).stopReason)

            testAgent.firePrompt(session.sessionId, "second", RequestId.create(302))
            assertEquals("second", withTimeout(5.seconds) { started.receive() })
            releaseCancel.complete(Unit)
            assertEquals(StopReason.END_TURN, testAgent.receivePromptResponse(RequestId.create(302)).stopReason)
        }
    }

    /**
     * Sends a `session/prompt` without waiting for its reply, so that several turns can be in flight.
     */
    private fun TestAgent.firePrompt(sessionId: SessionId, text: String, id: RequestId) {
        val prompt = AcpMethod.AgentMethods.V1.SessionPrompt
        transport.fireTestRequestWithoutReply(
            prompt.methodName,
            ACPJson.encodeToJsonElement(prompt.requestSerializer, PromptRequest(sessionId, listOf(ContentBlock.Text(text)))),
            id,
        )
    }

    /**
     * Reads the next message from the agent as the successful reply to the prompt [id].
     */
    private suspend fun TestAgent.receivePromptResponse(id: RequestId): PromptResponse {
        val message = assertNotNull(transport.receiveTestMessages(1).singleOrNull(), "No reply to prompt $id")
        val reply = assertIs<JsonRpcSuccessResponse>(message)
        assertEquals(id, reply.id)
        return ACPJson.decodeFromJsonElement(AcpMethod.AgentMethods.V1.SessionPrompt.responseSerializer, reply.result)
    }
}

@OptIn(UnstableApi::class)
private fun v2UserMessage(id: String) = V2SessionUpdate.UserMessage(V2UserMessage(MessageId(id)))
