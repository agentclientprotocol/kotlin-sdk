package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(UnstableApi::class)
class ResumeSessionSerializationTest {

    @Test
    fun `decodes ResumeSessionRequest without mcpServers as an empty list`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project"
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(ResumeSessionRequest.serializer(), payload)

        assertEquals(SessionId("sess-1"), request.sessionId)
        assertEquals("/project", request.cwd)
        assertEquals(emptyList(), request.mcpServers)
    }

    @Test
    fun `encodes ResumeSessionRequest with an empty mcpServers list`() {
        val request = ResumeSessionRequest(SessionId("sess-1"), "/project")

        val json = ACPJson.encodeToJsonElement(ResumeSessionRequest.serializer(), request).jsonObject

        assertEquals(JsonArray(emptyList()), json["mcpServers"])
    }
}
