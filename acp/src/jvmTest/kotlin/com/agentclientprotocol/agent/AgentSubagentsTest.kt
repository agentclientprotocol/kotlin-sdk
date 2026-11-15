@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.CloseSessionRequest
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.DeleteSessionRequest
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationUrlCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.ForkSessionRequest
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.ResumeSessionRequest
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionConfigOptionRequest
import com.agentclientprotocol.model.SetSessionModeRequest
import com.agentclientprotocol.model.SetSessionModelRequest
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.SubagentCapabilities
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.SuggestNesRequest
import com.agentclientprotocol.model.SuggestNesResponse
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentSubagentsTest {

    private val rootId = SessionId("root")
    private val childId = SessionId("child")
    private val siblingId = SessionId("sibling")
    private val grandchildId = SessionId("grandchild")

    private class RootSession(override val sessionId: SessionId) : AgentSession {
        val context = CompletableDeferred<CoroutineContext>()

        @Volatile
        var promptHandler: PromptHandler = {}

        @Volatile
        var replay: suspend () -> Unit = {}

        /**
         * Runs in [postInitialize] after [context] is completed.
         */
        @Volatile
        var afterInitialize: suspend () -> Unit = {}

        @Volatile
        var onClose: suspend () -> Unit = {}

        /**
         * Thrown while the SDK builds the response that opens this session.
         */
        @Volatile
        var responseFailure: Throwable? = null

        override val availableModes: List<SessionMode>
            get() = responseFailure?.let { throw it } ?: emptyList()

        override suspend fun postInitialize() {
            context.complete(checkNotNull(currentCoroutineContext()[SessionWrapperContextElement.Key]))
            afterInitialize()
        }

        override suspend fun replayHistory() = replay()

        override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
            onClose()
            return CloseSessionResponse()
        }

        override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
            promptHandler(content)
        }
    }

    private class TestSubagent(
        override val events: Flow<SessionUpdate> = emptyFlow(),
        private val onCancel: suspend () -> Unit = {},
    ) : AgentSubagentSession {
        override suspend fun cancel() = onCancel()
    }

    /**
     * An initialized agent with one root session. [rootContext] holds only the root's session element, so
     * `withContext(rootContext)` runs a block as the root's code while keeping the caller's job.
     */
    private class Fixture(
        val transport: TestTransport,
        val protocol: Protocol,
        val agent: Agent,
        val root: RootSession,
        val rootContext: CoroutineContext,
        private val openable: OpenableSessions,
    ) {
        val rootSubagents: AgentSubagents get() = assertNotNull(rootContext.subagents)
        val rootClient get() = rootContext.client
        val rootWrapper get() = rootContext.rootWrapper

        /**
         * Waits until every collection of [root]'s subtree has ended, including the SDK's feed-end handling.
         */
        suspend fun awaitCollectionsEnded(root: Agent.SessionWrapper = rootWrapper) {
            withTimeout(5.seconds) { root.subtreeScope.coroutineContext.job.children.forEach { it.join() } }
        }

        /**
         * Opens another root session with `session/new` and returns its context, once its postInitialize ran.
         */
        suspend fun newRoot(session: RootSession): CoroutineContext {
            openable.created.add(session)
            transport.testRequest(AcpMethod.AgentMethods.V1.SessionNew, NewSessionRequest(".", emptyList()))
            return withTimeout(5.seconds) { session.context.await() }
        }

        /**
         * Serves `session/load` for [session] and returns everything sent up to and including the response.
         */
        suspend fun load(session: RootSession): List<JsonRpcMessage> {
            openable.byId[session.sessionId] = session
            return transport.fireTestRequest(
                AcpMethod.AgentMethods.V1.SessionLoad.methodName,
                ACPJson.encodeToJsonElement(
                    AcpMethod.AgentMethods.V1.SessionLoad.requestSerializer,
                    LoadSessionRequest(session.sessionId, ".", emptyList()),
                ),
            )
        }

        suspend fun resume(session: RootSession): JsonRpcMessage? {
            openable.byId[session.sessionId] = session
            return request(AcpMethod.AgentMethods.V1.SessionResume, ResumeSessionRequest(session.sessionId, "."))
        }

        /**
         * Serves `session/close` for [sessionId] and returns everything sent up to and including the response.
         */
        suspend fun close(sessionId: SessionId): List<JsonRpcMessage> = transport.fireTestRequest(
            AcpMethod.AgentMethods.V1.SessionClose.methodName,
            ACPJson.encodeToJsonElement(AcpMethod.AgentMethods.V1.SessionClose.requestSerializer, CloseSessionRequest(sessionId)),
        )

        fun fireCancel(sessionId: SessionId) {
            transport.fireTestNotification(
                AcpMethod.AgentMethods.V1.SessionCancel.methodName,
                ACPJson.encodeToJsonElement(AcpMethod.AgentMethods.V1.SessionCancel.serializer, CancelNotification(sessionId)),
            )
        }

        suspend fun <T : AcpRequest> request(method: AcpMethod.AcpRequestResponseMethod<T, *>, params: T): JsonRpcMessage? =
            transport.fireTestRequest(method.methodName, ACPJson.encodeToJsonElement(method.requestSerializer, params))
                .lastOrNull()

        suspend fun <T> inRoot(block: suspend () -> T): T = withContext(rootContext) { block() }

        suspend fun nextNotification(): SessionNotification {
            val message = assertNotNull(transport.receiveTestMessages(1).firstOrNull(), "Expected a notification")
            return assertNotNull(message.sessionNotificationOrNull(), "Expected session/update, got $message")
        }

        /**
         * Asserts that nothing was sent since the last read: a marker the root sends now is the next message.
         */
        suspend fun assertNothingMoreSent() {
            val marker = text("marker")
            rootClient.notify(marker)
            assertEquals(SessionNotification(SessionId("root"), marker), nextNotification())
        }
    }

    /**
     * The sessions the fixture's agent opens: the next `session/new` takes [created]'s head, or the fixture's root,
     * and `session/load` and `session/resume` look sessions up in [byId].
     */
    private class OpenableSessions {
        val created = ConcurrentLinkedQueue<RootSession>()
        val byId = ConcurrentHashMap<SessionId, RootSession>()
    }

    private fun withSubagents(
        subagentsCapability: Boolean = true,
        otherCapabilities: ClientCapabilities = ClientCapabilities(),
        block: suspend CoroutineScope.(Fixture) -> Unit,
    ) = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        val root = RootSession(rootId)
        val openable = OpenableSessions()
        val support = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo()
            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
                openable.created.poll() ?: root

            override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters) =
                checkNotNull(openable.byId[sessionId])

            override suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters) =
                checkNotNull(openable.byId[sessionId])
        }
        val agent = Agent(protocol, support)
        protocol.start()
        try {
            val capabilities = otherCapabilities.copy(subagents = if (subagentsCapability) SubagentCapabilities() else null)
            transport.testRequest(AcpMethod.AgentMethods.V1.Initialize, InitializeRequest(LATEST_PROTOCOL_VERSION, capabilities))
            transport.testRequest(AcpMethod.AgentMethods.V1.SessionNew, NewSessionRequest(".", emptyList()))
            val rootContext = withTimeout(5.seconds) { root.context.await() }
            block(Fixture(transport, protocol, agent, root, rootContext, openable))
        } finally {
            protocol.close()
        }
    }

    // Capability gate and context accessors

    @Test
    fun `subagents accessor is null without the capability`() = withSubagents(subagentsCapability = false) { f ->
        assertNull(f.rootContext.subagents)
    }

    @Test
    fun `subagents accessor fails outside a session context`() = runBlocking {
        assertFailsWith<IllegalStateException> { currentCoroutineContext().subagents }
        Unit
    }

    @Test
    fun `subagents accessor fails in a NES session context`() = withSubagents { f ->
        val nesSession = object : NesAgentSession {
            override val nesSessionId = SessionId("nes")
            override suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse = error("Not called")
        }
        val nesContext = Agent.NesSessionWrapper(f.agent, nesSession, f.protocol).asContextElement()

        val failure = assertFailsWith<IllegalStateException> { nesContext.subagents }
        assertTrue(failure.message!!.contains("NES"), failure.message)
    }

    @Test
    fun `child context resolves its own client, subagents and agent`() = withSubagents { f ->
        assertNotNull(f.rootContext.subagents)
        var checked = false
        f.rootSubagents.announce(childId, { context ->
            val coroutineContext = currentCoroutineContext()
            assertEquals(childId, context.sessionId)
            assertSame(context.client, coroutineContext.client)
            assertSame(context.subagents, coroutineContext.subagents)
            assertSame(f.agent, coroutineContext.agent)
            context.client.notify(announcement(childId))
            checked = true
            TestSubagent()
        })
        assertTrue(checked)
    }

    // Announcement

    @Test
    fun `announce through the factory`() = withSubagents { f ->
        val session = TestSubagent()
        val announced = f.rootSubagents.announce(childId, { context ->
            context.client.notify(announcement(childId))
            context.client.notify(text("from factory"))
            session
        })

        assertSame(session, announced)
        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertEquals(SessionNotification(childId, text("from factory")), f.nextNotification())
    }

    @Test
    fun `announce through the first events item`() = withSubagents { f ->
        val patch = SessionUpdate.SubagentUpdate(childId, state = MaybeUndefined.Value(StateUpdate.Running()))
        f.rootSubagents.announce(childId, { TestSubagent(flowOf(announcement(childId), text("from events"), patch)) })

        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertEquals(SessionNotification(childId, text("from events")), f.nextNotification())
        assertEquals(SessionNotification(rootId, patch), f.nextNotification())
    }

    @Test
    fun `announce returns after the announcement and a parent message sent then follows it`() = withSubagents { f ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val announcing = async {
            f.rootSubagents.announce(childId, {
                TestSubagent(flow {
                    started.complete(Unit)
                    release.await()
                    emit(announcement(childId))
                })
            })
        }
        withTimeout(5.seconds) { started.await() }
        assertFalse(announcing.isCompleted)

        release.complete(Unit)
        withTimeout(5.seconds) { announcing.await() }
        val message = SessionUpdate.SessionMessage(MessageId("m1"), recipientSessionId = MaybeUndefined.Value(childId))
        f.rootClient.notify(message)

        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertEquals(SessionNotification(rootId, message), f.nextNotification())
    }

    @Test
    fun `operations fail before the announcement`() = withSubagents { f ->
        val failures = mutableListOf<Throwable>()
        f.rootSubagents.announce(childId, { context ->
            val client = context.client
            failures += assertFailsWith<IllegalStateException> { client.notify(text("early")) }
            failures += assertFailsWith<IllegalStateException> {
                client.requestPermissions(SessionUpdate.ToolCallUpdate(ToolCallId("t")), emptyList())
            }
            failures += assertFailsWith<IllegalStateException> { client.fsReadTextFile("/a") }
            failures += assertFailsWith<IllegalStateException> { client.terminalCreate("ls") }

            client.notify(announcement(childId))
            client.notify(text("after"))
            TestSubagent()
        })

        assertEquals(4, failures.size)
        failures.forEach { assertTrue(it.message!!.contains("not announced"), it.message) }
        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertEquals(SessionNotification(childId, text("after")), f.nextNotification())
    }

    @Test
    fun `an announced child's requests are sent for the child's session`() = withSubagents(
        otherCapabilities = ClientCapabilities(
            fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
            terminal = true,
            elicitation = ElicitationCapabilities(url = ElicitationUrlCapabilities()),
        ),
    ) { f ->
        val client = announceChild(f, childId).client
        val sent = mutableListOf<JsonRpcRequest>()
        // Captures each request as it is sent and fails the call, so no response is needed.
        f.transport.onSend = { message ->
            if (message is JsonRpcRequest) {
                sent += message
                throw UncopiedException("request captured")
            }
        }
        val requests: List<Pair<String, suspend () -> Unit>> = listOf(
            "requestPermissions" to { client.requestPermissions(SessionUpdate.ToolCallUpdate(ToolCallId("t")), emptyList()) },
            "fsReadTextFile" to { client.fsReadTextFile("/a") },
            "fsWriteTextFile" to { client.fsWriteTextFile("/a", "content") },
            "terminalCreate" to { client.terminalCreate("ls") },
            "terminalOutput" to { client.terminalOutput("term") },
            "terminalRelease" to { client.terminalRelease("term") },
            "terminalWaitForExit" to { client.terminalWaitForExit("term") },
            "terminalKill" to { client.terminalKill("term") },
            "createSessionElicitation" to {
                (client as RemoteClientSessionOperations).createSessionElicitation(
                    ElicitationMode.Url(ElicitationId("e"), "https://example.com"),
                    "Sign in",
                )
            },
        )

        for ((name, request) in requests) {
            assertFailsWith<UncopiedException>(name) { request() }
            val params = assertNotNull(sent.removeLastOrNull()?.params, name).jsonObject
            assertEquals(childId.value, params["sessionId"]?.jsonPrimitive?.content, name)
        }
    }

    @Test
    fun `grant is recorded before the update is sent, on every path`() = withSubagents { f ->
        val grantsAtSend = mutableListOf<Boolean?>()
        f.transport.onSend = { message ->
            val update = message.sessionNotificationOrNull()?.update
            if (update is SessionUpdate.SubagentUpdate && update.sessionId == childId) {
                grantsAtSend += f.agent.subagentOrNull(childId)?.isCancelGranted
            }
        }
        val revoke = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Null)
        val regrant = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(grant()))
        val withoutCancel = SessionUpdate.SubagentUpdate(
            childId,
            capabilities = MaybeUndefined.Value(SubagentSessionCapabilities())
        )
        val events = Channel<SessionUpdate>(Channel.UNLIMITED)
        events.send(announcement(childId, granted = true))
        events.send(revoke)

        lateinit var context: AgentSubagentContext
        f.rootSubagents.announce(childId, {
            context = it
            TestSubagent(events.consumeAsFlow())
        })
        repeat(2) { f.nextNotification() }
        context.client.notify(regrant)
        f.nextNotification()
        events.send(withoutCancel)
        f.nextNotification()

        assertEquals(listOf<Boolean?>(true, false, true, false), grantsAtSend)
        events.close()
    }

    // announce errors

    @Test
    fun `an unknown parent is rejected`() = withSubagents { f ->
        var factoryCalled = false
        assertFailsWith<IllegalArgumentException> {
            f.rootSubagents.announce(childId, { factoryCalled = true; TestSubagent() }, parent = SessionId("unknown"))
        }
        assertFalse(factoryCalled)
        assertNull(f.agent.subagentOrNull(childId))
    }

    @Test
    fun `a parent outside the bound session's subtree is rejected`() = withSubagents { f ->
        val child = announceChild(f, childId)
        announceChild(f, siblingId)

        assertFailsWith<IllegalArgumentException> {
            child.subagents.announce(grandchildId, { TestSubagent() }, parent = siblingId)
        }
        assertFailsWith<IllegalArgumentException> {
            child.subagents.announce(grandchildId, { TestSubagent() }, parent = rootId)
        }
        f.assertNothingMoreSent()
    }

    @Test
    fun `an unannounced child cannot announce grandchildren`() = withSubagents { f ->
        var nested: Throwable? = null
        val failure = assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { context ->
                nested = runCatching { context.subagents.announce(grandchildId, { TestSubagent() }) }.exceptionOrNull()
                error("factory gives up")
            })
        }

        assertIs<IllegalStateException>(nested)
        assertTrue(failure.causes().any { it.message == "factory gives up" }, failure.stackTraceToString())
        assertNull(f.agent.subagentOrNull(grandchildId))
        f.assertNothingMoreSent()
    }

    @Test
    fun `a duplicate id is rejected, including a root session id`() = withSubagents { f ->
        announceChild(f, childId)

        assertFailsWith<IllegalArgumentException> { f.rootSubagents.announce(childId, { TestSubagent() }) }
        assertFailsWith<IllegalArgumentException> { f.rootSubagents.announce(rootId, { TestSubagent() }) }
        f.assertNothingMoreSent()
    }

    @Test
    fun `a first events item that is not the child's announcement fails announce`() = withSubagents { f ->
        assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { TestSubagent(flowOf(text("too early"), announcement(childId))) })
        }
        assertNull(f.agent.subagentOrNull(childId))
        assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { TestSubagent(flowOf(announcement(siblingId), announcement(childId))) })
        }
        assertNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()

        announceChild(f, childId)
    }

    @Test
    fun `empty events with nothing sent fail announce`() = withSubagents { f ->
        assertFailsWith<IllegalStateException> { f.rootSubagents.announce(childId, { TestSubagent() }) }
        assertNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()
    }

    @Test
    fun `events that throw before emitting fail announce`() = withSubagents { f ->
        val failure = assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { TestSubagent(flow { throw RuntimeException("feed down") }) })
        }
        assertTrue(failure.causes().any { it.message == "feed down" }, failure.stackTraceToString())
        assertNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()
    }

    @Test
    fun `a cancellation thrown by events before emitting surfaces as IllegalStateException`() = withSubagents { f ->
        val failure = assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { TestSubagent(flow { throw CancellationException("app timeout") }) })
        }
        assertWrapsCancellation(failure)
        assertTrue(currentCoroutineContext().isActive)
        assertNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()
    }

    @Test
    fun `a cancellation thrown by the factory while the caller is active surfaces as IllegalStateException`() =
        withSubagents { f ->
            val failure = assertFailsWith<IllegalStateException> {
                f.rootSubagents.announce(childId, { throw CancellationException("app timeout") })
            }
            assertWrapsCancellation(failure)
            assertTrue(currentCoroutineContext().isActive)
            assertNull(f.agent.subagentOrNull(childId))
        }

    @Test
    fun `a factory that throws before announcing fails announce and frees the id`() = withSubagents { f ->
        val failure = assertFailsWith<IllegalStateException> {
            f.rootSubagents.announce(childId, { throw IllegalArgumentException("bad config") })
        }
        assertTrue(failure.causes().any { it is IllegalArgumentException }, failure.stackTraceToString())
        assertNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()

        announceChild(f, childId)
    }

    @Test
    fun `a factory that throws after announcing an active child revokes the grant and reports unknown`() =
        withSubagents { f ->
            val thrown = UncopiedException("factory failed late")
            lateinit var context: AgentSubagentContext
            val failure = assertFailsWith<UncopiedException> {
                f.rootSubagents.announce(childId, {
                    context = it
                    it.client.notify(announcement(childId, granted = true, state = StateUpdate.Running()))
                    throw thrown
                })
            }

            assertSame(thrown, failure)
            assertEquals(SessionNotification(rootId, announcement(childId, granted = true, state = StateUpdate.Running())), f.nextNotification())
            val correction = SessionUpdate.SubagentUpdate(
                childId,
                capabilities = MaybeUndefined.Null,
                state = MaybeUndefined.Value(StateUpdate.Unknown()),
            )
            assertEquals(SessionNotification(rootId, correction), f.nextNotification())
            f.assertNothingMoreSent()

            val child = assertNotNull(f.agent.subagentOrNull(childId))
            assertFalse(child.isCancelGranted)
            assertNull(child.session)
            assertFailsWith<IllegalArgumentException> { f.rootSubagents.announce(childId, { TestSubagent() }) }

            context.client.notify(text("still valid"))
            assertEquals(SessionNotification(childId, text("still valid")), f.nextNotification())
        }

    @Test
    fun `a factory that throws after announcing an idle child without grant sends nothing more`() =
        withSubagents { f ->
            val idle = StateUpdate.Idle(IdleStopReason.EndTurn)
            assertFailsWith<RuntimeException> {
                f.rootSubagents.announce(childId, {
                    it.client.notify(announcement(childId, state = StateUpdate.Running()))
                    it.client.notify(SessionUpdate.SubagentUpdate(childId, state = MaybeUndefined.Value(idle)))
                    throw RuntimeException("factory failed late")
                })
            }

            repeat(2) { f.nextNotification() }
            f.assertNothingMoreSent()
            assertNotNull(f.agent.subagentOrNull(childId))
        }

    @Test
    fun `a caller cancelled while the factory runs after announcing still sends the correction`() =
        withSubagents { f ->
            val announced = CompletableDeferred<Unit>()
            var outcome: Result<AgentSubagentSession>? = null
            val caller = launch {
                outcome = runCatching {
                    f.rootSubagents.announce(childId, {
                        it.client.notify(announcement(childId, granted = true))
                        announced.complete(Unit)
                        awaitCancellation()
                    })
                }
            }
            withTimeout(5.seconds) { announced.await() }
            caller.cancelAndJoin()

            assertIs<CancellationException>(outcome?.exceptionOrNull())
            assertEquals(SessionNotification(rootId, announcement(childId, granted = true)), f.nextNotification())
            val correction = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Null)
            assertEquals(SessionNotification(rootId, correction), f.nextNotification())
            assertNotNull(f.agent.subagentOrNull(childId))
        }

    /*
     * The counterpart of the test above: there the caller itself is cancelled, and gets its cancellation as is.
     */
    @Test
    fun `a cancellation thrown by the factory after announcing, while the caller is active, surfaces as IllegalStateException`() =
        withSubagents { f ->
            val failure = assertFailsWith<IllegalStateException> {
                f.rootSubagents.announce(childId, {
                    it.client.notify(announcement(childId, granted = true, state = StateUpdate.Running()))
                    throw CancellationException("app timeout")
                })
            }

            assertWrapsCancellation(failure)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(SessionNotification(rootId, announcement(childId, granted = true, state = StateUpdate.Running())), f.nextNotification())
            val correction = SessionUpdate.SubagentUpdate(
                childId,
                capabilities = MaybeUndefined.Null,
                state = MaybeUndefined.Value(StateUpdate.Unknown()),
            )
            assertEquals(SessionNotification(rootId, correction), f.nextNotification())
            f.assertNothingMoreSent()
            assertNotNull(f.agent.subagentOrNull(childId))
        }

    @Test
    fun `a caller cancelled before the announcement unregisters the child and stops its events`() =
        withSubagents { f ->
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val caller = launch {
                f.rootSubagents.announce(childId, {
                    TestSubagent(flow {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    })
                })
            }
            withTimeout(5.seconds) { started.await() }
            caller.cancelAndJoin()

            withTimeout(5.seconds) { stopped.await() }
            assertNull(f.agent.subagentOrNull(childId))
            f.assertNothingMoreSent()
            announceChild(f, childId)
        }

    @Test
    fun `a caller cancelled after the announcement keeps the child and its events`() = withSubagents { f ->
        lateinit var caller: Job
        f.transport.onSend = { message ->
            val update = message.sessionNotificationOrNull()?.update
            if (update is SessionUpdate.SubagentUpdate && update.sessionId == childId) caller.cancel()
        }
        val more = Channel<SessionUpdate>(Channel.UNLIMITED)
        var outcome: Result<AgentSubagentSession>? = null
        caller = launch {
            outcome = runCatching {
                f.rootSubagents.announce(childId, {
                    TestSubagent(flow {
                        emit(announcement(childId))
                        emitAll(more)
                    })
                })
            }
        }
        caller.join()

        assertIs<CancellationException>(outcome?.exceptionOrNull())
        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertTrue(assertNotNull(f.agent.subagentOrNull(childId)).isAnnounced)
        more.send(text("still flowing"))
        assertEquals(SessionNotification(childId, text("still flowing")), f.nextNotification())
        more.close()
    }

    // Routing

    @Test
    fun `the opening marker can't be sent through the root's or a child's operations`() = withSubagents { f ->
        val child = announceChild(f, childId)

        assertFailsWith<IllegalStateException> { f.rootClient.notify(SessionEvent.Opened(LoadSessionResponse())) }
        assertFailsWith<IllegalStateException> { child.client.notify(SessionEvent.Opened(null)) }
        f.assertNothingMoreSent()
    }

    @Test
    fun `a SubagentUpdate for another session is rejected by a child's operations`() = withSubagents { f ->
        val child = announceChild(f, childId)

        assertFailsWith<IllegalArgumentException> { child.client.notify(announcement(grandchildId)) }
        assertFailsWith<IllegalArgumentException> { child.client.notify(announcement(rootId)) }
        f.assertNothingMoreSent()
    }

    @Test
    fun `a child's session messages and session references go on the child's stream`() = withSubagents { f ->
        val child = announceChild(f, childId)

        for (update in subagentsDefinedUpdates()) {
            child.client.notify(update)
            assertEquals(SessionNotification(childId, update), f.nextNotification())
        }
    }

    @Test
    fun `updates sent through the child's coroutine context go on the child's stream`() = withSubagents { f ->
        f.rootSubagents.announce(childId, {
            TestSubagent(flow {
                emit(announcement(childId))
                currentCoroutineContext().client.notify(text("from context"))
            })
        })

        assertEquals(SessionNotification(rootId, announcement(childId)), f.nextNotification())
        assertEquals(SessionNotification(childId, text("from context")), f.nextNotification())
    }

    @Test
    fun `a grandchild announced from the child's context goes on the child's stream`() = withSubagents { f ->
        val child = announceChild(f, childId)

        child.subagents.announce(grandchildId, { TestSubagent(flowOf(announcement(grandchildId), text("grand"))) })

        assertEquals(SessionNotification(childId, announcement(grandchildId)), f.nextNotification())
        assertEquals(SessionNotification(grandchildId, text("grand")), f.nextNotification())
    }

    @Test
    fun `an explicit parent naming a descendant puts the announcement on that parent's stream`() = withSubagents { f ->
        val child = announceChild(f, childId)
        child.subagents.announce(grandchildId, { TestSubagent(flowOf(announcement(grandchildId))) })
        f.nextNotification()

        val greatGrandchildId = SessionId("great-grandchild")
        f.rootSubagents.announce(greatGrandchildId, { TestSubagent(flowOf(announcement(greatGrandchildId))) }, parent = grandchildId)
        f.rootSubagents.announce(siblingId, { TestSubagent(flowOf(announcement(siblingId))) }, parent = rootId)

        assertEquals(SessionNotification(grandchildId, announcement(greatGrandchildId)), f.nextNotification())
        assertEquals(SessionNotification(rootId, announcement(siblingId)), f.nextNotification())
    }

    // Root session checks

    @Test
    fun `a root rejects any SubagentUpdate, with and without the capability`() {
        for (capability in listOf(true, false)) {
            withSubagents(subagentsCapability = capability) { f ->
                val failure = assertFailsWith<IllegalArgumentException> { f.rootClient.notify(announcement(childId)) }
                assertTrue(failure.message!!.contains("subagents.announce"), failure.message)
                f.assertNothingMoreSent()
            }
        }
    }

    @Test
    fun `a root prompt flow fails on a SubagentUpdate`() = withSubagents { f ->
        f.root.promptHandler = { emit(Event.SessionUpdateEvent(announcement(childId))) }

        assertPromptFails(f)
    }

    @Test
    fun `a root without the capability rejects subagents updates`() = withSubagents(subagentsCapability = false) { f ->
        for (update in subagentsDefinedUpdates()) {
            val failure = assertFailsWith<IllegalStateException> { f.rootClient.notify(update) }
            assertEquals("Client does not support subagents capability", failure.message)
        }
        f.assertNothingMoreSent()

        val plainToolCall = SessionUpdate.ToolCall(
            ToolCallId("plain"),
            "Plain",
            content = listOf(ToolCallContent.Content(ContentBlock.Text("output"))),
        )
        f.rootClient.notify(plainToolCall)
        assertEquals(SessionNotification(rootId, plainToolCall), f.nextNotification())
    }

    @Test
    fun `a root with the capability sends subagents updates`() = withSubagents { f ->
        for (update in subagentsDefinedUpdates()) {
            f.rootClient.notify(update)
            assertEquals(SessionNotification(rootId, update), f.nextNotification())
        }
    }

    @Test
    fun `a root prompt flow without the capability fails on a session message`() =
        withSubagents(subagentsCapability = false) { f ->
            f.root.promptHandler = { emit(Event.SessionUpdateEvent(subagentsDefinedUpdates().first())) }

            assertPromptFails(f)
        }

    @Test
    fun `a root prompt flow with the capability sends a session message`() = withSubagents { f ->
        val message = subagentsDefinedUpdates().first()
        f.root.promptHandler = { emit(Event.SessionUpdateEvent(message)) }

        val (response, notifications) = f.transport.testRequest(
            AcpMethod.AgentMethods.V1.SessionPrompt,
            PromptRequest(rootId, listOf(ContentBlock.Text("go"))),
        )
        assertEquals(StopReason.END_TURN, response?.stopReason)
        assertEquals(listOf(SessionNotification(rootId, message)), notifications.map { it.sessionNotificationOrNull() })
    }

    // Collection lifetime

    @Test
    fun `a failing collection does not affect a sibling`() = withSubagents { f ->
        val failed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.rootSubagents.announce(childId, {
            TestSubagent(flow {
                emit(announcement(childId))
                release.await()
                throw RuntimeException("child feed down")
            }.onCompletion { failed.complete(Unit) })
        })
        val siblingEvents = Channel<SessionUpdate>(Channel.UNLIMITED)
        f.rootSubagents.announce(siblingId, {
            TestSubagent(flow {
                emit(announcement(siblingId))
                emitAll(siblingEvents)
            })
        })
        repeat(2) { f.nextNotification() }

        release.complete(Unit)
        withTimeout(5.seconds) { failed.await() }
        siblingEvents.send(text("sibling alive"))

        assertEquals(SessionNotification(siblingId, text("sibling alive")), f.nextNotification())
        assertNotNull(f.agent.subagentOrNull(childId))
        siblingEvents.close()
    }

    @Test
    fun `collections are cancelled when the protocol closes`() = withSubagents { f ->
        val stopped = announceEndlessChild(f)

        f.protocol.close()

        withTimeout(5.seconds) { stopped.await() }
    }

    @Test
    fun `collections are cancelled when the transport ends`() = withSubagents { f ->
        val stopped = announceEndlessChild(f)

        f.transport.close()

        withTimeout(5.seconds) { stopped.await() }
    }

    // session/cancel on a child id

    @Test
    fun `session cancel on a granted child calls its cancel in the child's context`() = withSubagents { f ->
        val cancelClients = Channel<ClientSessionOperations>(Channel.UNLIMITED)
        val events = Channel<SessionUpdate>(Channel.UNLIMITED)
        lateinit var context: AgentSubagentContext
        f.rootSubagents.announce(childId, {
            context = it
            it.client.notify(announcement(childId, granted = true))
            TestSubagent(events.consumeAsFlow(), onCancel = { cancelClients.send(currentCoroutineContext().client) })
        })
        f.nextNotification()

        f.fireCancel(childId)

        assertSame(context.client, withTimeout(5.seconds) { cancelClients.receive() })
        events.send(text("still collected"))
        assertEquals(SessionNotification(childId, text("still collected")), f.nextNotification())
        f.assertNothingMoreSent()
        events.close()
    }

    @Test
    fun `session cancel on a child without a grant is ignored`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        f.rootSubagents.announce(childId, {
            it.client.notify(announcement(childId))
            TestSubagent(onCancel = { cancels.send(childId) })
        })
        f.nextNotification()
        announceProbe(f, cancels)

        assertCancelIgnored(f, childId, cancels)
    }

    @Test
    fun `session cancel after the grant is revoked is ignored`() {
        val revocations = listOf(
            SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Null),
            SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(SubagentSessionCapabilities())),
        )
        for (revocation in revocations) {
            withSubagents { f ->
                val cancels = Channel<SessionId>(Channel.UNLIMITED)
                val events = Channel<SessionUpdate>(Channel.UNLIMITED)
                events.send(announcement(childId, granted = true))
                events.send(revocation)
                f.rootSubagents.announce(childId, {
                    TestSubagent(events.consumeAsFlow(), onCancel = { cancels.send(childId) })
                })
                repeat(2) { f.nextNotification() }
                announceProbe(f, cancels)

                assertCancelIgnored(f, childId, cancels)
                events.close()
            }
        }
    }

    @Test
    fun `session cancel handled while the grant is still being sent is routed`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        announceProbe(f, cancels)
        val child = announceGranted(f.rootSubagents, childId, cancels, granted = false)
        f.nextNotification()
        // The blocked send below holds the child's lock, which the end of its feed would wait for.
        f.awaitCollectionsEnded()
        val grantUpdate = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(grant()))
        var firstCancelled: SessionId? = null
        /*
         * The grant's send doesn't return until a cancel has been handled, so the child's cancel is routed only if the
         * grant was recorded before the send. Otherwise the probe's cancel, handled after it, is the first.
         */
        f.transport.onSend = { message ->
            if (message.sessionNotificationOrNull()?.update == grantUpdate) {
                f.fireCancel(childId)
                f.fireCancel(probeId)
                firstCancelled = runBlocking { withTimeout(5.seconds) { cancels.receive() } }
            }
        }

        // Off the thread that reads incoming messages, which the blocked send would otherwise stall.
        withContext(Dispatchers.IO) { child.client.notify(grantUpdate) }

        assertEquals(childId, firstCancelled)
        assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
    }

    @Test
    fun `a throwing cancel does not break the connection`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        f.rootSubagents.announce(childId, {
            it.client.notify(announcement(childId, granted = true))
            TestSubagent(onCancel = {
                cancels.send(childId)
                throw RuntimeException("cancel failed")
            })
        })
        f.nextNotification()
        announceProbe(f, cancels)

        repeat(2) {
            f.fireCancel(childId)
            assertEquals(childId, withTimeout(5.seconds) { cancels.receive() })
        }
        f.fireCancel(probeId)
        assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
        val (response, _) = f.transport.testRequest(
            AcpMethod.AgentMethods.V1.SessionPrompt,
            PromptRequest(rootId, listOf(ContentBlock.Text("go"))),
        )
        assertEquals(StopReason.END_TURN, response?.stopReason)
    }

    @Test
    fun `session cancel for a child whose factory threw after announcing is ignored`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        assertFailsWith<RuntimeException> {
            f.rootSubagents.announce(childId, {
                it.client.notify(announcement(childId, granted = true))
                throw RuntimeException("factory failed late")
            })
        }
        repeat(2) { f.nextNotification() }
        announceProbe(f, cancels)

        assertCancelIgnored(f, childId, cancels)
        assertNotNull(f.agent.subagentOrNull(childId))
    }

    @Test
    fun `session cancel while a granted factory runs is delivered once, after it returns`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        val cancelClients = Channel<ClientSessionOperations>(Channel.UNLIMITED)
        announceProbe(f, cancels)
        lateinit var context: AgentSubagentContext
        val announced = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val announcing = async {
            f.rootSubagents.announce(childId, {
                context = it
                it.client.notify(announcement(childId, granted = true))
                announced.complete(Unit)
                release.await()
                TestSubagent(onCancel = {
                    cancelClients.send(currentCoroutineContext().client)
                    cancels.send(childId)
                })
            })
        }
        withTimeout(5.seconds) { announced.await() }
        f.nextNotification()

        // Two cancels while the factory runs: the probe's cancel proves both were handled, and held.
        repeat(2) { f.fireCancel(childId) }
        f.fireCancel(probeId)
        assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
        assertTrue(cancels.isEmpty)
        release.complete(Unit)
        withTimeout(5.seconds) { announcing.await() }

        assertEquals(childId, withTimeout(5.seconds) { cancels.receive() })
        assertSame(context.client, cancelClients.receive())
        f.awaitCollectionsEnded()
        assertTrue(cancels.isEmpty)
    }

    @Test
    fun `session cancel while the factory runs without a grant is not delivered`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        announceProbe(f, cancels)
        val announced = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val announcing = async {
            f.rootSubagents.announce(childId, {
                it.client.notify(announcement(childId))
                announced.complete(Unit)
                release.await()
                // Granted only after the cancel arrived: the grant in effect on arrival decides.
                it.client.notify(SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(grant())))
                TestSubagent(onCancel = { cancels.send(childId) })
            })
        }
        withTimeout(5.seconds) { announced.await() }
        f.nextNotification()

        assertCancelIgnored(f, childId, cancels)
        release.complete(Unit)
        withTimeout(5.seconds) { announcing.await() }
        f.nextNotification()

        f.awaitCollectionsEnded()
        assertTrue(cancels.isEmpty)
    }

    @Test
    fun `a held session cancel is dropped when the grant is revoked before the factory returns`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        announceProbe(f, cancels)
        val announced = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val announcing = async {
            f.rootSubagents.announce(childId, {
                it.client.notify(announcement(childId, granted = true))
                announced.complete(Unit)
                release.await()
                // The grant in effect on delivery decides.
                it.client.notify(SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Null))
                TestSubagent(onCancel = { cancels.send(childId) })
            })
        }
        withTimeout(5.seconds) { announced.await() }
        f.nextNotification()

        f.fireCancel(childId)
        f.fireCancel(probeId)
        assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
        release.complete(Unit)
        withTimeout(5.seconds) { announcing.await() }
        f.nextNotification()

        f.awaitCollectionsEnded()
        assertTrue(cancels.isEmpty)
    }

    @Test
    fun `session cancel while a replaying factory runs is not held`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        announceProbe(f, cancels)
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId, granted = true))
                // A history grant is never in effect, so this cancel is ignored rather than held.
                f.fireCancel(childId)
                f.fireCancel(probeId)
                assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
                TestSubagent(onCancel = { cancels.send(childId) })
            })
        }

        assertIs<JsonRpcSuccessResponse>(f.load(loaded).last())

        f.awaitCollectionsEnded(loaded.wrapper())
        assertTrue(cancels.isEmpty)
    }

    @Test
    fun `session cancel is still routed after the feed ended`() = withSubagents { f ->
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        val running = stateUpdate(childId, StateUpdate.Running())
        f.rootSubagents.announce(childId, {
            TestSubagent(flowOf(announcement(childId, granted = true), running), onCancel = { cancels.send(childId) })
        })
        repeat(2) { f.nextNotification() }
        assertEquals(SessionNotification(rootId, stateUpdate(childId, StateUpdate.Unknown())), f.nextNotification())
        f.awaitCollectionsEnded()

        f.fireCancel(childId)

        assertEquals(childId, withTimeout(5.seconds) { cancels.receive() })
        assertTrue(assertNotNull(f.agent.subagentOrNull(childId)).isCancelGranted)
    }

    // Other session methods on a child id

    @Test
    fun `session methods on a child id fail because it is a subagent`() = withSubagents { f ->
        announceChild(f, childId)
        val unknownId = SessionId("unknown")

        for (id in listOf(childId, unknownId)) {
            for ((name, request) in sessionRequests(f, id)) {
                val response = assertIs<JsonRpcErrorResponse>(request(), "$name on $id")
                val isSubagentError = response.error.message == "Session $id is a subagent"
                assertEquals(id == childId, isSubagentError, "$name on $id: ${response.error}")
            }
        }
        assertNotNull(f.agent.subagentOrNull(childId))
        f.assertNothingMoreSent()
    }

    @Test
    fun `session methods that look sessions up keep answering not found for an unknown id`() = withSubagents { f ->
        val unknownId = SessionId("unknown")
        val lookups = setOf("prompt", "set_mode", "set_model", "set_config_option", "close")

        for ((name, request) in sessionRequests(f, unknownId).filter { it.first in lookups }) {
            val response = assertIs<JsonRpcErrorResponse>(request(), name)
            assertEquals("Session $unknownId not found", response.error.message, name)
        }
    }

    // Feed end

    @Test
    fun `a feed that ends while the child is active reports unknown`() = withSubagents { f ->
        val activeStates = listOf(StateUpdate.Running(), StateUpdate.RequiresAction(), StateUpdate.Other("paused"))
        for ((index, state) in activeStates.withIndex()) {
            for (fails in listOf(false, true)) {
                val id = SessionId("child-$index-$fails")
                f.rootSubagents.announce(id, {
                    TestSubagent(flow {
                        emit(announcement(id))
                        emit(stateUpdate(id, state))
                        if (fails) throw RuntimeException("feed down")
                    })
                })

                assertEquals(SessionNotification(rootId, announcement(id)), f.nextNotification())
                assertEquals(SessionNotification(rootId, stateUpdate(id, state)), f.nextNotification())
                assertEquals(SessionNotification(rootId, stateUpdate(id, StateUpdate.Unknown())), f.nextNotification())
                f.awaitCollectionsEnded()
                f.assertNothingMoreSent()
                assertNotNull(f.agent.subagentOrNull(id))
            }
        }
    }

    @Test
    fun `a feed that ends while the child is not active sends nothing`() = withSubagents { f ->
        val endings = listOf(
            "idle" to listOf(StateUpdate.Running(), StateUpdate.Idle(IdleStopReason.EndTurn)),
            "unknown" to listOf(StateUpdate.Running(), StateUpdate.Unknown()),
            "none" to emptyList(),
            "cleared" to listOf(StateUpdate.Running(), null),
        )
        for ((name, states) in endings) {
            for (fails in listOf(false, true)) {
                val id = SessionId("child-$name-$fails")
                val updates = states.map { state ->
                    SessionUpdate.SubagentUpdate(id, state = state?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Null)
                }
                f.rootSubagents.announce(id, {
                    TestSubagent(flow {
                        emit(announcement(id))
                        updates.forEach { emit(it) }
                        if (fails) throw RuntimeException("feed down")
                    })
                })

                assertEquals(SessionNotification(rootId, announcement(id)), f.nextNotification())
                for (update in updates) assertEquals(SessionNotification(rootId, update), f.nextNotification())
                f.awaitCollectionsEnded()
                f.assertNothingMoreSent()
                assertNotNull(f.agent.subagentOrNull(id))
            }
        }
    }

    @Test
    fun `a cancellation thrown by the app's feed reports unknown and completes the collection`() = withSubagents { f ->
        val collectionJob = CompletableDeferred<Job>()
        f.rootSubagents.announce(childId, {
            TestSubagent(flow {
                collectionJob.complete(currentCoroutineContext().job)
                emit(announcement(childId))
                emit(stateUpdate(childId, StateUpdate.Running()))
                throw CancellationException("app timeout")
            })
        })

        repeat(2) { f.nextNotification() }
        assertEquals(SessionNotification(rootId, stateUpdate(childId, StateUpdate.Unknown())), f.nextNotification())
        val collection = withTimeout(5.seconds) { collectionJob.await() }
        withTimeout(5.seconds) { collection.join() }
        assertFalse(collection.isCancelled)
        f.assertNothingMoreSent()
    }

    @Test
    fun `a collection the SDK cancels sends nothing, however the feed ends`() {
        val endings: List<suspend () -> Unit> = listOf(
            { awaitCancellation() },
            { runCatching { awaitCancellation() } },
            { runCatching { awaitCancellation() }; throw RuntimeException("feed down on cancel") },
        )
        for (ending in endings) {
            withSubagents { f ->
                val waiting = CompletableDeferred<Unit>()
                f.rootSubagents.announce(childId, {
                    TestSubagent(flow {
                        emit(announcement(childId))
                        emit(stateUpdate(childId, StateUpdate.Running()))
                        waiting.complete(Unit)
                        ending()
                    })
                })
                repeat(2) { f.nextNotification() }
                withTimeout(5.seconds) { waiting.await() }

                f.rootWrapper.subtreeScope.cancel()

                f.awaitCollectionsEnded()
                f.assertNothingMoreSent()
            }
        }
    }

    @Test
    fun `state sent through the child's client operations does not make a feed owe unknown`() = withSubagents { f ->
        val running = stateUpdate(childId, StateUpdate.Running())
        f.rootSubagents.announce(childId, {
            it.client.notify(announcement(childId, state = StateUpdate.Running()))
            TestSubagent(flow { currentCoroutineContext().client.notify(running) })
        })

        assertEquals(SessionNotification(rootId, announcement(childId, state = StateUpdate.Running())), f.nextNotification())
        assertEquals(SessionNotification(rootId, running), f.nextNotification())
        f.awaitCollectionsEnded()
        f.assertNothingMoreSent()
    }

    @Test
    fun `a child whose feed ended keeps its id and can be named as a parent`() = withSubagents { f ->
        f.rootSubagents.announce(childId, { TestSubagent(flowOf(announcement(childId))) })
        f.nextNotification()
        f.awaitCollectionsEnded()

        assertFailsWith<IllegalArgumentException> { f.rootSubagents.announce(childId, { TestSubagent() }) }
        f.rootSubagents.announce(grandchildId, { TestSubagent(flowOf(announcement(grandchildId))) }, parent = childId)

        assertEquals(SessionNotification(childId, announcement(grandchildId)), f.nextNotification())
    }

    @Test
    fun `a feed that ends while active does not affect a sibling's collection`() = withSubagents { f ->
        val release = CompletableDeferred<Unit>()
        f.rootSubagents.announce(childId, {
            TestSubagent(flow {
                emit(announcement(childId))
                emit(stateUpdate(childId, StateUpdate.Running()))
                release.await()
                throw RuntimeException("child feed down")
            })
        })
        val siblingEvents = Channel<SessionUpdate>(Channel.UNLIMITED)
        f.rootSubagents.announce(siblingId, {
            TestSubagent(flow {
                emit(announcement(siblingId))
                emit(stateUpdate(siblingId, StateUpdate.Running()))
                emitAll(siblingEvents)
            })
        })
        repeat(4) { f.nextNotification() }

        release.complete(Unit)
        assertEquals(SessionNotification(rootId, stateUpdate(childId, StateUpdate.Unknown())), f.nextNotification())
        siblingEvents.send(text("sibling alive"))

        assertEquals(SessionNotification(siblingId, text("sibling alive")), f.nextNotification())
        f.assertNothingMoreSent()
        siblingEvents.close()
    }

    // Subtree cleanup

    @Test
    fun `closing a root sends the children's final updates before the response and nothing about them after it`() =
        withSubagents { f ->
            val closing = RootSession(closingId)
            val closingContext = f.newRoot(closing)
            val childStopped = CompletableDeferred<Unit>()
            val grandchildStopped = CompletableDeferred<Unit>()
            val child = announceActive(assertNotNull(closingContext.subagents), childId, childStopped)
            assertEquals(closingId, f.nextNotification().sessionId)
            val grandchild = announceActive(child.subagents, grandchildId, grandchildStopped)
            assertEquals(childId, f.nextNotification().sessionId)

            closing.onClose = {
                child.client.notify(text("final child"))
                grandchild.client.notify(text("final grandchild"))
            }
            val received = f.close(closingId)

            assertEquals(
                listOf(SessionNotification(childId, text("final child")), SessionNotification(grandchildId, text("final grandchild"))),
                received.dropLast(1).map { it.sessionNotificationOrNull() },
            )
            assertIs<JsonRpcSuccessResponse>(received.last())
            withTimeout(5.seconds) {
                childStopped.await()
                grandchildStopped.await()
            }
            f.awaitCollectionsEnded(closingContext.rootWrapper)
            f.assertNothingMoreSent()
        }

    @Test
    fun `after a root is closed its subagents are gone at every depth`() = withSubagents { f ->
        val closingContext = f.newRoot(RootSession(closingId))
        val closingSubagents = assertNotNull(closingContext.subagents)
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        val child = announceGranted(closingSubagents, childId, cancels)
        val grandchild = announceGranted(child.subagents, grandchildId, cancels)
        repeat(2) { f.nextNotification() }

        assertIs<JsonRpcSuccessResponse>(f.close(closingId).single())

        for ((id, context) in listOf(childId to child, grandchildId to grandchild)) {
            assertNull(f.agent.subagentOrNull(id))
            for (operation in childOperations(context)) {
                val failure = assertFailsWith<IllegalStateException> { operation() }
                assertTrue(failure.message!!.contains("not registered"), failure.message)
            }
            assertFailsWith<IllegalStateException> { context.subagents.announce(SessionId("late"), { TestSubagent() }) }
            assertFailsWith<IllegalArgumentException> {
                f.rootSubagents.announce(SessionId("orphan"), { TestSubagent() }, parent = id)
            }
        }
        assertFailsWith<IllegalStateException> { closingSubagents.announce(SessionId("late"), { TestSubagent() }) }

        announceProbe(f, cancels)
        assertCancelIgnored(f, childId, cancels)
        assertCancelIgnored(f, grandchildId, cancels)
        f.assertNothingMoreSent()

        val reused = announceChild(f, childId)
        reused.subagents.announce(grandchildId, { TestSubagent(flowOf(announcement(grandchildId))) })
        assertEquals(SessionNotification(childId, announcement(grandchildId)), f.nextNotification())
    }

    @Test
    fun `a close that throws leaves the subagents registered and working`() = withSubagents { f ->
        val closing = RootSession(closingId)
        val closingContext = f.newRoot(closing)
        val events = Channel<SessionUpdate>(Channel.UNLIMITED)
        lateinit var child: AgentSubagentContext
        assertNotNull(closingContext.subagents).announce(childId, {
            child = it
            it.client.notify(announcement(childId))
            TestSubagent(events.consumeAsFlow())
        })
        f.nextNotification()
        closing.onClose = { throw RuntimeException("close failed") }

        assertIs<JsonRpcErrorResponse>(f.close(closingId).single())

        assertTrue(assertNotNull(f.agent.subagentOrNull(childId)).isAnnounced)
        events.send(text("from events"))
        assertEquals(SessionNotification(childId, text("from events")), f.nextNotification())
        child.client.notify(text("from client"))
        assertEquals(SessionNotification(childId, text("from client")), f.nextNotification())
        child.subagents.announce(grandchildId, { TestSubagent(flowOf(announcement(grandchildId))) })
        assertEquals(SessionNotification(childId, announcement(grandchildId)), f.nextNotification())
        assertNotNull(closingContext.subagents).announce(siblingId, { TestSubagent(flowOf(announcement(siblingId))) })
        assertEquals(SessionNotification(closingId, announcement(siblingId)), f.nextNotification())
        events.close()
    }

    /*
     * Nothing can be sent once the connection has ended, so whether the SDK would report `unknown` for these
     * collections can't be observed here. `a collection the SDK cancels sends nothing, however the feed ends` covers
     * that rule.
     */
    @Test
    fun `the connection's end cancels the collections at every depth`() {
        val endings: List<(Fixture) -> Unit> = listOf({ it.protocol.close() }, { it.transport.close() })
        for (ending in endings) {
            withSubagents { f ->
                val childStopped = CompletableDeferred<Unit>()
                val grandchildStopped = CompletableDeferred<Unit>()
                val child = announceActive(f.rootSubagents, childId, childStopped)
                f.nextNotification()
                announceActive(child.subagents, grandchildId, grandchildStopped)
                f.nextNotification()

                ending(f)

                withTimeout(5.seconds) {
                    childStopped.await()
                    grandchildStopped.await()
                }
            }
        }
    }

    @Test
    fun `a feed that ends leaves the collections of the child's subagents running`() = withSubagents { f ->
        val release = CompletableDeferred<Unit>()
        lateinit var child: AgentSubagentContext
        f.rootSubagents.announce(childId, {
            child = it
            TestSubagent(flow {
                emit(announcement(childId, state = StateUpdate.Running()))
                release.await()
            })
        })
        f.nextNotification()
        val grandchildEvents = Channel<SessionUpdate>(Channel.UNLIMITED)
        child.subagents.announce(grandchildId, {
            TestSubagent(flow {
                emit(announcement(grandchildId))
                emitAll(grandchildEvents)
            })
        })
        f.nextNotification()

        release.complete(Unit)
        assertEquals(SessionNotification(rootId, stateUpdate(childId, StateUpdate.Unknown())), f.nextNotification())
        grandchildEvents.send(text("grandchild alive"))

        assertEquals(SessionNotification(grandchildId, text("grandchild alive")), f.nextNotification())
        assertNotNull(f.agent.subagentOrNull(childId))
        grandchildEvents.close()
    }

    // Replay during session/load

    @Test
    fun `a replayed child's announcement and history precede the load response and its events follow it`() =
        withSubagents { f ->
            val loaded = RootSession(loadedId)
            var announced: AgentSubagentSession? = null
            val session = TestSubagent(flowOf(text("live")))
            loaded.replay = {
                announced = currentSubagents().announce(childId, { context ->
                    context.client.notify(announcement(childId))
                    context.client.notify(text("history"))
                    session
                })
            }

            val received = f.load(loaded)

            assertSame(session, announced)
            assertEquals(
                listOf(SessionNotification(loadedId, announcement(childId)), SessionNotification(childId, text("history"))),
                received.dropLast(1).map { it.sessionNotificationOrNull() },
            )
            assertIs<JsonRpcSuccessResponse>(received.last())
            assertEquals(SessionNotification(childId, text("live")), f.nextNotification())
        }

    @Test
    fun `a history grant does not route session cancel, a fresh grant after the response does`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        val events = Channel<SessionUpdate>(Channel.UNLIMITED)
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId, granted = true))
                TestSubagent(events.consumeAsFlow(), onCancel = { cancels.send(childId) })
            })
        }
        assertIs<JsonRpcSuccessResponse>(f.load(loaded).last())

        assertFalse(assertNotNull(f.agent.subagentOrNull(childId)).isCancelGranted)
        announceProbe(f, cancels)
        assertCancelIgnored(f, childId, cancels)

        val freshGrant = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(grant()))
        events.send(freshGrant)
        assertEquals(SessionNotification(loadedId, freshGrant), f.nextNotification())
        f.fireCancel(childId)
        assertEquals(childId, withTimeout(5.seconds) { cancels.receive() })
        events.close()
    }

    @Test
    fun `a history running state does not make an ending feed owe unknown`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId, state = StateUpdate.Running()))
                TestSubagent()
            })
        }

        val received = f.load(loaded)

        assertEquals(2, received.size, "$received")
        f.awaitCollectionsEnded(loaded.wrapper())
        f.assertNothingMoreSent()
        assertNotNull(f.agent.subagentOrNull(childId))
    }

    @Test
    fun `a replaying factory that throws after announcing sends no correction`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        var failure: Throwable? = null
        loaded.replay = {
            failure = runCatching {
                currentSubagents().announce(childId, { context ->
                    context.client.notify(announcement(childId, granted = true, state = StateUpdate.Running()))
                    throw UncopiedException("factory failed late")
                })
            }.exceptionOrNull()
        }

        val received = f.load(loaded)

        assertIs<UncopiedException>(failure)
        assertEquals(
            listOf(SessionNotification(loadedId, announcement(childId, granted = true, state = StateUpdate.Running()))),
            received.dropLast(1).map { it.sessionNotificationOrNull() },
        )
        assertIs<JsonRpcSuccessResponse>(received.last())
        f.assertNothingMoreSent()
        assertNotNull(f.agent.subagentOrNull(childId))
    }

    @Test
    fun `a replaying factory that returns without announcing fails announce right away`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        var failure: Throwable? = null
        loaded.replay = {
            failure = runCatching {
                currentSubagents().announce(childId, { TestSubagent(flowOf(announcement(childId))) })
            }.exceptionOrNull()
        }

        val received = f.load(loaded)

        assertIs<IllegalStateException>(failure)
        assertIs<JsonRpcSuccessResponse>(received.single())
        assertNull(f.agent.subagentOrNull(childId))
        f.awaitCollectionsEnded(loaded.wrapper())
        f.assertNothingMoreSent()
    }

    @Test
    fun `a replaying factory replays its own subagents the same way`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        var nestedFailure: Throwable? = null
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId))
                context.subagents.announce(grandchildId, { grandchild ->
                    grandchild.client.notify(announcement(grandchildId))
                    grandchild.client.notify(text("grandchild history"))
                    TestSubagent(flowOf(text("grandchild live")))
                })
                nestedFailure = runCatching {
                    context.subagents.announce(siblingId, { TestSubagent(flowOf(announcement(siblingId))) })
                }.exceptionOrNull()
                TestSubagent(flowOf(text("child live")))
            })
        }

        val received = f.load(loaded)

        assertEquals(
            listOf(
                SessionNotification(loadedId, announcement(childId)),
                SessionNotification(childId, announcement(grandchildId)),
                SessionNotification(grandchildId, text("grandchild history")),
            ),
            received.dropLast(1).map { it.sessionNotificationOrNull() },
        )
        assertIs<JsonRpcSuccessResponse>(received.last())
        assertIs<IllegalStateException>(nestedFailure)
        assertNull(f.agent.subagentOrNull(siblingId))
        assertEquals(
            setOf(SessionNotification(childId, text("child live")), SessionNotification(grandchildId, text("grandchild live"))),
            setOf(f.nextNotification(), f.nextNotification()),
        )
    }

    @Test
    fun `replayed collections start without waiting for postInitialize`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        val liveSeen = CompletableDeferred<Unit>()
        val initialized = CompletableDeferred<Unit>()
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId))
                TestSubagent(flowOf(text("live")))
            })
        }
        // Blocks until the child's live event has been seen, so a collection that waited for it would never start.
        loaded.afterInitialize = {
            liveSeen.await()
            initialized.complete(Unit)
        }

        assertIs<JsonRpcSuccessResponse>(f.load(loaded).last())

        assertEquals(SessionNotification(childId, text("live")), f.nextNotification())
        assertFalse(initialized.isCompleted)
        liveSeen.complete(Unit)
        withTimeout(5.seconds) { initialized.await() }
    }

    @Test
    fun `replayed collections keep running when postInitialize throws`() = withSubagents { f ->
        val loaded = RootSession(loadedId)
        val events = Channel<SessionUpdate>(Channel.UNLIMITED)
        val failed = CompletableDeferred<Unit>()
        loaded.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId))
                TestSubagent(events.consumeAsFlow())
            })
        }
        loaded.afterInitialize = {
            try {
                throw RuntimeException("postInitialize failed")
            } finally {
                failed.complete(Unit)
            }
        }

        assertIs<JsonRpcSuccessResponse>(f.load(loaded).last())
        withTimeout(5.seconds) { failed.await() }

        events.send(text("still collected"))
        assertEquals(SessionNotification(childId, text("still collected")), f.nextNotification())
        events.close()
    }

    @Test
    fun `a failed load drops its subagents and a retried load can announce the same ids`() = withSubagents { f ->
        val failing = RootSession(loadedId)
        lateinit var failingSubagents: AgentSubagents
        lateinit var child: AgentSubagentContext
        failing.replay = {
            failingSubagents = currentSubagents()
            failingSubagents.announce(childId, { context ->
                child = context
                context.client.notify(announcement(childId))
                context.subagents.announce(grandchildId, { grandchild ->
                    grandchild.client.notify(announcement(grandchildId))
                    TestSubagent(flowOf(text("must never be collected")))
                })
                TestSubagent(flowOf(text("must never be collected")))
            })
            throw RuntimeException("replay failed")
        }

        val failed = f.load(failing)

        assertEquals(3, failed.size, "$failed")
        assertIs<JsonRpcErrorResponse>(failed.last())
        assertNull(f.agent.subagentOrNull(childId))
        assertNull(f.agent.subagentOrNull(grandchildId))
        assertFailsWith<IllegalStateException> { child.client.notify(text("late")) }
        assertFailsWith<IllegalStateException> { failingSubagents.announce(siblingId, { TestSubagent() }) }

        val retried = RootSession(loadedId)
        retried.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId))
                context.subagents.announce(grandchildId, { grandchild ->
                    grandchild.client.notify(announcement(grandchildId))
                    TestSubagent()
                })
                TestSubagent()
            })
        }
        val received = f.load(retried)

        assertEquals(
            listOf(SessionNotification(loadedId, announcement(childId)), SessionNotification(childId, announcement(grandchildId))),
            received.dropLast(1).map { it.sessionNotificationOrNull() },
        )
        assertIs<JsonRpcSuccessResponse>(received.last())
        f.awaitCollectionsEnded(retried.wrapper())
        f.assertNothingMoreSent()
    }

    @Test
    fun `a load whose response can't be built drops the session and its replayed subagents`() = withSubagents { f ->
        val failing = RootSession(loadedId)
        lateinit var child: AgentSubagentContext
        failing.replay = {
            currentSubagents().announce(childId, { context ->
                child = context
                context.client.notify(announcement(childId))
                TestSubagent(flowOf(text("must never be collected")))
            })
        }
        failing.responseFailure = RuntimeException("modes unavailable")

        val failed = f.load(failing)

        assertEquals(2, failed.size, "$failed")
        assertIs<JsonRpcErrorResponse>(failed.last())
        assertNull(f.agent.subagentOrNull(childId))
        assertFailsWith<IllegalStateException> { child.client.notify(text("late")) }
        assertIs<JsonRpcErrorResponse>(
            f.request(AcpMethod.AgentMethods.V1.SessionPrompt, PromptRequest(loadedId, listOf(ContentBlock.Text("go"))))
        )
        f.assertNothingMoreSent()

        val retried = RootSession(loadedId)
        retried.replay = {
            currentSubagents().announce(childId, { context ->
                context.client.notify(announcement(childId))
                TestSubagent()
            })
        }
        assertIs<JsonRpcSuccessResponse>(f.load(retried).last())
        assertNotNull(f.agent.subagentOrNull(childId))
    }

    @Test
    fun `a grant sent once the load response is queued is live, before the after-response work starts`() =
        withSubagents { f ->
            val loaded = RootSession(loadedId)
            val handlerThreadReleased = CountDownLatch(1)
            lateinit var child: AgentSubagentContext
            loaded.replay = {
                currentSubagents().announce(childId, { context ->
                    child = context
                    context.client.notify(announcement(childId))
                    TestSubagent()
                })
                /*
                 * Occupy the single handler thread from the moment the load handler suspends to wait for its
                 * response to be queued: its after-response work can't start until the test releases it.
                 */
                val handlerDispatcher = checkNotNull(currentCoroutineContext()[ContinuationInterceptor])
                CoroutineScope(handlerDispatcher).launch { handlerThreadReleased.await(5, TimeUnit.SECONDS) }
            }

            try {
                assertIs<JsonRpcSuccessResponse>(f.load(loaded).last())
                val freshGrant = SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Value(grant()))
                child.client.notify(freshGrant)

                assertEquals(SessionNotification(loadedId, freshGrant), f.nextNotification())
                assertTrue(assertNotNull(f.agent.subagentOrNull(childId)).isCancelGranted)
            } finally {
                handlerThreadReleased.countDown()
            }
        }

    @Test
    fun `subagents announced from postInitialize are live, however the session was opened`() = withSubagents { f ->
        val openings: List<Pair<String, suspend (RootSession) -> Unit>> = listOf(
            "new" to { session -> f.newRoot(session) },
            "resume" to { session -> assertNotNull(f.resume(session)) },
            "load" to { session -> assertIs<JsonRpcSuccessResponse>(f.load(session).single()) },
        )
        val cancels = Channel<SessionId>(Channel.UNLIMITED)
        for ((name, open) in openings) {
            val session = RootSession(SessionId("root-$name"))
            val id = SessionId("child-$name")
            val announced = CompletableDeferred<AgentSubagentSession>()
            session.afterInitialize = {
                /*
                 * A live child may announce itself in its events: a replayed one could not, and its announce
                 * would fail right away.
                 */
                announced.complete(currentSubagents().announce(id, {
                    TestSubagent(flowOf(announcement(id, granted = true)), onCancel = { cancels.send(id) })
                }))
            }

            open(session)

            assertEquals(SessionNotification(session.sessionId, announcement(id, granted = true)), f.nextNotification())
            withTimeout(5.seconds) { announced.await() }
            assertTrue(assertNotNull(f.agent.subagentOrNull(id)).isCancelGranted, name)
            f.fireCancel(id)
            assertEquals(id, withTimeout(5.seconds) { cancels.receive() }, name)
        }
    }

    // Helpers

    private val closingId = SessionId("closing")
    private val loadedId = SessionId("loaded")

    private suspend fun currentSubagents(): AgentSubagents = assertNotNull(currentCoroutineContext().subagents)

    /**
     * This root's wrapper, once its postInitialize ran.
     */
    private suspend fun RootSession.wrapper(): Agent.SessionWrapper = withTimeout(5.seconds) { context.await() }.rootWrapper

    /**
     * Announces a child that reports itself running in hot events that never complete, and completes [stopped] when
     * their collection stops.
     */
    private suspend fun announceActive(
        subagents: AgentSubagents,
        sessionId: SessionId,
        stopped: CompletableDeferred<Unit>,
    ): AgentSubagentContext {
        lateinit var context: AgentSubagentContext
        subagents.announce(sessionId, {
            context = it
            TestSubagent(flow {
                emit(announcement(sessionId, state = StateUpdate.Running()))
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            })
        })
        return context
    }

    /**
     * Announces a child through its factory, granted unless [granted] is `false`, whose cancel sends its id to
     * [cancels].
     */
    private suspend fun announceGranted(
        subagents: AgentSubagents,
        sessionId: SessionId,
        cancels: Channel<SessionId>,
        granted: Boolean = true,
    ): AgentSubagentContext {
        lateinit var context: AgentSubagentContext
        subagents.announce(sessionId, {
            context = it
            it.client.notify(announcement(sessionId, granted = granted))
            TestSubagent(onCancel = { cancels.send(sessionId) })
        })
        return context
    }

    /**
     * A sample of a child's client operations, own announcement included.
     */
    private fun childOperations(context: AgentSubagentContext): List<suspend () -> Unit> = listOf(
        { context.client.notify(text("late")) },
        { context.client.notify(SessionUpdate.SubagentUpdate(context.sessionId, state = MaybeUndefined.Value(StateUpdate.Running()))) },
        { context.client.requestPermissions(SessionUpdate.ToolCallUpdate(ToolCallId("t")), emptyList()) },
        { context.client.fsReadTextFile("/a") },
    )

    private val probeId = SessionId("probe")

    /**
     * Announces a granted child whose cancel sends [probeId] to [cancels], for proving that an earlier
     * `session/cancel` was handled: notifications are handled in wire order.
     */
    private suspend fun announceProbe(f: Fixture, cancels: Channel<SessionId>) {
        f.rootSubagents.announce(probeId, {
            it.client.notify(announcement(probeId, granted = true))
            TestSubagent(onCancel = { cancels.send(probeId) })
        })
        f.nextNotification()
    }

    /**
     * Sends `session/cancel` for [sessionId] and then for the probe, and asserts that only the probe's cancel ran.
     */
    private suspend fun assertCancelIgnored(f: Fixture, sessionId: SessionId, cancels: Channel<SessionId>) {
        f.fireCancel(sessionId)
        f.fireCancel(probeId)
        assertEquals(probeId, withTimeout(5.seconds) { cancels.receive() })
        assertTrue(cancels.isEmpty)
    }

    /**
     * Every session method other than `session/cancel` on [sessionId], by name.
     */
    private fun sessionRequests(f: Fixture, sessionId: SessionId): List<Pair<String, suspend () -> JsonRpcMessage?>> {
        return listOf(
            "prompt" to { f.request(AcpMethod.AgentMethods.V1.SessionPrompt, PromptRequest(sessionId, listOf(ContentBlock.Text("go")))) },
            "set_mode" to { f.request(AcpMethod.AgentMethods.V1.SessionSetMode, SetSessionModeRequest(sessionId, SessionModeId("m"))) },
            "set_model" to { f.request(AcpMethod.AgentMethods.V1.SessionSetModel, SetSessionModelRequest(sessionId, ModelId("m"))) },
            "set_config_option" to {
                f.request(
                    AcpMethod.AgentMethods.V1.SessionSetConfigOption,
                    SetSessionConfigOptionRequest(sessionId, SessionConfigId("c"), SessionConfigOptionValue.of(true)),
                )
            },
            "close" to { f.request(AcpMethod.AgentMethods.V1.SessionClose, CloseSessionRequest(sessionId)) },
            "delete" to { f.request(AcpMethod.AgentMethods.V1.SessionDelete, DeleteSessionRequest(sessionId)) },
            "fork" to { f.request(AcpMethod.AgentMethods.V1.SessionFork, ForkSessionRequest(sessionId, ".", emptyList())) },
            "load" to { f.request(AcpMethod.AgentMethods.V1.SessionLoad, LoadSessionRequest(sessionId, ".", emptyList())) },
            "resume" to { f.request(AcpMethod.AgentMethods.V1.SessionResume, ResumeSessionRequest(sessionId, ".")) },
        )
    }


    private suspend fun announceChild(f: Fixture, sessionId: SessionId): AgentSubagentContext {
        lateinit var context: AgentSubagentContext
        f.rootSubagents.announce(sessionId, {
            context = it
            it.client.notify(announcement(sessionId))
            TestSubagent()
        })
        assertEquals(SessionNotification(rootId, announcement(sessionId)), f.nextNotification())
        return context
    }

    /**
     * Announces a child whose hot events never complete, and returns a signal that completes when their collection
     * stops.
     */
    private suspend fun announceEndlessChild(f: Fixture): CompletableDeferred<Unit> {
        val stopped = CompletableDeferred<Unit>()
        f.rootSubagents.announce(childId, {
            TestSubagent(flow {
                emit(announcement(childId))
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            })
        })
        f.nextNotification()
        assertFalse(stopped.isCompleted)
        return stopped
    }

    private suspend fun assertPromptFails(f: Fixture) {
        val received = f.transport.fireTestRequest(
            AcpMethod.AgentMethods.V1.SessionPrompt.methodName,
            ACPJson.encodeToJsonElement(
                AcpMethod.AgentMethods.V1.SessionPrompt.requestSerializer,
                PromptRequest(rootId, listOf(ContentBlock.Text("go"))),
            ),
        )
        assertIs<JsonRpcErrorResponse>(received.last())
        assertEquals(1, received.size, "Nothing but the error response is sent: $received")
    }

    private fun subagentsDefinedUpdates(): List<SessionUpdate> {
        val reference = ToolCallContent.Session(childId)
        return listOf(
            SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Value(listOf(ContentBlock.Text("hi")))),
            SessionUpdate.SessionMessageChunk(MessageId("m1"), ContentBlock.Text("chunk")),
            SessionUpdate.ToolCall(ToolCallId("spawn"), "Spawn", content = listOf(reference)),
            SessionUpdate.ToolCallUpdate(
                ToolCallId("spawn"),
                content = listOf(ToolCallContent.Content(ContentBlock.Text("output")), reference),
            ),
        )
    }
}

private fun grant() = SubagentSessionCapabilities(cancel = SessionCancelCapabilities())

/**
 * Asserts that [failure] is an ordinary failure caused by a cancellation. On the JVM a [CancellationException] is an
 * [IllegalStateException] itself, so `assertFailsWith<IllegalStateException>` alone would accept it unwrapped.
 */
private fun assertWrapsCancellation(failure: Throwable) {
    assertFalse(failure is CancellationException, failure.stackTraceToString())
    assertTrue(failure.causes().any { it is CancellationException }, failure.stackTraceToString())
}

private val CoroutineContext.rootWrapper: Agent.SessionWrapper
    get() = this[SessionWrapperContextElement.Key]!!.sessionWrapper as Agent.SessionWrapper

private fun announcement(
    sessionId: SessionId,
    granted: Boolean = false,
    state: StateUpdate? = null,
) = SessionUpdate.SubagentUpdate(
    sessionId = sessionId,
    title = MaybeUndefined.Value("Subagent $sessionId"),
    capabilities = if (granted) MaybeUndefined.Value(grant()) else MaybeUndefined.Undefined,
    state = state?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
)

private fun stateUpdate(sessionId: SessionId, state: StateUpdate) =
    SessionUpdate.SubagentUpdate(sessionId, state = MaybeUndefined.Value(state))

private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

/**
 * The causes of this exception. Coroutine stack trace recovery may wrap an exception that crosses a suspension point
 * in a copy whose cause is the original, so tests look for a cause anywhere in the chain.
 */
private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause }

/**
 * An exception that coroutine stack trace recovery leaves as is, for asserting that the very instance is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class UncopiedException(message: String) : RuntimeException(message), CopyableThrowable<UncopiedException> {
    override fun createCopy(): UncopiedException? = null
}

private fun JsonRpcMessage.sessionNotificationOrNull(): SessionNotification? {
    if (this !is JsonRpcNotification || method != AcpMethod.ClientMethods.V1.SessionUpdate.methodName) return null
    return ACPJson.decodeFromJsonElement(AcpMethod.ClientMethods.V1.SessionUpdate.serializer, checkNotNull(params))
}
