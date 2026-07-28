@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.McpServer as V1McpServer

class McpServerTest {

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(
            McpServer.Http(name = "srv", url = "https://example.com/mcp"),
            decode("""{"type":"http","name":"srv","url":"https://example.com/mcp"}"""),
        )
        assertEquals(
            McpServer.Stdio(name = "srv", command = "/bin/mcp", args = listOf("--fast")),
            decode("""{"type":"stdio","name":"srv","command":"/bin/mcp","args":["--fast"]}"""),
        )
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals(
            """{"type":"http","name":"srv","url":"https://example.com/mcp","headers":[]}""",
            encode(McpServer.Http(name = "srv", url = "https://example.com/mcp")),
        )
        assertEquals(
            """{"type":"stdio","name":"srv","command":"/bin/mcp","args":[],"env":[]}""",
            encode(McpServer.Stdio(name = "srv", command = "/bin/mcp")),
        )
    }

    @Test
    fun `known variants round-trip`() {
        val server = McpServer.Stdio(
            name = "srv",
            command = "/bin/mcp",
            args = listOf("--fast"),
            env = listOf(EnvVariable("KEY", "value")),
        )

        assertEquals(server, decode(encode(server)))

        val http = McpServer.Http(
            name = "srv",
            url = "https://example.com/mcp",
            headers = listOf(HttpHeader("Authorization", "Bearer token")),
        )

        assertEquals(http, decode(encode(http)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown transport as Unknown preserving the full payload`() {
        val json = """{"type":"websocket","name":"srv","endpoint":"wss://example.com"}"""

        val server = decode(json)

        assertIs<McpServer.Unknown>(server)
        assertEquals("websocket", server.type)
        assertEquals(json, encode(server))
    }

    @Test
    fun `underscore-prefixed extension transport round-trips byte-identically`() {
        val json = """{"type":"_vendor_transport","name":"srv","custom":{"nested":[1,2]}}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness (the v1 default-to-Stdio hazard is gone)

    @Test
    fun `missing discriminator fails instead of defaulting to stdio`() {
        assertFailsWith<SerializationException> {
            decode("""{"name":"srv","command":"/bin/mcp","args":[],"env":[]}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"http","name":"srv"}""")
        }
    }

    @Test
    fun `non-string discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":42,"name":"srv"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1`() {
        assertEquals(
            V1McpServer.Http(name = "srv", url = "https://example.com/mcp", headers = emptyList()),
            McpServer.Http(name = "srv", url = "https://example.com/mcp").toV1(),
        )
        assertEquals(
            V1McpServer.Stdio(name = "srv", command = "/bin/mcp", args = emptyList(), env = emptyList()),
            McpServer.Stdio(name = "srv", command = "/bin/mcp").toV1(),
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            McpServer.Unknown("websocket", buildJsonObject {}).toV1()
        }

        assertEquals("v2 McpServer variant `websocket` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts v1 variants to v2`() {
        assertEquals(
            McpServer.Http(name = "srv", url = "https://example.com/mcp"),
            V1McpServer.Http(name = "srv", url = "https://example.com/mcp", headers = emptyList()).toV2(),
        )
        assertEquals(
            McpServer.Stdio(name = "srv", command = "/bin/mcp"),
            V1McpServer.Stdio(name = "srv", command = "/bin/mcp", args = emptyList(), env = emptyList()).toV2(),
        )
    }

    @Test
    fun `converting v1 Sse to v2 fails because the transport was removed`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            V1McpServer.Sse(name = "srv", url = "https://example.com/sse", headers = emptyList()).toV2()
        }

        assertEquals("v1 McpServer variant `sse` cannot be represented in v2", exception.message)
    }

    private fun decode(json: String): McpServer =
        ACPJson.decodeFromString(McpServer.serializer(), json)

    private fun encode(server: McpServer): String =
        ACPJson.encodeToString(McpServer.serializer(), server)
}
