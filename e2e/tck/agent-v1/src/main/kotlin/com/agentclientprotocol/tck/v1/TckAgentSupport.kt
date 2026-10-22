@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.tck.v1

import com.agentclientprotocol.acp.LIB_VERSION
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val TCK_AUTH_METHOD = AuthMethodId("tck")

private val MODES = listOf(
    SessionMode(SessionModeId("default"), "Default"),
    SessionMode(SessionModeId("yolo"), "YOLO"),
)

private val MODEL_CONFIG_ID = SessionConfigId("model")
private val MODEL_VALUES = listOf(
    SessionConfigSelectOption(SessionConfigValueId("fast"), "Fast"),
    SessionConfigSelectOption(SessionConfigValueId("slow"), "Slow"),
)

/*
 * Delay between streamed reply words. Long enough for a multi-sentence prompt to still be running
 * when the TCK's cancellation tests send `session/cancel`, short enough to keep short prompts fast.
 */
private const val WORD_DELAY_MS = 100L

/**
 * Everything kept about a session between requests, so that `session/load`,
 * `session/resume`, and `session/list` can find it again.
 */
private class StoredSession(
    val sessionId: SessionId,
    val cwd: String,
    val additionalDirectories: List<String>,
) {
    @Volatile
    var modeId: SessionModeId = MODES.first().id

    @Volatile
    var model: SessionConfigValueId = MODEL_VALUES.first().value

    /**
     * The job running the current prompt turn, cancelled by `session/close`.
     */
    @Volatile
    var activeTurn: Job? = null

    /**
     * Conversation history replayed by `session/load`.
     */
    val history: MutableList<SessionUpdate> = java.util.Collections.synchronizedList(mutableListOf())
}

/**
 * A stable ACP agent exercising every capability that acp-tck checks for protocol v1.
 *
 * It keeps sessions in memory and answers each prompt by streaming an echo of it word by word.
 */
class TckAgentSupport : AgentSupport {
    private val sessions = ConcurrentHashMap<SessionId, StoredSession>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        capabilities = AgentCapabilities(
            loadSession = true,
            promptCapabilities = PromptCapabilities(audio = true, image = true, embeddedContext = true),
            mcpCapabilities = McpCapabilities(http = true, sse = true),
            sessionCapabilities = SessionCapabilities(
                list = SessionListCapabilities(),
                resume = SessionResumeCapabilities(),
                delete = SessionDeleteCapabilities(),
                close = SessionCloseCapabilities(),
                additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
            ),
            auth = AgentAuthCapabilities(logout = LogoutCapabilities()),
        ),
        authMethods = listOf(AuthMethod.AgentAuth(TCK_AUTH_METHOD, "TCK")),
        implementation = Implementation("acp-kotlin-sdk-tck-agent-v1", LIB_VERSION),
    )

    override suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse {
        if (methodId != TCK_AUTH_METHOD) jsonRpcInvalidParams("Unknown authentication method: $methodId")
        return AuthenticateResponse()
    }

    override suspend fun logout(_meta: JsonElement?): LogoutResponse = LogoutResponse()

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val stored = store(SessionId("session-${UUID.randomUUID()}"), sessionParameters)
        return TckAgentSession(stored)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
    ): AgentSession = TckAgentSession(sessions[sessionId] ?: store(sessionId, sessionParameters))

    override suspend fun resumeSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
    ): AgentSession = TckAgentSession(sessions[sessionId] ?: store(sessionId, sessionParameters))

    override suspend fun listSessions(
        cwd: String?,
        additionalDirectories: List<String>?,
        _meta: JsonElement?,
    ): Sequence<SessionInfo> = sessions.values
        .filter { cwd == null || it.cwd == cwd }
        .map { SessionInfo(it.sessionId, it.cwd, additionalDirectories = it.additionalDirectories) }
        .asSequence()

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        sessions.remove(sessionId)
        return DeleteSessionResponse()
    }

    private fun store(sessionId: SessionId, parameters: SessionCreationParameters): StoredSession {
        val stored = StoredSession(sessionId, parameters.cwd, parameters.additionalDirectories.orEmpty())
        sessions[sessionId] = stored
        return stored
    }
}

private class TckAgentSession(
    private val stored: StoredSession,
) : AgentSession {
    override val sessionId: SessionId
        get() = stored.sessionId

    override val availableModes: List<SessionMode>
        get() = MODES

    override val defaultMode: SessionModeId
        get() = stored.modeId

    override val configOptions: List<SessionConfigOption>
        get() = listOf(
            SessionConfigOption.select(
                id = MODEL_CONFIG_ID.value,
                name = "Model",
                currentValue = stored.model.value,
                options = SessionConfigSelectOptions.Flat(MODEL_VALUES),
            )
        )

    override suspend fun replayHistory() {
        val client = currentCoroutineContext().client
        for (update in stored.history.toList()) {
            client.notify(update)
        }
    }

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        stored.activeTurn = currentCoroutineContext().job
        content.forEach { stored.history += SessionUpdate.UserMessageChunk(it) }

        val words = listOf("Echo:") + content.joinToString(" ") { it.describe() }.split(' ').filter { it.isNotBlank() }
        for ((index, word) in words.withIndex()) {
            delay(WORD_DELAY_MS)
            val chunk = SessionUpdate.AgentMessageChunk(ContentBlock.Text(if (index == 0) word else " $word"))
            stored.history += chunk
            emit(Event.SessionUpdateEvent(chunk))
        }

        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }

    override suspend fun cancel() {
        logger.info { "Cancellation requested for session $sessionId" }
    }

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        // Closing must stop the running turn as `session/cancel` would; the SDK then reports it cancelled.
        stored.activeTurn?.cancel()
        return CloseSessionResponse()
    }

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        if (MODES.none { it.id == modeId }) jsonRpcInvalidParams("Unknown mode: $modeId")
        stored.modeId = modeId
        currentCoroutineContext().client.notify(SessionUpdate.CurrentModeUpdate(modeId))
        return SetSessionModeResponse()
    }

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?,
    ): SetSessionConfigOptionResponse {
        if (configId != MODEL_CONFIG_ID) jsonRpcInvalidParams("Unknown config option: $configId")
        val selected = (value as? SessionConfigOptionValue.StringValue)?.value
            ?: jsonRpcInvalidParams("Config option $configId requires a string value")
        val model = MODEL_VALUES.firstOrNull { it.value.value == selected }?.value
            ?: jsonRpcInvalidParams("Unknown value for $configId: $selected")
        stored.model = model
        return SetSessionConfigOptionResponse(configOptions)
    }
}

private fun ContentBlock.describe(): String = when (this) {
    is ContentBlock.Text -> text
    is ContentBlock.Image -> "[image]"
    is ContentBlock.Audio -> "[audio]"
    is ContentBlock.Resource -> "[resource]"
    is ContentBlock.ResourceLink -> "[resource link: $uri]"
}
