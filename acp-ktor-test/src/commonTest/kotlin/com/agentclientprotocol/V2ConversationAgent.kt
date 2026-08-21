@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.v2.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/** The one config option this agent offers: v2 folded v1's session modes into config options. */
internal val MODE = SessionConfigId("mode")
internal val ASK = SessionConfigValueId("ask")
internal val ARCHITECT = SessionConfigValueId("architect")

/** The choices it offers before it touches a file. */
internal val ALLOW = PermissionOptionId("allow")
internal val REJECT = PermissionOptionId("reject")

/**
 * The agent [V2ConversationTest] talks to: one small assistant that remembers its sessions.
 *
 * It keeps a record per session — the mode chosen on it, and every update it has sent — so a session
 * outlives a `session/close` and comes back on a `session/resume`, which is what makes a conversation
 * rather than a single turn testable.
 *
 * Everything the test wants to know about what reached the agent is exposed as a property here, so the
 * assertions read as statements about the agent instead of reaching into its sessions.
 */
internal class ConversationAgent : AgentSupport {
    /** What `session/new` was asked for, one entry per session created. */
    val newSessionParameters = mutableListOf<SessionCreationParameters>()
    val closedSessions = mutableListOf<SessionId>()
    val deletedSessions = mutableListOf<SessionId>()
    var loggedInWith: AuthMethodId? = null
        private set
    var loggedOut: Boolean = false
        private set

    /** What `session/resume` carried, or `null` while nothing has been resumed. */
    var resume: Resume? = null
        private set

    /** The three fields of a `session/resume` this agent cares about. */
    internal class Resume(val sessionId: SessionId, val cwd: String, val replayFrom: ReplayFrom?)

    /** The `_meta` of the last prompt the open session was given. */
    val lastPromptMeta: JsonElement? get() = openSession.promptMeta

    /** How the user answered the last permission request. */
    val permissionOutcome: RequestPermissionOutcome? get() = openSession.permissionOutcome

    /** What the user did with the last elicitation. */
    val elicitedAction: ElicitationAction? get() = openSession.elicitedAction

    /** Every update the session has sent, which is also what it would replay on a resume. */
    fun historyOf(sessionId: SessionId): List<SessionUpdate> = records.getValue(sessionId).history

    private val records = mutableMapOf<SessionId, SessionRecord>()
    private lateinit var openSession: ConversationSession

    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(
        implementation = Implementation(name = "conversation-agent", version = "1.0.0"),
        capabilities = AgentCapabilities(
            session = SessionCapabilities(
                delete = SessionDeleteCapabilities(),
                additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
            ),
            auth = AgentAuthCapabilities(),
        ),
        authMethods = listOf(AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "Sign in with OAuth")),
    )

    override suspend fun createSession(
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession {
        newSessionParameters += parameters
        val sessionId = SessionId("session-${records.size + 1}")
        records[sessionId] = SessionRecord(
            SessionInfo(
                sessionId = sessionId,
                cwd = parameters.cwd,
                additionalDirectories = parameters.additionalDirectories,
                title = "config.toml",
            )
        )
        return open(sessionId, client)
    }

    override suspend fun resumeSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replayFrom: ReplayFrom?,
        client: ClientOperations,
    ): AgentSession {
        resume = Resume(sessionId, parameters.cwd, replayFrom)
        // The record outlived the close, so the session comes back with the mode that was chosen on it and
        // the history behind it.
        return open(sessionId, client)
    }

    override suspend fun listSessions(cwd: String?, cursor: String?, _meta: JsonElement?) = ListSessionsResponse(
        sessions = records.values.map { it.info }.filter { cwd == null || it.cwd == cwd },
    )

    override suspend fun login(methodId: AuthMethodId, _meta: JsonElement?): LoginAuthResponse {
        loggedInWith = methodId
        return LoginAuthResponse()
    }

    override suspend fun logout(_meta: JsonElement?): LogoutAuthResponse {
        loggedOut = true
        return LogoutAuthResponse()
    }

    override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?): CloseSessionResponse {
        closedSessions += sessionId
        // Closing ends the live session and keeps the record; only a delete drops it.
        return CloseSessionResponse()
    }

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        deletedSessions += sessionId
        records.remove(sessionId)
        return DeleteSessionResponse()
    }

    private fun open(sessionId: SessionId, client: ClientOperations): AgentSession =
        ConversationSession(sessionId, records.getValue(sessionId), client).also { openSession = it }
}

/** What the agent keeps about one session between turns, and across a close and a resume. */
private class SessionRecord(val info: SessionInfo) {
    val history = mutableListOf<SessionUpdate>()
    var mode: SessionConfigValueId = ASK
}

/**
 * One session of the conversation. What a turn does is decided by what the user asked for:
 *
 * | the prompt starts with | the turn                                                    |
 * |------------------------|-------------------------------------------------------------|
 * | `read`                 | runs a tool call, once the user has allowed it               |
 * | `set`                  | edits a file, with the value elicited from the user          |
 * | `tail`                 | starts work that runs until the user cancels the turn        |
 * | anything else          | answers from what the session remembers                     |
 *
 * One implementation for all of them, the way a real agent decides per turn rather than per connection.
 */
private class ConversationSession(
    override val sessionId: SessionId,
    private val record: SessionRecord,
    private val client: ClientOperations,
) : AgentSession {
    var promptMeta: JsonElement? = null
        private set
    var permissionOutcome: RequestPermissionOutcome? = null
        private set
    var elicitedAction: ElicitationAction? = null
        private set

    private val cancelRequested = CompletableDeferred<Unit>()
    private var turns = 0

    override val configOptions: List<SessionConfigOption> get() = listOf(modeOption(record.mode))

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?,
    ): List<SessionConfigOption> {
        record.mode = (value as SessionConfigOptionValue.Id).value
        return configOptions
    }

    override suspend fun cancel() {
        cancelRequested.complete(Unit)
    }

    override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate> = flow {
        promptMeta = _meta
        turns++
        // The lifecycle makes the agent the source of truth for where the user's message landed in history.
        send(SessionUpdate.UserMessage(UserMessage(MessageId("user-$turns"), MaybeUndefined.Value(content))))
        send(running())
        val request = content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        when (request.substringBefore(' ')) {
            "read" -> readFileOnceAllowed()
            "set" -> editFileWithAnElicitedValue()
            "tail" -> workUntilCancelled()
            else -> answerFromHistory()
        }
    }

    private suspend fun FlowCollector<SessionUpdate>.readFileOnceAllowed() {
        val call = ToolCallId("read-config")
        send(toolCall(call, ToolCallStatus.Pending, title = "Read config.toml", kind = ToolKind.Read))
        send(requiresAction())
        val answer = client.requestPermission(
            title = "Read config.toml?",
            description = "the agent wants to read a file in the working directory",
            options = listOf(
                PermissionOption(ALLOW, "Allow", PermissionOptionKind.AllowOnce),
                PermissionOption(REJECT, "Reject", PermissionOptionKind.RejectOnce),
            ),
            // v2 asks about a subject union; v1 had a single flat `toolCall` field.
            subject = RequestPermissionSubject.ToolCall(
                ToolCallUpdate(call, title = MaybeUndefined.Value("Read config.toml"))
            ),
        )
        permissionOutcome = answer.outcome
        if ((answer.outcome as? RequestPermissionOutcome.Selected)?.optionId != ALLOW) {
            // A rejection, a cancel, or an outcome from a newer client this agent has never heard of: none
            // of them may pass as approval, so the file stays unread.
            send(idle(StopReason.Refusal))
            return
        }
        send(running())
        send(toolCall(call, ToolCallStatus.InProgress))
        send(SessionUpdate.ToolCallContentChunk(ToolCallContentChunk(call, toolOutput("port = 8080"))))
        send(toolCall(call, ToolCallStatus.Completed))
        send(agentSays("config.toml sets port 8080."))
        send(idle(StopReason.EndTurn))
    }

    private suspend fun FlowCollector<SessionUpdate>.editFileWithAnElicitedValue() {
        send(requiresAction())
        val answer = client.createElicitation(
            message = "Which port should config.toml use?",
            mode = ElicitationMode.Form(
                scope = ElicitationScope.Session(sessionId),
                requestedSchema = ElicitationSchema(
                    properties = mapOf("port" to ElicitationPropertySchema.IntegerProperty(title = "Port")),
                    required = listOf("port"),
                ),
            ),
        )
        elicitedAction = answer.action
        val port = answer.action.acceptedInteger("port")
        if (port == null) {
            // A decline, a cancel, or an action this agent does not understand is not acceptance.
            send(idle(StopReason.Refusal))
            return
        }
        val call = ToolCallId("edit-config")
        send(running())
        send(toolCall(call, ToolCallStatus.InProgress, title = "Edit config.toml", kind = ToolKind.Edit))
        send(toolCall(call, ToolCallStatus.Completed, content = listOf(toolOutput("port = $port"))))
        send(agentSays("config.toml now sets port $port."))
        send(idle(StopReason.EndTurn))
    }

    private suspend fun FlowCollector<SessionUpdate>.workUntilCancelled() {
        val call = ToolCallId("tail-log")
        send(toolCall(call, ToolCallStatus.InProgress, title = "Tail server.log", kind = ToolKind.Execute))
        cancelRequested.await()
        // `session/cancel` does not cut the turn short: saying how it ended is the agent's job, and the idle
        // update carrying `cancelled` has to be the last one.
        send(toolCall(call, ToolCallStatus.Failed))
        send(idle(StopReason.Cancelled))
    }

    private suspend fun FlowCollector<SessionUpdate>.answerFromHistory() {
        val finished = record.history.count { it is SessionUpdate.StateUpdate && it.state is StateUpdate.Idle }
        send(agentSays("This session has finished $finished turns so far."))
        send(idle(StopReason.EndTurn))
    }

    private fun agentSays(text: String) =
        SessionUpdate.AgentMessageChunk(ContentChunk(MessageId("agent-$turns"), ContentBlock.Text(text)))

    /** Sends one update to the client, and keeps it as part of the session's history. */
    private suspend fun FlowCollector<SessionUpdate>.send(update: SessionUpdate) {
        record.history += update
        emit(update)
    }
}

private fun running() = SessionUpdate.StateUpdate(StateUpdate.Running())

private fun requiresAction() = SessionUpdate.StateUpdate(StateUpdate.RequiresAction())

private fun idle(stopReason: StopReason) = SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = stopReason))

private fun toolCall(
    toolCallId: ToolCallId,
    status: ToolCallStatus,
    title: String? = null,
    kind: ToolKind? = null,
    content: List<ToolCallContent>? = null,
) = SessionUpdate.ToolCallUpdate(
    ToolCallUpdate(
        toolCallId = toolCallId,
        title = title.orUndefined(),
        kind = kind.orUndefined(),
        status = MaybeUndefined.Value(status),
        content = content.orUndefined(),
    )
)

private fun toolOutput(text: String) = ToolCallContent.Content(ContentBlock.Text(text))

/** A field to send, or nothing at all: v2 tells "unchanged" apart from "cleared". */
private fun <T> T?.orUndefined(): MaybeUndefined<T> =
    if (this == null) MaybeUndefined.Undefined else MaybeUndefined.Value(this)

/** The integer the user filled in for [property], or `null` if they did not accept the form. */
private fun ElicitationAction.acceptedInteger(property: String): Long? =
    ((this as? ElicitationAction.Accept)?.content?.get(property) as? ElicitationContentValue.IntegerValue)?.value

private fun modeOption(current: SessionConfigValueId) = SessionConfigOption(
    configId = MODE,
    name = "Mode",
    category = SessionConfigOptionCategory.Mode,
    kind = SessionConfigKind.Select(
        currentValue = current,
        options = SessionConfigSelectOptions.Ungrouped(
            listOf(
                SessionConfigSelectOption(value = ASK, name = "Ask first"),
                SessionConfigSelectOption(value = ARCHITECT, name = "Architect"),
            )
        ),
    ),
)

/** The value the mode option currently stands at, from either side's view of the options. */
internal fun currentMode(options: List<SessionConfigOption>): SessionConfigValueId =
    (options.single { it.configId == MODE }.kind as SessionConfigKind.Select).currentValue
