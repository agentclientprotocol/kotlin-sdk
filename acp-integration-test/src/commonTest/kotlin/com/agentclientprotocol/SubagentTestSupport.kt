@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSubagentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.client.SubagentOperations
import com.agentclientprotocol.client.baseClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionCancelCapabilities
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.SubagentCapabilities
import com.agentclientprotocol.model.SubagentSessionCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/*
 * Shared helpers of the subagent end-to-end tests: a scripted agent whose scenario code runs inside the session's
 * context, and a client that records what its operations observe.
 */

val subagentTimeout = 10.seconds

/**
 * Client capabilities that enable the subagent updates, with the file system access the child request scenarios use.
 */
val subagentClientCapabilities = ClientCapabilities(
    fs = FileSystemCapability(readTextFile = true),
    subagents = SubagentCapabilities(),
)

val cancelGrant: MaybeUndefined<SubagentSessionCapabilities> =
    MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities()))

val running: MaybeUndefined<StateUpdate> = MaybeUndefined.Value(StateUpdate.Running())

fun idle(stopReason: IdleStopReason): MaybeUndefined<StateUpdate> =
    MaybeUndefined.Value(StateUpdate.Idle(stopReason))

fun announcement(
    id: SessionId,
    title: String? = null,
    description: String? = null,
    cwd: String? = null,
    capabilities: MaybeUndefined<SubagentSessionCapabilities> = MaybeUndefined.Undefined,
    state: MaybeUndefined<StateUpdate> = MaybeUndefined.Undefined,
): SessionUpdate.SubagentUpdate = SessionUpdate.SubagentUpdate(
    sessionId = id,
    title = title?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
    description = description?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
    cwd = cwd?.let { MaybeUndefined.Value(it) } ?: MaybeUndefined.Undefined,
    capabilities = capabilities,
    state = state,
)

/**
 * A root session of the scripted agent. Each script runs in the session's context, so
 * `currentCoroutineContext().subagents` and `.client` are the root's.
 *
 * [context] is the session's context as [postInitialize] saw it. It stays usable afterwards, so a test body can act
 * as the agent's app and drive the session's `subagents` and `client` outside any request.
 */
class ScriptedRoot(override val sessionId: SessionId) : AgentSession {
    val context = CompletableDeferred<CoroutineContext>()

    var promptScript: suspend () -> Unit = {}
    var replayScript: suspend () -> Unit = {}
    var postInitializeScript: suspend () -> Unit = {}
    var closeScript: suspend () -> Unit = {}

    override suspend fun postInitialize() {
        context.complete(currentCoroutineContext())
        postInitializeScript()
    }

    override suspend fun replayHistory() = replayScript()

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow { promptScript() }

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        closeScript()
        return CloseSessionResponse()
    }
}

class ScriptedAgentSupport : AgentSupport {
    private var onNew: suspend (SessionCreationParameters) -> AgentSession = { error("session/new is not expected") }
    private var onLoad: suspend (SessionId, SessionCreationParameters) -> AgentSession = { _, _ -> error("session/load is not expected") }
    private var onResume: suspend (SessionId, SessionCreationParameters) -> AgentSession = { _, _ -> error("session/resume is not expected") }

    private val _roots = MutableStateFlow<List<ScriptedRoot>>(emptyList())

    /**
     * Every root the agent created, in creation order.
     */
    val roots: List<ScriptedRoot> get() = _roots.value

    /**
     * Makes `session/new` answer with a root built by [configure].
     */
    fun newRoot(id: SessionId, configure: ScriptedRoot.() -> Unit = {}) {
        onNew = { track(id, configure) }
    }

    /**
     * Makes `session/load` answer with a root built by [configure].
     */
    fun loadRoot(configure: ScriptedRoot.() -> Unit = {}) {
        onLoad = { id, _ -> track(id, configure) }
    }

    /**
     * Makes `session/resume` answer with a root built by [configure].
     */
    fun resumeRoot(configure: ScriptedRoot.() -> Unit = {}) {
        onResume = { id, _ -> track(id, configure) }
    }

    private fun track(id: SessionId, configure: ScriptedRoot.() -> Unit): ScriptedRoot =
        ScriptedRoot(id).apply(configure).also { root -> _roots.update { it + root } }

    /**
     * The context of the latest root, once its `postInitialize` ran.
     */
    suspend fun rootContext(): CoroutineContext = withTimeout(subagentTimeout) { roots.last().context.await() }

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        return AgentInfo(
            clientInfo.protocolVersion,
            AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities(), close = SessionCloseCapabilities()),
            ),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession = onNew(sessionParameters)

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        onLoad(sessionId, sessionParameters)

    override suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        onResume(sessionId, sessionParameters)
}

class ScriptedChild(
    override val events: Flow<SessionUpdate> = emptyFlow(),
    private val onCancel: suspend () -> Unit = {},
) : AgentSubagentSession {
    override suspend fun cancel() = onCancel()
}

/**
 * A view of a [ClientSubagentSession] at one moment.
 */
data class ChildView(
    val sessionId: SessionId,
    val parentSessionId: SessionId,
    val title: String?,
    val description: String?,
    val cwd: String,
    val additionalDirectories: List<String>,
    val canCancel: Boolean,
    val state: StateUpdate?,
)

fun ClientSubagentSession.view() = ChildView(
    sessionId, parentSessionId, title, description, cwd, additionalDirectories, canCancel, state,
)

/**
 * One event that reached an operations object, with what the client runtime showed at that moment.
 *
 * @property children the subagents of the session of the call, `null` where the context has no session, as in the
 * history of an early load
 * @property self this session as a subagent, `null` for a root
 */
class Delivered(val event: SessionEvent, val children: List<ChildView>?, val self: ChildView?) {
    val update: SessionUpdate? get() = (event as? SessionEvent.Update)?.update
}

class PermissionRequest(val sessionInContext: SessionId, val toolCall: SessionUpdate.ToolCallUpdate)

val allowOption = PermissionOption(
    PermissionOptionId("allow"),
    "Allow",
    PermissionOptionKind.ALLOW_ONCE,
)

/**
 * Operations of a session that record every event and request. They know nothing about subagents.
 */
open class RecordingOps(val sessionId: SessionId) : ClientSessionOperations {
    val delivered = MutableStateFlow<List<Delivered>>(emptyList())
    val permissionRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val fileReads = MutableStateFlow<List<Pair<SessionId, String>>>(emptyList())

    var permissionHandler: suspend (PermissionRequest) -> RequestPermissionResponse =
        { RequestPermissionResponse(RequestPermissionOutcome.Selected(allowOption.optionId)) }

    /**
     * What `fsReadTextFile` answers.
     */
    var fileContent: String = "file content of $sessionId"

    val events: List<SessionEvent> get() = delivered.value.map { it.event }

    /**
     * The updates received so far, without the [SessionEvent.Opened] marker.
     */
    val updates: List<SessionUpdate> get() = delivered.value.mapNotNull { it.update }

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val request = PermissionRequest(currentCoroutineContext().baseClientSession.sessionId, toolCall)
        permissionRequests.update { it + request }
        return permissionHandler(request)
    }

    override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?): ReadTextFileResponse {
        fileReads.update { it + (currentCoroutineContext().baseClientSession.sessionId to path) }
        return ReadTextFileResponse(fileContent)
    }

    override suspend fun notify(event: SessionEvent) {
        val session = runCatching { currentCoroutineContext().baseClientSession }.getOrNull()
        val record = Delivered(
            event,
            children = session?.subagents?.map { it.view() },
            self = (session as? ClientSubagentSession)?.view(),
        )
        delivered.update { it + record }
    }

    suspend fun awaitDelivered(predicate: (Delivered) -> Boolean): Delivered =
        withTimeout(subagentTimeout) { delivered.first { list -> list.any(predicate) } }.first(predicate)

    suspend fun awaitUpdate(predicate: (SessionUpdate) -> Boolean): Delivered =
        awaitDelivered { it.update?.let(predicate) == true }

    /**
     * Waits for the [SessionEvent.Opened] marker. A subagent's marker follows the items queued before it, so awaiting
     * one of those items does not mean the marker has been delivered.
     */
    suspend fun awaitOpened(): Delivered = awaitDelivered { it.event is SessionEvent.Opened }

    suspend fun awaitPermissionRequest(): PermissionRequest =
        withTimeout(subagentTimeout) { permissionRequests.first { it.isNotEmpty() } }.first()
}

/**
 * Operations that also build the operations of the subagents, through the shared [SubagentWorld].
 */
class TreeOps(sessionId: SessionId, private val world: SubagentWorld) : RecordingOps(sessionId), SubagentOperations {
    override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations = world.create(sessionId)
}

/**
 * Every operations object a client built, in creation order.
 */
class SubagentWorld {
    private val created = MutableStateFlow<List<TreeOps>>(emptyList())

    val all: List<TreeOps> get() = created.value

    /**
     * Customizes each new operations object before it is used.
     */
    var configure: (TreeOps) -> Unit = {}

    fun create(id: SessionId): TreeOps = TreeOps(id, this).also { ops ->
        configure(ops)
        created.update { it + ops }
    }

    /**
     * The latest operations built for [id].
     */
    operator fun get(id: SessionId): TreeOps = all.last { it.sessionId == id }

    /**
     * Waits until operations for [id] were built: the client builds a child's before the parent's `notify` for its
     * announcement, but a test reads the child's before it has waited for that.
     */
    suspend fun await(id: SessionId): TreeOps = withTimeout(subagentTimeout) { created.first { list -> list.any { it.sessionId == id } } }.last { it.sessionId == id }
}
