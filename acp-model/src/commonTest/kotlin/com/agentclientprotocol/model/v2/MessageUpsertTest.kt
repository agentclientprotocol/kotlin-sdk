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

/**
 * [UserMessage], [AgentMessage], and [AgentThought] share one wire shape and one
 * serializer base, so they are covered together.
 */
class MessageUpsertTest {

    // Encoding: Undefined is omitted, Null is an explicit null, Value is encoded

    @Test
    fun `encodes only set fields as an upsert`() {
        assertEquals("""{"messageId":"msg_1"}""", encodeUser(UserMessage(messageId = MessageId("msg_1"))))
        assertEquals(
            """{"messageId":"msg_1","content":[{"type":"text","text":"hi"}]}""",
            encodeUser(
                UserMessage(
                    messageId = MessageId("msg_1"),
                    content = MaybeUndefined.Value(listOf(ContentBlock.Text(text = "hi"))),
                ),
            ),
        )
    }

    @Test
    fun `encodes an explicit null for cleared content and meta`() {
        assertEquals(
            """{"messageId":"msg_1","content":null,"_meta":null}""",
            encodeUser(
                UserMessage(
                    messageId = MessageId("msg_1"),
                    content = MaybeUndefined.Null,
                    _meta = MaybeUndefined.Null,
                ),
            ),
        )
    }

    @Test
    fun `all three upserts share the same wire shape`() {
        val json = """{"messageId":"msg_1","content":[{"type":"text","text":"hi"}]}"""
        val content = MaybeUndefined.Value(listOf<ContentBlock>(ContentBlock.Text(text = "hi")))

        assertEquals(json, encodeUser(UserMessage(messageId = MessageId("msg_1"), content = content)))
        assertEquals(
            json,
            ACPJson.encodeToString(
                AgentMessage.serializer(),
                AgentMessage(messageId = MessageId("msg_1"), content = content),
            ),
        )
        assertEquals(
            json,
            ACPJson.encodeToString(
                AgentThought.serializer(),
                AgentThought(messageId = MessageId("msg_1"), content = content),
            ),
        )
    }

    // Decoding: absent, null, and value are three distinct states

    @Test
    fun `decodes omitted null and value states distinctly`() {
        assertEquals(MaybeUndefined.Undefined, decodeUser("""{"messageId":"msg_1"}""").content)
        assertEquals(MaybeUndefined.Null, decodeUser("""{"messageId":"msg_1","content":null}""").content)
        assertEquals(
            MaybeUndefined.Value(listOf(ContentBlock.Text(text = "hi"))),
            decodeUser("""{"messageId":"msg_1","content":[{"type":"text","text":"hi"}]}""").content,
        )
    }

    @Test
    fun `decodes an empty content array as a value that clears accumulated content`() {
        assertEquals(MaybeUndefined.Value(emptyList()), decodeUser("""{"messageId":"msg_1","content":[]}""").content)
    }

    @Test
    fun `round-trips meta`() {
        val meta = buildJsonObject { put("source", JsonPrimitive("cli")) }
        val message = UserMessage(messageId = MessageId("msg_1"), _meta = MaybeUndefined.Value(meta))
        val json = """{"messageId":"msg_1","_meta":{"source":"cli"}}"""

        assertEquals(json, encodeUser(message))
        assertEquals(message, decodeUser(json))
    }

    // Graceful degradation, mirroring the Rust schema's DefaultOnError / VecSkipError

    @Test
    fun `skips malformed content items instead of failing the field`() {
        val decoded = decodeUser(
            """{"messageId":"msg_1","content":[{"type":"text","text":"hi"},{"type":"text"}]}""",
        )

        assertEquals(MaybeUndefined.Value(listOf(ContentBlock.Text(text = "hi"))), decoded.content)
    }

    @Test
    fun `degrades a non-array content value to Undefined`() {
        assertEquals(MaybeUndefined.Undefined, decodeUser("""{"messageId":"msg_1","content":"oops"}""").content)
    }

    @Test
    fun `preserves unknown content block types`() {
        val decoded = decodeUser("""{"messageId":"msg_1","content":[{"type":"_vendor","x":1}]}""")

        assertEquals(1, (decoded.content as MaybeUndefined.Value).value.size)
    }

    @Test
    fun `requires a message id`() {
        assertFailsWith<SerializationException> { decodeUser("""{"content":[]}""") }
    }

    private fun decodeUser(json: String): UserMessage =
        ACPJson.decodeFromString(UserMessage.serializer(), json)

    private fun encodeUser(message: UserMessage): String =
        ACPJson.encodeToString(UserMessage.serializer(), message)
}
