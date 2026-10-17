@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.tck.v2

import com.agentclientprotocol.acp.LIB_VERSION
import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.AuthMethodId
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
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val TCK_AUTH_METHOD = AuthMethodId("tck")

private val MODEL_CONFIG_ID = SessionConfigId("model")
private val MODEL_VALUES = listOf(
    SessionConfigSelectOption(SessionConfigValueId("fast"), "Fast"),
    SessionConfigSelectOption(SessionConfigValueId("slow"), "Slow"),
)

private val ALLOW_OPTION = PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.AllowOnce)
private val REJECT_OPTION = PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.RejectOnce)

/*
 * Delay between streamed reply words. Long enough for a multi-sentence prompt to still be running
 * when the TCK's cancellation tests send `session/cancel`, short enough to keep short prompts fast.
 */
private const val WORD_DELAY_MS = 100L

/**
 * Everything kept about a session between requests, so that `session/resume` and `session/list`
 * can find it again.
 */
private class StoredSession(
    val sessionId: SessionId,
    val cwd: String,
    val additionalDirectories: List<String>,
) {
    val turnCounter = AtomicInteger()

    @Volatile
    var model: SessionConfigValueId = MODEL_VALUES.first().value

    /**
     * Completed when `session/cancel` or `session/close` asks the running turn to stop.
     */
    @Volatile
    var cancelSignal: CompletableDeferred<Unit>? = null

    /**
     * Whole-message history replayed by `session/resume` with `replayFrom: start`.
     */
    val history: MutableList<SessionUpdate> = java.util.Collections.synchronizedList(mutableListOf())

    fun cancelTurn() {
        cancelSignal?.complete(Unit)
    }
}

/**
 * A protocol v2 agent exercising every capability that acp-tck checks for protocol v2.
 *
 * It keeps sessions in memory. Each turn asks for permission, reports a tool call and a plan, and
 * streams an echo of the prompt word by word.
 */
class TckAgentSupport : AgentSupport {
    private val sessions = ConcurrentHashMap<SessionId, StoredSession>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        implementation = Implementation("acp-kotlin-sdk-tck-agent-v2", LIB_VERSION),
        capabilities = AgentCapabilities(
            session = SessionCapabilities(
                prompt = PromptCapabilities(
                    image = PromptImageCapabilities(),
                    audio = PromptAudioCapabilities(),
                    embeddedContext = PromptEmbeddedContextCapabilities(),
                ),
                mcp = McpCapabilities(stdio = McpStdioCapabilities(), http = McpHttpCapabilities()),
                delete = SessionDeleteCapabilities(),
                additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
            ),
        ),
        authMethods = listOf(AuthMethod.Agent(TCK_AUTH_METHOD, "TCK")),
    )

    override suspend fun login(methodId: AuthMethodId, _meta: JsonElement?): LoginAuthResponse {
        if (methodId != TCK_AUTH_METHOD) jsonRpcInvalidParams("Unknown authentication method: $methodId")
        return LoginAuthResponse()
    }

    override suspend fun logout(_meta: JsonElement?): LogoutAuthResponse = LogoutAuthResponse()

    override suspend fun createSession(
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession = TckAgentSession(store(SessionId("session-${UUID.randomUUID()}"), parameters), client)

    override suspend fun resumeSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replayFrom: ReplayFrom?,
        client: ClientOperations,
    ): AgentSession {
        val stored = sessions[sessionId] ?: store(sessionId, parameters)
        // Replay belongs to the resume call itself: it has to reach the client before the response.
        if (replayFrom is ReplayFrom.Start) {
            stored.history.toList().forEach { client.notify(it) }
        }
        return TckAgentSession(stored, client)
    }

    override suspend fun listSessions(cwd: String?, cursor: String?, _meta: JsonElement?): ListSessionsResponse =
        ListSessionsResponse(
            sessions = sessions.values
                .filter { cwd == null || it.cwd == cwd }
                .map { SessionInfo(it.sessionId, it.cwd, it.additionalDirectories) }
        )

    override suspend fun closeSession(sessionId: SessionId, _meta: JsonElement?): CloseSessionResponse {
        sessions[sessionId]?.cancelTurn()
        return CloseSessionResponse()
    }

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        sessions.remove(sessionId)?.cancelTurn()
        return DeleteSessionResponse()
    }

    private fun store(sessionId: SessionId, parameters: SessionCreationParameters): StoredSession {
        val stored = StoredSession(sessionId, parameters.cwd, parameters.additionalDirectories)
        sessions[sessionId] = stored
        return stored
    }
}

private class TckAgentSession(
    private val stored: StoredSession,
    private val client: ClientOperations,
) : AgentSession {
    override val sessionId: SessionId
        get() = stored.sessionId

    override val configOptions: List<SessionConfigOption>
        get() = listOf(
            SessionConfigOption(
                configId = MODEL_CONFIG_ID,
                name = "Model",
                kind = SessionConfigKind.Select(
                    currentValue = stored.model,
                    options = SessionConfigSelectOptions.Ungrouped(MODEL_VALUES),
                ),
            )
        )

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?,
    ): List<SessionConfigOption> {
        if (configId != MODEL_CONFIG_ID) jsonRpcInvalidParams("Unknown config option: $configId")
        val selected = (value as? SessionConfigOptionValue.Id)?.value
            ?: jsonRpcInvalidParams("Config option $configId requires an id value")
        stored.model = MODEL_VALUES.firstOrNull { it.value == selected }?.value
            ?: jsonRpcInvalidParams("Unknown value for $configId: $selected")
        return configOptions
    }

    override suspend fun cancel() {
        stored.cancelTurn()
    }

    override fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<SessionUpdate> = flow {
        val cancelSignal = CompletableDeferred<Unit>()
        stored.cancelSignal = cancelSignal

        val turn = stored.turnCounter.incrementAndGet()
        val userMessage = SessionUpdate.UserMessage(
            UserMessage(MessageId("$sessionId-user-$turn"), MaybeUndefined.Value(content))
        )
        stored.history += userMessage
        emit(userMessage)
        emit(SessionUpdate.StateUpdate(StateUpdate.Running()))

        val planId = PlanId("$sessionId-plan-$turn")
        emitPlan(planId, PlanEntryStatus.InProgress)

        val toolCallId = ToolCallId("$sessionId-tool-$turn")
        emit(
            SessionUpdate.ToolCallUpdate(
                ToolCallUpdate(
                    toolCallId = toolCallId,
                    title = MaybeUndefined.Value("Echo the prompt"),
                    kind = MaybeUndefined.Value(ToolKind.Other),
                    status = MaybeUndefined.Value(ToolCallStatus.Pending),
                )
            )
        )

        emit(SessionUpdate.StateUpdate(StateUpdate.RequiresAction()))
        val permission = client.requestPermission(
            title = "Echo the prompt?",
            options = listOf(ALLOW_OPTION, REJECT_OPTION),
            subject = RequestPermissionSubject.ToolCall(ToolCallUpdate(toolCallId)),
        )
        if (cancelSignal.isCompleted || permission.outcome is RequestPermissionOutcome.Cancelled) {
            emitCancelled(toolCallId)
            return@flow
        }
        emit(SessionUpdate.StateUpdate(StateUpdate.Running()))

        val messageId = MessageId("$sessionId-agent-$turn")
        val words = listOf("Echo:") + content.joinToString(" ") { it.describe() }.split(' ').filter { it.isNotBlank() }
        val reply = StringBuilder()
        for ((index, word) in words.withIndex()) {
            if (withTimeoutOrNull(WORD_DELAY_MS) { cancelSignal.await() } != null) {
                emitCancelled(toolCallId)
                return@flow
            }
            val text = if (index == 0) word else " $word"
            reply.append(text)
            emit(SessionUpdate.AgentMessageChunk(ContentChunk(messageId, ContentBlock.Text(text))))
        }
        stored.history += SessionUpdate.AgentMessage(
            AgentMessage(messageId, MaybeUndefined.Value(listOf(ContentBlock.Text(reply.toString()))))
        )

        emit(
            SessionUpdate.ToolCallUpdate(
                ToolCallUpdate(
                    toolCallId = toolCallId,
                    status = MaybeUndefined.Value(ToolCallStatus.Completed),
                    content = MaybeUndefined.Value(
                        listOf(ToolCallContent.Content(ContentBlock.Text(reply.toString())))
                    ),
                )
            )
        )
        emitPlan(planId, PlanEntryStatus.Completed)
        emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
    }

    private suspend fun FlowCollector<SessionUpdate>.emitPlan(planId: PlanId, status: PlanEntryStatus) {
        emit(
            SessionUpdate.PlanUpdate(
                PlanUpdate(PlanUpdateContent.Items(planId, listOf(PlanEntry("Echo the prompt", PlanEntryPriority.High, status))))
            )
        )
    }

    private suspend fun FlowCollector<SessionUpdate>.emitCancelled(toolCallId: ToolCallId) {
        emit(
            SessionUpdate.ToolCallUpdate(
                ToolCallUpdate(toolCallId = toolCallId, status = MaybeUndefined.Value(ToolCallStatus.Cancelled))
            )
        )
        emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.Cancelled)))
    }
}

private fun ContentBlock.describe(): String = when (this) {
    is ContentBlock.Text -> text
    is ContentBlock.Image -> "[image]"
    is ContentBlock.Audio -> "[audio]"
    is ContentBlock.Resource -> "[resource]"
    is ContentBlock.ResourceLink -> "[resource link: $uri]"
    else -> "[content]"
}
