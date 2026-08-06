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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
            ConversationScenario(this, clientProtocol, agentProtocol).run {
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

private class ConversationScenario(
    private val scope: CoroutineScope,
    clientProtocol: Protocol,
    agentProtocol: Protocol,
) {
    private val agent = ConversationAgent()
    private val user = User(fillsIn = mapOf("port" to ElicitationContentValue.IntegerValue(9090)))
    private val client: Client
    private lateinit var session: ClientSession
    private lateinit var conversation: Conversation
    private lateinit var resumedConversation: Conversation

    init {
        Agent(agentProtocol, agent)
        client = Client(clientProtocol, elicitation = user)
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
        assertEquals(PROTOCOL_VERSION_V2, client.negotiatedProtocolVersion)
        assertEquals("conversation-agent", agentInfo.implementation.name)
    }

    suspend fun login() {
        // Arrange
        val authMethod = assertNotNull(client.agentInfo.authMethods.singleOrNull()).methodId

        // Act
        client.login(authMethod)

        // Assert
        assertEquals(authMethod, agent.loggedInWith)
    }

    suspend fun newSession() {
        // Arrange
        val docsServer = McpServer.Stdio(name = "docs", command = "/opt/docs-mcp", args = listOf("--stdio"))

        // Act
        session = client.newSession(
            cwd = "/work",
            mcpServers = listOf(docsServer),
            additionalDirectories = listOf("/work/vendor"),
            operations = user,
        )
        conversation = Conversation(scope, session)

        // Assert
        with(assertNotNull(agent.newSessionParameters.singleOrNull())) {
            assertEquals("/work", cwd)
            assertEquals(listOf(docsServer), mcpServers)
            assertEquals(listOf("/work/vendor"), additionalDirectories)
        }
    }

    suspend fun selectArchitectMode() {
        // Arrange
        assertEquals(ASK, currentMode(session.configOptions))

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
        assertEquals(listOf("tool_call:failed", "state:idle(cancelled)"), updatesAfterCancellation.labels)
    }

    suspend fun closeSession() {
        // Act
        session.close()
        conversation.stop()

        // Assert
        assertEquals(listOf(session.sessionId), agent.closedSessions)
        assertNull(client.getSession(session.sessionId), "a closed session is not addressable any more")
    }

    suspend fun resumeSession() {
        // Act
        val resumedSession = client.resumeSession(
            sessionId = session.sessionId,
            cwd = "/work",
            replayFrom = ReplayFrom.Start(),
            operations = user,
        )
        resumedConversation = Conversation(scope, resumedSession)

        // Assert
        with(assertNotNull(agent.resume)) {
            assertEquals(session.sessionId, sessionId)
            assertEquals("/work", cwd)
            assertIs<ReplayFrom.Start>(replayFrom)
        }
        assertEquals(ARCHITECT, currentMode(resumedSession.configOptions))
        assertEquals(3, agent.historyOf(session.sessionId).count { it.label().startsWith("state:idle") })
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
        assertNull(client.getSession(session.sessionId))
        assertTrue(client.listSessions(cwd = "/work").sessions.isEmpty())
    }

    suspend fun logout() {
        // Act
        client.logout()
        resumedConversation.stop()

        // Assert
        assertTrue(agent.loggedOut)
    }
}

/**
 * The person on the client's side of the conversation: allows what the agent asks to do, and fills in the
 * forms it sends.
 *
 * One object for both surfaces because that is how it looks to a user, even though the SDK takes them in
 * two places — permissions per session, elicitations for the whole connection, since a v2 elicitation
 * carries its own scope and may belong to no session at all.
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
 * A session whose updates are already being collected, so a test can read the conversation turn by turn.
 *
 * [ClientSession.updates] is a cold flow over a buffer that may be collected once — collecting it per turn
 * would consume the buffer and drop everything after it — so a multi-turn test has to fan it out itself.
 */
private class Conversation(scope: CoroutineScope, private val session: ClientSession) {
    private val received = Channel<ClientSession.UpdateWithMeta>(Channel.UNLIMITED)
    private val collecting: Job = scope.launch { session.updates.collect { received.send(it) } }

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

    /** Ends the collection, so the test's scope is not held open by it. */
    fun stop() {
        collecting.cancel()
    }

    private suspend fun collectUntil(reached: (String) -> Boolean): Turn = withTimeout(10.seconds) {
        Turn(
            buildList {
                while (true) {
                    val update = received.receive()
                    add(update)
                    if (reached(update.update.label())) break
                }
            }
        )
    }
}

/** One turn as the client saw it. */
private class Turn(private val updates: List<ClientSession.UpdateWithMeta>) {
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
}

/**
 * A short name for one update: `state:idle(end_turn)`, `tool_call:completed`, `agent_message_chunk`.
 *
 * Close to the wire discriminator, with the one field that says what changed, which is what makes a turn
 * assertable as a sequence instead of a dozen fields per update.
 */
private fun SessionUpdate.label(): String = when (this) {
    is SessionUpdate.UserMessage -> "user_message"
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
