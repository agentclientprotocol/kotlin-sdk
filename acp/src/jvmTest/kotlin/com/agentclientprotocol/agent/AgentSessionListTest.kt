package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ListSessionsRequest
import com.agentclientprotocol.model.ListSessionsResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class AgentSessionListTest {

    private fun testSessionInfo(id: String) = SessionInfo(sessionId = SessionId(id), cwd = "/tmp", title = id, updatedAt = "now")

    private class StubAgentSupport(
        private val pageHandler: (suspend (cursor: String?) -> Pair<List<SessionInfo>, String?>?)? = null,
        private val sequenceItems: List<SessionInfo> = emptyList(),
    ) : AgentSupport {
        override suspend fun initialize(clientInfo: ClientInfo) = AgentInfo()
        override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
            error("Not needed for session/list tests")

        override suspend fun listSessions(cwd: String?, additionalDirectories: List<String>?, _meta: JsonElement?): Sequence<SessionInfo> {
            if (pageHandler != null) fail("listSessions should not be called when listSessionsPage is overridden")
            return sequenceItems.asSequence()
        }

        override suspend fun listSessionsPage(
            cwd: String?,
            additionalDirectories: List<String>?,
            cursor: String?,
            _meta: JsonElement?,
        ): Pair<List<SessionInfo>, String?>? = pageHandler?.invoke(cursor)
    }

    private suspend fun TestTransport.testSessionList(request: ListSessionsRequest): ListSessionsResponse {
        val received = fireTestRequest(
            AcpMethod.AgentMethods.SessionList.methodName,
            ACPJson.encodeToJsonElement(AcpMethod.AgentMethods.SessionList.requestSerializer, request),
        )
        val response = received.last() as JsonRpcResponse
        return ACPJson.decodeFromJsonElement(AcpMethod.AgentMethods.SessionList.responseSerializer, requireNotNull(response.result))
    }

    @Test
    fun `session list uses listSessionsPage when the agent overrides it`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        val agentSupport = StubAgentSupport(pageHandler = { cursor ->
            assertEquals("db-keyset-position-42", cursor)
            listOf(testSessionInfo("a"), testSessionInfo("b")) to "next-page"
        })
        Agent(protocol, agentSupport)
        protocol.start()

        val response = transport.testSessionList(ListSessionsRequest(cursor = "db-keyset-position-42"))

        assertEquals(listOf(SessionId("a"), SessionId("b")), response.sessions.map { it.sessionId })
        assertEquals("next-page", response.nextCursor)
        protocol.close()
    }

    @Test
    fun `session list falls back to listSessions when listSessionsPage is not overridden`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        val agentSupport = StubAgentSupport(sequenceItems = listOf(testSessionInfo("a"), testSessionInfo("b")))
        Agent(protocol, agentSupport)
        protocol.start()

        val response = transport.testSessionList(ListSessionsRequest())

        assertEquals(listOf(SessionId("a"), SessionId("b")), response.sessions.map { it.sessionId })
        assertNull(response.nextCursor)
        protocol.close()
    }
}
