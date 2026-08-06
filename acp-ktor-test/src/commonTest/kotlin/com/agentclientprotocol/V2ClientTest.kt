package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.v2.AgentSession as V2AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations as V2ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters as V2SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.client.v2.Client as V2Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.client.v2.ClientSessionOperations as V2ClientSessionOperations
import com.agentclientprotocol.client.v2.ElicitationHandler as V2ElicitationHandler
import com.agentclientprotocol.client.v2.ClientSession as V2ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.DisableProviderResponse
import com.agentclientprotocol.model.v2.ListProvidersResponse
import com.agentclientprotocol.model.v2.LlmProtocol
import com.agentclientprotocol.model.v2.ProviderCurrentConfig
import com.agentclientprotocol.model.v2.ProviderId
import com.agentclientprotocol.model.v2.ProviderInfo
import com.agentclientprotocol.model.v2.SetProviderResponse
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import com.agentclientprotocol.model.v2.ElicitationAction
import com.agentclientprotocol.model.v2.ElicitationMode
import com.agentclientprotocol.model.v2.ElicitationPropertySchema
import com.agentclientprotocol.model.v2.ElicitationSchema
import com.agentclientprotocol.model.v2.DeleteSessionResponse
import com.agentclientprotocol.model.v2.ListSessionsResponse
import com.agentclientprotocol.model.v2.LoginAuthResponse
import com.agentclientprotocol.model.v2.LogoutAuthResponse
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigKind
import com.agentclientprotocol.model.v2.SessionConfigOptionCategory
import com.agentclientprotocol.model.v2.SessionConfigSelectOptions
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionInfo
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.PermissionOption
import com.agentclientprotocol.model.v2.PermissionOptionKind
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.RequestPermissionSubject
import com.agentclientprotocol.model.v2.ToolCallUpdate
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The v2 client against the v2 agent, over a real transport.
 *
 * Everything here goes through the SDK on both sides, unlike the raw-JSON tests in `acp`, so it also
 * checks that the two halves agree on the wire.
 */
@OptIn(UnstableApi::class)
abstract class V2ClientTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    private fun v2ClientInfo() = V2ClientInfo(
        protocolVersion = PROTOCOL_VERSION_V2,
        implementation = Implementation(name = "test-client", version = "1.0.0"),
    )

    /** Asks for permission before "running a tool", then reports how the turn ended. */
    private class PermissionV2Session(
        override val sessionId: SessionId,
        private val client: V2ClientOperations,
    ) : V2AgentSession {
        val asked = CompletableDeferred<Unit>()
        var outcome: RequestPermissionOutcome? = null
        var failure: Throwable? = null

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
            emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
            // The lifecycle says to report that the turn is waiting on the user while the request is out.
            emit(SessionUpdate.StateUpdate(StateUpdate.RequiresAction()))
            asked.complete(Unit)
            val response = try {
                client.requestPermission(
                    title = "Run read_file?",
                    description = "the agent wants to read a file",
                    options = listOf(
                        PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.AllowOnce),
                        PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.RejectOnce),
                    ),
                    subject = RequestPermissionSubject.ToolCall(
                        ToolCallUpdate(
                            toolCallId = ToolCallId("call-1"),
                            title = MaybeUndefined.Value("read_file"),
                        )
                    ),
                )
            } catch (t: Throwable) {
                failure = t
                emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.Refusal)))
                return@flow
            }
            outcome = response.outcome
            emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
            val stopReason = when (val o = response.outcome) {
                is RequestPermissionOutcome.Selected ->
                    if (o.optionId == PermissionOptionId("allow")) StopReason.EndTurn else StopReason.Refusal
                RequestPermissionOutcome.Cancelled -> StopReason.Cancelled
                // An outcome we do not understand MUST NOT be treated as approval.
                is RequestPermissionOutcome.Unknown -> StopReason.Refusal
            }
            emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = stopReason)))
        }

        override suspend fun cancel() {}
    }

    private class PermissionSupport : V2AgentSupport {
        val sessions = mutableListOf<PermissionV2Session>()

        override suspend fun initialize(clientInfo: V2ClientInfo) =
            V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = PermissionV2Session(SessionId("v2-${sessions.size + 1}"), client)
            .also { sessions += it }
    }

    /** Answers permission requests with a fixed option, and records what it was asked. */
    private class ScriptedPermissions(private val optionId: String?) : V2ClientSessionOperations {
        var seen: RequestPermissionRequest? = null
        val asked = CompletableDeferred<Unit>()

        override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
            seen = request
            asked.complete(Unit)
            if (optionId == null) awaitCancellation()
            return RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
        }
    }

    /** A session that carries one config option, for resume and set_config_option. */
    private class ConfigurableV2Session(override val sessionId: SessionId) : V2AgentSession {
        var lastSet: Pair<SessionConfigId, SessionConfigValueId>? = null

        override val configOptions: List<SessionConfigOption>
            get() = listOf(
                SessionConfigOption(
                    configId = SessionConfigId("mode"),
                    name = "Mode",
                    category = SessionConfigOptionCategory.Mode,
                    kind = SessionConfigKind.Select(
                        currentValue = SessionConfigValueId("architect"),
                        options = SessionConfigSelectOptions.Ungrouped(
                            listOf(
                                SessionConfigSelectOption(
                                    value = SessionConfigValueId("architect"),
                                    name = "Architect",
                                )
                            )
                        ),
                    ),
                )
            )

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow<SessionUpdate> {}

        override suspend fun setConfigOption(
            configId: SessionConfigId,
            value: SessionConfigOptionValue,
            _meta: JsonElement?,
        ): List<SessionConfigOption> {
            lastSet = configId to (value as SessionConfigOptionValue.Id).value
            return configOptions
        }
    }

    /** Streams one message chunk and then reports the turn as idle, which is how a v2 turn ends. */
    private class EchoV2Session(override val sessionId: SessionId) : V2AgentSession {
        val cancelRequested = CompletableDeferred<Unit>()
        val turnStarted = CompletableDeferred<Unit>()
        var hangUntilCancelled: Boolean = false

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
            val text = (content.first() as ContentBlock.Text).text
            emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
            turnStarted.complete(Unit)
            if (hangUntilCancelled) {
                cancelRequested.await()
                emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.Cancelled)))
                return@flow
            }
            emit(
                SessionUpdate.AgentMessageChunk(
                    ContentChunk(messageId = MessageId("m1"), content = ContentBlock.Text("echo: $text"))
                )
            )
            emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
        }

        override suspend fun cancel() {
            cancelRequested.complete(Unit)
        }
    }

    private class V2Support(private val hangUntilCancelled: Boolean = false) : V2AgentSupport {
        val sessions = mutableListOf<EchoV2Session>()
        var initializeCalls = 0

        override suspend fun initialize(clientInfo: V2ClientInfo): V2AgentInfo {
            initializeCalls++
            return V2AgentInfo(
                implementation = Implementation(name = "test-agent", version = "1.0.0"),
            )
        }

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = EchoV2Session(SessionId("v2-${sessions.size + 1}"))
            .also { it.hangUntilCancelled = hangUntilCancelled; sessions += it }
    }

    /** A v1 implementation, for the tests that put a v1 agent in front of a v2 client. */
    private fun v1Support(onInitialize: () -> Unit = {}): AgentSupport = object : AgentSupport {
        override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
            onInitialize()
            return AgentInfo(clientInfo.protocolVersion, implementation = Implementation("test-agent", "1.0.0"))
        }

        override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
            error("the v1 session surface must not be reached on a v2 connection")
    }

    @Test
    fun `v2 handshake settles both sides on v2`() = testWithProtocols { clientProtocol, agentProtocol ->
        val agent = V2Agent(agentProtocol, V2Support())
        val client = V2Client(clientProtocol)

        val agentInfo = client.initialize(v2ClientInfo())

        assertEquals("test-agent", agentInfo.implementation.name)
        assertEquals(agentInfo, client.agentInfo)
        assertEquals("test-client", agent.clientInfo.implementation.name)
        assertEquals(PROTOCOL_VERSION_V2, client.negotiatedProtocolVersion)
        assertEquals(PROTOCOL_VERSION_V2, agent.negotiatedProtocolVersion)
    }

    @Test
    fun `a v2 turn streams updates and ends with an idle stop reason`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V2Support()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        assertEquals(SessionId("v2-1"), session.sessionId)

        session.prompt(listOf(ContentBlock.Text("hi")))

        // running -> chunk -> idle(end_turn): the prompt response said nothing about any of it.
        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }.map { it.update }
        assertIs<SessionUpdate.StateUpdate>(updates[0]).also { assertIs<StateUpdate.Running>(it.state) }

        val chunk = assertIs<SessionUpdate.AgentMessageChunk>(updates[1])
        assertEquals("echo: hi", (chunk.chunk.content as ContentBlock.Text).text)

        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[2]).state)
        assertEquals(StopReason.EndTurn, idle.stopReason)
    }

    @Test
    fun `cancel is reported as an idle update with the cancelled stop reason`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V2Support(hangUntilCancelled = true)
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        // Acceptance is acknowledged before this deliberately hanging turn finishes.
        withTimeout(10.seconds) { session.prompt(listOf(ContentBlock.Text("hi"))) }
        // Wait for the turn to be running, otherwise the cancel could arrive before there is anything to
        // cancel and the test would pass without exercising it.
        withTimeout(10.seconds) { support.sessions.single().turnStarted.await() }

        session.cancel()

        val updates = withTimeout(10.seconds) { session.updates.take(2).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[1]).state)
        assertEquals(StopReason.Cancelled, idle.stopReason)
    }

    @Test
    fun `a v1 agent answers v1 and the v2 client falls back on the same connection`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            var v1Initialized = false
            Agent(agentProtocol, v1Support { v1Initialized = true })
            val v2Client = V2Client(clientProtocol)

            // Negotiation, not an error response: the agent answers with the version it speaks.
            val refusal = assertFails { v2Client.initialize(v2ClientInfo()) }
            val unsupported = assertIs<UnsupportedProtocolVersionException>(refusal)
            assertEquals(LATEST_PROTOCOL_VERSION, unsupported.offeredVersion)
            assertTrue(v1Initialized, "the v1 agent served the handshake with v1 types")

            // The connection is still usable, which is what makes a fallback possible at all: the v1
            // client puts its own handlers on it and repeats the handshake in v1's shapes.
            val client = Client(clientProtocol)
            val agentInfo = client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION))
            assertEquals(LATEST_PROTOCOL_VERSION, agentInfo.protocolVersion)
            assertEquals(LATEST_PROTOCOL_VERSION, client.negotiatedProtocolVersion)
        }

    @Test
    fun `a v1 client is refused by a v2 agent`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = V2Support()
        V2Agent(agentProtocol, support)
        val client = Client(clientProtocol)

        val failure = assertFails { client.initialize(ClientInfo(protocolVersion = LATEST_PROTOCOL_VERSION)) }

        assertTrue(
            failure.message!!.contains("Protocol version 1 is not supported by this agent"),
            "unexpected failure: ${failure.message}",
        )
        assertEquals(0, support.initializeCalls, "the refusal happens before the implementation is called")
    }

    @Test
    fun `client rejects reinitialization before sending another handshake`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val support = V2Support()
            val agent = V2Agent(agentProtocol, support)
            val client = V2Client(clientProtocol)
            client.initialize(v2ClientInfo())

            val failure = assertFails { client.initialize(v2ClientInfo()) }

            assertTrue(failure.message!!.contains("already initialized with protocol version 2"))
            assertEquals(1, support.initializeCalls, "the repeated handshake must not reach the agent")
            assertEquals(PROTOCOL_VERSION_V2, client.negotiatedProtocolVersion)
            assertEquals(PROTOCOL_VERSION_V2, agent.negotiatedProtocolVersion)
        }

    @Test
    fun `a v2 agent does not serve the v1 session surface`() = testWithProtocols { clientProtocol, agentProtocol ->
        V2Agent(agentProtocol, V2Support())
        V2Client(clientProtocol).initialize(v2ClientInfo())

        // `session/load` does not exist in v2, so nothing on this connection serves it. Asking takes a v1
        // client, which is a separate object over the same protocol.
        val v1Client = Client(clientProtocol)
        val failure = assertFails {
            v1Client.loadSession(
                SessionId("some-session"),
                SessionCreationParameters(cwd = ".", mcpServers = emptyList()),
            ) { _, _ -> error("operations must not be created") }
        }
        assertTrue(
            failure.message!!.contains("Method not supported"),
            "unexpected failure: ${failure.message}",
        )
    }

    @Test
    fun `newSession registers the session it returns and nothing else`() = testWithProtocols { clientProtocol, agentProtocol ->
        // Updates that arrive before `session/new` has answered are covered by
        // `V2SessionUpdateDeliveryTest`, which scripts the agent as raw JSON to get that order; the SDK's own
        // v2 agent cannot send an update outside a prompt turn.
        val support = V2Support()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session: V2ClientSession = client.newSession(cwd = ".")
        session.prompt(listOf(ContentBlock.Text("hi")))
        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }
        assertEquals(3, updates.size)
        assertNull(client.getSession(SessionId("nope")))
        assertEquals(session, client.getSession(session.sessionId))
    }

    @Test
    fun `a granted permission lets the turn finish`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = PermissionSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())
        val permissions = ScriptedPermissions("allow")

        val session = client.newSession(cwd = ".", operations = permissions)
        session.prompt(listOf(ContentBlock.Text("hi")))

        val updates = withTimeout(10.seconds) { session.updates.take(4).toList() }.map { it.update }
        // running -> requires_action while the user is asked -> running -> idle(end_turn)
        assertIs<StateUpdate.RequiresAction>(assertIs<SessionUpdate.StateUpdate>(updates[1]).state)
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[3]).state)
        assertEquals(StopReason.EndTurn, idle.stopReason)

        // The request arrived in the v2 shape: a subject union, not v1's flat toolCall field.
        val seen = assertNotNull(permissions.seen)
        assertEquals("Run read_file?", seen.title)
        assertEquals(session.sessionId, seen.sessionId)
        assertEquals(2, seen.options.size)
        val subject = assertIs<RequestPermissionSubject.ToolCall>(assertNotNull(seen.subject))
        assertEquals(ToolCallId("call-1"), subject.toolCall.toolCallId)
    }

    @Test
    fun `a rejected permission ends the turn with a refusal`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = PermissionSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".", operations = ScriptedPermissions("reject"))
        session.prompt(listOf(ContentBlock.Text("hi")))

        val updates = withTimeout(10.seconds) { session.updates.take(4).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[3]).state)
        assertEquals(StopReason.Refusal, idle.stopReason)
        assertEquals(
            RequestPermissionOutcome.Selected(PermissionOptionId("reject")),
            support.sessions.single().outcome,
        )
    }

    @Test
    fun `cancelling answers the pending permission request with cancelled`() = testWithProtocols { clientProtocol, agentProtocol ->
        // The MUST from the lifecycle: a client that cancels answers pending permission requests with
        // `cancelled` instead of leaving the agent waiting for a user who is never coming.
        // https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation
        val support = PermissionSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())
        // Never answers on its own, so only the cancel can resolve the request.
        val permissions = ScriptedPermissions(optionId = null)

        val session = client.newSession(cwd = ".", operations = permissions)
        // The prompt is accepted even though the turn then waits for permission.
        withTimeout(10.seconds) { session.prompt(listOf(ContentBlock.Text("hi"))) }
        withTimeout(10.seconds) { permissions.asked.await() }

        session.cancel()

        val updates = withTimeout(10.seconds) { session.updates.take(4).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[3]).state)
        assertEquals(StopReason.Cancelled, idle.stopReason)
        assertEquals(RequestPermissionOutcome.Cancelled, support.sessions.single().outcome)
    }

    @Test
    fun `an outcome the agent does not understand is not treated as approval`() = testWithProtocols { clientProtocol, agentProtocol ->
        // "Agents that do not understand this outcome MUST NOT treat it as approval" — the outcome union is
        // open, so a newer client can answer with something this agent has never heard of.
        val support = PermissionSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val unknownOutcome = buildJsonObject {
            put("outcome", "_deferred_to_policy")
            put("policy", "ask-later")
        }
        val operations = object : V2ClientSessionOperations {
            override suspend fun requestPermission(request: RequestPermissionRequest) =
                RequestPermissionResponse(RequestPermissionOutcome.Unknown("_deferred_to_policy", unknownOutcome))
        }

        val session = client.newSession(cwd = ".", operations = operations)
        session.prompt(listOf(ContentBlock.Text("hi")))

        val updates = withTimeout(10.seconds) { session.updates.take(4).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[3]).state)
        assertEquals(StopReason.Refusal, idle.stopReason, "an unknown outcome must not end the turn as success")

        // It reached the agent as Unknown with the payload intact, so an agent can forward or store it.
        val seen = assertIs<RequestPermissionOutcome.Unknown>(assertNotNull(support.sessions.single().outcome))
        assertEquals("_deferred_to_policy", seen.outcome)
        assertEquals(unknownOutcome, seen.rawJson)
    }

    @Test
    fun `a session without operations refuses permission requests instead of hanging`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = PermissionSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")

        // The prompt itself was accepted; the nested permission request is refused and the agent maps
        // that failure to the terminal v2 update.
        withTimeout(10.seconds) { session.prompt(listOf(ContentBlock.Text("hi"))) }
        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[2]).state)
        assertEquals(StopReason.Refusal, idle.stopReason)
        val failure = assertNotNull(support.sessions.single().failure)
        assertTrue(
            failure.message!!.contains("no v2 session operations"),
            "unexpected failure: ${failure.message}",
        )
        assertTrue(support.sessions.single().asked.isCompleted, "the agent did ask")
    }

    /** A v2 agent that implements the connection-level methods and records what it was asked. */
    private class LifecycleSupport : V2AgentSupport {
        var loggedInWith: AuthMethodId? = null
        var loggedOut = false
        var closed: SessionId? = null
        var deleted: SessionId? = null
        val listed = mutableListOf<Pair<String?, String?>>()

        override suspend fun initialize(clientInfo: V2ClientInfo) =
            V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = EchoV2Session(SessionId("v2-1"))

        override suspend fun login(methodId: AuthMethodId, _meta: JsonElement?): LoginAuthResponse {
            loggedInWith = methodId
            return LoginAuthResponse()
        }

        override suspend fun logout(_meta: JsonElement?): LogoutAuthResponse {
            loggedOut = true
            return LogoutAuthResponse()
        }

        override suspend fun listSessions(cwd: String?, cursor: String?, _meta: JsonElement?): ListSessionsResponse {
            listed += cwd to cursor
            // Two pages, so the cursor round trip is actually exercised.
            return if (cursor == null) {
                ListSessionsResponse(
                    sessions = listOf(SessionInfo(SessionId("s1"), cwd = "/one")),
                    nextCursor = "page-2",
                )
            } else {
                ListSessionsResponse(sessions = listOf(SessionInfo(SessionId("s2"), cwd = "/two")))
            }
        }

        override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?): CloseSessionResponse {
            closed = sessionId
            return CloseSessionResponse()
        }

        override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
            deleted = sessionId
            return DeleteSessionResponse()
        }
    }

    @Test
    fun `auth login and logout reach the agent`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = LifecycleSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        client.login(AuthMethodId("oauth"))
        client.logout()

        assertEquals(AuthMethodId("oauth"), support.loggedInWith)
        assertTrue(support.loggedOut)
    }

    @Test
    fun `session list pages through with the agent's cursor`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = LifecycleSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val first = client.listSessions(cwd = "/work")
        assertEquals(listOf(SessionId("s1")), first.sessions.map { it.sessionId })
        assertEquals("page-2", first.nextCursor)

        val second = client.listSessions(cwd = "/work", cursor = first.nextCursor)
        assertEquals(listOf(SessionId("s2")), second.sessions.map { it.sessionId })
        assertNull(second.nextCursor, "the last page reports no cursor")
        assertEquals(
            listOf<Pair<String?, String?>>("/work" to null, "/work" to "page-2"),
            support.listed.toList(),
        )
    }

    @Test
    fun `closing a session ends it on both sides`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = LifecycleSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        session.close()

        assertEquals(session.sessionId, support.closed)
        assertNull(client.getSession(session.sessionId), "a closed session is no longer tracked")
    }

    @Test
    fun `deleting a session takes an id and forgets it locally`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = LifecycleSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        client.deleteSession(session.sessionId)

        assertEquals(session.sessionId, support.deleted)
        assertNull(client.getSession(session.sessionId))
    }

    @Test
    fun `a method the agent did not implement refuses instead of pretending`() = testWithProtocols { clientProtocol, agentProtocol ->
        // The defaults on V2AgentSupport must fail loudly: an `Error` from a handler would leave the caller
        // waiting forever, and a silent success would be worse.
        V2Agent(agentProtocol, V2Support())
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val failure = assertFails { withTimeout(10.seconds) { client.login(AuthMethodId("oauth")) } }
        assertTrue(
            failure.message!!.contains("auth/login is not implemented"),
            "unexpected failure: ${failure.message}",
        )
        // The connection survives a refusal, so the client can carry on.
        assertEquals(PROTOCOL_VERSION_V2, client.negotiatedProtocolVersion)
    }

    @Test
    fun `resume brings a session back and replays from the requested cursor`() = testWithProtocols { clientProtocol, agentProtocol ->
        var resumedFrom: ReplayFrom? = null
        var resumedId: SessionId? = null
        val support = object : V2AgentSupport {
            override suspend fun initialize(clientInfo: V2ClientInfo) =
                V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

            override suspend fun createSession(
                parameters: V2SessionCreationParameters,
                client: V2ClientOperations,
            ): V2AgentSession = EchoV2Session(SessionId("v2-1"))

            override suspend fun resumeSession(
                sessionId: SessionId,
                parameters: V2SessionCreationParameters,
                replayFrom: ReplayFrom?,
                client: V2ClientOperations,
            ): V2AgentSession {
                resumedId = sessionId
                resumedFrom = replayFrom
                return ConfigurableV2Session(sessionId)
            }
        }
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.resumeSession(
            sessionId = SessionId("old-session"),
            cwd = "/work",
            replayFrom = ReplayFrom.Start(),
        )

        assertEquals(SessionId("old-session"), resumedId)
        assertIs<ReplayFrom.Start>(assertNotNull(resumedFrom))
        assertEquals(SessionId("old-session"), session.sessionId)
        // The resumed session reports its options, and it is prompt-able like a new one.
        assertEquals(listOf(SessionConfigId("mode")), session.configOptions.map { it.configId })
    }

    @Test
    fun `setting a config option flattens the value and returns the current options`() = testWithProtocols { clientProtocol, agentProtocol ->
        val session = ConfigurableV2Session(SessionId("v2-1"))
        val support = object : V2AgentSupport {
            override suspend fun initialize(clientInfo: V2ClientInfo) =
                V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

            override suspend fun createSession(
                parameters: V2SessionCreationParameters,
                client: V2ClientOperations,
            ): V2AgentSession = session
        }
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val clientSession = client.newSession(cwd = ".")
        val options = clientSession.setConfigOption(
            SessionConfigId("mode"),
            SessionConfigOptionValue.Id(SessionConfigValueId("architect")),
        )

        assertEquals(SessionConfigId("mode") to SessionConfigValueId("architect"), session.lastSet)
        assertEquals(listOf(SessionConfigId("mode")), options.map { it.configId })
    }

    /** A session that elicits input mid-turn and reports what came back. */
    private class ElicitingV2Session(
        override val sessionId: SessionId,
        private val client: V2ClientOperations,
        private val mode: (SessionId) -> ElicitationMode,
        private val completeWith: ElicitationId? = null,
    ) : V2AgentSession {
        var action: ElicitationAction? = null
        var failure: Throwable? = null

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
            emit(SessionUpdate.StateUpdate(StateUpdate.Running()))
            emit(SessionUpdate.StateUpdate(StateUpdate.RequiresAction()))
            val response = try {
                client.createElicitation("What is your name?", mode(sessionId))
            } catch (t: Throwable) {
                failure = t
                emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.Refusal)))
                return@flow
            }
            action = response.action
            completeWith?.let { client.completeElicitation(it) }
            val stopReason = when (response.action) {
                is ElicitationAction.Accept -> StopReason.EndTurn
                // Neither a decline nor an outcome we do not understand may pass as acceptance.
                else -> StopReason.Refusal
            }
            emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = stopReason)))
        }
    }

    private class ElicitingSupport(
        private val mode: (SessionId) -> ElicitationMode,
        private val completeWith: ElicitationId? = null,
    ) : V2AgentSupport {
        lateinit var session: ElicitingV2Session

        override suspend fun initialize(clientInfo: V2ClientInfo) =
            V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession =
            ElicitingV2Session(SessionId("v2-1"), client, mode, completeWith).also { session = it }
    }

    private fun formMode(sessionId: SessionId) = ElicitationMode.Form(
        scope = ElicitationScope.Session(sessionId),
        requestedSchema = ElicitationSchema(
            properties = mapOf("name" to ElicitationPropertySchema.StringProperty(title = "Name")),
        ),
    )

    @Test
    fun `a form elicitation is answered by the client and carries its scope flattened`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = ElicitingSupport(::formMode)
        V2Agent(agentProtocol, support)
        var seen: CreateElicitationRequest? = null
        val handler = object : V2ElicitationHandler {
            override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
                seen = request
                return CreateElicitationResponse(
                    ElicitationAction.Accept(content = mapOf("name" to ElicitationContentValue.StringValue("Ada")))
                )
            }
        }
        val client = V2Client(clientProtocol, elicitation = handler)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        session.prompt(listOf(ContentBlock.Text("hi")))

        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[2]).state)
        assertEquals(StopReason.EndTurn, idle.stopReason)

        // The request arrived whole: message, mode and the scope the mode carries.
        val request = assertNotNull(seen)
        assertEquals("What is your name?", request.message)
        val form = assertIs<ElicitationMode.Form>(request.mode)
        assertEquals(ElicitationScope.Session(session.sessionId), form.scope)
        assertEquals(setOf("name"), form.requestedSchema.properties.keys)

        val accepted = assertIs<ElicitationAction.Accept>(assertNotNull(support.session.action))
        assertEquals("Ada", (accepted.content?.get("name") as ElicitationContentValue.StringValue).value)
    }

    @Test
    fun `a declined elicitation does not pass as acceptance`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = ElicitingSupport(::formMode)
        V2Agent(agentProtocol, support)
        val handler = object : V2ElicitationHandler {
            override suspend fun createElicitation(request: CreateElicitationRequest) =
                CreateElicitationResponse(ElicitationAction.Decline)
        }
        val client = V2Client(clientProtocol, elicitation = handler)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        session.prompt(listOf(ContentBlock.Text("hi")))

        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[2]).state)
        assertEquals(StopReason.Refusal, idle.stopReason)
        assertEquals(ElicitationAction.Decline, support.session.action)
    }

    @Test
    fun `a url elicitation is followed by a completion notification`() = testWithProtocols { clientProtocol, agentProtocol ->
        val elicitationId = ElicitationId("el-1")
        val support = ElicitingSupport(
            mode = { sessionId ->
                ElicitationMode.Url(
                    scope = ElicitationScope.Session(sessionId),
                    elicitationId = elicitationId,
                    url = "https://example.test/form",
                )
            },
            // A url elicitation is answered at once; the user acts outside the client, and the agent says so
            // afterwards with elicitation/complete.
            completeWith = elicitationId,
        )
        V2Agent(agentProtocol, support)
        val completed = CompletableDeferred<ElicitationId>()
        var seenUrl: String? = null
        val handler = object : V2ElicitationHandler {
            override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
                seenUrl = assertIs<ElicitationMode.Url>(request.mode).url
                return CreateElicitationResponse(ElicitationAction.Accept())
            }

            override fun elicitationCompleted(elicitationId: ElicitationId, _meta: JsonElement?) {
                completed.complete(elicitationId)
            }
        }
        val client = V2Client(clientProtocol, elicitation = handler)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        session.prompt(listOf(ContentBlock.Text("hi")))
        withTimeout(10.seconds) { session.updates.take(3).toList() }

        assertEquals("https://example.test/form", seenUrl)
        assertEquals(elicitationId, withTimeout(10.seconds) { completed.await() })
    }

    @Test
    fun `a client without an elicitation handler refuses instead of hanging`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = ElicitingSupport(::formMode)
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val session = client.newSession(cwd = ".")
        withTimeout(10.seconds) { session.prompt(listOf(ContentBlock.Text("hi"))) }
        val updates = withTimeout(10.seconds) { session.updates.take(3).toList() }.map { it.update }
        val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[2]).state)
        assertEquals(StopReason.Refusal, idle.stopReason)
        val failure = assertNotNull(support.session.failure)
        assertTrue(
            failure.message!!.contains("no elicitation handler"),
            "unexpected failure: ${failure.message}",
        )
    }

    /** A v2 agent implementing the unstable surface: fork and provider configuration. */
    private class UnstableSupport : V2AgentSupport {
        var forkedFrom: SessionId? = null
        var setProvider: Triple<ProviderId, LlmProtocol, String>? = null
        var setHeaders: Map<String, String>? = null
        var disabled: ProviderId? = null

        override suspend fun initialize(clientInfo: V2ClientInfo) =
            V2AgentInfo(implementation = Implementation(name = "test-agent", version = "1.0.0"))

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = EchoV2Session(SessionId("v2-1"))

        override suspend fun forkSession(
            sessionId: SessionId,
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession {
            forkedFrom = sessionId
            // A fork gets its own id; the request named the session being forked from.
            return ConfigurableV2Session(SessionId("${sessionId.value}-fork"))
        }

        override suspend fun listProviders(_meta: JsonElement?) = ListProvidersResponse(
            providers = listOf(
                ProviderInfo(
                    providerId = ProviderId("main"),
                    supported = listOf(LlmProtocol.Anthropic, LlmProtocol.OpenAi),
                    required = true,
                    current = ProviderCurrentConfig(LlmProtocol.Anthropic, "https://api.anthropic.test"),
                ),
                ProviderInfo(
                    providerId = ProviderId("side"),
                    supported = listOf(LlmProtocol.OpenAi),
                    required = false,
                ),
            )
        )

        override suspend fun setProvider(
            providerId: ProviderId,
            apiType: LlmProtocol,
            baseUrl: String,
            headers: Map<String, String>,
            _meta: JsonElement?,
        ): SetProviderResponse {
            setProvider = Triple(providerId, apiType, baseUrl)
            setHeaders = headers
            return SetProviderResponse()
        }

        override suspend fun disableProvider(providerId: ProviderId, _meta: JsonElement?): DisableProviderResponse {
            disabled = providerId
            return DisableProviderResponse()
        }
    }

    @Test
    fun `forking a session yields a new id`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = UnstableSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val fork = client.forkSession(sessionId = SessionId("original"), cwd = "/work")

        assertEquals(SessionId("original"), support.forkedFrom)
        assertEquals(SessionId("original-fork"), fork.sessionId, "the fork must be addressed by its own id")
        assertEquals(fork, client.getSession(fork.sessionId))
        assertNull(client.getSession(SessionId("original")), "forking does not register the source session")
        assertEquals(listOf(SessionConfigId("mode")), fork.configOptions.map { it.configId })
    }

    @Test
    fun `providers can be listed configured and disabled`() = testWithProtocols { clientProtocol, agentProtocol ->
        val support = UnstableSupport()
        V2Agent(agentProtocol, support)
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        val listed = client.listProviders().providers
        assertEquals(listOf(ProviderId("main"), ProviderId("side")), listed.map { it.providerId })
        // `current` absent means disabled; `required` means it must not be disabled at all.
        assertEquals(LlmProtocol.Anthropic, assertNotNull(listed[0].current).apiType)
        assertTrue(listed[0].required)
        assertNull(listed[1].current)

        client.setProvider(
            providerId = ProviderId("side"),
            apiType = LlmProtocol.OpenAi,
            baseUrl = "https://api.openai.test",
            headers = mapOf("authorization" to "Bearer x"),
        )
        assertEquals(
            Triple(ProviderId("side"), LlmProtocol.OpenAi, "https://api.openai.test"),
            support.setProvider,
        )
        assertEquals(mapOf("authorization" to "Bearer x"), support.setHeaders)

        client.disableProvider(ProviderId("side"))
        assertEquals(ProviderId("side"), support.disabled)
    }

    @Test
    fun `the unstable methods refuse when the agent does not implement them`() = testWithProtocols { clientProtocol, agentProtocol ->
        V2Agent(agentProtocol, V2Support())
        val client = V2Client(clientProtocol)
        client.initialize(v2ClientInfo())

        for (call in listOf<suspend () -> Any>(
            { client.listProviders() },
            { client.disableProvider(ProviderId("main")) },
            { client.forkSession(SessionId("original"), cwd = ".") },
        )) {
            val failure = assertFails { withTimeout(10.seconds) { call() } }
            assertTrue(
                failure.message!!.contains("is not implemented by this agent"),
                "unexpected failure: ${failure.message}",
            )
        }
        assertEquals(PROTOCOL_VERSION_V2, client.negotiatedProtocolVersion)
    }
}
