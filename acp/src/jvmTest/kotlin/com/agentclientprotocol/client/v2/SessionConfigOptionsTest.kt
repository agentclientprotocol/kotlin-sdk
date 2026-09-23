@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.client.v2

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.agent.v2.AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.ConfigOptionUpdate
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ListSessionsResponse
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.SessionConfigKind
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionCategory
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionConfigSelectOptions
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class SessionConfigOptionsTest {
    @Test
    fun `setter sends configId with a flattened value and replaces the whole option list`() = withConnection {
        val session = client.newSession(".")
        val agentSession = support.sessions.getValue(session.sessionId)
        assertEquals(initialOptions, session.configOptions)
        val meta = json("""{"trace":"setter"}""")
        val changed = listOf(toggle(true), mode("code")) // Changes another option and the preferred order.
        agentSession.onSet = { id, value, receivedMeta ->
            assertEquals(SessionConfigId("mode"), id)
            assertEquals(SessionConfigOptionValue.Id(SessionConfigValueId("code")), value)
            assertEquals(meta, receivedMeta)
            changed
        }

        val options = session.setConfigOption(
            SessionConfigId("mode"), SessionConfigOptionValue.Id(SessionConfigValueId("code")), meta,
        )
        assertEquals(changed, options)
        val sent = clientWire.requests.receiveMatching("session/set_config_option")
        assertEquals(
            json("""{"sessionId":"${session.sessionId.value}","configId":"mode","type":"id","value":"code","_meta":{"trace":"setter"}}"""),
            sent.params,
        )

        agentSession.onSet = { id, value, _ ->
            assertEquals(SessionConfigId("verbose"), id)
            assertEquals(SessionConfigOptionValue.Boolean(false), value)
            emptyList()
        }
        assertEquals(emptyList(), session.setConfigOption(SessionConfigId("verbose"), SessionConfigOptionValue.Boolean(false)))
        assertEquals(emptyList(), agentSession.configOptions)
    }

    @Test
    fun `an idle agent reports a configuration change as a notification`() = withConnection {
        val session = client.newSession(".")
        val received = Channel<ClientSession.UpdateWithMeta>(Channel.UNLIMITED)
        val collector = scope.launch { session.updates.collect { received.send(it) } }
        val meta = json("""{"trace":"notification"}""")
        val payloadMeta = json("""{"source":"agent"}""")
        val changed = listOf(mode("code")) // The missing verbose option must be removed.
        val update = SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(changed, payloadMeta))

        // No prompt is running: a config change does not need a turn to be reported.
        support.sessions.getValue(session.sessionId).publish(update, meta)

        val notification = received.receive()
        assertEquals(update, notification.update)
        assertEquals(meta, notification._meta)
        collector.cancel()
    }

    @Test
    fun `new resume and fork report the options the session currently has`() = withConnection {
        val created = client.newSession(".")
        support.sessions.getValue(created.sessionId).options = listOf(mode("code"))
        val resumed = client.resumeSession(created.sessionId, ".")
        val forked = client.forkSession(resumed.sessionId, ".")
        assertEquals(listOf(mode("code")), resumed.configOptions)
        assertEquals(resumed.configOptions, forked.configOptions)
        assertFalse(resumed.sessionId == forked.sessionId)
    }

    @Test
    fun `a resumed session can replay updates before the resume response`() = withConnection {
        val replayed = SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(listOf(mode("code"))))
        // Replay belongs inside the callback: ACP wants it before `session/resume` answers.
        support.onResume = { it.publish(replayed) }

        val resumed = client.resumeSession(SessionId("from-disk"), ".", replayFrom = ReplayFrom.Start())

        // Buffered while the call was still in flight, and delivered once the session exists.
        val received = Channel<ClientSession.UpdateWithMeta>(Channel.UNLIMITED)
        val collector = scope.launch { resumed.updates.collect { received.send(it) } }
        assertEquals(replayed, received.receive().update)
        assertEquals(listOf(mode("code")), resumed.configOptions)
        collector.cancel()
    }

    @Test
    fun `a rejected or malformed setter response leaves the connection usable`() = withConnection {
        val session = client.newSession(".")
        support.sessions.getValue(session.sessionId).onSet = { _, _, _ -> jsonRpcInvalidParams("Unknown option") }
        assertFailsWith<AcpExpectedError> {
            session.setConfigOption(SessionConfigId("missing"), SessionConfigOptionValue.Boolean(true))
        }

        // The response carries the complete list, so anything else has to fail rather than be guessed at.
        agentProtocol.setRequestHandlerRaw(AcpMethod.AgentMethods.V2.SessionSetConfigOption) {
            json("""{"configOptions":"invalid"}""")
        }
        assertFailsWith<SerializationException> {
            session.setConfigOption(SessionConfigId("verbose"), SessionConfigOptionValue.Boolean(true))
        }
        client.listSessions()
    }

    @Test
    fun `v2 agent refuses the removed set mode method`() = withConnection {
        val session = client.newSession(".")
        val error = assertFailsWith<JsonRpcException> {
            client.protocol.sendRequestRaw(
                MethodName("session/set_mode"),
                json("""{"sessionId":"${session.sessionId.value}","modeId":"code"}"""),
            )
        }
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, error.code)
    }

    @Test
    fun `session notification requires a bound session and reports send failures`() = withConnection {
        val unbound = com.agentclientprotocol.agent.v2.RemoteClientOperations(agentProtocol)
        val update = SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(emptyList()))
        assertFailsWith<IllegalStateException> { unbound.notify(update) }
        val session = client.newSession(".")
        agentWire.failure = IllegalStateException("writer closed")
        assertFailsWith<IllegalStateException> { support.sessions.getValue(session.sessionId).client.notify(update) }
    }
}

private fun json(value: String): JsonElement = ACPJson.parseToJsonElement(value)

private fun mode(value: String) = SessionConfigOption(
    SessionConfigId("mode"), "Mode", category = SessionConfigOptionCategory.Mode,
    kind = SessionConfigKind.Select(
        SessionConfigValueId(value),
        SessionConfigSelectOptions.Ungrouped(listOf("ask", "code").map { SessionConfigSelectOption(SessionConfigValueId(it), it) }),
    ),
)

private fun toggle(value: Boolean) = SessionConfigOption(
    SessionConfigId("verbose"), "Verbose", kind = SessionConfigKind.Boolean(value),
)

private val initialOptions = listOf(mode("ask"), toggle(false))

private class ConfigSession(
    override val sessionId: SessionId,
    val client: ClientOperations,
    initial: List<SessionConfigOption>,
) : AgentSession {
    @Volatile var options: List<SessionConfigOption> = initial
    override val configOptions get() = options
    var onSet: suspend (SessionConfigId, SessionConfigOptionValue, JsonElement?) -> List<SessionConfigOption> =
        { _, _, _ -> configOptions }

    override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = emptyFlow<SessionUpdate>()

    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?,
    ): List<SessionConfigOption> = onSet(configId, value, _meta).also { options = it }

    fun publish(update: SessionUpdate.ConfigOptionUpdate, meta: JsonElement? = null) {
        options = update.update.configOptions
        client.notify(update, meta)
    }
}

private class ConfigSupport : AgentSupport {
    private val ids = AtomicInteger()
    val sessions = ConcurrentHashMap<SessionId, ConfigSession>()
    var onResume: (ConfigSession) -> Unit = {}
    override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo(Implementation("config-agent", "1"))
    override suspend fun createSession(parameters: SessionCreationParameters, client: ClientOperations): AgentSession =
        add(SessionId("session-${ids.incrementAndGet()}"), client, initialOptions)

    override suspend fun resumeSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replayFrom: ReplayFrom?,
        client: ClientOperations,
    ): AgentSession = add(sessionId, client, sessions[sessionId]?.configOptions ?: initialOptions).also(onResume)

    override suspend fun forkSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession = add(SessionId("session-${ids.incrementAndGet()}"), client, sessions.getValue(sessionId).configOptions)

    override suspend fun listSessions(cwd: String?, cursor: String?, _meta: JsonElement?) = ListSessionsResponse(emptyList())

    private fun add(id: SessionId, client: ClientOperations, options: List<SessionConfigOption>) =
        ConfigSession(id, client, options).also { sessions[id] = it }
}

private class ConfigWire : BaseTransport() {
    lateinit var peer: ConfigWire
    var failure: Throwable? = null
    val requests = Channel<JsonRpcRequest>(Channel.UNLIMITED)

    override fun start() {
        _state.value = Transport.State.STARTED
    }

    override fun close() {
        _state.value = Transport.State.CLOSED
        fireClose()
    }

    override fun send(frame: TransportFrame) {
        failure?.let { throw it }
        val message = (frame as TransportFrame.Single).message
        if (message is JsonRpcRequest) requests.trySend(message).getOrThrow()
        peer.fireFrame(frame)
    }
}

private suspend fun Channel<JsonRpcRequest>.receiveMatching(method: String): JsonRpcRequest {
    while (true) {
        val request = receive()
        if (request.method.name == method) return request
    }
}

private class ConfigConnection(val scope: CoroutineScope) {
    val clientWire = ConfigWire()
    val agentWire = ConfigWire()
    val agentProtocol = Protocol(scope, agentWire)
    val client = Client(Protocol(scope, clientWire))
    val support = ConfigSupport()

    init {
        clientWire.peer = agentWire
        agentWire.peer = clientWire
        Agent(agentProtocol, support)
        agentProtocol.start()
        client.protocol.start()
    }
}

private fun withConnection(block: suspend ConfigConnection.() -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob())
    try {
        withTimeout(10.seconds) {
            val connection = ConfigConnection(scope)
            connection.client.initialize(ClientInfo(PROTOCOL_VERSION_V2, implementation = Implementation("config-client", "1")))
            connection.block()
        }
    } finally {
        scope.cancel()
    }
}
