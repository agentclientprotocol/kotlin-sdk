@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContentChunkTest {

    private val chunk = ContentChunk(
        messageId = MessageId("msg_1"),
        content = ContentBlock.Text(text = "hello"),
    )

    private val chunkJson = """{"messageId":"msg_1","content":{"type":"text","text":"hello"}}"""

    @Test
    fun `decodes a chunk with its message id and content block`() {
        assertEquals(chunk, decode(chunkJson))
    }

    @Test
    fun `encodes a chunk without optional meta`() {
        assertEquals(chunkJson, encode(chunk))
    }

    @Test
    fun `round-trips chunk-scoped meta`() {
        val withMeta = chunk.copy(_meta = buildJsonObject { put("index", JsonPrimitive(3)) })
        val json = """{"messageId":"msg_1","content":{"type":"text","text":"hello"},"_meta":{"index":3}}"""

        assertEquals(json, encode(withMeta))
        assertEquals(withMeta, decode(json))
    }

    @Test
    fun `requires a message id unlike v1 chunks`() {
        assertFailsWith<SerializationException> {
            decode("""{"content":{"type":"text","text":"hello"}}""")
        }
    }

    @Test
    fun `requires content`() {
        assertFailsWith<SerializationException> { decode("""{"messageId":"msg_1"}""") }
    }

    private fun decode(json: String): ContentChunk =
        ACPJson.decodeFromString(ContentChunk.serializer(), json)

    private fun encode(chunk: ContentChunk): String =
        ACPJson.encodeToString(ContentChunk.serializer(), chunk)
}
