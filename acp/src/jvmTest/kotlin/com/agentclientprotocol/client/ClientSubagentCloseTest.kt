@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Closing a v1 root session closes its subagents with it: their permission requests are answered `cancelled` when
 * the close starts, and the whole tree is removed after a successful response. The tests play the agent on a scripted
 * transport, so they control the exact wire order, and they hold the app's calls on deferreds to pin each race.
 */
class ClientSubagentCloseTest {
    private val parameters = SessionCreationParameters(cwd = "/root", mcpServers = emptyList())

    private val root = SessionId("root")
    private val child = SessionId("child")
    private val grandchild = SessionId("grandchild")

    @Test
    fun `close answers the pending permission requests of every subagent level before its response`() = withConnection { c ->
        val world = SubagentWorld()
        world.configure = { operations -> if (operations.id != root) operations.answerPermission = { awaitCancellation() } }
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child))
        c.agent.sendUpdate(child, announce(grandchild))
        val subagents = listOf(child, grandchild)
        val pending = subagents.map { id -> c.agent.sendRequest(PERMISSION, permissionRequest(id, "pending-$id")) }
        for (id in subagents) world.awaitOperations(id).awaitPermissionRequests(1)

        val closing = async { session.close() }
        val request = c.agent.receiveRequest(CLOSE.methodName)
        for (permission in pending) assertEquals(CANCELLED, decodePermission(c.agent.awaitResponse(permission)))

        // A request between the close request and its response is answered without reaching the app.
        for (id in subagents) {
            val late = c.agent.sendRequest(PERMISSION, permissionRequest(id, "late-$id"))
            assertEquals(CANCELLED, decodePermission(c.agent.awaitResponse(late)), "$id")
            assertEquals(1, world.awaitOperations(id).permissionRequests.value.size, "$id")
        }

        c.agent.answer(request, CLOSE.responseSerializer, CloseSessionResponse())
        closing.await()
    }

    @Test
    fun `after a successful close the tree is unconnected and the closed subagents keep their values`() = withConnection { c ->
        val world = SubagentWorld()
        val other = SessionId("other")
        val otherSession = openLive(c, other, world)
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child, title = "child", capabilities = CANCEL_GRANT, state = StateUpdate.Running()))
        c.agent.sendUpdate(child, announce(grandchild, title = "grandchild"))
        assertIs<JsonRpcSuccessResponse>(c.agent.awaitResponse(c.agent.sendRequest(READ, ReadTextFileRequest(grandchild, "/file"))))
        world.awaitOperations(root).awaitEvents(2)
        world.awaitOperations(child).awaitEvents(2)
        val tree = listOf(root, child, grandchild)
        val eventsBefore = tree.associateWith { world.awaitOperations(it).events.value }
        val subagents = session.subagents
        val childSession = subagents.single()
        assertTrue(childSession.canCancel)

        closeSession(c, session)

        for (id in tree) {
            c.agent.sendUpdate(id, text("after-$id"))
            awaitUnconnected(c, id, text("after-$id"))
            assertIs<JsonRpcErrorResponse>(c.agent.awaitResponse(c.agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))), "$id")
        }
        for ((id, events) in eventsBefore) assertEquals(events, world.awaitOperations(id).events.value, "$id gets no more calls")

        assertEquals(subagents, session.subagents)
        assertEquals(listOf(grandchild), childSession.subagents.map { it.sessionId })
        assertEquals("child", childSession.title)
        assertEquals(StateUpdate.Running(), childSession.state)
        assertEquals("grandchild", childSession.subagents.single().title)
        assertFalse(childSession.canCancel)
        assertFailsWith<IllegalStateException> { childSession.cancel() }
        // Notifications go out in call order, so the first session/cancel after the failed call is the other session's.
        otherSession.cancel()
        assertEquals(other, cancelledSessionId(c.agent), "the closed subagent sent no session/cancel")

        assertReloadRegisters(c, root to child, child to grandchild)
    }

    @Test
    fun `close fails the subagents that have not finished opening and hands their queues to the global handler`() = withConnection { c ->
        val world = SubagentWorld()
        val inNotify = SessionId("in-notify")         // still in its notify, before its marker
        val beforeSwitch = SessionId("before-switch") // has its marker and waits for its parent to complete
        val notSetUp = SessionId("not-set-up")        // announced behind the blocked notify
        val inFactory = SessionId("in-factory")       // its createSubagentOperations runs while close() runs
        val blocking = text("blocking")
        val notifyEntered = CompletableDeferred<Unit>()
        val notifyCancelled = CompletableDeferred<Throwable>()
        val factoryEntered = CompletableDeferred<Unit>()
        val releaseFactory = CompletableDeferred<Unit>()
        world.configure = { operations ->
            when (operations.id) {
                inNotify -> operations.onNotify = { event, _ ->
                    if (event is SessionEvent.Update && event.update == blocking) {
                        notifyEntered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (ce: CancellationException) {
                            notifyCancelled.complete(ce)
                            throw ce
                        }
                    }
                }
                root -> operations.createChild = { id ->
                    if (id == inFactory) {
                        factoryEntered.complete(Unit)
                        releaseFactory.await()
                    }
                    world.create(id)
                }
            }
        }

        val open = async { c.client.loadSession(root, parameters, world.root(root)) }
        val load = c.agent.receiveRequest(LOAD.methodName)
        c.agent.sendUpdate(root, announce(inNotify))
        c.agent.sendUpdate(inNotify, announce(beforeSwitch))
        c.agent.sendUpdate(inNotify, blocking)
        loadResponse(c.agent, load)
        val session = open.await()
        notifyEntered.await()
        world.awaitOperations(beforeSwitch).awaitEvent(TreeOperations.OPENED)

        c.agent.sendUpdate(inNotify, announce(notSetUp))
        c.agent.sendUpdate(notSetUp, text("not-set-up-queued"))
        c.agent.sendUpdate(beforeSwitch, text("before-switch-queued"))
        c.agent.sendUpdate(root, announce(inFactory))
        factoryEntered.await()
        c.agent.sendUpdate(inFactory, text("in-factory-queued"))
        val unfinished = listOf(inNotify, beforeSwitch, notSetUp, inFactory)
        val reads = unfinished.associateWith { c.agent.sendRequest(READ, ReadTextFileRequest(it, "/file")) }
        c.agent.awaitHandled()
        for (read in reads.values) assertNull(c.agent.responseOrNull(read), "a request waits until its subagent has opened")

        // The removal joins the consumers of the first two, which wait in the app's notify and for the parent.
        closeSession(c, session)

        assertIs<CancellationException>(notifyCancelled.await())
        for ((id, read) in reads) {
            val failure = assertIs<JsonRpcErrorResponse>(c.agent.awaitResponse(read), "$id")
            assertEquals("Session $id closed", failure.error.message, "$id")
        }
        awaitUnconnected(c, inNotify, announce(notSetUp))
        awaitUnconnected(c, notSetUp, text("not-set-up-queued"))
        awaitUnconnected(c, beforeSwitch, text("before-switch-queued"))
        awaitUnconnected(c, inFactory, text("in-factory-queued"))
        assertFalse(TreeOperations.OPENED in world.awaitOperations(inNotify).events.value)

        // The factory returns after the close: the subagent is dropped and its id stays free.
        releaseFactory.complete(Unit)
        world.awaitOperations(root).awaitEvent(announce(inFactory))
        assertEquals(listOf(inNotify), session.subagents.map { it.sessionId })
        c.agent.sendUpdate(inFactory, text("in-factory-after"))
        awaitUnconnected(c, inFactory, text("in-factory-after"))

        assertReloadRegisters(c, root to inNotify, inNotify to beforeSwitch, inNotify to notSetUp, root to inFactory)
    }

    @Test
    fun `a grandchild whose setup runs while close runs gets no consumer and leaves its id free`() = withConnection { c ->
        val world = SubagentWorld()
        val factoryJob = CompletableDeferred<Job>()
        val releaseFactory = CompletableDeferred<Unit>()
        world.configure = { operations ->
            if (operations.id == child) operations.createChild = { id ->
                factoryJob.complete(currentCoroutineContext()[Job]!!)
                releaseFactory.await()
                world.create(id)
            }
        }
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child))
        // The child has opened, so its stream is delivered directly, and the grandchild's setup runs in the handler.
        assertIs<JsonRpcSuccessResponse>(c.agent.awaitResponse(c.agent.sendRequest(READ, ReadTextFileRequest(child, "/file"))))
        c.agent.sendUpdate(child, announce(grandchild))
        val setup = factoryJob.await()
        c.agent.sendUpdate(grandchild, text("queued"))
        val read = c.agent.sendRequest(READ, ReadTextFileRequest(grandchild, "/file"))
        c.agent.awaitHandled()

        // close() returns while the factory still waits.
        closeSession(c, session)
        awaitUnconnected(c, grandchild, text("queued"))
        assertEquals("Session $grandchild closed", assertIs<JsonRpcErrorResponse>(c.agent.awaitResponse(read)).error.message)

        releaseFactory.complete(Unit)
        withTimeout(5.seconds) { setup.join() }
        val childSession = session.subagents.single()
        assertTrue(childSession.subagents.isEmpty(), "the closed parent's list stays as it was")
        assertTrue(world.awaitOperations(grandchild).events.value.isEmpty())
        c.agent.sendUpdate(grandchild, text("after"))
        awaitUnconnected(c, grandchild, text("after"))

        assertReloadRegisters(c, root to child, child to grandchild)
    }

    @Test
    fun `an announcement racing the close registers nothing that survives it`() = withConnection { c ->
        val world = SubagentWorld()
        val duringRequest = SessionId("during-request")
        val lateChild = SessionId("late-child")
        val lateGrandchild = SessionId("late-grandchild")
        val notifyEntered = CompletableDeferred<Unit>()
        val notifyCancelled = CompletableDeferred<Unit>()
        val releaseNotify = CompletableDeferred<Unit>()
        world.configure = { operations ->
            if (operations.id == child) operations.onNotify = { event, _ ->
                if (event is SessionEvent.Opened) {
                    notifyEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (ce: CancellationException) {
                        // Holds the removal after it closed the holders' subagents and while it joins this consumer.
                        notifyCancelled.complete(Unit)
                        withContext(NonCancellable) { releaseNotify.await() }
                        throw ce
                    }
                }
            }
        }
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child))
        notifyEntered.await()

        val closing = async { session.close() }
        val request = c.agent.receiveRequest(CLOSE.methodName)
        // Registered between the request and its response, so the removal includes it.
        c.agent.sendUpdate(root, announce(duringRequest))
        world.awaitOperations(duringRequest).awaitEvent(TreeOperations.OPENED)
        c.agent.answer(request, CLOSE.responseSerializer, CloseSessionResponse())

        notifyCancelled.await()
        c.agent.sendUpdate(root, announce(lateChild))
        c.agent.sendUpdate(child, announce(lateGrandchild))
        c.agent.sendUpdate(lateChild, text("late-child-update"))
        c.agent.sendUpdate(lateGrandchild, text("late-grandchild-update"))
        c.agent.awaitHandled()
        releaseNotify.complete(Unit)
        closing.await()

        awaitUnconnected(c, lateChild, text("late-child-update"))
        awaitUnconnected(c, lateGrandchild, text("late-grandchild-update"))
        awaitUnconnected(c, child, announce(lateGrandchild))
        val setups = world.log.value.filter { "->" in it }
        assertEquals(listOf("$root -> $child", "$root -> $duringRequest"), setups)
        assertEquals(listOf(child, duringRequest), session.subagents.map { it.sessionId })
        c.agent.sendUpdate(duringRequest, text("after"))
        awaitUnconnected(c, duringRequest, text("after"))

        assertReloadRegisters(c, root to child, root to duringRequest, root to lateChild, child to lateGrandchild)
    }

    @Test
    fun `a close that a subagent's own notify starts does not wait for itself`() = withConnection { c ->
        val world = SubagentWorld()
        val rootSession = CompletableDeferred<ClientSession>()
        val closed = CompletableDeferred<Result<CloseSessionResponse>>()
        world.configure = { operations ->
            if (operations.id == child) operations.onNotify = { event, _ ->
                // A subagent's marker is delivered by its consumer, before the subagent has finished opening.
                if (event is SessionEvent.Opened) closed.complete(runCatching { rootSession.await().close() })
            }
        }
        rootSession.complete(openLive(c, root, world))
        c.agent.sendUpdate(root, announce(child))
        c.agent.answer(c.agent.receiveRequest(CLOSE.methodName), CLOSE.responseSerializer, CloseSessionResponse())

        assertEquals(CloseSessionResponse(), withTimeout(5.seconds) { closed.await() }.getOrThrow())
        for (id in listOf(root, child)) {
            c.agent.sendUpdate(id, text("after-$id"))
            awaitUnconnected(c, id, text("after-$id"))
        }
    }

    @Test
    fun `after a failed close the tree stays and keeps delivering, and its permission requests stay cancelled`() = withConnection { c ->
        val world = SubagentWorld()
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child))
        c.agent.sendUpdate(child, announce(grandchild))
        assertIs<JsonRpcSuccessResponse>(c.agent.awaitResponse(c.agent.sendRequest(READ, ReadTextFileRequest(grandchild, "/file"))))

        val closing = async { runCatching { session.close() } }
        c.agent.fail(c.agent.receiveRequest(CLOSE.methodName))
        assertTrue(closing.await().isFailure)

        for (id in listOf(root, child, grandchild)) {
            c.agent.sendUpdate(id, text("after-$id"))
            world.awaitOperations(id).awaitEvent(text("after-$id"))
            val read = decode(c.agent.awaitResponse(c.agent.sendRequest(READ, ReadTextFileRequest(id, "/file"))), READ.responseSerializer)
            assertEquals("$id in $id", read.content)
        }
        for (id in listOf(child, grandchild)) {
            val permission = c.agent.sendRequest(PERMISSION, permissionRequest(id, "after-$id"))
            assertEquals(CANCELLED, decodePermission(c.agent.awaitResponse(permission)), "$id")
            assertTrue(world.awaitOperations(id).permissionRequests.value.isEmpty(), "$id")
        }
        assertEquals(listOf(child), session.subagents.map { it.sessionId })
        assertTrue(c.unconnected.value.isEmpty(), "nothing is unconnected, got ${c.unconnected.value}")
    }

    @Test
    fun `close removes the elicitation entries of the subagents' ids`() = withConnection { c ->
        val world = SubagentWorld()
        world.configure = { operations -> operations.elicitationAction = ElicitationAction.Accept() }
        val session = openLive(c, root, world)
        c.agent.sendUpdate(root, announce(child))
        val before = ElicitationId("before-close")
        assertIs<JsonRpcSuccessResponse>(c.agent.awaitResponse(c.agent.sendRequest(ELICITATION, urlElicitation(child, before))))

        closeSession(c, session)

        val reloaded = SubagentWorld()
        reloaded.configure = { operations -> operations.elicitationAction = ElicitationAction.Accept() }
        val retry = async { c.client.loadSession(root, parameters, reloaded.root(root)) }
        val load = c.agent.receiveRequest(LOAD.methodName)
        c.agent.sendUpdate(root, announce(child))
        loadResponse(c.agent, load)
        retry.await()
        val after = ElicitationId("after-reload")
        assertIs<JsonRpcSuccessResponse>(c.agent.awaitResponse(c.agent.sendRequest(ELICITATION, urlElicitation(child, after))))

        for (id in listOf(before, after)) c.agent.sendNotification(AcpMethod.ClientMethods.V1.ElicitationComplete, CompleteElicitationNotification(id))
        val completed = reloaded.awaitOperations(child).completedElicitations
        withTimeout(5.seconds) { completed.first { after in it } }
        assertEquals(listOf(after), completed.value, "the completion of an elicitation before the close reaches nobody")
    }

    @Test
    fun `the connection ending while a subagent's operations are created cancels the setup without failing it`() {
        for (inConsumer in listOf(false, true)) withConnection { c ->
            val world = SubagentWorld()
            val target = if (inConsumer) grandchild else child
            val factoryJob = CompletableDeferred<Job>()
            val factoryFailure = CompletableDeferred<Throwable>()
            val releaseChild = CompletableDeferred<Unit>()
            world.configure = { operations ->
                if (inConsumer && operations.id == child) {
                    // Holds the child's consumer at its marker, so the grandchild's announcement is set up by that consumer.
                    operations.onNotify = { event, _ -> if (event is SessionEvent.Opened) releaseChild.await() }
                }
                operations.createChild = { id ->
                    if (id == target) {
                        factoryJob.complete(currentCoroutineContext()[Job]!!)
                        try {
                            awaitCancellation()
                        } catch (t: Throwable) {
                            factoryFailure.complete(t)
                            throw t
                        }
                    }
                    world.create(id)
                }
            }
            openLive(c, root, world)
            c.agent.sendUpdate(root, announce(child))
            if (inConsumer) {
                c.agent.sendUpdate(child, announce(grandchild))
                c.agent.awaitHandled()
                releaseChild.complete(Unit)
            }
            val setup = factoryJob.await()
            c.agent.sendUpdate(target, text("queued"))
            c.agent.awaitHandled()

            c.protocol.close()

            withTimeout(5.seconds) { setup.join() }
            assertIs<CancellationException>(factoryFailure.await(), "in consumer: $inConsumer")
            assertTrue(c.unconnected.value.none { it.first == target }, "in consumer: $inConsumer: a failed setup would hand on its queue")
        }
    }

    /**
     * One connection of a test: the client, the scripted agent and the updates for unconnected sessions.
     */
    private class Connection(
        val client: Client,
        val agent: ScriptedAgentTransport,
        val protocol: Protocol,
        val unconnected: MutableStateFlow<List<Pair<SessionId, SessionUpdate>>>,
    )

    private fun withConnection(block: suspend CoroutineScope.(Connection) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = ScriptedAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val unconnected = MutableStateFlow<List<Pair<SessionId, SessionUpdate>>>(emptyList())
            val client = Client(protocol, globalSessionUpdateHandler = { id, update, _ -> unconnected.update { it + (id to update) } })
            runBlocking {
                withContext(Dispatchers.Default) {
                    withTimeout(60.seconds) { block(Connection(client, transport, protocol, unconnected)) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Opens [root] with a load, and returns once the opening has completed.
     */
    private suspend fun CoroutineScope.openLive(c: Connection, root: SessionId, world: SubagentWorld): ClientSession {
        val open = async { c.client.loadSession(root, parameters, world.root(root)) }
        loadResponse(c.agent, c.agent.receiveRequest(LOAD.methodName))
        return open.await()
    }

    private suspend fun CoroutineScope.closeSession(c: Connection, session: ClientSession) {
        val closing = async { session.close() }
        c.agent.answer(c.agent.receiveRequest(CLOSE.methodName), CLOSE.responseSerializer, CloseSessionResponse())
        withTimeout(5.seconds) { closing.await() }
    }

    /**
     * Asserts that a load of [root] whose history announces every subagent of [edges], given as parent to subagent,
     * sets each one up again and delivers its marker.
     */
    private suspend fun CoroutineScope.assertReloadRegisters(c: Connection, vararg edges: Pair<SessionId, SessionId>) {
        val world = SubagentWorld()
        val retry = async { c.client.loadSession(root, parameters, world.root(root)) }
        val load = c.agent.receiveRequest(LOAD.methodName)
        for ((parent, subagent) in edges) c.agent.sendUpdate(parent, announce(subagent))
        loadResponse(c.agent, load)
        val session = retry.await()

        for ((parent, subagent) in edges) {
            world.awaitOperations(subagent).awaitEvent(TreeOperations.OPENED)
            assertTrue("$parent -> $subagent" in world.log.value, "$subagent is set up again: ${world.log.value}")
        }
        assertEquals(edges.filter { it.first == root }.map { it.second }, session.subagents.map { it.sessionId })
    }

    private suspend fun awaitUnconnected(c: Connection, id: SessionId, update: SessionUpdate) {
        withTimeout(5.seconds) { c.unconnected.first { (id to update) in it } }
    }

    private suspend fun cancelledSessionId(agent: ScriptedAgentTransport): SessionId {
        val notification = agent.receiveNotification(AcpMethod.AgentMethods.V1.SessionCancel.methodName)
        return ACPJson.decodeFromJsonElement(AcpMethod.AgentMethods.V1.SessionCancel.serializer, notification.params!!).sessionId
    }

    private companion object {
        private val counter = AtomicInteger()
        private val LOAD = AcpMethod.AgentMethods.V1.SessionLoad
        private val CLOSE = AcpMethod.AgentMethods.V1.SessionClose
        private val READ = AcpMethod.ClientMethods.V1.FsReadTextFile
        private val PERMISSION = AcpMethod.ClientMethods.V1.SessionRequestPermission
        private val ELICITATION = AcpMethod.ClientMethods.V1.ElicitationCreate
        private val CANCELLED = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
        private val CANCEL_GRANT = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities()))

        private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        private fun announce(
            id: SessionId,
            title: String? = null,
            capabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined,
            state: StateUpdate? = null,
        ) = SessionUpdate.SubagentUpdate(
            sessionId = id,
            title = title?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
            capabilities = capabilities,
            state = state?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
        )

        private fun permissionRequest(id: SessionId, toolCall: String) =
            RequestPermissionRequest(id, SessionUpdate.ToolCallUpdate(ToolCallId(toolCall)), emptyList())

        private fun urlElicitation(id: SessionId, elicitationId: ElicitationId) =
            CreateElicitationRequest(ElicitationScope.Session(id), ElicitationMode.Url(elicitationId, "https://example.com"), "message")

        private fun decodePermission(response: JsonRpcResponse): RequestPermissionResponse = decode(response, PERMISSION.responseSerializer)

        private fun <T> decode(response: JsonRpcResponse, serializer: KSerializer<T>): T =
            ACPJson.decodeFromJsonElement(serializer, assertIs<JsonRpcSuccessResponse>(response, "an answer, got $response").result)

        private fun loadResponse(agent: ScriptedAgentTransport, request: JsonRpcRequest): LoadSessionResponse =
            LoadSessionResponse(_meta = JsonPrimitive("load-${counter.incrementAndGet()}"))
                .also { agent.answer(request, LOAD.responseSerializer, it) }
    }
}
