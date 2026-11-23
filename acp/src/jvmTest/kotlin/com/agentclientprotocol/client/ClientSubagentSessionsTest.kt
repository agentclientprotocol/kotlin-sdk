@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.ForkSessionResponse
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.ResumeSessionResponse
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionModeState
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The v1 client registers the subagents that the agent announces, sets each one up through
 * [SubagentOperations.createSubagentOperations] when the parent's stream reaches its announcement, and delivers each
 * session's own stream: its history, exactly one [SessionEvent.Opened], then its live updates. The tests play the agent
 * on a scripted transport, so they control the exact wire order.
 */
class ClientSubagentSessionsTest {
    private val parameters = SessionCreationParameters(cwd = "/root", mcpServers = emptyList(), additionalDirectories = listOf("/extra"))

    /**
     * One way to open a root session: the call, its method, and the answer the scripted agent gives.
     */
    private class Opening(
        val name: String,
        val method: MethodName,
        val replayRequested: Boolean,
        val open: suspend (Client, SessionId, SessionCreationParameters, SubagentWorld) -> ClientSession,
        val answer: (ScriptedAgentTransport, JsonRpcRequest, SessionId) -> AcpCreatedSessionResponse,
    ) {
        override fun toString() = name
    }

    private val loadWithOperations = Opening(
        "load with operations", AcpMethod.AgentMethods.V1.SessionLoad.methodName, replayRequested = true,
        open = { client, id, parameters, world -> client.loadSession(id, parameters, world.root(id)) },
        answer = { agent, request, _ -> loadResponse(agent, request) },
    )
    private val resumeWithOperations = Opening(
        "resume with operations", AcpMethod.AgentMethods.V1.SessionResume.methodName, replayRequested = false,
        open = { client, id, parameters, world -> client.resumeSession(id, parameters, world.root(id)) },
        answer = { agent, request, _ -> resumeResponse(agent, request) },
    )
    private val loadWithFactory = Opening(
        "load with a factory", AcpMethod.AgentMethods.V1.SessionLoad.methodName, replayRequested = true,
        open = { client, id, parameters, world -> client.loadSession(id, parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, _ -> loadResponse(agent, request) },
    )
    private val resumeWithFactory = Opening(
        "resume with a factory", AcpMethod.AgentMethods.V1.SessionResume.methodName, replayRequested = false,
        open = { client, id, parameters, world -> client.resumeSession(id, parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, _ -> resumeResponse(agent, request) },
    )
    private val newWithFactory = Opening(
        "new", AcpMethod.AgentMethods.V1.SessionNew.methodName, replayRequested = false,
        open = { client, _, parameters, world -> client.newSession(parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, id ->
            NewSessionResponse(id).also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionNew.responseSerializer, it) }
        },
    )
    private val forkWithFactory = Opening(
        "fork", AcpMethod.AgentMethods.V1.SessionFork.methodName, replayRequested = false,
        open = { client, _, parameters, world -> client.forkSession(SessionId("fork-source"), parameters) { sessionId, _ -> world.root(sessionId) } },
        answer = { agent, request, id ->
            ForkSessionResponse(id).also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionFork.responseSerializer, it) }
        },
    )

    private val allOpenings = listOf(loadWithOperations, resumeWithOperations, loadWithFactory, resumeWithFactory, newWithFactory, forkWithFactory)
    private val factoryOpenings = listOf(loadWithFactory, resumeWithFactory, newWithFactory, forkWithFactory)

    @Test
    fun `each stream gets its history then exactly one Opened then its live updates`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val world = SubagentWorld()
            val root = SessionId("root-$opening")
            val child = SessionId("child-$opening")
            val grandchild = SessionId("grandchild-$opening")
            val late = SessionId("late-$opening")
            val lateGrandchild = SessionId("late-grandchild-$opening")

            val open = async { opening.open(client, root, parameters, world) }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(root, text("root-history"))
            agent.sendUpdate(root, announce(child))
            agent.sendUpdate(child, text("child-history"))
            agent.sendUpdate(child, announce(grandchild))
            agent.sendUpdate(grandchild, text("grandchild-history"))
            opening.answer(agent, request, root)
            agent.sendUpdate(root, text("root-live"))
            agent.sendUpdate(child, text("child-live"))
            agent.sendUpdate(grandchild, text("grandchild-live"))
            agent.sendUpdate(root, announce(late))
            agent.sendUpdate(late, text("late-live"))
            agent.sendUpdate(late, announce(lateGrandchild))
            agent.sendUpdate(lateGrandchild, text("late-grandchild-live"))
            val session = open.await()

            val opened = TreeOperations.OPENED
            assertEquals(
                listOf(text("root-history"), announce(child), opened, text("root-live"), announce(late)),
                world.awaitOperations(root).awaitEvents(5), "$opening: root",
            )
            assertEquals(listOf(text("child-history"), announce(grandchild), opened, text("child-live")), world.awaitOperations(child).awaitEvents(4), "$opening: child")
            assertEquals(listOf(text("grandchild-history"), opened, text("grandchild-live")), world.awaitOperations(grandchild).awaitEvents(3), "$opening: grandchild")
            assertEquals(listOf(opened, text("late-live"), announce(lateGrandchild)), world.awaitOperations(late).awaitEvents(3), "$opening: late child")
            assertEquals(listOf(opened, text("late-grandchild-live")), world.awaitOperations(lateGrandchild).awaitEvents(2), "$opening: late grandchild")

            assertEquals(listOf(child, late), session.subagents.map { it.sessionId }, "$opening")
            assertEquals(listOf(grandchild), session.subagents[0].subagents.map { it.sessionId }, "$opening")
            assertEquals(listOf(lateGrandchild), session.subagents[1].subagents.map { it.sessionId }, "$opening")
            assertEquals(root, session.subagents[0].parentSessionId)
            assertEquals(child, session.subagents[0].subagents[0].parentSessionId)

            // Each setup runs on its parent's operations, after the parent's own operations exist.
            val log = world.log.value
            val order = listOf("root $root", "$root -> $child", "$child -> $grandchild")
            assertEquals(order, log.filter { it in order }, "$opening: $log")
            assertTrue(log.indexOf("$root -> $late") < log.indexOf("$late -> $lateGrandchild"), "$opening: $log")
            assertEquals(5, log.size, "$opening: each factory runs once: $log")
        }
    }

    @Test
    fun `a subagent exists with its operations and announced values inside the parent's notify for its announcement`() =
        withClient { client, agent ->
            // Before the answer, only a factory call's consumer runs in the parent's context.
            for ((opening, beforeAnswer) in allOpenings.map { it to false } + factoryOpenings.map { it to true }) {
                val world = SubagentWorld()
                val root = SessionId("root-$opening-$beforeAnswer")
                val child = SessionId("child-$opening-$beforeAnswer")
                val seen = CompletableDeferred<List<Any?>>()
                world.configure = { operations ->
                    if (operations.id == root) operations.onNotify = { event, session ->
                        if (event is SessionEvent.Update && event.update is SessionUpdate.SubagentUpdate) {
                            val subagent = session!!.subagents.single()
                            seen.complete(listOf(subagent.sessionId, subagent.operations, subagent.title, subagent.cwd, subagent.additionalDirectories))
                        }
                    }
                    if (operations.id == child) operations.onNotify = { _, session -> childContext.update { it + session?.sessionId } }
                }
                val announcement = announce(child, title = "title", cwd = "/child")

                val open = async { opening.open(client, root, parameters, world) }
                val request = agent.receiveRequest(opening.method)
                if (beforeAnswer) agent.sendUpdate(root, announcement)
                opening.answer(agent, request, root)
                if (!beforeAnswer) agent.sendUpdate(root, announcement)
                agent.sendUpdate(child, text("child-update"))
                open.await()

                val (id, operations, title, cwd, directories) = withTimeout(5.seconds) { seen.await() }
                assertEquals(child, id, "$opening")
                assertSame(world.awaitOperations(child), operations, "$opening: the operations are the factory's result")
                assertEquals("title", title, "$opening")
                assertEquals("/child", cwd, "$opening")
                assertEquals(listOf("/extra"), directories, "$opening: inherited from the root's parameters")
                assertTrue("$root -> $child" in world.log.value, "$opening: the factory gets the subagent's id")

                world.awaitOperations(child).awaitEvent(text("child-update"))
                assertEquals(child, childContext.value.last(), "$opening: the subagent's calls run in its context")
            }
        }

    private val childContext = MutableStateFlow<List<SessionId?>>(emptyList())

    @Test
    fun `a second subagent_update before the setup is applied as a patch and the factory runs once`() = withClient { client, agent ->
        for (opening in factoryOpenings) {
            val world = SubagentWorld()
            val root = SessionId("root-$opening")
            val child = SessionId("child-$opening")
            val titles = MutableStateFlow<List<String?>>(emptyList())
            world.configure = { operations ->
                if (operations.id == root) operations.onNotify = { event, session ->
                    if (event is SessionEvent.Update && event.update is SessionUpdate.SubagentUpdate) {
                        titles.update { it + session!!.subagents.single().title }
                    }
                }
            }

            val open = async { opening.open(client, root, parameters, world) }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(root, announce(child, title = "first"))
            agent.sendUpdate(root, announce(child, title = "second"))
            opening.answer(agent, request, root)
            val session = open.await()

            world.awaitOperations(root).awaitEvents(3)
            assertEquals(listOf<String?>("first", "second"), titles.value, "$opening")
            assertEquals(listOf("root $root", "$root -> $child"), world.log.value, "$opening")
            assertEquals("second", session.subagents.single().title)
        }
    }

    @Test
    fun `a factory that suspends delays the parent's later updates and not other sessions'`() = withClient { client, agent ->
        for (parentOpen in listOf(false, true)) {
            val world = SubagentWorld()
            val root = SessionId("root-$parentOpen")
            val other = SessionId("other-$parentOpen")
            val child = SessionId("child-$parentOpen")
            val factoryEntered = CompletableDeferred<Unit>()
            val releaseFactory = CompletableDeferred<Unit>()
            val titles = MutableStateFlow<List<String?>>(emptyList())
            world.configure = { operations ->
                if (operations.id == root) {
                    operations.createChild = { id ->
                        factoryEntered.complete(Unit)
                        releaseFactory.await()
                        world.create(id)
                    }
                    // Before the answer the early delivery has no session in the context.
                    operations.onNotify = { event, session ->
                        if (session != null && event is SessionEvent.Update && event.update is SessionUpdate.SubagentUpdate) {
                            titles.update { it + session.subagents.single().title }
                        }
                    }
                }
            }
            openLive(client, agent, other, world)

            val open = async { loadWithOperations.open(client, root, parameters, world) }
            val request = agent.receiveRequest(loadWithOperations.method)
            if (parentOpen) {
                loadWithOperations.answer(agent, request, root)
                open.await()
            }
            agent.sendUpdate(root, announce(child, title = "first"))
            agent.sendUpdate(root, announce(child, title = "second"))
            agent.sendUpdate(root, text("after"))
            agent.sendUpdate(child, text("child-update"))
            factoryEntered.await()
            agent.sendUpdate(other, text("other-update"))
            world.awaitOperations(other).awaitEvent(text("other-update"))
            agent.awaitHandled()
            val rootOperations = world.awaitOperations(root)
            val beforeRelease = rootOperations.events.value

            releaseFactory.complete(Unit)
            if (!parentOpen) {
                loadWithOperations.answer(agent, request, root)
                open.await()
            }
            val opened = TreeOperations.OPENED
            val expected = if (parentOpen) {
                listOf(opened, announce(child, title = "first"), announce(child, title = "second"), text("after"))
            } else {
                listOf(announce(child, title = "first"), announce(child, title = "second"), text("after"), opened)
            }
            assertEquals(expected, rootOperations.awaitEvents(4), "parent open: $parentOpen")
            assertEquals(if (parentOpen) listOf<Any>(opened) else emptyList(), beforeRelease, "parent open: $parentOpen")
            if (parentOpen) assertEquals(listOf<String?>("first", "second"), titles.value)
            val childEvents = if (parentOpen) listOf(opened, text("child-update")) else listOf(text("child-update"), opened)
            assertEquals(childEvents, world.awaitOperations(child).awaitEvents(2), "parent open: $parentOpen")
            assertEquals("second", open.await().subagents.single().title, "parent open: $parentOpen")
        }
    }

    @Test
    fun `subagents grow in announcement order and an earlier read stays unchanged`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val snapshots = MutableStateFlow<List<List<SessionId>>>(emptyList())
        world.configure = { operations ->
            if (operations.id == root) operations.onNotify = { event, session ->
                if (event is SessionEvent.Update && event.update is SessionUpdate.SubagentUpdate) {
                    snapshots.update { it + listOf(session!!.subagents.map { child -> child.sessionId }) }
                }
            }
        }
        val session = openLive(client, agent, root, world)
        val first = session.subagents

        val ids = listOf(SessionId("a"), SessionId("b"), SessionId("c"))
        ids.forEach { agent.sendUpdate(root, announce(it)) }
        world.awaitOperations(root).awaitEvents(1 + ids.size)

        assertEquals(listOf(ids.take(1), ids.take(2), ids), snapshots.value)
        assertEquals(ids, session.subagents.map { it.sessionId })
        assertTrue(first.isEmpty(), "a list read before stays as it was")
    }

    @Test
    fun `capabilities and state before the answer are dropped after a load and promoted otherwise`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val world = SubagentWorld()
            val root = SessionId("root-$opening")
            val child = SessionId("child-$opening")
            val atOpened = CompletableDeferred<Triple<String?, Boolean, StateUpdate?>>()
            val atLivePatch = CompletableDeferred<Pair<Boolean, StateUpdate?>>()
            world.configure = { operations ->
                if (operations.id == root) operations.onNotify = { event, session ->
                    val subagent = session?.subagents?.singleOrNull()
                    if (event is SessionEvent.Opened) atOpened.complete(Triple(subagent?.title, subagent!!.canCancel, subagent.state))
                    if (event is SessionEvent.Update && event.update == livePatch(child)) {
                        atLivePatch.complete(subagent!!.canCancel to subagent.state)
                    }
                }
            }

            val open = async { opening.open(client, root, parameters, world) }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(root, announce(child, title = "title", capabilities = cancelGrant, state = StateUpdate.Running()))
            opening.answer(agent, request, root)
            agent.sendUpdate(root, livePatch(child))
            open.await()

            val (title, canCancel, state) = withTimeout(5.seconds) { atOpened.await() }
            assertEquals("title", title, "$opening: the history's title stays visible")
            if (opening.replayRequested) {
                assertFalse(canCancel, "$opening: a grant from the history never takes effect")
                assertNull(state, "$opening: a state from the history is not current")
            } else {
                assertTrue(canCancel, "$opening: a grant before the answer takes effect at Opened")
                assertEquals(StateUpdate.Running(), state, "$opening")
            }
            assertEquals(true to StateUpdate.Idle(IdleStopReason.EndTurn), withTimeout(5.seconds) { atLivePatch.await() }, "$opening: a live patch applies at once")
        }
    }

    @Test
    fun `foreground work before the answer counts for cancel after a resume and not after a load`() = withClient { client, agent ->
        for (opening in allOpenings) {
            val world = SubagentWorld()
            val root = SessionId("root-$opening")
            val child = SessionId("child-$opening")
            val open = async { opening.open(client, root, parameters, world) }
            val request = agent.receiveRequest(opening.method)
            agent.sendUpdate(root, announce(child, capabilities = cancelGrant, state = StateUpdate.Running()))
            opening.answer(agent, request, root)
            // After a load the grant is history, so a live one is needed to cancel at all.
            if (opening.replayRequested) agent.sendUpdate(root, announce(child, capabilities = cancelGrant))
            val session = open.await()
            world.awaitOperations(root).awaitEvents(if (opening.replayRequested) 3 else 2)

            val subagent = session.subagents.single()
            subagent.cancel()
            assertEquals(child, cancelledSessionId(agent))
            val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "after-cancel"))
            val answer = decode(agent.awaitResponse(permission), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer)

            if (opening.replayRequested) {
                assertEquals(TreeOperations.ALLOWED, answer, "$opening: the history's running confirms no work")
                assertEquals(1, world.awaitOperations(child).permissionRequests.value.size, "$opening")
            } else {
                assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), answer, "$opening: the promoted running is work being cancelled")
                assertTrue(world.awaitOperations(child).permissionRequests.value.isEmpty(), "$opening")
            }
        }
    }

    @Test
    fun `an idle cancelled before the answer answers the requests pending at its arrival after a resume and not after a load`() =
        withClient { client, agent ->
            for (opening in allOpenings) {
                val world = SubagentWorld()
                val root = SessionId("root-$opening")
                val child = SessionId("child-$opening")
                val open = async { opening.open(client, root, parameters, world) }
                val request = agent.receiveRequest(opening.method)
                agent.sendUpdate(root, announce(child, state = StateUpdate.Running()))
                val cancelledWork = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "cancelled-work"))
                agent.sendUpdate(root, announce(child, state = StateUpdate.Idle(IdleStopReason.Cancelled)))
                // Arrives after the cancelled state, as a live request would reach the app then.
                val later = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "later"))
                agent.awaitHandled()
                assertNull(agent.responseOrNull(cancelledWork), "$opening: the request waits for the root's answer")

                opening.answer(agent, request, root)
                open.await()

                val answer = decode(agent.awaitResponse(cancelledWork), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer)
                assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(later), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer), "$opening")
                val reached = world.awaitOperations(child).permissionRequests.value.map { it.toolCallId.value }
                if (opening.replayRequested) {
                    assertEquals(TreeOperations.ALLOWED, answer, "$opening: the history's idle cancels nothing")
                    assertEquals(setOf("cancelled-work", "later"), reached.toSet(), "$opening")
                } else {
                    assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), answer, "$opening: the held idle answers it at the answer")
                    assertEquals(listOf("later"), reached, "$opening")
                }
            }
        }

    @Test
    fun `a subagent's permission requests follow the wire order of its state, not the parent's delivery`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("child")
        val blocking = text("blocking")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        world.configure = { operations ->
            if (operations.id == root) operations.onNotify = { event, _ ->
                if (event is SessionEvent.Update && event.update == blocking) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        val session = openLive(client, agent, root, world)
        agent.sendUpdate(root, announce(child, capabilities = cancelGrant, state = StateUpdate.Running()))
        world.awaitOperations(child).awaitEvents(1)
        val subagent = session.subagents.single()

        // A cancel while the child runs blocks later requests until its next state.
        subagent.cancel()
        cancelledSessionId(agent)
        val blocked = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "blocked"))
        assertEquals(
            RequestPermissionResponse(RequestPermissionOutcome.Cancelled),
            decode(agent.awaitResponse(blocked), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer),
        )

        agent.sendUpdate(root, blocking)
        entered.await()
        agent.sendUpdate(root, announce(child, state = StateUpdate.Running()))
        val allowed = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "allowed"))

        assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(allowed), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer))
        assertEquals(listOf(SessionUpdate.ToolCallUpdate(ToolCallId("allowed"))), world.awaitOperations(child).permissionRequests.value)
        assertFalse(release.isCompleted, "the request is answered while the parent's notify is still blocked")
        release.complete(Unit)
    }

    @Test
    fun `a permission request that waits for its subagent's setup is answered cancelled when the subagent reports idle cancelled`() =
        withClient { client, agent ->
            val world = SubagentWorld()
            val root = SessionId("root")
            val child = SessionId("child")
            val factoryEntered = CompletableDeferred<Unit>()
            val releaseFactory = CompletableDeferred<Unit>()
            world.configure = { operations ->
                if (operations.id == root) operations.createChild = { id ->
                    factoryEntered.complete(Unit)
                    releaseFactory.await()
                    world.create(id)
                }
            }
            openLive(client, agent, root, world)
            agent.sendUpdate(root, announce(child, state = StateUpdate.Running()))
            factoryEntered.await()

            // The request arrives before the cancelled state, so it is pending when the work is cancelled.
            val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "cancelled-work"))
            agent.sendUpdate(root, announce(child, state = StateUpdate.Idle(IdleStopReason.Cancelled)))
            assertEquals(
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled),
                decode(agent.awaitResponse(permission), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer),
                "answered without waiting for the subagent's setup",
            )

            releaseFactory.complete(Unit)
            world.awaitOperations(child).awaitEvent(TreeOperations.OPENED)
            agent.awaitHandled()
            assertTrue(world.awaitOperations(child).permissionRequests.value.isEmpty())
        }

    @Test
    fun `a permission request that waits for its subagent's holder is answered cancelled by a cancel in the root's Opened`() =
        withClient { client, agent ->
            val world = SubagentWorld()
            val root = SessionId("root")
            val child = SessionId("child")
            world.configure = { operations ->
                if (operations.id == root) operations.onNotify = { event, session ->
                    if (event is SessionEvent.Opened) session!!.subagents.single().cancel()
                }
            }
            val open = async { resumeWithOperations.open(client, root, parameters, world) }
            val request = agent.receiveRequest(resumeWithOperations.method)
            agent.sendUpdate(root, announce(child, capabilities = cancelGrant, state = StateUpdate.Idle(IdleStopReason.EndTurn)))
            world.awaitOperations(child)
            val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "before-cancel"))
            agent.awaitHandled()
            assertNull(agent.responseOrNull(permission), "the request waits until the root's opening has completed")

            resumeWithOperations.answer(agent, request, root)
            open.await()

            assertEquals(child, cancelledSessionId(agent))
            assertEquals(
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled),
                decode(agent.awaitResponse(permission), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer),
            )
            assertTrue(world.awaitOperations(child).permissionRequests.value.isEmpty())
        }

    @Test
    fun `requests on a subagent reach its operations once its holder completes`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("child")
        val open = async { loadWithOperations.open(client, root, parameters, world) }
        val request = agent.receiveRequest(loadWithOperations.method)
        agent.sendUpdate(root, announce(child))
        world.awaitOperations(child)

        val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "tool"))
        val read = agent.sendRequest(AcpMethod.ClientMethods.V1.FsReadTextFile, ReadTextFileRequest(child, "/file"))
        val terminal = agent.sendRequest(AcpMethod.ClientMethods.V1.TerminalCreate, CreateTerminalRequest(child, "ls"))
        val elicitation = agent.sendRequest(
            AcpMethod.ClientMethods.V1.ElicitationCreate,
            CreateElicitationRequest(ElicitationScope.Session(child), ElicitationMode.Url(ElicitationId("e"), "https://example.com"), "message"),
        )
        agent.awaitHandled()
        for (id in listOf(permission, read, terminal, elicitation)) {
            assertNull(agent.responseOrNull(id), "a request on a subagent waits until the root's answer")
        }

        loadWithOperations.answer(agent, request, root)
        open.await()
        val expected = "$child in $child"
        assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(permission), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer))
        assertEquals(expected, decode(agent.awaitResponse(read), AcpMethod.ClientMethods.V1.FsReadTextFile.responseSerializer).content)
        assertEquals(expected, decode(agent.awaitResponse(terminal), AcpMethod.ClientMethods.V1.TerminalCreate.responseSerializer).terminalId)
        assertEquals(JsonPrimitive(expected), decode(agent.awaitResponse(elicitation), AcpMethod.ClientMethods.V1.ElicitationCreate.responseSerializer)._meta)
        assertEquals(1, world.awaitOperations(child).permissionRequests.value.size)
    }

    @Test
    fun `a request that the subagent's operations don't override fails with their default error`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("plain-child")
        world.configure = { operations -> if (operations.id == root) operations.createChild = { PlainOperations() } }
        openLive(client, agent, root, world)
        agent.sendUpdate(root, announce(child))

        val read = agent.sendRequest(AcpMethod.ClientMethods.V1.FsReadTextFile, ReadTextFileRequest(child, "/file"))
        val failure = assertIs<JsonRpcErrorResponse>(agent.awaitResponse(read))
        assertEquals("Must be implemented by client when advertising fs.readTextFile capability", failure.error.message)
    }

    @Test
    fun `a subagent's update and request right after its announcement reach it while the parent's notify is blocked`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            for (parentOpen in listOf(false, true)) {
                val world = SubagentWorld()
                val root = SessionId("root-$parentOpen")
                val child = SessionId("child-$parentOpen")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                world.configure = { operations ->
                    if (operations.id == root) operations.onNotify = { event, _ ->
                        if (event is SessionEvent.Update && event.update is SessionUpdate.SubagentUpdate) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }

                val open = async { loadWithOperations.open(client, root, parameters, world) }
                val request = agent.receiveRequest(loadWithOperations.method)
                if (parentOpen) {
                    loadWithOperations.answer(agent, request, root)
                    open.await()
                }
                agent.sendUpdate(root, announce(child))
                agent.sendUpdate(child, text("right-after"))
                val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "right-after"))
                entered.await()

                val childOperations = world.awaitOperations(child)
                childOperations.awaitEvent(text("right-after"))
                if (parentOpen) {
                    childOperations.awaitPermissionRequests(1)
                    assertFalse(release.isCompleted)
                    release.complete(Unit)
                } else {
                    release.complete(Unit)
                    loadWithOperations.answer(agent, request, root)
                    open.await()
                }
                assertEquals(TreeOperations.ALLOWED, decode(agent.awaitResponse(permission), AcpMethod.ClientMethods.V1.SessionRequestPermission.responseSerializer))
                assertEquals(1, childOperations.permissionRequests.value.size, "parent open: $parentOpen")
                assertTrue(unconnected.value.isEmpty(), "parent open: $parentOpen: nothing is unconnected, got ${unconnected.value}")
            }
        }

    @Test
    fun `a failed opening delivers no Opened to its subagents and their ids register again on a retry`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            for (opening in allOpenings) {
                val world = SubagentWorld()
                val root = SessionId("root-$opening")
                val child = SessionId("child-$opening")
                val grandchild = SessionId("grandchild-$opening")

                val open = async { runCatching { opening.open(client, root, parameters, world) } }
                val request = agent.receiveRequest(opening.method)
                agent.sendUpdate(root, announce(child))
                agent.sendUpdate(child, announce(grandchild))
                agent.sendUpdate(grandchild, text("grandchild-history"))
                agent.awaitHandled()
                agent.fail(request)
                assertTrue(open.await().isFailure, "$opening must fail")

                for (id in listOf(child, grandchild)) {
                    val events = world.operationsOrNull(id)?.events?.value.orEmpty()
                    assertFalse(TreeOperations.OPENED in events, "$opening: $id gets no Opened")
                }
                // On the early path the history may have reached the operations already; otherwise it goes to the handler.
                if (world.operationsOrNull(grandchild) == null) awaitUnconnected(unconnected, grandchild, text("grandchild-history"))

                agent.sendUpdate(child, text("after-failure"))
                awaitUnconnected(unconnected, child, text("after-failure"))

                assertRetryRegistersAgain(client, agent, opening, root, child)
            }
        }

    @Test
    fun `an opening that fails while it delivers its Opened fails every subagent below`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            for (opening in allOpenings) {
                val world = SubagentWorld()
                val root = SessionId("root-$opening")
                val child = SessionId("child-$opening")
                val grandchild = SessionId("grandchild-$opening")
                val childOpened = CompletableDeferred<Unit>()
                world.configure = { operations ->
                    if (operations.id == root) operations.onNotify = { event, _ ->
                        if (event is SessionEvent.Opened) {
                            // Fail only after the subagents got their own marker, to show that it doesn't save them.
                            childOpened.await()
                            error("notify failed for the marker")
                        }
                    }
                    if (operations.id == grandchild) operations.onNotify = { event, _ -> if (event is SessionEvent.Opened) childOpened.complete(Unit) }
                }

                val open = async { runCatching { opening.open(client, root, parameters, world) } }
                val request = agent.receiveRequest(opening.method)
                agent.sendUpdate(root, announce(child))
                agent.sendUpdate(child, announce(grandchild))
                opening.answer(agent, request, root)
                assertEquals("notify failed for the marker", open.await().exceptionOrNull()?.message, "$opening")

                val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(grandchild, "after-failure"))
                assertIs<JsonRpcErrorResponse>(agent.awaitResponse(permission), "$opening: a failed subagent's requests get an error")
                agent.sendUpdate(grandchild, text("after-failure"))
                awaitUnconnected(unconnected, grandchild, text("after-failure"))
                assertTrue(world.awaitOperations(grandchild).permissionRequests.value.isEmpty())

                assertRetryRegistersAgain(client, agent, opening, root, child)
            }
        }

    @Test
    fun `a failed setup fails only that subagent and the parent keeps getting its updates`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            for (failure in listOf("throws", "cancels", "missing interface")) {
                val world = SubagentWorld()
                val root = SessionId("root-$failure")
                val parent = SessionId("parent-$failure")
                val failed = SessionId("failed-$failure")
                val sibling = SessionId("sibling-$failure")
                val grandchild = SessionId("grandchild-$failure")
                world.configure = { operations ->
                    if (operations.id == root) operations.createChild = { id ->
                        when {
                            id != parent -> world.create(id)
                            failure == "missing interface" -> PlainOperations()
                            else -> world.create(id).also { created ->
                                created.createChild = { childId ->
                                    when {
                                        childId != failed -> world.create(childId)
                                        failure == "throws" -> error("factory failed")
                                        else -> throw CancellationException("factory cancelled itself")
                                    }
                                }
                            }
                        }
                    }
                }
                val session = openLive(client, agent, root, world)
                agent.sendUpdate(root, announce(parent))
                agent.sendUpdate(parent, announce(failed, title = "first"))
                agent.sendUpdate(parent, announce(sibling))
                agent.sendUpdate(parent, announce(failed, title = "second"))
                agent.sendUpdate(failed, text("failed-update"))
                agent.sendUpdate(failed, announce(grandchild))
                agent.sendUpdate(grandchild, text("grandchild-update"))
                agent.sendUpdate(sibling, text("sibling-update"))
                agent.sendUpdate(root, text("root-update"))

                awaitUnconnected(unconnected, failed, text("failed-update"))
                awaitUnconnected(unconnected, failed, announce(grandchild))
                awaitUnconnected(unconnected, grandchild, text("grandchild-update"))
                world.awaitOperations(root).awaitEvent(text("root-update"))
                val parentSession = session.subagents.single()
                assertEquals(parent, parentSession.sessionId)

                val expectedParentEvents = if (failure == "missing interface") {
                    listOf(TreeOperations.OPENED, announce(failed, title = "first"), announce(sibling), announce(failed, title = "second"))
                } else {
                    world.awaitOperations(sibling).awaitEvent(text("sibling-update"))
                    assertEquals(listOf(sibling), parentSession.subagents.map { it.sessionId }, failure)
                    assertEquals(1, world.log.value.count { it == "$parent -> $failed" }, "$failure: a later patch doesn't set it up again")
                    listOf(TreeOperations.OPENED, announce(failed, title = "first"), announce(sibling), announce(failed, title = "second"))
                }
                val parentEvents = (parentSession.operations as? TreeOperations)?.awaitEvents(4)
                    ?: (parentSession.operations as PlainOperations).awaitEvents(4)
                assertEquals(expectedParentEvents, parentEvents, failure)
                assertTrue(parentSession.subagents.none { it.sessionId == failed }, failure)

                val permission = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(failed, "tool"))
                assertIs<JsonRpcErrorResponse>(agent.awaitResponse(permission), "$failure: its requests get an error")

                // The failed subagent's id stays taken while its parent lives.
                val refused = runCatching { client.loadSession(failed, parameters, world.create(SessionId("refused-$failure"))) }
                assertEquals("Session $failed is already open or being opened", refused.exceptionOrNull()?.message, failure)
            }
        }

    @Test
    fun `a malformed announcement reaches the parent as a plain update and registers nothing`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            val world = SubagentWorld()
            val first = SessionId("first")
            val second = SessionId("second")
            val child = SessionId("child")
            val grandchild = SessionId("grandchild")
            val firstSession = openLive(client, agent, first, world)
            val secondSession = openLive(client, agent, second, world)
            agent.sendUpdate(first, announce(child))
            agent.sendUpdate(child, announce(grandchild))
            world.awaitOperations(grandchild).awaitEvents(1)
            val logBefore = world.log.value.filter { "->" in it }

            val pending = SessionId("being-opened")
            val pendingOpen = async { loadWithOperations.open(client, pending, parameters, world) }
            val pendingRequest = agent.receiveRequest(loadWithOperations.method)
            val speculative = SessionId("speculative")
            agent.sendUpdate(speculative, text("speculative-update"))

            val malformed = listOf(
                first to announce(first),             // its own parent
                grandchild to announce(first),        // an ancestor
                grandchild to announce(child),        // its parent
                second to announce(child),            // another parent's subagent
                first to announce(second),            // an open root
                first to announce(pending),           // a root being opened
                first to announce(speculative),       // an id with a speculative holder
            )
            for ((parent, update) in malformed) agent.sendUpdate(parent, update)
            agent.sendUpdate(child, text("child-update"))
            agent.sendUpdate(second, text("second-update"))

            world.awaitOperations(child).awaitEvent(text("child-update"))
            world.awaitOperations(second).awaitEvent(text("second-update"))
            for ((parent, update) in malformed) world.awaitOperations(parent).awaitEvent(update)
            assertEquals(logBefore, world.log.value.filter { "->" in it }, "no setup runs for a malformed announcement")
            assertEquals(listOf(child), firstSession.subagents.map { it.sessionId })
            assertTrue(secondSession.subagents.isEmpty())
            assertEquals(listOf(grandchild), firstSession.subagents.single().subagents.map { it.sessionId })

            val history = text("pending-history")
            agent.sendUpdate(pending, history)
            loadWithOperations.answer(agent, pendingRequest, pending)
            pendingOpen.await()
            assertEquals(listOf(text("pending-history"), TreeOperations.OPENED), world.awaitOperations(pending).awaitEvents(2))
            awaitUnconnected(unconnected, speculative, text("speculative-update"))
        }

    @Test
    fun `an announcement rejected for a speculative holder stays rejected when that holder is reaped before delivery`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            val world = SubagentWorld()
            val root = SessionId("root")
            val blocking = text("blocking")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            world.configure = { operations ->
                if (operations.id == root) operations.onNotify = { event, _ ->
                    if (event is SessionEvent.Update && event.update == blocking) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
            openLive(client, agent, root, world)
            agent.sendUpdate(root, blocking)
            entered.await()

            val pending = SessionId("pending")
            val pendingOpen = async { loadWithOperations.open(client, pending, parameters, world) }
            val pendingRequest = agent.receiveRequest(loadWithOperations.method)
            val speculative = SessionId("speculative")
            agent.sendUpdate(speculative, text("speculative-update"))
            agent.sendUpdate(root, announce(speculative))
            loadWithOperations.answer(agent, pendingRequest, pending)
            pendingOpen.await()
            awaitUnconnected(unconnected, speculative, text("speculative-update"))

            release.complete(Unit)
            world.awaitOperations(root).awaitEvent(announce(speculative))
            agent.sendUpdate(speculative, text("after-reap"))
            awaitUnconnected(unconnected, speculative, text("after-reap"))
            assertTrue(world.log.value.none { "->" in it }, "nothing is set up: ${world.log.value}")
        }

    @Test
    fun `a request for an unknown id during an opening leaves the id free for a later announcement`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val session = openLive(client, agent, root, world)

        val pending = SessionId("pending")
        val pendingOpen = async { loadWithOperations.open(client, pending, parameters, world) }
        val pendingRequest = agent.receiveRequest(loadWithOperations.method)
        val child = SessionId("child")
        val early = agent.sendRequest(AcpMethod.ClientMethods.V1.SessionRequestPermission, permissionRequest(child, "early"))
        val failure = assertIs<JsonRpcErrorResponse>(agent.awaitResponse(early)).error
        assertEquals(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, failure.code)

        agent.sendUpdate(root, announce(child))
        world.awaitOperations(child)
        assertEquals(listOf(child), session.subagents.map { it.sessionId })
        loadWithOperations.answer(agent, pendingRequest, pending)
        pendingOpen.await()
    }

    @Test
    fun `an announcement for an unknown or failed parent registers nothing`() = withUnconnectedRecorder { client, agent, unconnected ->
        val unknownParent = SessionId("unknown-parent")
        val orphan = SessionId("orphan")
        agent.sendUpdate(unknownParent, announce(orphan))
        agent.sendUpdate(orphan, text("orphan-update"))
        awaitUnconnected(unconnected, unknownParent, announce(orphan))
        awaitUnconnected(unconnected, orphan, text("orphan-update"))

        val world = SubagentWorld()
        val root = SessionId("root")
        val failed = SessionId("failed")
        val underFailed = SessionId("under-failed")
        world.configure = { operations -> if (operations.id == root) operations.createChild = { error("factory failed") } }
        openLive(client, agent, root, world)
        agent.sendUpdate(root, announce(failed))
        agent.sendUpdate(failed, announce(underFailed))
        agent.sendUpdate(underFailed, text("under-failed-update"))
        awaitUnconnected(unconnected, failed, announce(underFailed))
        awaitUnconnected(unconnected, underFailed, text("under-failed-update"))
    }

    @Test
    fun `an unrelated opening that ends does not fail a subagent waiting for its operations`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("child")
        val factoryEntered = CompletableDeferred<Unit>()
        val releaseFactory = CompletableDeferred<Unit>()
        world.configure = { operations ->
            if (operations.id == root) operations.createChild = { id ->
                factoryEntered.complete(Unit)
                releaseFactory.await()
                world.create(id)
            }
        }
        openLive(client, agent, root, world)
        agent.sendUpdate(root, announce(child))
        agent.sendUpdate(child, text("queued"))
        factoryEntered.await()

        // The end of the last opening reaps every root holder that hasn't completed.
        val other = SessionId("other")
        openLive(client, agent, other, world)

        releaseFactory.complete(Unit)
        agent.sendUpdate(child, text("after"))
        assertEquals(listOf(TreeOperations.OPENED, text("queued"), text("after")), world.awaitOperations(child).awaitEvents(3))
    }

    @Test
    fun `a subagent's mode and config updates reach its notify and leave the root's state alone`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("child")
        val modes = SessionModeState(SessionModeId("root-mode"), listOf(SessionMode(SessionModeId("root-mode"), "Root")))
        val rootOptions = listOf(SessionConfigOption.boolean("root-option", "Root option", currentValue = true))
        val open = async { client.loadSession(root, parameters, world.root(root)) }
        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        agent.sendUpdate(root, announce(child))
        val childMode = SessionUpdate.CurrentModeUpdate(SessionModeId("child-history-mode"))
        agent.sendUpdate(child, childMode)
        val historyConfig = SessionUpdate.ConfigOptionUpdate(listOf(SessionConfigOption.boolean("child-history-option", "Child", currentValue = false)))
        agent.sendUpdate(child, historyConfig)
        agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, LoadSessionResponse(modes = modes, configOptions = rootOptions))
        val session = open.await()
        val liveMode = SessionUpdate.CurrentModeUpdate(SessionModeId("child-live-mode"))
        agent.sendUpdate(child, liveMode)
        val config = SessionUpdate.ConfigOptionUpdate(emptyList())
        agent.sendUpdate(child, config)

        assertEquals(listOf(childMode, historyConfig, TreeOperations.OPENED, liveMode, config), world.awaitOperations(child).awaitEvents(5))
        @Suppress("DEPRECATION")
        assertEquals(SessionModeId("root-mode"), session.currentMode.value)
        @Suppress("DEPRECATION")
        assertEquals(rootOptions, session.configOptions.value)
    }

    @Test
    fun `a subagent's notify that throws before its Opened fails it, and after its Opened fails nothing`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            val world = SubagentWorld()
            val root = SessionId("root")
            val early = SessionId("early")
            val live = SessionId("live")
            world.configure = { operations ->
                if (operations.id == early) operations.onNotify = { event, _ ->
                    if (event is SessionEvent.Update && event.update == text("history")) error("notify failed")
                }
                if (operations.id == live) operations.onNotify = { event, _ ->
                    if (event is SessionEvent.Update && event.update == text("first")) error("notify failed")
                }
            }
            val open = async { loadWithOperations.open(client, root, parameters, world) }
            val request = agent.receiveRequest(loadWithOperations.method)
            agent.sendUpdate(root, announce(early))
            agent.sendUpdate(early, text("history"))
            agent.sendUpdate(early, text("queued"))
            loadWithOperations.answer(agent, request, root)
            open.await()
            awaitUnconnected(unconnected, early, text("queued"))
            assertFalse(TreeOperations.OPENED in world.awaitOperations(early).events.value)

            agent.sendUpdate(root, announce(live))
            agent.sendUpdate(live, text("first"))
            agent.sendUpdate(live, text("second"))
            assertEquals(listOf(TreeOperations.OPENED, text("second")), world.awaitOperations(live).awaitEvents(2))
            agent.sendUpdate(root, text("root-update"))
            world.awaitOperations(root).awaitEvent(text("root-update"))
            assertTrue(unconnected.value.none { it.first == live }, "nothing of the live subagent is unconnected")
        }

    @Test
    fun `a subagent whose notify fails before its Opened stays listed but is closed`() =
        withUnconnectedRecorder { client, agent, unconnected ->
            val world = SubagentWorld()
            val root = SessionId("root")
            val failed = SessionId("failed")
            world.configure = { operations ->
                if (operations.id == failed) operations.onNotify = { event, _ ->
                    if (event is SessionEvent.Update && event.update == text("history")) error("notify failed")
                }
            }
            val open = async { loadWithOperations.open(client, root, parameters, world) }
            val request = agent.receiveRequest(loadWithOperations.method)
            agent.sendUpdate(root, announce(failed, title = "announced"))
            agent.sendUpdate(failed, text("history"))
            loadWithOperations.answer(agent, request, root)
            val session = open.await()
            agent.sendUpdate(failed, text("after-failure"))
            awaitUnconnected(unconnected, failed, text("after-failure"))

            val patch = announce(failed, title = "patched", capabilities = cancelGrant, state = StateUpdate.Running())
            agent.sendUpdate(root, patch)
            world.awaitOperations(root).awaitEvent(patch)

            val subagent = session.subagents.single()
            assertEquals(failed, subagent.sessionId)
            assertEquals("announced", subagent.title, "a later patch is not applied")
            assertNull(subagent.state)
            assertFalse(subagent.canCancel)
            assertFailsWith<IllegalStateException> { subagent.cancel() }
        }

    @Test
    fun `a subagent's updates keep their order across the switch to synchronous delivery`() = withClient { client, agent ->
        val world = SubagentWorld()
        val root = SessionId("root")
        val child = SessionId("child")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The order in which the calls of notify start: once the session is complete, a notify that suspends lets the next one start.
        val started = MutableStateFlow<List<Any>>(emptyList())
        world.configure = { operations ->
            if (operations.id == child) operations.onNotify = { event, _ ->
                started.update { it + if (event is SessionEvent.Update) event.update else TreeOperations.OPENED }
                if (event is SessionEvent.Update && event.update == text("1")) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        openLive(client, agent, root, world)
        agent.sendUpdate(root, announce(child))
        agent.sendUpdate(child, text("1"))
        entered.await()
        val later = (2..20).map { text("$it") }
        later.forEach { agent.sendUpdate(child, it) }
        agent.awaitHandled()
        release.complete(Unit)

        world.awaitOperations(child).awaitEvents(2 + later.size)
        assertEquals(listOf(TreeOperations.OPENED, text("1")) + later, started.value)
    }

    /**
     * Opens [root] with a load, and returns once the opening has completed.
     */
    private suspend fun CoroutineScope.openLive(client: Client, agent: ScriptedAgentTransport, root: SessionId, world: SubagentWorld): ClientSession {
        val open = async { loadWithOperations.open(client, root, parameters, world) }
        loadWithOperations.answer(agent, agent.receiveRequest(loadWithOperations.method), root)
        return open.await()
    }

    /**
     * Asserts that a load of [root] after its failed opening registers [child] again, sets it up, and delivers its
     * marker.
     */
    private suspend fun CoroutineScope.assertRetryRegistersAgain(
        client: Client,
        agent: ScriptedAgentTransport,
        opening: Opening,
        root: SessionId,
        child: SessionId,
    ) {
        val world = SubagentWorld()
        val retry = async { client.loadSession(root, parameters, world.root(root)) }
        val request = agent.receiveRequest(AcpMethod.AgentMethods.V1.SessionLoad.methodName)
        agent.sendUpdate(root, announce(child))
        loadResponse(agent, request)
        val session = retry.await()
        assertEquals(listOf(child), session.subagents.map { it.sessionId }, "$opening: the retry registers the same id")
        world.awaitOperations(child).awaitEvent(TreeOperations.OPENED)
    }

    private suspend fun awaitUnconnected(unconnected: MutableStateFlow<List<Pair<SessionId, SessionUpdate>>>, id: SessionId, update: SessionUpdate) {
        withTimeout(5.seconds) { unconnected.first { (id to update) in it } }
    }

    private suspend fun cancelledSessionId(agent: ScriptedAgentTransport): SessionId {
        val notification = agent.receiveNotification(AcpMethod.AgentMethods.V1.SessionCancel.methodName)
        return ACPJson.decodeFromJsonElement(AcpMethod.AgentMethods.V1.SessionCancel.serializer, notification.params!!).sessionId
    }

    private fun withUnconnectedRecorder(
        block: suspend CoroutineScope.(Client, ScriptedAgentTransport, MutableStateFlow<List<Pair<SessionId, SessionUpdate>>>) -> Unit,
    ) {
        val unconnected = MutableStateFlow<List<Pair<SessionId, SessionUpdate>>>(emptyList())
        withClient(globalSessionUpdateHandler = { id, update, _ -> unconnected.update { it + (id to update) } }) { client, agent ->
            block(client, agent, unconnected)
        }
    }

    private fun withClient(
        globalSessionUpdateHandler: GlobalSessionUpdateHandler? = null,
        block: suspend CoroutineScope.(Client, ScriptedAgentTransport) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val transport = ScriptedAgentTransport()
            val protocol = Protocol(scope, transport)
            protocol.start()
            transport.start()
            val client = Client(protocol, globalSessionUpdateHandler = globalSessionUpdateHandler)
            runBlocking {
                withContext(Dispatchers.Default) {
                    withTimeout(60.seconds) { block(client, transport) }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        private val counter = AtomicInteger()
        private val cancelGrant = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities()))

        private fun text(value: String) = SessionUpdate.AgentMessageChunk(ContentBlock.Text(value))

        private fun announce(
            id: SessionId,
            title: String? = null,
            cwd: String? = null,
            capabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined,
            state: StateUpdate? = null,
        ) = SessionUpdate.SubagentUpdate(
            sessionId = id,
            title = title?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
            cwd = cwd?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
            capabilities = capabilities,
            state = state?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
        )

        private fun livePatch(id: SessionId) = announce(id, capabilities = cancelGrant, state = StateUpdate.Idle(IdleStopReason.EndTurn))

        private fun permissionRequest(id: SessionId, toolCall: String) =
            RequestPermissionRequest(id, SessionUpdate.ToolCallUpdate(ToolCallId(toolCall)), emptyList())

        private fun <T> decode(response: com.agentclientprotocol.rpc.JsonRpcResponse, serializer: KSerializer<T>): T =
            ACPJson.decodeFromJsonElement(serializer, assertIs<JsonRpcSuccessResponse>(response, "an answer, got $response").result)

        private fun loadResponse(agent: ScriptedAgentTransport, request: JsonRpcRequest): LoadSessionResponse =
            LoadSessionResponse(_meta = JsonPrimitive("load-${counter.incrementAndGet()}"))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionLoad.responseSerializer, it) }

        private fun resumeResponse(agent: ScriptedAgentTransport, request: JsonRpcRequest): ResumeSessionResponse =
            ResumeSessionResponse(_meta = JsonPrimitive("resume-${counter.incrementAndGet()}"))
                .also { agent.answer(request, AcpMethod.AgentMethods.V1.SessionResume.responseSerializer, it) }
    }
}
