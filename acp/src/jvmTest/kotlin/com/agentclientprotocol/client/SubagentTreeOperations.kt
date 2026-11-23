@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.internal.ClientSessionContextElement
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.ElicitationOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * The operations of every session in one test, root or subagent, by session id, and the log of the calls that
 * created them.
 */
internal class SubagentWorld {
    /**
     * Every creation of operations, in call order: `root <id>` for a root, `<parent> -> <child>` for a subagent.
     */
    val log = MutableStateFlow<List<String>>(emptyList())

    private val operations = MutableStateFlow<Map<SessionId, TreeOperations>>(emptyMap())

    /**
     * Customizes each new [TreeOperations] before it is used.
     */
    var configure: (TreeOperations) -> Unit = {}

    /**
     * Creates the operations of a root session [id].
     */
    fun root(id: SessionId): TreeOperations {
        log.update { it + "root $id" }
        return create(id)
    }

    fun create(id: SessionId): TreeOperations =
        TreeOperations(id, this).also { configure(it) }.also { created -> operations.update { it + (id to created) } }

    fun operationsOrNull(id: SessionId): TreeOperations? = operations.value[id]

    suspend fun awaitOperations(id: SessionId): TreeOperations =
        withTimeout(TIMEOUT) { operations.first { id in it }.getValue(id) }

    companion object {
        val TIMEOUT = 5.seconds
    }
}

/**
 * Operations that record the events of their session, create the operations of its subagents through [world], and
 * answer every request with their own session id and the session id of the call's context.
 */
internal class TreeOperations(
    val id: SessionId,
    private val world: SubagentWorld,
) : ClientSessionOperations, SubagentOperations, FileSystemOperations, TerminalOperations, ElicitationOperations {
    /**
     * Every event in delivery order: the update, or [OPENED] for the marker.
     */
    val events = MutableStateFlow<List<Any>>(emptyList())

    val permissionRequests = MutableStateFlow<List<SessionUpdate.ToolCallUpdate>>(emptyList())

    /**
     * Runs inside `notify` before the event is recorded, with the session of the call's context, if any.
     */
    var onNotify: suspend (SessionEvent, BaseClientSession?) -> Unit = { _, _ -> }

    /**
     * Creates the operations of the subagent; runs inside [createSubagentOperations] after the call is logged.
     */
    var createChild: suspend (SessionId) -> ClientSessionOperations = { world.create(it) }

    var answerPermission: suspend (SessionUpdate.ToolCallUpdate) -> RequestPermissionResponse = { ALLOWED }

    /**
     * The action of every elicitation this session answers.
     */
    var elicitationAction: ElicitationAction = ElicitationAction.Decline

    /**
     * The ids of the elicitations whose completion reached this session, in arrival order.
     */
    val completedElicitations = MutableStateFlow<List<ElicitationId>>(emptyList())

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequests.update { it + toolCall }
        return answerPermission(toolCall)
    }

    override suspend fun notify(event: SessionEvent) {
        onNotify(event, contextSession())
        events.update {
            it + when (event) {
                is SessionEvent.Update -> event.update
                is SessionEvent.Opened -> OPENED
            }
        }
    }

    override suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations {
        world.log.update { it + "$id -> $sessionId" }
        return createChild(sessionId)
    }

    override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?): ReadTextFileResponse =
        ReadTextFileResponse(answer())

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse = CreateTerminalResponse(answer())

    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse =
        CreateElicitationResponse(elicitationAction, _meta = JsonPrimitive(answer()))

    override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
        completedElicitations.update { it + notification.elicitationId }
    }

    private suspend fun answer() = "$id in ${contextSession()?.sessionId}"

    suspend fun awaitEvents(count: Int): List<Any> = withTimeout(SubagentWorld.TIMEOUT) { events.first { it.size >= count } }

    suspend fun awaitEvent(event: Any) {
        withTimeout(SubagentWorld.TIMEOUT) { events.first { event in it } }
    }

    suspend fun awaitPermissionRequests(count: Int) {
        withTimeout(SubagentWorld.TIMEOUT) { permissionRequests.first { it.size >= count } }
    }

    companion object {
        const val OPENED = "Opened"
        val ALLOWED = RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))

        private suspend fun contextSession(): BaseClientSession? =
            currentCoroutineContext()[ClientSessionContextElement.Key]?.session
    }
}

/**
 * Operations that implement no mixin, not even [SubagentOperations].
 */
internal class PlainOperations : ClientSessionOperations {
    val events = MutableStateFlow<List<Any>>(emptyList())

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = TreeOperations.ALLOWED

    override suspend fun notify(event: SessionEvent) {
        events.update {
            it + when (event) {
                is SessionEvent.Update -> event.update
                is SessionEvent.Opened -> TreeOperations.OPENED
            }
        }
    }

    suspend fun awaitEvents(count: Int): List<Any> = withTimeout(SubagentWorld.TIMEOUT) { events.first { it.size >= count } }
}
