@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentSubagentFactory
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.agent.subagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.GlobalSessionUpdateHandler
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An agent and a client of this SDK exchange subagents: the agent's app announces children, and the client's app
 * observes them through `notify` and [com.agentclientprotocol.client.ClientSubagentSession]. Each test runs on every
 * transport of the module.
 *
 * Most tests act as the agent's app from the test body, through the `subagents` and `client` that the root session's
 * context gives, because a root's updates reach `notify` only outside a prompt turn: during a turn they go to the
 * prompt's flow. Streams of different sessions are delivered concurrently, so a test checks the order within one
 * stream, and waits for the last event of a stream instead of sleeping.
 */
abstract class SubagentsTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    private val rootId = SessionId("root-1")
    private val childId = SessionId("child-1")
    private val secondChildId = SessionId("child-2")
    private val grandchildId = SessionId("grandchild-1")
    private val params = SessionCreationParameters("/work", emptyList())
    private val promptText = listOf(ContentBlock.Text("go"))
    private val toolCall = SessionUpdate.ToolCallUpdate(ToolCallId("edit-1"), title = "Edit Foo.kt")

    private fun connect(
        clientProtocol: Protocol,
        agentProtocol: Protocol,
        support: ScriptedAgentSupport,
        globalHandler: GlobalSessionUpdateHandler? = null,
    ): Client {
        Agent(agentProtocol, support)
        return Client(clientProtocol, globalSessionUpdateHandler = globalHandler)
    }

    private suspend fun Client.start(capabilities: ClientCapabilities = subagentClientCapabilities) {
        initialize(ClientInfo(capabilities = capabilities))
    }

    private suspend fun Client.openNew(ops: (SessionId) -> ClientSessionOperations): ClientSession =
        newSession(params) { id, _ -> ops(id) }

    private suspend fun ClientSession.runTurn(): List<Event> = withTimeout(subagentTimeout) { prompt(promptText).toList() }

    private suspend fun <T> CompletableDeferred<T>.awaitTimed(): T = withTimeout(subagentTimeout) { await() }

    private fun RecordingOps.subagentUpdates(id: SessionId) =
        updates.filterIsInstance<SessionUpdate.SubagentUpdate>().filter { it.sessionId == id }

    // 1. Capability

    @Test
    fun `without the subagents capability the agent has no subagents and sends nothing about them`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start(capabilities = ClientCapabilities())
            val world = SubagentWorld()

            val session = client.openNew { world.create(it) }

            val context = support.rootContext()
            assertNull(context.subagents, "the agent's context has no subagents without the capability")
            assertFailsWith<IllegalStateException>("session_message is refused before it reaches the wire") {
                context.client.notify(SessionUpdate.SessionMessage(MessageId("m-1")))
            }
            assertFailsWith<IllegalStateException>("a tool call with session content is refused too") {
                context.client.notify(
                    SessionUpdate.ToolCall(ToolCallId("t-1"), "Delegate", content = listOf(ToolCallContent.Session(childId)))
                )
            }
            context.client.notify(agentTextChunk("after"))

            val ops = world[rootId]
            ops.awaitUpdate { it == agentTextChunk("after") }
            assertEquals(listOf<SessionUpdate>(agentTextChunk("after")), ops.updates, "only the marker came through")
            assertTrue(session.subagents.isEmpty())
            assertEquals(1, world.all.size, "no operations were built for a subagent")
        }

    @Test
    fun `an announced child is in the parent's subagents inside the notify for its announcement`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val subagents = checkNotNull(support.rootContext().subagents)

            subagents.announce(childId, AgentSubagentFactory { ctx ->
                ctx.client.notify(announcement(childId, title = "Reviewer", description = "Reviews the diff", cwd = "/work/reviewer"))
                ScriptedChild()
            })
            subagents.announce(secondChildId, AgentSubagentFactory { ctx ->
                ctx.client.notify(announcement(secondChildId, title = "Plain"))
                ScriptedChild()
            })
            support.rootContext().client.notify(agentTextChunk("after"))

            val ops = world[rootId]
            ops.awaitUpdate { it == agentTextChunk("after") }
            val announcements = ops.delivered.value.filter { it.update is SessionUpdate.SubagentUpdate }
            assertEquals(2, announcements.size)

            // The child is already listed, with the announced values, when the parent's notify runs.
            val reviewer = announcements[0].children!!.single()
            assertEquals(childId, reviewer.sessionId)
            assertEquals(rootId, reviewer.parentSessionId)
            assertEquals("Reviewer", reviewer.title)
            assertEquals("Reviews the diff", reviewer.description)
            assertEquals("/work/reviewer", reviewer.cwd)
            assertEquals(listOf(childId, secondChildId), announcements[1].children!!.map { it.sessionId })

            assertEquals(listOf(childId, secondChildId), session.subagents.map { it.sessionId })
            val plain = session.subagents[1]
            assertEquals("/work", plain.cwd, "a child without a cwd of its own inherits the root's")
            assertEquals(emptyList(), plain.additionalDirectories)
        }

    @Test
    fun `a child announced during a prompt is delivered to the prompt flow and listed after it`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val delegate = SessionUpdate.ToolCall(
                ToolCallId("delegate"), "Delegate", content = listOf(ToolCallContent.Session(childId)),
            )
            val support = ScriptedAgentSupport().apply {
                newRoot(rootId) {
                    promptScript = {
                        val context = currentCoroutineContext()
                        context.subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                            ctx.client.notify(announcement(childId, title = "Reviewer", description = "Reviews the diff"))
                            ScriptedChild()
                        })
                        context.client.notify(delegate)
                    }
                }
            }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }

            val events = session.runTurn()

            val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
            assertEquals(
                listOf(announcement(childId, title = "Reviewer", description = "Reviews the diff"), delegate),
                updates,
                "the announcement comes before the tool call that points at the child",
            )
            val child = session.subagents.single()
            assertEquals(childId, child.sessionId)
            assertEquals("Reviewer", child.title)
            assertEquals("Reviews the diff", child.description)
            assertEquals(listOf(rootId, childId), world.all.map { it.sessionId })
        }

    // 2. A live child

    @Test
    fun `a live child reports its work and updates on the right streams in order`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val toolCallSent = CompletableDeferred<Unit>()
            val childFinished = CompletableDeferred<Unit>()
            val task = SessionUpdate.SessionMessage(
                MessageId("task-1"),
                senderSessionId = MaybeUndefined.Value(rootId),
                recipientSessionId = MaybeUndefined.Value(childId),
                content = MaybeUndefined.Value(listOf(ContentBlock.Text("Review the diff"))),
            )
            val childToolCall = SessionUpdate.ToolCall(ToolCallId("read-1"), "Read Foo.kt", ToolKind.READ, ToolCallStatus.IN_PROGRESS)
            val delegateCall = SessionUpdate.ToolCall(
                ToolCallId("delegate"), "Delegate the review", ToolKind.OTHER, ToolCallStatus.IN_PROGRESS,
                content = listOf(ToolCallContent.Session(childId)),
            )
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val context = support.rootContext()

            context.subagents!!.announce(childId, AgentSubagentFactory {
                ScriptedChild(flow {
                    emit(announcement(childId, title = "Reviewer", description = "Reviews the diff"))
                    // The parent's stream order is fixed by waiting for the parent's own update.
                    toolCallSent.await()
                    emit(announcement(childId, state = running))
                    emit(task)
                    emit(agentTextChunk("Looking at it"))
                    emit(childToolCall)
                    emit(announcement(childId, state = idle(IdleStopReason.EndTurn)))
                    childFinished.complete(Unit)
                })
            })
            context.client.notify(delegateCall)
            toolCallSent.complete(Unit)
            childFinished.awaitTimed()
            val completed = SessionUpdate.ToolCallUpdate(ToolCallId("delegate"), status = ToolCallStatus.COMPLETED)
            context.client.notify(completed)

            val rootOps = world[rootId]
            rootOps.awaitUpdate { it == completed }
            val childOps = world[childId]
            childOps.awaitUpdate { it == childToolCall }

            assertEquals(
                listOf<SessionUpdate>(
                    announcement(childId, title = "Reviewer", description = "Reviews the diff"),
                    delegateCall,
                    announcement(childId, state = running),
                    announcement(childId, state = idle(IdleStopReason.EndTurn)),
                    completed,
                ),
                rootOps.updates,
                "the parent's stream carries the announcement, its own tool call and the child's states",
            )
            val states = rootOps.delivered.value.filter { it.update is SessionUpdate.SubagentUpdate }.map { it.children!!.single().state }
            assertEquals(listOf(null, StateUpdate.Running(), StateUpdate.Idle(IdleStopReason.EndTurn)), states)
            assertEquals(StateUpdate.Idle(IdleStopReason.EndTurn), session.subagents.single().state)

            assertEquals(
                listOf<SessionUpdate>(task, agentTextChunk("Looking at it"), childToolCall),
                childOps.updates,
                "the child's own stream carries its message and updates, and no subagent_update",
            )
            val opened = childOps.events.first()
            assertIs<SessionEvent.Opened>(opened)
            assertNull(opened.response)
            assertEquals(1, childOps.events.count { it is SessionEvent.Opened })
        }

    // 3. Nesting

    @Test
    fun `a grandchild and a relayed child with an explicit parent appear in the right place of the tree`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val relayedId = SessionId("relayed-1")
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val subagents = support.rootContext().subagents!!

            subagents.announce(childId, AgentSubagentFactory { ctx ->
                ctx.client.notify(announcement(childId, title = "Child"))
                ctx.subagents.announce(grandchildId, AgentSubagentFactory { grand ->
                    grand.client.notify(announcement(grandchildId, title = "Grandchild"))
                    ScriptedChild(flow { emit(agentTextChunk("grandchild speaks")) })
                })
                ScriptedChild(flow { emit(agentTextChunk("child speaks")) })
            })
            // A relay learns about nested children from a flat stream and names the parent.
            subagents.announce(relayedId, AgentSubagentFactory { relayed ->
                relayed.client.notify(announcement(relayedId, title = "Relayed"))
                ScriptedChild(flow { emit(agentTextChunk("relayed speaks")) })
            }, parent = childId)

            world.await(grandchildId).awaitUpdate { it == agentTextChunk("grandchild speaks") }
            world.await(relayedId).awaitUpdate { it == agentTextChunk("relayed speaks") }
            world.await(childId).awaitUpdate { it == agentTextChunk("child speaks") }

            val child = session.subagents.single()
            assertEquals(childId, child.sessionId)
            assertEquals(listOf(grandchildId, relayedId), child.subagents.map { it.sessionId })
            assertEquals(listOf(childId, childId), child.subagents.map { it.parentSessionId })
            assertEquals(listOf("Grandchild", "Relayed"), child.subagents.map { it.title })
            assertTrue(child.subagents.all { it.subagents.isEmpty() })

            // The announcements of the nested children are on the child's stream, not on the root's.
            assertEquals(listOf<SessionUpdate>(announcement(childId, title = "Child")), world[rootId].updates)
            val childUpdates = world[childId].updates
            assertEquals(
                setOf(announcement(grandchildId, title = "Grandchild"), announcement(relayedId, title = "Relayed"), agentTextChunk("child speaks")),
                childUpdates.toSet(),
            )
            assertEquals(3, childUpdates.size)
            for (id in listOf(childId, grandchildId, relayedId)) {
                assertIs<SessionEvent.Opened>(world[id].events.first(), "$id starts with Opened")
                assertEquals(1, world[id].events.count { it is SessionEvent.Opened }, "$id gets exactly one Opened")
            }
        }

    // 4. Child requests

    @Test
    fun `a child's permission and file requests reach its operations and the answers return to it`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val permission = CompletableDeferred<RequestPermissionResponse>()
            val fileContent = CompletableDeferred<String>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            world.configure = { ops -> if (ops.sessionId == childId) ops.fileContent = "child file" }
            client.openNew { world.create(it) }

            support.rootContext().subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                ScriptedChild(flow {
                    emit(announcement(childId, title = "Editor"))
                    permission.complete(ctx.client.requestPermissions(toolCall, listOf(allowOption)))
                    fileContent.complete(ctx.client.fsReadTextFile("/work/Foo.kt").content)
                })
            })

            assertEquals(RequestPermissionOutcome.Selected(allowOption.optionId), permission.awaitTimed().outcome)
            assertEquals("child file", fileContent.awaitTimed())
            val childOps = world[childId]
            val request = childOps.awaitPermissionRequest()
            assertEquals(childId, request.sessionInContext, "the call runs in the child's session context")
            assertEquals(toolCall, request.toolCall)
            assertEquals(listOf(childId to "/work/Foo.kt"), childOps.fileReads.value)
            assertTrue(world[rootId].permissionRequests.value.isEmpty(), "the root's operations got no request")
            assertTrue(world[rootId].fileReads.value.isEmpty())
        }

    // 5. Cancel

    @Test
    fun `cancel with a grant reaches the child and answers its pending permission request cancelled`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val cancelCalled = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<RequestPermissionOutcome>()
            val clientHandlerEnded = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            world.configure = { ops ->
                if (ops.sessionId == childId) {
                    ops.permissionHandler = {
                        try {
                            awaitCancellation()
                        } finally {
                            clientHandlerEnded.complete(Unit)
                        }
                    }
                }
            }
            val session = client.openNew { world.create(it) }

            support.rootContext().subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                ScriptedChild(
                    events = flow {
                        emit(announcement(childId, title = "Worker", capabilities = cancelGrant, state = running))
                        outcome.complete(ctx.client.requestPermissions(toolCall, listOf(allowOption)).outcome)
                        cancelCalled.await()
                        emit(announcement(childId, state = idle(IdleStopReason.Cancelled)))
                    },
                    onCancel = { cancelCalled.complete(Unit) },
                )
            })

            val rootOps = world[rootId]
            rootOps.awaitUpdate { it is SessionUpdate.SubagentUpdate }
            val child = session.subagents.single()
            assertTrue(child.canCancel, "the grant applies once the parent is open")
            world[childId].awaitPermissionRequest()

            child.cancel()

            cancelCalled.awaitTimed()
            assertEquals(RequestPermissionOutcome.Cancelled, outcome.awaitTimed(), "the client answered the pending request")
            clientHandlerEnded.awaitTimed()
            val cancelled = rootOps.awaitUpdate { it is SessionUpdate.SubagentUpdate && it.state == idle(IdleStopReason.Cancelled) }
            assertEquals(StateUpdate.Idle(IdleStopReason.Cancelled), cancelled.children!!.single().state)
            assertEquals(StateUpdate.Idle(IdleStopReason.Cancelled), child.state)
        }

    @Test
    fun `cancel without a grant throws locally and the agent's child is not cancelled`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val ungrantedCancels = CompletableDeferred<Unit>()
            val grantedCancel = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val subagents = support.rootContext().subagents!!

            subagents.announce(childId, AgentSubagentFactory { ctx ->
                ctx.client.notify(announcement(childId, state = running))
                ScriptedChild(onCancel = { ungrantedCancels.complete(Unit) })
            })
            subagents.announce(secondChildId, AgentSubagentFactory { ctx ->
                ctx.client.notify(announcement(secondChildId, capabilities = cancelGrant))
                ScriptedChild(onCancel = { grantedCancel.complete(Unit) })
            })
            world[rootId].awaitUpdate { it is SessionUpdate.SubagentUpdate && it.sessionId == secondChildId }
            val (ungranted, granted) = session.subagents

            assertFalse(ungranted.canCancel)
            assertFailsWith<IllegalStateException> { ungranted.cancel() }
            // The control: a granted child is cancelled, so a cancel that was sent for the other one would have arrived first.
            assertTrue(granted.canCancel)
            granted.cancel()
            grantedCancel.awaitTimed()
            assertFalse(ungrantedCancels.isCompleted, "nothing was sent for the child without a grant")
        }

    @Test
    fun `revoking the grant disables cancel for null capabilities and for a null cancel control`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val cancelled = CompletableDeferred<SessionId>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val subagents = support.rootContext().subagents!!
            val updates = listOf(childId, secondChildId).associateWith { id ->
                val feed = Channel<SessionUpdate>(Channel.UNLIMITED)
                subagents.announce(id, AgentSubagentFactory {
                    ScriptedChild(
                        events = flow {
                            emit(announcement(id, capabilities = cancelGrant))
                            for (update in feed) emit(update)
                        },
                        onCancel = { cancelled.complete(id) },
                    )
                })
                feed
            }
            world[rootId].awaitUpdate { it is SessionUpdate.SubagentUpdate && it.sessionId == secondChildId }
            val (first, second) = session.subagents
            assertTrue(first.canCancel && second.canCancel)

            updates.getValue(childId).send(SessionUpdate.SubagentUpdate(childId, capabilities = MaybeUndefined.Null))
            val noCapabilities = SubagentSessionCapabilities(cancel = null)
            updates.getValue(secondChildId).send(SessionUpdate.SubagentUpdate(secondChildId, capabilities = MaybeUndefined.Value(noCapabilities)))
            val rootOps = world[rootId]
            val revokedFirst = rootOps.awaitUpdate { it is SessionUpdate.SubagentUpdate && it.sessionId == childId && it.capabilities == MaybeUndefined.Null }
            val revokedSecond = rootOps.awaitUpdate { it is SessionUpdate.SubagentUpdate && it.sessionId == secondChildId && it.capabilities != cancelGrant && it.capabilities != MaybeUndefined.Undefined }

            assertFalse(revokedFirst.children!!.first { it.sessionId == childId }.canCancel, "reflected inside the notify")
            assertFalse(revokedSecond.children!!.first { it.sessionId == secondChildId }.canCancel)
            assertFalse(first.canCancel)
            assertFalse(second.canCancel)
            assertFailsWith<IllegalStateException> { first.cancel() }
            assertFailsWith<IllegalStateException> { second.cancel() }
            assertFalse(cancelled.isCompleted, "no cancel reached the agent")
        }

    @Test
    fun `a cancel that arrives while the child's factory runs is delivered once the factory returned`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val order = MutableStateFlow<List<String>>(emptyList())
            val factoryMayReturn = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val subagents = support.rootContext().subagents!!

            val announced = async {
                subagents.announce(childId, AgentSubagentFactory { ctx ->
                    // The grant is in effect as soon as the announcement is sent, while the factory still runs.
                    ctx.client.notify(announcement(childId, capabilities = cancelGrant))
                    factoryMayReturn.await()
                    order.update { it + "factory returned" }
                    ScriptedChild(onCancel = { order.update { it + "cancel" } })
                })
            }
            world[rootId].awaitUpdate { it is SessionUpdate.SubagentUpdate }
            val child = session.subagents.single()
            assertTrue(child.canCancel)

            child.cancel()
            // A round trip after the cancel, so the agent has handled the cancel before the factory is released.
            session.runTurn()
            factoryMayReturn.complete(Unit)
            announced.await()

            withTimeout(subagentTimeout) { order.first { it.size == 2 } }
            assertEquals(listOf("factory returned", "cancel"), order.value, "the cancel was held until the factory returned")
        }

    // 6. Feed end

    @Test
    fun `a feed that ends while running reports unknown and one that ends after idle reports nothing`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val endingRunning = CompletableDeferred<Job>()
            val endingIdle = CompletableDeferred<Job>()
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            val session = client.openNew { world.create(it) }
            val context = support.rootContext()
            val subagents = context.subagents!!

            subagents.announce(childId, AgentSubagentFactory {
                ScriptedChild(flow {
                    // The collection's job: joined later, once the SDK has handled the end of the feed.
                    endingRunning.complete(currentCoroutineContext().job)
                    emit(announcement(childId, state = running))
                })
            })
            subagents.announce(secondChildId, AgentSubagentFactory {
                ScriptedChild(flow {
                    endingIdle.complete(currentCoroutineContext().job)
                    emit(announcement(secondChildId, state = running))
                    emit(announcement(secondChildId, state = idle(IdleStopReason.EndTurn)))
                })
            })
            withTimeout(subagentTimeout) {
                endingRunning.await().join()
                endingIdle.await().join()
            }
            context.client.notify(agentTextChunk("end"))

            val rootOps = world[rootId]
            val marker = rootOps.awaitUpdate { it == agentTextChunk("end") }
            assertEquals(
                listOf(announcement(childId, state = running), SessionUpdate.SubagentUpdate(childId, state = MaybeUndefined.Value(StateUpdate.Unknown()))),
                rootOps.subagentUpdates(childId),
                "a feed that ends while running owes unknown",
            )
            assertEquals(
                listOf(announcement(secondChildId, state = running), announcement(secondChildId, state = idle(IdleStopReason.EndTurn))),
                rootOps.subagentUpdates(secondChildId),
                "a feed that ends after idle owes nothing",
            )
            assertEquals(5, rootOps.updates.size, "four child updates and the marker")
            assertEquals(StateUpdate.Unknown(), marker.children!!.first { it.sessionId == childId }.state)
            assertEquals(StateUpdate.Unknown(), session.subagents[0].state)
            assertEquals(StateUpdate.Idle(IdleStopReason.EndTurn), session.subagents[1].state)
        }

    // 7. session/load and session/resume

    private fun ScriptedAgentSupport.replayingChild(onCancel: suspend () -> Unit = {}) {
        loadRoot {
            replayScript = {
                val context = currentCoroutineContext()
                context.client.notify(agentTextChunk("root history"))
                context.subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                    // The recorded grant and state are history: the client doesn't act on them.
                    ctx.client.notify(
                        announcement(childId, title = "Recorded", description = "Was reviewing", capabilities = cancelGrant, state = idle(IdleStopReason.EndTurn))
                    )
                    ctx.client.notify(agentTextChunk("child history"))
                    ctx.subagents.announce(grandchildId, AgentSubagentFactory { grand ->
                        grand.client.notify(announcement(grandchildId, title = "Recorded grandchild", capabilities = cancelGrant, state = idle(IdleStopReason.EndTurn)))
                        grand.client.notify(agentTextChunk("grandchild history"))
                        ScriptedChild()
                    })
                    ScriptedChild(
                        events = flow {
                            // Fresh snapshots after the response, then the child lives on.
                            emit(announcement(childId, capabilities = cancelGrant, state = running))
                            emit(agentTextChunk("child live"))
                            awaitCancellation()
                        },
                        onCancel = onCancel,
                    )
                })
                context.client.notify(agentTextChunk("root history end"))
            }
        }
    }

    private suspend fun assertLoadedTree(world: SubagentWorld, session: ClientSession, cancelCalled: CompletableDeferred<Unit>) {
        val rootOps = world[rootId]
        val childOps = world.await(childId)
        val grandchildOps = world.await(grandchildId)
        val fresh = announcement(childId, capabilities = cancelGrant, state = running)
        rootOps.awaitUpdate { it == fresh }
        childOps.awaitUpdate { it == agentTextChunk("child live") }
        grandchildOps.awaitUpdate { it == agentTextChunk("grandchild history") }

        // The root's stream: history, the response, then the fresh snapshot.
        val rootEvents = rootOps.events
        val opened = rootEvents.indexOfFirst { it is SessionEvent.Opened }
        assertEquals(1, rootEvents.count { it is SessionEvent.Opened })
        assertNotNull((rootEvents[opened] as SessionEvent.Opened).response, "a root's marker carries the response")
        assertEquals(
            listOf<SessionUpdate>(
                agentTextChunk("root history"),
                announcement(childId, title = "Recorded", description = "Was reviewing", capabilities = cancelGrant, state = idle(IdleStopReason.EndTurn)),
                agentTextChunk("root history end"),
            ),
            rootOps.delivered.value.take(opened).map { it.update!! },
            "everything replayed comes before the response",
        )
        assertEquals(listOf<SessionUpdate>(fresh), rootOps.delivered.value.drop(opened + 1).map { it.update!! })

        // Held-back grant and state are not in effect at Opened, and the fresh snapshot applies after it.
        val atOpened = rootOps.delivered.value[opened].children!!.single()
        assertFalse(atOpened.canCancel)
        assertNull(atOpened.state)
        assertEquals("Recorded", atOpened.title, "history titles are visible")
        val afterResponse = rootOps.delivered.value.last().children!!.single()
        assertTrue(afterResponse.canCancel)
        assertEquals(StateUpdate.Running(), afterResponse.state)

        // Each child gets its history, one Opened(null), then its live updates.
        val childEvents = childOps.events
        assertEquals(1, childEvents.count { it is SessionEvent.Opened })
        val childOpened = childEvents.indexOfFirst { it is SessionEvent.Opened }
        assertNull((childEvents[childOpened] as SessionEvent.Opened).response)
        assertEquals(
            setOf<SessionUpdate>(agentTextChunk("child history"), announcement(grandchildId, title = "Recorded grandchild", capabilities = cancelGrant, state = idle(IdleStopReason.EndTurn))),
            childOps.delivered.value.take(childOpened).map { it.update!! }.toSet(),
        )
        assertEquals(listOf<SessionUpdate>(agentTextChunk("child live")), childOps.delivered.value.drop(childOpened + 1).map { it.update!! })

        val grandchildEvents = grandchildOps.events
        assertEquals(1, grandchildEvents.count { it is SessionEvent.Opened })
        assertEquals(agentTextChunk("grandchild history"), (grandchildEvents[0] as SessionEvent.Update).update)
        assertIs<SessionEvent.Opened>(grandchildEvents[1])
        assertEquals("Recorded grandchild", grandchildOps.delivered.value[0].self!!.title)
        assertFalse(grandchildOps.delivered.value[0].self!!.canCancel)

        val child = session.subagents.single()
        assertTrue(child.canCancel)
        assertEquals(StateUpdate.Running(), child.state)
        val grandchild = child.subagents.single()
        assertFalse(grandchild.canCancel, "a grant that was only history never enables cancel")
        assertNull(grandchild.state, "a state that was only history is not shown")

        child.cancel()
        cancelCalled.awaitTimed()
    }

    @Test
    fun `session load with early delivery replays children before the response and the live child continues`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val cancelCalled = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply { replayingChild(onCancel = { cancelCalled.complete(Unit) }) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()

            val session = withTimeout(subagentTimeout) { client.loadSession(rootId, params, world.create(rootId)) }

            assertLoadedTree(world, session, cancelCalled)
        }

    @Test
    fun `session load with an operations factory delivers the same sequence`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val cancelCalled = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply { replayingChild(onCancel = { cancelCalled.complete(Unit) }) }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()

            val session = withTimeout(subagentTimeout) { client.loadSession(rootId, params) { _, _ -> world.create(rootId) } }

            assertLoadedTree(world, session, cancelCalled)
        }

    @Test
    fun `session resume makes children announced after the response live and their grants apply`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val cancelCalled = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply {
                resumeRoot {
                    postInitializeScript = {
                        currentCoroutineContext().subagents!!.announce(childId, AgentSubagentFactory {
                            ScriptedChild(
                                events = flow {
                                    emit(announcement(childId, title = "Resumed", capabilities = cancelGrant, state = running))
                                    emit(agentTextChunk("child live"))
                                    awaitCancellation()
                                },
                                onCancel = { cancelCalled.complete(Unit) },
                            )
                        })
                    }
                }
            }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()

            val session = withTimeout(subagentTimeout) { client.resumeSession(rootId, params, world.create(rootId)) }

            val rootOps = world[rootId]
            val announced = rootOps.awaitUpdate { it is SessionUpdate.SubagentUpdate }
            val child = announced.children!!.single()
            assertTrue(child.canCancel, "the grant applies at once: the child was announced after the response")
            assertEquals(StateUpdate.Running(), child.state)
            val childOps = world[childId]
            childOps.awaitUpdate { it == agentTextChunk("child live") }
            assertIs<SessionEvent.Opened>(childOps.events.first())
            assertEquals(1, childOps.events.count { it is SessionEvent.Opened })

            session.subagents.single().cancel()
            cancelCalled.awaitTimed()
        }

    // 8. Close

    @Test
    fun `closing the root answers a pending child permission request cancelled and ends the child`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val outcome = CompletableDeferred<RequestPermissionOutcome>()
            val collectionEnded = CompletableDeferred<Throwable?>()
            val reannounced = CompletableDeferred<Unit>()
            val support = ScriptedAgentSupport().apply {
                newRoot(rootId) {
                    // The close waits for the child's request to be answered, as the SDK's client lets it.
                    closeScript = { outcome.await() }
                }
                loadRoot {
                    replayScript = {
                        currentCoroutineContext().subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                            ctx.client.notify(announcement(childId, title = "Recorded again"))
                            ctx.client.notify(agentTextChunk("recorded again"))
                            reannounced.complete(Unit)
                            ScriptedChild()
                        })
                    }
                }
            }
            val client = connect(clientProtocol, agentProtocol, support)
            client.start()
            val world = SubagentWorld()
            world.configure = { ops -> if (ops.sessionId == childId) ops.permissionHandler = { awaitCancellation() } }
            val session = client.openNew { world.create(it) }
            val rootContext = support.rootContext()
            val subagents = rootContext.subagents!!

            subagents.announce(childId, AgentSubagentFactory { ctx ->
                ScriptedChild(
                    flow {
                        emit(announcement(childId, title = "Worker", capabilities = cancelGrant, state = running))
                        outcome.complete(ctx.client.requestPermissions(toolCall, listOf(allowOption)).outcome)
                        awaitCancellation()
                    }.onCompletion { collectionEnded.complete(it) }
                )
            })
            world[rootId].awaitUpdate { it is SessionUpdate.SubagentUpdate }
            val child = session.subagents.single()
            assertTrue(child.canCancel)
            world[childId].awaitPermissionRequest()

            withTimeout(subagentTimeout) { session.close() }

            assertEquals(RequestPermissionOutcome.Cancelled, outcome.awaitTimed())
            assertIs<CancellationException>(collectionEnded.awaitTimed(), "the child's collection was cancelled")
            assertFalse(child.canCancel)
            assertFailsWith<IllegalStateException> { child.cancel() }
            assertEquals("Worker", child.title)
            assertEquals(listOf(child), session.subagents, "the closed root keeps its list")
            assertFailsWith<IllegalStateException>("the closed root has no subagents to announce") {
                subagents.announce(secondChildId, AgentSubagentFactory { ScriptedChild() })
            }

            // The same child id can be announced again once the root is loaded again.
            val reloaded = withTimeout(subagentTimeout) { client.loadSession(rootId, params, world.create(rootId)) }
            reannounced.awaitTimed()
            val reloadedChild = reloaded.subagents.single()
            assertEquals(childId, reloadedChild.sessionId)
            assertNotSame(child, reloadedChild)
            assertEquals("Recorded again", reloadedChild.title)
            assertEquals(listOf(child), session.subagents, "the old object's list doesn't change")
            val reloadedOps = world[childId]
            reloadedOps.awaitUpdate { it == agentTextChunk("recorded again") }
            assertEquals(1, reloadedOps.events.count { it is SessionEvent.Opened })
            assertEquals(2, world.all.count { it.sessionId == childId })
        }

    // 9. Setup failure on the client

    @Test
    fun `a child whose setup fails on the client leaves the parent's stream working and its requests fail`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            val requestFailure = CompletableDeferred<Throwable?>()
            val globalUpdates = Channel<Pair<SessionId, SessionUpdate>>(Channel.UNLIMITED)
            val support = ScriptedAgentSupport().apply { newRoot(rootId) }
            val client = connect(
                clientProtocol, agentProtocol, support,
                globalHandler = GlobalSessionUpdateHandler { id, update, _ -> globalUpdates.send(id to update) },
            )
            client.start()
            // The parent's operations don't implement SubagentOperations.
            val rootOps = RecordingOps(rootId)
            val session = client.openNew { rootOps }
            val context = support.rootContext()

            context.subagents!!.announce(childId, AgentSubagentFactory { ctx ->
                ScriptedChild(flow {
                    emit(announcement(childId, title = "Orphan"))
                    emit(agentTextChunk("child text"))
                    requestFailure.complete(runCatching { ctx.client.requestPermissions(toolCall, listOf(allowOption)) }.exceptionOrNull())
                })
            })
            val failure = requestFailure.awaitTimed()
            context.client.notify(agentTextChunk("parent still works"))

            assertNotNull(failure, "the child's request gets an error")
            assertFalse(failure is CancellationException)
            rootOps.awaitUpdate { it == agentTextChunk("parent still works") }
            assertEquals(
                listOf(announcement(childId, title = "Orphan"), agentTextChunk("parent still works")),
                rootOps.updates,
                "the parent still gets the announcement as a plain update",
            )
            assertTrue(session.subagents.isEmpty())
            assertEquals(childId to agentTextChunk("child text"), withTimeout(subagentTimeout) { globalUpdates.receive() })
        }
}
