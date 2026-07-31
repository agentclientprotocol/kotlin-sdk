@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToolCallContentChunkTest {

    private val chunk = ToolCallContentChunk(
        toolCallId = ToolCallId("tc_1"),
        content = ToolCallContent.Content(content = ContentBlock.Text(text = "line 1")),
    )

    private val chunkJson =
        """{"toolCallId":"tc_1","content":{"type":"content","content":{"type":"text","text":"line 1"}}}"""

    @Test
    fun `decodes a chunk with a single content item`() {
        assertEquals(chunk, decode(chunkJson))
    }

    @Test
    fun `encodes a chunk without optional meta`() {
        assertEquals(chunkJson, encode(chunk))
    }

    @Test
    fun `preserves unknown content types as raw json`() {
        val json = """{"toolCallId":"tc_1","content":{"type":"_vendor_chart","points":[1,2]}}"""

        val decoded = decode(json)
        assertIs<ToolCallContent.Unknown>(decoded.content)
        assertEquals("_vendor_chart", decoded.content.type)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `requires a tool call id`() {
        assertFailsWith<SerializationException> {
            decode("""{"content":{"type":"content","content":{"type":"text","text":"line 1"}}}""")
        }
    }

    @Test
    fun `requires content`() {
        assertFailsWith<SerializationException> { decode("""{"toolCallId":"tc_1"}""") }
    }

    private fun decode(json: String): ToolCallContentChunk =
        ACPJson.decodeFromString(ToolCallContentChunk.serializer(), json)

    private fun encode(chunk: ToolCallContentChunk): String =
        ACPJson.encodeToString(ToolCallContentChunk.serializer(), chunk)
}
