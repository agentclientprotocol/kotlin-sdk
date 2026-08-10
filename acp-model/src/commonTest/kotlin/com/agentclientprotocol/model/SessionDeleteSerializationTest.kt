package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionDeleteSerializationTest {

    @Test
    fun `agent capabilities round trip session delete marker with meta`() {
        val payload = """
            {
              "sessionCapabilities": {
                "delete": {
                  "_meta": {"source": "history"}
                }
              }
            }
        """.trimIndent()

        val decoded = ACPJson.decodeFromString(AgentCapabilities.serializer(), payload)
        val roundTripped = ACPJson.decodeFromString(
            AgentCapabilities.serializer(),
            ACPJson.encodeToString(AgentCapabilities.serializer(), decoded),
        )

        val expectedMeta = buildJsonObject { put("source", JsonPrimitive("history")) }
        assertEquals(expectedMeta, assertNotNull(decoded.sessionCapabilities.delete)._meta)
        assertEquals(decoded, roundTripped)
    }

    @Test
    fun `session delete marker remains absent when not advertised`() {
        val decoded = ACPJson.decodeFromString(
            AgentCapabilities.serializer(),
            """{"sessionCapabilities": {}}""",
        )

        assertNull(decoded.sessionCapabilities.delete)
    }

    @Test
    fun `delete session request round trips session id and meta`() {
        val request = DeleteSessionRequest(
            sessionId = SessionId("session-1"),
            _meta = buildJsonObject { put("request", JsonPrimitive(42)) },
        )

        val encoded = ACPJson.encodeToString(DeleteSessionRequest.serializer(), request)

        assertEquals(request, ACPJson.decodeFromString(DeleteSessionRequest.serializer(), encoded))
    }

    @Test
    fun `delete session response serializes as empty object by default`() {
        assertEquals("{}", ACPJson.encodeToString(DeleteSessionResponse.serializer(), DeleteSessionResponse()))
    }

    @Test
    fun `session delete method uses stable wire name`() {
        assertEquals("session/delete", AcpMethod.AgentMethods.SessionDelete.methodName.name)
    }
}