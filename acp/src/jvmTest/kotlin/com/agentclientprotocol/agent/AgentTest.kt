package com.agentclientprotocol.agent

import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession as V2AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations as V2ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters as V2SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    private class TestV2Support : V2AgentSupport {
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
                    )
                ),
            )
        }
    }

    private fun v2InitializeRequest() = com.agentclientprotocol.model.v2.InitializeRequest(
        protocolVersion = PROTOCOL_VERSION_V2,
        info = Implementation(name = "test-client", version = "1.0.0"),
    )

    @Test
    fun `a v2 agent speaks v2`() {
        withTestV2Agent(TestV2Support()) { testAgent ->
            val (response) = testAgent.testInitialize(v2InitializeRequest())
            assertEquals(PROTOCOL_VERSION_V2, assertNotNull(response).protocolVersion)
            assertEquals(PROTOCOL_VERSION_V2, testAgent.agent.negotiatedProtocolVersion)
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
            val response = received.last() as JsonRpcResponse
            assertNull(response.error, "a v1 agent negotiates instead of refusing")
            assertEquals(LATEST_PROTOCOL_VERSION, assertNotNull(response.result).jsonObject["protocolVersion"]?.jsonPrimitive?.int)
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.negotiatedProtocolVersion)
            // The v2-only fields of the request are not something v1 types can carry.
            assertEquals(PROTOCOL_VERSION_V2, assertNotNull(testAgent.agentSupport.initializedWith).protocolVersion)
        }
    }

    @Test
    fun `a v2 agent refuses a v1 request`() {
        withTestV2Agent(TestV2Support()) { testAgent ->
            val received = testAgent.transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.Initialize.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.Initialize.requestSerializer,
                    InitializeRequest(LATEST_PROTOCOL_VERSION),
                ),
            )
            val error = assertNotNull((received.last() as JsonRpcResponse).error)
            assertTrue(
                error.message.contains("Protocol version 1 is not supported by this agent"),
                "unexpected message: ${error.message}",
            )
            assertFailsWith<IllegalStateException> { testAgent.agent.negotiatedProtocolVersion }
        }
    }

    @Test
    fun `declaring no version this agent speaks refuses a v1 request`() = withTestAgent(
        supportedProtocolVersions = setOf(PROTOCOL_VERSION_V2)
    ) { testAgent ->
        val received = testAgent.transport.fireTestRequest(
            AcpMethod.AgentMethods.V1.Initialize.methodName,
            ACPJson.encodeToJsonElement(
                AcpMethod.AgentMethods.V1.Initialize.requestSerializer,
                InitializeRequest(LATEST_PROTOCOL_VERSION),
            ),
        )

        val error = assertNotNull((received.last() as JsonRpcResponse).error)
        assertTrue(
            error.message.contains("supportedProtocolVersions"),
            "unexpected message: ${error.message}",
        )
        assertFailsWith<IllegalStateException> { testAgent.agent.negotiatedProtocolVersion }
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
            val result = assertNotNull((received.last() as JsonRpcResponse).result).jsonObject

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
            assertEquals(seen, testAgent.agent.clientInfo)
            assertEquals(PROTOCOL_VERSION_V2, testAgent.agent.negotiatedProtocolVersion)
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
            val error = assertNotNull((received.last() as JsonRpcResponse).error)
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, error.code, "unexpected error: $error")
        }
    }

    /** A v2 session that streams one agent message and then reports the turn as idle. */
    private class TestV2Session(override val sessionId: SessionId) : V2AgentSession {
        val cancelled = CompletableDeferred<Unit>()
        var promptSeen: List<com.agentclientprotocol.model.v2.ContentBlock>? = null

        override fun prompt(
            content: List<com.agentclientprotocol.model.v2.ContentBlock>,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) = flow {
            promptSeen = content
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
            assertNotNull(promptResponse, "a v2 prompt response is an empty object, but it must arrive")
            assertEquals(emptyList(), notificationsBeforeResponse, "updates must follow the prompt response")

            // The updates went out as v2 `session/update` notifications, in order.
            val updates = testAgent.transport.receiveTestMessages(2)
                .filterIsInstance<com.agentclientprotocol.rpc.JsonRpcNotification>()
                .filter { it.method == AcpMethod.ClientMethods.V2.SessionUpdate.methodName }
                .mapNotNull { it.params }
                .map {
                    ACPJson.decodeFromJsonElement(AcpMethod.ClientMethods.V2.SessionUpdate.serializer, it).update
                }
            assertEquals(2, updates.size, "expected a message chunk and the idle state, got $updates")
            val chunk = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk>(updates[0])
            assertEquals(
                "hello from v2",
                (chunk.chunk.content as com.agentclientprotocol.model.v2.ContentBlock.Text).text,
            )
            val state = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(updates[1])
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
            assertEquals(emptyList(), notificationsBeforeResponse, "updates must follow the prompt response")

            // Without waiting the cancel can arrive before the turn registers, and then the test would
            // pass whether or not the SDK cuts the turn short.
            withTimeout(5.seconds) { turnStarted.await() }
            testAgent.testNotification(
                AcpMethod.AgentMethods.V2.SessionCancel,
                com.agentclientprotocol.model.v2.CancelSessionNotification(sessionId)
            )

            val updates = testAgent.transport.receiveTestMessages(3)
                .filterIsInstance<com.agentclientprotocol.rpc.JsonRpcNotification>()
                .filter { it.method == AcpMethod.ClientMethods.V2.SessionUpdate.methodName }
                .mapNotNull { it.params }
                .map {
                    ACPJson.decodeFromJsonElement(AcpMethod.ClientMethods.V2.SessionUpdate.serializer, it).update
                }
            // running -> the post-cancel chunk -> idle(cancelled), in that order.
            assertEquals(3, updates.size, "expected running, a chunk and the idle report, got $updates")
            assertIs<com.agentclientprotocol.model.v2.SessionUpdate.AgentMessageChunk>(updates[1])
            val last = assertIs<com.agentclientprotocol.model.v2.SessionUpdate.StateUpdate>(updates[2])
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
            assertNotNull((v2Initialize.last() as JsonRpcResponse).result)

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
                assertNotNull((v1Initialize.last() as JsonRpcResponse).result)
            )
            assertEquals(LATEST_PROTOCOL_VERSION, v1Response.protocolVersion)
            assertEquals(LATEST_PROTOCOL_VERSION, v1Agent.negotiatedProtocolVersion)

            val v1Session = v1Transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.SessionNew.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.SessionNew.requestSerializer,
                    NewSessionRequest(cwd = ".", mcpServers = emptyList())
                )
            )
            assertNull(
                (v1Session.last() as JsonRpcResponse).error,
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
                    CancelRequestNotification(requestId, message = "cancelled by the test")
                )
            )

            // Fails by timeout if the cancel notification was swallowed by the version switch.
            withTimeout(5.seconds) { v2Support.cancelled.await() }
            inFlight.cancel()
        }
    }

    @Test
    fun `negotiated version is retained for the connection`() {
        withTestAgent { testAgent ->
            assertFailsWith<IllegalStateException> { testAgent.agent.negotiatedProtocolVersion }

            testAgent.testInitialize(InitializeRequest(LATEST_PROTOCOL_VERSION))
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.negotiatedProtocolVersion)
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

            val error = assertNotNull((received.last() as JsonRpcResponse).error)
            assertTrue(error.message.contains("Protocol version 1 is not supported by this agent"))
            assertEquals(PROTOCOL_VERSION_V2, testAgent.agent.negotiatedProtocolVersion)

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
            val response = received.last() as JsonRpcResponse
            assertNull(response.error)
            assertEquals(LATEST_PROTOCOL_VERSION, assertNotNull(response.result).jsonObject["protocolVersion"]?.jsonPrimitive?.int)
            assertEquals(LATEST_PROTOCOL_VERSION, testAgent.agent.negotiatedProtocolVersion)

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
}
