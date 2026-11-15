@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.SubagentCapabilities
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
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

class AgentSubagentsTest {

    private val rootId = SessionId("root")
    private val childId = SessionId("child")
    private val siblingId = SessionId("sibling")
    private val grandchildId = SessionId("grandchild")

    private class RootSession(override val sessionId: SessionId) : AgentSession {
        val context = CompletableDeferred<CoroutineContext>()

        @Volatile
        var promptHandler: PromptHandler = {}

        override suspend fun postInitialize() {
            context.complete(checkNotNull(currentCoroutineContext()[SessionWrapperContextElement.Key]))
        }

        override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
            promptHandler(content)
        }
    }

    private class TestSubagent(override val events: Flow<SessionUpdate> = emptyFlow()) : AgentSubagentSession

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
    ) {
        val rootSubagents: AgentSubagents get() = assertNotNull(rootContext.subagents)
        val rootClient get() = rootContext.client

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

    private fun withSubagents(
        subagentsCapability: Boolean = true,
        block: suspend CoroutineScope.(Fixture) -> Unit,
    ) = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        val root = RootSession(rootId)
        val support = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo()
            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession = root
        }
        val agent = Agent(protocol, support)
        protocol.start()
        try {
            val capabilities = ClientCapabilities(subagents = if (subagentsCapability) SubagentCapabilities() else null)
            transport.testRequest(AcpMethod.AgentMethods.V1.Initialize, InitializeRequest(LATEST_PROTOCOL_VERSION, capabilities))
            transport.testRequest(AcpMethod.AgentMethods.V1.SessionNew, NewSessionRequest(".", emptyList()))
            val rootContext = withTimeout(5.seconds) { root.context.await() }
            block(Fixture(transport, protocol, agent, root, rootContext))
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
        assertTrue(failure.causes().any { it is CancellationException }, failure.stackTraceToString())
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
            assertTrue(failure.causes().any { it is CancellationException }, failure.stackTraceToString())
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

    // Helpers

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
