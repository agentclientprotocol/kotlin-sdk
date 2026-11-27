package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.internal.ClientSessionHolder
import com.agentclientprotocol.client.internal.ClientSessionImpl
import com.agentclientprotocol.client.internal.RootSessionHolder
import com.agentclientprotocol.client.internal.SubagentSessionHolder
import com.agentclientprotocol.client.internal.deliverUpdate
import com.agentclientprotocol.client.internal.executeWithSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentSessionCapabilities
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class FrameRecordingTransport : BaseTransport() {
    val frames = Channel<TransportFrame>(capacity = Channel.UNLIMITED)
    var sendFailure: Throwable? = null

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSED
    }

    override fun send(frame: TransportFrame) {
        sendFailure?.let { throw it }
        frames.trySend(frame)
    }
}

private object NoOperations : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = error("not expected in this test")

    override suspend fun notify(event: SessionEvent) {}
}

private class RecordingChildOperations : ClientSessionOperations {
    val events = mutableListOf<SessionEvent>()
    val permissionRequests = mutableListOf<SessionUpdate.ToolCallUpdate>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequests += toolCall
        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
    }

    override suspend fun notify(event: SessionEvent) {
        events += event
    }
}

private val ALLOWED = RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))

private fun toolCall(id: String) = SessionUpdate.ToolCallUpdate(ToolCallId(id))

/*
 * Answers a permission request as the client's `session/request_permission` handler does with the holder it takes at
 * arrival.
 */
private suspend fun ClientSessionHolder.answerPermissionRequest(
    toolCall: SessionUpdate.ToolCallUpdate,
    options: List<PermissionOption>,
    meta: JsonElement?,
): RequestPermissionResponse = permissions.answerUnlessCancelled(
    cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
) {
    val session = session.await()
    session.executeWithSession { session.operations.requestPermissions(toolCall, options, meta) }
}

/**
 * Operations whose permission handler reports each request on [started] and waits for the user's answer on
 * [answers].
 */
private class WaitingChildOperations : ClientSessionOperations {
    val started = Channel<Unit>(Channel.UNLIMITED)
    val answers = Channel<RequestPermissionResponse>(Channel.UNLIMITED)
    val requests = mutableListOf<SessionUpdate.ToolCallUpdate>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        requests += toolCall
        started.send(Unit)
        return answers.receive()
    }

    override suspend fun notify(event: SessionEvent) {}
}

/**
 * The state of a [ClientSubagentSession], built through its internal constructor: the patches, the grant, the
 * held-back values before the parent's answer, the inherited directories and `cancel()`.
 */
@OptIn(UnstableApi::class)
class ClientSubagentSessionTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val transport = FrameRecordingTransport()
    private val protocol = Protocol(scope, transport).also { it.start() }

    private val rootCwd = "/root"
    private val rootDirectories = listOf("/root/extra")

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val rootParameters = SessionCreationParameters(cwd = rootCwd, mcpServers = emptyList(), additionalDirectories = rootDirectories)

    private fun claimedRoot(): RootSessionHolder {
        val root = rootHolder()
        assertTrue(root.claimForOpening(replayRequested = false, rootParameters))
        return root
    }

    /**
     * A subagent holder under [parent] that stores its session, as the setup leaves it, and that session. Its opening
     * waits for the opened marker, which only [openChild] puts into its queue.
     */
    private fun setUpChild(
        id: String = "child",
        parent: ClientSessionHolder = claimedRoot(),
        operations: ClientSessionOperations = NoOperations,
    ): Child {
        val holder = SubagentSessionHolder(SessionId(id), parent)
        val session = ClientSubagentSession(holder, operations, protocol)
        holder.startOpening(session)
        return Child(holder, session)
    }

    private fun child(
        id: String = "child",
        parent: ClientSessionHolder = claimedRoot(),
        operations: ClientSessionOperations = NoOperations,
    ): ClientSubagentSession = setUpChild(id, parent, operations).session

    private fun patch(
        title: MaybeUndefined<String> = MaybeUndefined.Undefined,
        description: MaybeUndefined<String> = MaybeUndefined.Undefined,
        capabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined,
        state: MaybeUndefined<StateUpdate> = MaybeUndefined.Undefined,
        cwd: MaybeUndefined<String> = MaybeUndefined.Undefined,
        additionalDirectories: MaybeUndefined<List<String>> = MaybeUndefined.Undefined,
    ) = SessionUpdate.SubagentUpdate(
        sessionId = SessionId("child"),
        title = title,
        description = description,
        capabilities = capabilities,
        state = state,
        cwd = cwd,
        additionalDirectories = additionalDirectories,
    )

    private val cancelGrant = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities()))

    private fun capabilities(json: String) =
        MaybeUndefined.Value(ACPJson.decodeFromString(SubagentSessionCapabilities.serializer(), json))

    @Test
    fun `a new subagent has no reported values and inherits the directories`() {
        val child = child()

        assertEquals(SessionId("child"), child.sessionId)
        assertEquals(SessionId("root"), child.parentSessionId)
        assertSame(NoOperations, child.operations)
        assertNull(child.title)
        assertNull(child.description)
        assertNull(child.state)
        assertFalse(child.canCancel)
        assertTrue(child.subagents.isEmpty())
        assertEquals(rootCwd, child.cwd)
        assertEquals(rootDirectories, child.additionalDirectories)
    }

    @Test
    fun `title and description keep omitted clear on null and replace on a value`() {
        val child = child()

        child.applyPatch(patch(title = MaybeUndefined.Value("title"), description = MaybeUndefined.Value("description")), streamOpened = true)
        assertEquals("title", child.title)
        assertEquals("description", child.description)

        child.applyPatch(patch(), streamOpened = true)
        assertEquals("title", child.title, "an omitted title is unchanged")
        assertEquals("description", child.description, "an omitted description is unchanged")

        child.applyPatch(patch(title = MaybeUndefined.Null), streamOpened = true)
        assertNull(child.title, "a null title is cleared")
        assertEquals("description", child.description, "the description is patched on its own")

        child.applyPatch(patch(title = MaybeUndefined.Value("other"), description = MaybeUndefined.Null), streamOpened = true)
        assertEquals("other", child.title)
        assertNull(child.description)
    }

    @Test
    fun `title and description are applied before the parent's answer`() {
        val child = child()

        child.applyPatch(patch(title = MaybeUndefined.Value("title"), description = MaybeUndefined.Value("description")), streamOpened = false)

        assertEquals("title", child.title)
        assertEquals("description", child.description)
    }

    @Test
    fun `own directories replace the inherited ones and clear back to them each on its own`() {
        val child = child()

        child.applyPatch(patch(cwd = MaybeUndefined.Value("/child")), streamOpened = true)
        assertEquals("/child", child.cwd)
        assertEquals(rootDirectories, child.additionalDirectories, "a cwd patch leaves the directories inherited")

        child.applyPatch(patch(additionalDirectories = MaybeUndefined.Value(listOf("/child/extra"))), streamOpened = true)
        assertEquals("/child", child.cwd, "an omitted cwd is unchanged")
        assertEquals(listOf("/child/extra"), child.additionalDirectories)

        child.applyPatch(patch(additionalDirectories = MaybeUndefined.Value(emptyList())), streamOpened = true)
        assertEquals(emptyList(), child.additionalDirectories, "an empty list is a value, not a clear")

        child.applyPatch(patch(cwd = MaybeUndefined.Null), streamOpened = true)
        assertEquals(rootCwd, child.cwd, "a null cwd inherits again")
        assertEquals(emptyList(), child.additionalDirectories, "the directories stay as they were")

        child.applyPatch(patch(additionalDirectories = MaybeUndefined.Null), streamOpened = true)
        assertEquals(rootDirectories, child.additionalDirectories, "null directories inherit again")
    }

    @Test
    fun `directories are applied before the parent's answer`() {
        val child = child()

        child.applyPatch(
            patch(cwd = MaybeUndefined.Value("/child"), additionalDirectories = MaybeUndefined.Value(listOf("/d"))),
            streamOpened = false,
        )

        assertEquals("/child", child.cwd)
        assertEquals(listOf("/d"), child.additionalDirectories)
    }

    @Test
    fun `effective directories follow the parent over two levels`() {
        val (middleHolder, middle) = setUpChild(id = "middle")
        val leaf = child(id = "leaf", parent = middleHolder)

        assertEquals(rootCwd, leaf.cwd)
        assertEquals(rootDirectories, leaf.additionalDirectories)

        middle.applyPatch(patch(cwd = MaybeUndefined.Value("/middle")), streamOpened = true)
        assertEquals("/middle", leaf.cwd, "a grandchild follows a later patch of its parent")
        assertEquals(rootDirectories, leaf.additionalDirectories, "the directories are inherited independently")

        leaf.applyPatch(patch(additionalDirectories = MaybeUndefined.Value(listOf("/leaf/extra"))), streamOpened = true)
        assertEquals("/middle", leaf.cwd)
        assertEquals(listOf("/leaf/extra"), leaf.additionalDirectories)
        assertEquals(rootDirectories, middle.additionalDirectories, "a child's value does not reach its parent")

        middle.applyPatch(patch(cwd = MaybeUndefined.Null), streamOpened = true)
        assertEquals(rootCwd, leaf.cwd, "clearing the parent's cwd changes what the grandchild inherits")

        leaf.applyPatch(patch(cwd = MaybeUndefined.Value("/leaf")), streamOpened = true)
        middle.applyPatch(patch(cwd = MaybeUndefined.Value("/middle again")), streamOpened = true)
        assertEquals("/leaf", leaf.cwd, "an own value wins over the parent's")
    }

    @Test
    fun `capabilities and state are held back before the parent's answer`() {
        val child = child()

        child.applyPatch(patch(capabilities = cancelGrant, state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = false)

        assertFalse(child.canCancel, "a grant before the answer is not in effect")
        assertNull(child.state, "a state before the answer is not shown")
    }

    @Test
    fun `held back capabilities and state are applied when the parent's answer follows a call without replay`() {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant, state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = false)

        child.onParentOpened(applyHeld = true)

        assertTrue(child.canCancel)
        assertEquals(StateUpdate.Running(), child.state)
    }

    @Test
    fun `held back capabilities and state are dropped when the parent's answer follows a load`() {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant, state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = false)

        child.onParentOpened(applyHeld = false)

        assertFalse(child.canCancel, "a grant from the history never takes effect")
        assertNull(child.state, "a state from the history is never shown as current")

        // Nothing is left to be promoted later, and live patches apply.
        child.onParentOpened(applyHeld = true)
        assertFalse(child.canCancel)
        assertNull(child.state)
        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.Idle())), streamOpened = true)
        assertEquals(StateUpdate.Idle(), child.state)
    }

    @Test
    fun `the latest held back patch of each field wins and an omitted one does not hide an earlier one`() {
        val child = child()

        child.applyPatch(patch(capabilities = cancelGrant, state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = false)
        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.RequiresAction())), streamOpened = false)
        child.onParentOpened(applyHeld = true)

        assertTrue(child.canCancel, "the second patch omitted the capabilities")
        assertEquals(StateUpdate.RequiresAction(), child.state)
    }

    @Test
    fun `a held back null state is applied as unconfirmed`() {
        val child = child()
        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = true)

        child.applyPatch(patch(state = MaybeUndefined.Null), streamOpened = false)
        assertEquals(StateUpdate.Running(), child.state, "nothing is applied before the answer")
        child.onParentOpened(applyHeld = true)

        assertNull(child.state)
    }

    @Test
    fun `a live state keeps omitted clears on null and replaces on a value`() {
        val child = child()

        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.Running())), streamOpened = true)
        assertEquals(StateUpdate.Running(), child.state)

        child.applyPatch(patch(title = MaybeUndefined.Value("title")), streamOpened = true)
        assertEquals(StateUpdate.Running(), child.state, "an omitted state is unchanged")

        child.applyPatch(patch(state = MaybeUndefined.Null), streamOpened = true)
        assertNull(child.state, "null means unconfirmed")

        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.Unknown())), streamOpened = true)
        assertEquals(StateUpdate.Unknown(), child.state, "unknown is a confirmed state")
    }

    @Test
    fun `canCancel follows the cancel control of the latest capabilities`() {
        val cases = listOf(
            "{}" to false,
            """{"cancel":null}""" to false,
            """{"cancel":{}}""" to true,
        )
        for ((json, granted) in cases) {
            val child = child()
            child.applyPatch(patch(capabilities = capabilities(json)), streamOpened = true)
            assertEquals(granted, child.canCancel, json)
        }

        val never = child()
        never.applyPatch(patch(title = MaybeUndefined.Value("title")), streamOpened = true)
        assertFalse(never.canCancel, "no capabilities were sent")

        val cleared = child()
        cleared.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
        cleared.applyPatch(patch(capabilities = MaybeUndefined.Null), streamOpened = true)
        assertFalse(cleared.canCancel, "null capabilities clear the grant")
    }

    @Test
    fun `capabilities are replaced as a whole and an omitted patch keeps the grant`() {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)

        child.applyPatch(patch(title = MaybeUndefined.Value("title")), streamOpened = true)
        assertTrue(child.canCancel, "omitted capabilities are unchanged")

        child.applyPatch(patch(capabilities = capabilities("{}")), streamOpened = true)
        assertFalse(child.canCancel, "capabilities without cancel replace the ones with it")

        child.applyPatch(patch(capabilities = capabilities("""{"cancel":{}}""")), streamOpened = true)
        assertTrue(child.canCancel)
    }

    @Test
    fun `an unknown state does not revoke the grant`() {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)

        child.applyPatch(patch(state = MaybeUndefined.Value(StateUpdate.Unknown())), streamOpened = true)

        assertTrue(child.canCancel)
        assertEquals(StateUpdate.Unknown(), child.state)
    }

    @Test
    fun `canCancel is false once the subagent is closed`() {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
        assertTrue(child.canCancel)

        child.markClosed()

        assertFalse(child.canCancel)
    }

    @Test
    fun `cancel without a grant throws and sends nothing`() = runTest {
        val child = child()

        for (capabilities in listOf(null, capabilities("{}"))) {
            if (capabilities != null) child.applyPatch(patch(capabilities = capabilities), streamOpened = true)
            assertFailsWith<IllegalStateException> { child.cancel() }
        }

        assertNull(transport.frames.tryReceive().getOrNull(), "nothing was sent")
    }

    @Test
    fun `cancel with a held back grant throws before the parent's answer`() = runTest {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = false)

        assertFailsWith<IllegalStateException> { child.cancel() }

        assertNull(transport.frames.tryReceive().getOrNull(), "nothing was sent")
    }

    @Test
    fun `cancel after the subagent is closed throws and sends nothing even with a grant`() = runTest {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
        child.markClosed()

        assertFailsWith<IllegalStateException> { child.cancel() }

        assertNull(transport.frames.tryReceive().getOrNull(), "nothing was sent")
    }

    @Test
    fun `cancel with a grant sends session cancel for the subagent's own id`() = runTest {
        val child = child()
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)

        child.cancel()

        val frame = withContext(Dispatchers.Default) { withTimeout(5.seconds) { transport.frames.receive() } }
        val notification = (frame as TransportFrame.Single).message as JsonRpcNotification
        assertEquals(AcpMethod.AgentMethods.V1.SessionCancel.methodName, notification.method)
        val sent = ACPJson.decodeFromJsonElement(CancelNotification.serializer(), notification.params!!)
        assertEquals(CancelNotification(SessionId("child")), sent)
    }

    @Test
    fun `cancel answers the pending permission requests and blocks later ones while work is confirmed`() = runTest {
        val operations = WaitingChildOperations()
        val (holder, child) = openChild(operations)
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
        holder.permissions.onState(StateUpdate.Running(), live = true)
        val pending = backgroundScope.async { holder.answerPermissionRequest(toolCall("pending"), emptyList(), null) }
        operations.started.receive()

        child.cancel()

        assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), pending.await())
        val later = holder.answerPermissionRequest(toolCall("later"), emptyList(), null)
        assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), later)
        assertEquals(listOf(toolCall("pending")), operations.requests, "the later request never reached the app")
    }

    @Test
    fun `cancel answers the pending permission requests even if sending session cancel fails`() = runTest {
        val operations = WaitingChildOperations()
        val (holder, child) = openChild(operations)
        child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
        val pending = backgroundScope.async { holder.answerPermissionRequest(toolCall("pending"), emptyList(), null) }
        operations.started.receive()
        transport.sendFailure = IllegalStateException("writer closed")

        val failure = assertFailsWith<IllegalStateException> { child.cancel() }

        assertEquals("writer closed", failure.message)
        assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), pending.await())
    }

    @Test
    fun `cancel that throws for a missing grant or a closed session leaves the permission requests alone`() = runTest {
        for (closed in listOf(false, true)) {
            val operations = WaitingChildOperations()
            val (holder, child) = openChild(operations)
            holder.permissions.onState(StateUpdate.Running(), live = true)
            if (closed) {
                child.applyPatch(patch(capabilities = cancelGrant), streamOpened = true)
                child.markClosed()
            }
            val pending = backgroundScope.async { holder.answerPermissionRequest(toolCall("pending"), emptyList(), null) }
            operations.started.receive()

            assertFailsWith<IllegalStateException> { child.cancel() }

            assertFalse(pending.isCompleted, "the pending request still waits for the user, closed = $closed")
            val later = backgroundScope.async { holder.answerPermissionRequest(toolCall("later"), emptyList(), null) }
            operations.started.receive()
            operations.answers.send(ALLOWED)
            operations.answers.send(ALLOWED)
            assertEquals(ALLOWED, pending.await())
            assertEquals(ALLOWED, later.await(), "a later request reached the app, closed = $closed")
        }
        assertNull(transport.frames.tryReceive().getOrNull(), "nothing was sent")
    }

    @Test
    fun `the subagents of a subagent are the ones listed under its holder`() {
        val (parentHolder, parent) = setUpChild(id = "parent")
        val grandchild = child(id = "grandchild", parent = parentHolder)
        assertTrue(parent.subagents.isEmpty())

        assertTrue(parentHolder.listSubagent(grandchild))

        assertEquals(listOf(grandchild), parent.subagents)
    }

    @Test
    fun `a root session has no subagents and its directories come from its parameters`() {
        val holder = rootHolder()
        val parameters = SessionCreationParameters(cwd = "/project", mcpServers = emptyList(), additionalDirectories = listOf("/lib"))
        assertTrue(holder.claimForOpening(replayRequested = false, parameters))
        val root = rootSession(holder, parameters)

        assertTrue(root.subagents.isEmpty())
        assertEquals("/project", root.cwd)
        assertEquals(listOf("/lib"), root.additionalDirectories)
        assertSame(parameters, holder.phase.opening?.parameters, "a child can read the parameters before the root session exists")
    }

    @Test
    fun `a root session opened without additional directories reports an empty list`() {
        val holder = rootHolder()
        val parameters = SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
        assertTrue(holder.claimForOpening(replayRequested = false, parameters))

        assertEquals(emptyList(), rootSession(holder, parameters).additionalDirectories)
    }

    @Test
    fun `the root's subagents are the holder's list in announcement order and an earlier read stays unchanged`() {
        val holder = rootHolder()
        val parameters = SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
        assertTrue(holder.claimForOpening(replayRequested = false, parameters))
        val first = child(id = "first", parent = holder)
        val second = child(id = "second", parent = holder)

        assertTrue(holder.listSubagent(first))
        val root = rootSession(holder, parameters)
        val beforeSecond = root.subagents
        assertTrue(holder.listSubagent(second))

        assertEquals(listOf(first), beforeSecond)
        assertEquals(listOf(first, second), root.subagents)
    }

    @Test
    fun `a sealed list keeps its subagents and refuses later listings`() {
        val holder = rootHolder()
        val first = child(id = "first", parent = holder)
        assertTrue(holder.listSubagent(first))

        holder.sealSubagents()

        assertFalse(holder.listSubagent(child(id = "late", parent = holder)))
        assertEquals(listOf(first), holder.subagents)
        holder.sealSubagents()
        assertEquals(listOf(first), holder.subagents, "a second seal keeps the list")
    }

    @Test
    fun `a subagent's updates go to its operations' notify and its permission requests to its operations`() = runTest {
        val operations = RecordingChildOperations()
        val (holder, child) = openChild(operations)
        val update = SessionUpdate.CurrentModeUpdate(SessionModeId("mode"))
        val toolCall = SessionUpdate.ToolCallUpdate(ToolCallId("tool"))

        child.deliverUpdate(update, null)
        val response = holder.answerPermissionRequest(toolCall, emptyList(), null)

        assertNull(assertIs<SessionEvent.Opened>(operations.events.first()).response)
        val event = assertIs<SessionEvent.Update>(operations.events.last())
        assertEquals(2, operations.events.size)
        assertSame(update, event.update)
        assertEquals(listOf(toolCall), operations.permissionRequests)
        assertEquals(RequestPermissionResponse(RequestPermissionOutcome.Cancelled), response)
    }

    @Test
    fun `a subagent's calls run with the subagent in the context and clientSession refuses it`() = runTest {
        val child = child()

        child.executeWithSession {
            assertSame(child, currentCoroutineContext().baseClientSession)
            assertFailsWith<IllegalStateException> { currentCoroutineContext().clientSession }
        }
    }

    @Test
    fun `a root's calls run with the root in the context for both accessors`() = runTest {
        val holder = rootHolder()
        val parameters = SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
        val root = rootSession(holder, parameters)

        root.executeWithSession {
            assertSame(root, currentCoroutineContext().baseClientSession)
            assertSame(root, currentCoroutineContext().clientSession)
        }
        assertFailsWith<IllegalStateException> { currentCoroutineContext().baseClientSession }
    }

    @Test
    fun `clientSubagentSession returns the subagent in a subagent's calls`() = runTest {
        val child = child()

        child.executeWithSession {
            assertSame(child, currentCoroutineContext().clientSubagentSession)
        }
    }

    @Test
    fun `clientSubagentSession refuses a root's calls`() = runTest {
        val holder = rootHolder()
        val parameters = SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
        val root = rootSession(holder, parameters)

        root.executeWithSession {
            assertFailsWith<IllegalStateException> { currentCoroutineContext().clientSubagentSession }
        }
    }

    @Test
    fun `clientSubagentSession refuses a context without a session`() = runTest {
        assertFailsWith<IllegalStateException> { currentCoroutineContext().clientSubagentSession }
    }

    /**
     * A subagent's holder that has completed with a session over [operations], as after the root's opening: the
     * holder's permissions are the session's.
     */
    private suspend fun openChild(operations: ClientSessionOperations): Child {
        val root = claimedRoot()
        val child = setUpChild(parent = root, operations = operations)

        val rootSession = rootSession(root, rootParameters)
        assertTrue(root.markOpened(NewSessionResponse(SessionId("root"))))
        root.completeOpening(rootSession, root.drainHistory(rootSession))
        child.holder.markOpened(applyHeld = true)
        child.holder.session.await()
        return child
    }

    private data class Child(val holder: SubagentSessionHolder, val session: ClientSubagentSession)

    private fun rootHolder() = RootSessionHolder(SessionId("root"), protocol, scope, { _, _ -> true }, { emptyList() })

    private fun rootSession(holder: RootSessionHolder, parameters: SessionCreationParameters) = ClientSessionImpl(
        client = Client(protocol),
        sessionId = SessionId("root"),
        parameters = parameters,
        operations = NoOperations,
        createdResponse = NewSessionResponse(SessionId("root")),
        protocol = protocol,
        isOpen = { true },
        markClosing = {},
        onClosed = {},
        holder = holder,
    )
}
