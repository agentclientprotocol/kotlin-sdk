package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class AdditionalDirectoriesSerializationTest {

    // === NewSessionRequest ===

    @Test
    fun `decodes NewSessionRequest with additionalDirectories`() {
        val payload = """
            {
                "cwd": "/home/user/project",
                "mcpServers": [],
                "additionalDirectories": ["/home/user/libs", "/home/user/config"]
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(NewSessionRequest.serializer(), payload)

        assertEquals("/home/user/project", request.cwd)
        assertEquals(listOf("/home/user/libs", "/home/user/config"), request.additionalDirectories)
    }

    @Test
    fun `decodes NewSessionRequest without additionalDirectories defaults to null`() {
        val payload = """
            {
                "cwd": "/home/user/project",
                "mcpServers": []
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(NewSessionRequest.serializer(), payload)

        assertNull(request.additionalDirectories)
    }

    @Test
    fun `round-trip serialization for NewSessionRequest with additionalDirectories`() {
        val original = NewSessionRequest(
            cwd = "/project",
            mcpServers = emptyList(),
            additionalDirectories = listOf("/libs", "/tools")
        )

        val encoded = ACPJson.encodeToString(NewSessionRequest.serializer(), original)
        val decoded = ACPJson.decodeFromString(NewSessionRequest.serializer(), encoded)

        assertEquals(original.cwd, decoded.cwd)
        assertEquals(original.additionalDirectories, decoded.additionalDirectories)
    }

    @Test
    fun `round-trip serialization for NewSessionRequest without additionalDirectories`() {
        val original = NewSessionRequest(
            cwd = "/project",
            mcpServers = emptyList()
        )

        val encoded = ACPJson.encodeToString(NewSessionRequest.serializer(), original)
        val decoded = ACPJson.decodeFromString(NewSessionRequest.serializer(), encoded)

        assertNull(decoded.additionalDirectories)
    }

    @Test
    fun `NewSessionRequest without additionalDirectories does not include field in JSON`() {
        val original = NewSessionRequest(
            cwd = "/project",
            mcpServers = emptyList()
        )

        val encoded = ACPJson.encodeToString(NewSessionRequest.serializer(), original)

        assertTrue(!encoded.contains("additionalDirectories"))
    }

    // === LoadSessionRequest ===

    @Test
    fun `decodes LoadSessionRequest with additionalDirectories`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": [],
                "additionalDirectories": ["/shared"]
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(LoadSessionRequest.serializer(), payload)

        assertEquals("/project", request.cwd)
        assertEquals(listOf("/shared"), request.additionalDirectories)
    }

    @Test
    fun `decodes LoadSessionRequest without additionalDirectories defaults to null`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": []
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(LoadSessionRequest.serializer(), payload)

        assertNull(request.additionalDirectories)
    }

    // === ForkSessionRequest ===

    @Test
    fun `decodes ForkSessionRequest with additionalDirectories`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": [],
                "additionalDirectories": ["/extra1", "/extra2"]
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(ForkSessionRequest.serializer(), payload)

        assertEquals(listOf("/extra1", "/extra2"), request.additionalDirectories)
    }

    @Test
    fun `decodes ForkSessionRequest without additionalDirectories defaults to null`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": []
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(ForkSessionRequest.serializer(), payload)

        assertNull(request.additionalDirectories)
    }

    // === ResumeSessionRequest ===

    @Test
    fun `decodes ResumeSessionRequest with additionalDirectories`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": [],
                "additionalDirectories": ["/workspace/shared"]
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(ResumeSessionRequest.serializer(), payload)

        assertEquals(listOf("/workspace/shared"), request.additionalDirectories)
    }

    @Test
    fun `decodes ResumeSessionRequest without additionalDirectories defaults to null`() {
        val payload = """
            {
                "sessionId": "sess-1",
                "cwd": "/project",
                "mcpServers": []
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(ResumeSessionRequest.serializer(), payload)

        assertNull(request.additionalDirectories)
    }

    // === SessionInfo ===

    @Test
    fun `decodes SessionInfo with additionalDirectories`() {
        val payload = """
            {
                "sessionId": "sess-123",
                "cwd": "/project",
                "title": "My Session",
                "additionalDirectories": ["/shared/libs"]
            }
        """.trimIndent()

        val info = ACPJson.decodeFromString(SessionInfo.serializer(), payload)

        assertEquals("sess-123", info.sessionId.value)
        assertEquals("/project", info.cwd)
        assertEquals("My Session", info.title)
        assertEquals(listOf("/shared/libs"), info.additionalDirectories)
    }

    @Test
    fun `decodes SessionInfo without additionalDirectories defaults to null`() {
        val payload = """
            {
                "sessionId": "sess-123",
                "cwd": "/project"
            }
        """.trimIndent()

        val info = ACPJson.decodeFromString(SessionInfo.serializer(), payload)

        assertNull(info.additionalDirectories)
    }

    @Test
    fun `round-trip serialization for SessionInfo with additionalDirectories`() {
        val original = SessionInfo(
            sessionId = SessionId("sess-rt"),
            cwd = "/project",
            title = "Test",
            additionalDirectories = listOf("/a", "/b", "/c")
        )

        val encoded = ACPJson.encodeToString(SessionInfo.serializer(), original)
        val decoded = ACPJson.decodeFromString(SessionInfo.serializer(), encoded)

        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.cwd, decoded.cwd)
        assertEquals(original.additionalDirectories, decoded.additionalDirectories)
    }

    // === SessionCapabilities ===

    @Test
    fun `decodes SessionCapabilities with additionalDirectories capability`() {
        val payload = """
            {
                "additionalDirectories": {}
            }
        """.trimIndent()

        val capabilities = ACPJson.decodeFromString(SessionCapabilities.serializer(), payload)

        assertNotNull(capabilities.additionalDirectories)
    }

    @Test
    fun `decodes SessionCapabilities without additionalDirectories capability`() {
        val payload = """
            {}
        """.trimIndent()

        val capabilities = ACPJson.decodeFromString(SessionCapabilities.serializer(), payload)

        assertNull(capabilities.additionalDirectories)
    }

    @Test
    fun `decodes AgentCapabilities with additionalDirectories session capability`() {
        val payload = """
            {
                "sessionCapabilities": {
                    "additionalDirectories": {}
                }
            }
        """.trimIndent()

        val capabilities = ACPJson.decodeFromString(AgentCapabilities.serializer(), payload)

        assertNotNull(capabilities.sessionCapabilities.additionalDirectories)
    }

    // === Context: additionalDirectories in InitializeResponse ===

    @Test
    fun `decodes InitializeResponse with additionalDirectories session capability`() {
        val payload = """
            {
                "protocolVersion": 1,
                "agentCapabilities": {
                    "sessionCapabilities": {
                        "additionalDirectories": {}
                    }
                }
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(InitializeResponse.serializer(), payload)

        assertNotNull(response.agentCapabilities.sessionCapabilities.additionalDirectories)
    }

    // === Context: ListSessionsResponse with additionalDirectories ===

    @Test
    fun `decodes ListSessionsResponse with sessions containing additionalDirectories`() {
        val payload = """
            {
                "sessions": [
                    {
                        "sessionId": "s1",
                        "cwd": "/project1",
                        "additionalDirectories": ["/libs"]
                    },
                    {
                        "sessionId": "s2",
                        "cwd": "/project2"
                    }
                ]
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(ListSessionsResponse.serializer(), payload)

        assertEquals(2, response.sessions.size)
        assertEquals(listOf("/libs"), response.sessions[0].additionalDirectories)
        assertNull(response.sessions[1].additionalDirectories)
    }
}
