package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerSerializerTest {
    @Test
    fun `decodes stdio transport without discriminator`() {
        val payload = """
            {
                "name": "filesystem",
                "command": "/path/to/mcp-server",
                "args": ["--stdio"],
                "env": [{"name": "TOKEN", "value": "secret"}]
            }
        """.trimIndent()

        val server = ACPJson.decodeFromString(McpServer.serializer(), payload)
        assertTrue(server is McpServer.Stdio)
        assertEquals("filesystem", server.name)
        assertEquals("/path/to/mcp-server", server.command)
        assertEquals(listOf("--stdio"), server.args)
        assertEquals("TOKEN", server.env.first().name)
        assertEquals("secret", server.env.first().value)
    }

    @Test
    fun `decodes http transport with type discriminator`() {
        val payload = """
            {
                "type": "http",
                "name": "api-server",
                "url": "https://api.example.com/mcp",
                "headers": [{"name": "Authorization", "value": "Bearer token123"}]
            }
        """.trimIndent()

        val server = ACPJson.decodeFromString(McpServer.serializer(), payload)
        assertTrue(server is McpServer.Http)
        assertEquals("api-server", server.name)
        assertEquals("https://api.example.com/mcp", server.url)
        assertEquals("Authorization", server.headers.first().name)
        assertEquals("Bearer token123", server.headers.first().value)
    }

    @Test
    fun `decodes sse transport when type is sse`() {
        val payload = """
            {
                "type": "sse",
                "name": "event-stream",
                "url": "https://events.example.com/mcp",
                "headers": [{"name": "X-API-Key", "value": "apikey456"}]
            }
        """.trimIndent()

        val server = ACPJson.decodeFromString(McpServer.serializer(), payload)
        assertTrue(server is McpServer.Sse)
        assertEquals("event-stream", server.name)
        assertEquals("https://events.example.com/mcp", server.url)
        assertEquals("X-API-Key", server.headers.first().name)
        assertEquals("apikey456", server.headers.first().value)
    }

    @Test
    fun `encodes discriminator when serializing`() {
        val server = McpServer.Sse(
            name = "event-stream",
            url = "https://events.example.com/mcp",
            headers = listOf(HttpHeader("X-API-Key", "apikey456"))
        )

        val encoded = ACPJson.encodeToString(McpServer.serializer(), server)
        assertTrue(encoded.contains("\"type\":\"sse\""))
    }

    @Test
    fun `encodes stdio discriminator when serializing`() {
        val server = McpServer.Stdio(
            name = "filesystem",
            command = "/path/to/mcp-server",
            args = listOf("--stdio"),
            env = listOf(EnvVariable("TOKEN", "secret"))
        )

        val encoded = ACPJson.encodeToString(McpServer.serializer(), server)
        assertTrue(encoded.contains("\"type\":\"stdio\""))
    }
}
