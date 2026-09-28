@file:OptIn(UnstableApi::class)

package com.agentclientprotocol

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.Client
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.client.v2.ClientSessionOperations
import com.agentclientprotocol.client.v2.ElicitationHandler
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * One v2 conversation from end to end, over a real transport and through the SDK on both sides.
 *
 * [V2ClientTest] covers the methods one at a time; this runs them in the order a client would and in a
 * single pass, because what only a whole flow can catch is what each method leaves behind for the next: a
 * mode chosen before the first prompt, a session that outlives a close, a history a resume brings back.
 *
 * The agent is [ConversationAgent]; the person on this side is [User]. A turn is asserted as the sequence
 * of updates it is — see [label].
 */
abstract class V2ConversationTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    @Test
    fun `one conversation runs from initialize through turns and a cancellation to resume list and delete`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            ConversationScenario(clientProtocol, agentProtocol).run {
                initialize()
                login()
                newSession()
                selectArchitectMode()
                readConfig()
                setPort()
                cancelLogTail()
                closeSession()
                resumeSession()
                recapSession()
                listSession()
                deleteSession()
                logout()
            }
        }
}

/** How the resume replays the scenario's three earlier turns, one label per update. */
private val REPLAYED = listOf(
    "user_message", "tool_call:pending", "tool_call:in_progress", "tool_call_content_chunk", "tool_call:completed",
    "agent_message", "agent_message_chunk",
    "user_message", "tool_call:in_progress", "tool_call:completed", "agent_message", "agent_message_chunk",
    "user_message", "tool_call:in_progress", "tool_call:cancelled",
)

private class ConversationScenario(
    clientProtocol: Protocol,
    agentProtocol: Protocol,
) {
    private val agent = ConversationAgent()
    private val user = User(fillsIn = mapOf("port" to ElicitationContentValue.IntegerValue(9090)))
    private val received = SessionUpdates()
    private var configOptions: List<SessionConfigOption> = emptyList()
    private val client: Client
    private lateinit var session: ClientSession
    private lateinit var conversation: Conversation
    private lateinit var resumedConversation: Conversation

    init {
        Agent(agentProtocol, agent)
        client = Client(clientProtocol, elicitation = user, operations = user, onSessionUpdate = received::accept)
    }

    suspend fun initialize() {
        // Arrange
        val clientInfo = ClientInfo(
            protocolVersion = PROTOCOL_VERSION_V2,
            implementation = Implementation("ide", "2.0.0"),
        )

        // Act
        val agentInfo = client.initialize(clientInfo)

        // Assert
        assertEquals("conversation-agent", agentInfo.implementation.name)
    }

    suspend fun login() {
        // Arrange
        val authMethod = assertNotNull(client.agentInfo.await().authMethods.singleOrNull()).methodId

        // Act
        client.login(authMethod)

        // Assert
        assertEquals(authMethod, agent.loggedInWith)
    }

    suspend fun newSession() {
        // Arrange
        val docsServer = McpServer.Stdio(name = "docs", command = "/opt/docs-mcp", args = listOf("--stdio"))

        // Act
        val response = client.newSession(
            cwd = "/work",
            mcpServers = listOf(docsServer),
            additionalDirectories = listOf("/work/vendor"),
        )
        configOptions = response.configOptions
        session = client.session(response.sessionId)
        conversation = Conversation(session, received)

        // Assert
        with(assertNotNull(agent.newSessionParameters.singleOrNull())) {
            assertEquals("/work", cwd)
            assertEquals(listOf(docsServer), mcpServers)
            assertEquals(listOf("/work/vendor"), additionalDirectories)
        }
    }

    suspend fun selectArchitectMode() {
        // Arrange
        assertEquals(ASK, currentMode(configOptions))

        // Act
        val options = session.setConfigOption(MODE, SessionConfigOptionValue.Id(ARCHITECT))

        // Assert
        assertEquals(ARCHITECT, currentMode(options))
    }

    suspend fun readConfig() {
        // Arrange
        val trace = buildJsonObject { put("traceId", "turn-1") }

        // Act
        val turn = conversation.turn("read config.toml", _meta = trace)

        // Assert
        assertEquals(
            listOf(
                "user_message",
                "state:running",
                "tool_call:pending",
                "state:requires_action",
                "state:running",
                "tool_call:in_progress",
                "tool_call_content_chunk",
                "tool_call:completed",
                "agent_message_chunk",
                "state:idle(end_turn)",
            ),
            turn.labels,
        )
        assertEquals("config.toml sets port 8080.", turn.agentText)
        assertEquals(trace, agent.lastPromptMeta)
        assertEquals(trace, turn.meta)
        with(assertNotNull(user.permissionRequests.singleOrNull())) {
            assertEquals(session.sessionId, sessionId)
            val toolCall = assertIs<RequestPermissionSubject.ToolCall>(assertNotNull(subject)).toolCall
            assertEquals(ToolCallId("read-config"), toolCall.toolCallId)
        }
        assertEquals(RequestPermissionOutcome.Selected(ALLOW), agent.permissionOutcome)
    }

    suspend fun setPort() {
        // Act
        val turn = conversation.turn("set the port")

        // Assert
        assertEquals(
            listOf(
                "user_message",
                "state:running",
                "state:requires_action",
                "state:running",
                "tool_call:in_progress",
                "tool_call:completed",
                "agent_message_chunk",
                "state:idle(end_turn)",
            ),
            turn.labels,
        )
        assertEquals("config.toml now sets port 9090.", turn.agentText)
        val form = assertIs<ElicitationMode.Form>(assertNotNull(user.elicitations.singleOrNull()).mode)
        assertEquals(ElicitationScope.Session(session.sessionId), form.scope)
        assertEquals(setOf("port"), form.requestedSchema.properties.keys)
        assertIs<ElicitationAction.Accept>(assertNotNull(agent.elicitedAction))
    }

    suspend fun cancelLogTail() {
        // Arrange
        conversation.prompt("tail server.log")
        val updatesBeforeCancellation = conversation.updatesUntil("tool_call:in_progress")

        // Act
        session.cancel()
        val updatesAfterCancellation = conversation.awaitTurn()

        // Assert
        assertEquals(
            listOf("user_message", "state:running", "tool_call:in_progress"),
            updatesBeforeCancellation.labels,
        )
        assertEquals(listOf("tool_call:cancelled", "state:idle(cancelled)"), updatesAfterCancellation.labels)
    }

    suspend fun closeSession() {
        // Act
        session.close()

        // Assert
        assertEquals(listOf(session.sessionId), agent.closedSessions)
    }

    suspend fun resumeSession() {
        // Act
        val response = client.resumeSession(
            sessionId = session.sessionId,
            cwd = "/work",
            replayFrom = ReplayFrom.Start(),
        )
        resumedConversation = Conversation(session, received)

        // Assert
        with(assertNotNull(agent.resume)) {
            assertEquals(session.sessionId, sessionId)
            assertEquals("/work", cwd)
            assertIs<ReplayFrom.Start>(replayFrom)
        }
        assertEquals(ARCHITECT, currentMode(response.configOptions))
        // The three earlier turns, replayed before the resume answered: messages and tool calls, with each
        // chunked reply cleared first, and none of the turns' state updates.
        val replay = resumedConversation.next(REPLAYED.size)
        assertEquals(REPLAYED, replay.labels)
        assertEquals(
            listOf("read config.toml", "set the port", "tail server.log"),
            replay.userTexts,
        )
    }

    suspend fun recapSession() {
        // Act
        val turn = resumedConversation.turn("what happened?")

        // Assert
        assertEquals(
            listOf("user_message", "state:running", "agent_message_chunk", "state:idle(end_turn)"),
            turn.labels,
        )
        assertEquals("This session has finished 3 turns so far.", turn.agentText)
    }

    suspend fun listSession() {
        // Act
        val listed = client.listSessions(cwd = "/work")

        // Assert
        assertNull(listed.nextCursor, "one page is all this agent has")
        assertEquals(listOf(session.sessionId), listed.sessions.map { it.sessionId })
        assertEquals(listOf("/work/vendor"), listed.sessions.single().additionalDirectories)
    }

    suspend fun deleteSession() {
        // Act
        client.deleteSession(session.sessionId)

        // Assert
        assertEquals(listOf(session.sessionId), agent.deletedSessions)
        assertTrue(client.listSessions(cwd = "/work").sessions.isEmpty())
    }

    suspend fun logout() {
        // Act
        client.logout()

        // Assert
        assertTrue(agent.loggedOut)
    }
}

/**
 * The person on the client's side of the conversation: allows what the agent asks to do, and fills in the
 * forms it sends.
 *
 * Both handlers are installed on the connection before any session is opened.
 */
private class User(private val fillsIn: Map<String, ElicitationContentValue>) :
    ClientSessionOperations, ElicitationHandler {
    val permissionRequests = mutableListOf<RequestPermissionRequest>()
    val elicitations = mutableListOf<CreateElicitationRequest>()

    override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        permissionRequests += request
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(ALLOW))
    }

    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
        elicitations += request
        return CreateElicitationResponse(ElicitationAction.Accept(content = fillsIn))
    }
}

/**
 * Reads the application's queue turn by turn, including history delivered during resume.
 */
private class Conversation(private val session: ClientSession, private val received: SessionUpdates) {
    /** Prompts, and returns the whole turn that follows. */
    suspend fun turn(text: String, _meta: JsonElement? = null): Turn {
        prompt(text, _meta)
        return awaitTurn()
    }

    /** Prompts without waiting for the turn, for the turns that get interrupted halfway. */
    suspend fun prompt(text: String, _meta: JsonElement? = null) {
        session.prompt(listOf(ContentBlock.Text(text)), _meta)
    }

    /** Everything up to and including the idle update that ends the turn. */
    suspend fun awaitTurn(): Turn = collectUntil { it.startsWith("state:idle") }

    /** Everything up to and including the first update with this [label]. */
    suspend fun updatesUntil(label: String): Turn = collectUntil { it == label }

    /** The next [count] updates, for a replay, which is not a turn and has no idle update to end at. */
    suspend fun next(count: Int): Turn = withTimeout(10.seconds) { Turn(List(count) { received.next() }) }

    private suspend fun collectUntil(reached: (String) -> Boolean): Turn = withTimeout(10.seconds) {
        Turn(
            buildList {
                while (true) {
                    val update = received.next()
                    add(update)
                    if (reached(update.update.label())) break
                }
            }
        )
    }
}

/** One turn as the client saw it. */
private class Turn(private val updates: List<UpdateSessionNotification>) {
    /** The updates in order, by name: the sequence is most of what a turn is. */
    val labels: List<String> = updates.map { it.update.label() }

    /** The text of the turn's one agent message chunk. */
    val agentText: String
        get() = updates.mapNotNull { (it.update as? SessionUpdate.AgentMessageChunk)?.chunk?.content }
            .filterIsInstance<ContentBlock.Text>()
            .single()
            .text

    /** The metadata the turn's updates arrived with. */
    val meta: JsonElement? get() = updates.first()._meta

    /** The text of each user message, in order. */
    val userTexts: List<String>
        get() = updates.mapNotNull { (it.update as? SessionUpdate.UserMessage)?.message?.content?.valueOrNull() }
            .map { content -> content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text } }
}

/**
 * A short name for one update: `state:idle(end_turn)`, `tool_call:completed`, `agent_message_chunk`.
 *
 * Close to the wire discriminator, with the one field that says what changed, which is what makes a turn
 * assertable as a sequence instead of a dozen fields per update.
 */
private fun SessionUpdate.label(): String = when (this) {
    is SessionUpdate.UserMessage -> "user_message"
    is SessionUpdate.AgentMessage -> "agent_message"
    is SessionUpdate.AgentMessageChunk -> "agent_message_chunk"
    is SessionUpdate.ToolCallContentChunk -> "tool_call_content_chunk"
    is SessionUpdate.ToolCallUpdate -> "tool_call:${update.status.valueOrNull()?.value ?: "unchanged"}"
    is SessionUpdate.StateUpdate -> when (val state = state) {
        is StateUpdate.Running -> "state:running"
        is StateUpdate.RequiresAction -> "state:requires_action"
        is StateUpdate.Idle -> "state:idle(${state.stopReason?.value ?: "none"})"
        is StateUpdate.Unknown -> "state:${state.state}"
    }
    else -> this::class.simpleName ?: "unknown"
}
