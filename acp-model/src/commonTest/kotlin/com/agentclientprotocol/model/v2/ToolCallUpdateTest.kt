@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToolCallUpdateTest {

    // Encoding: Undefined is omitted, Null is an explicit null, Value is encoded

    @Test
    fun `encodes only set fields as an upsert`() {
        val update = ToolCallUpdate(
            toolCallId = ToolCallId("tc_1"),
            title = MaybeUndefined.Value("Reading configuration"),
            status = MaybeUndefined.Value(ToolCallStatus.InProgress),
            rawInput = MaybeUndefined.Value(buildJsonObject { put("path", "settings.json") }),
        )

        assertEquals(
            """{"toolCallId":"tc_1","title":"Reading configuration","status":"in_progress","rawInput":{"path":"settings.json"}}""",
            encode(update),
        )
    }

    @Test
    fun `encodes an explicit null for cleared fields`() {
        val update = ToolCallUpdate(
            toolCallId = ToolCallId("tc_1"),
            status = MaybeUndefined.Value(ToolCallStatus.Completed),
            content = MaybeUndefined.Null,
        )

        assertEquals(
            """{"toolCallId":"tc_1","status":"completed","content":null}""",
            encode(update),
        )
    }

    @Test
    fun `encodes an explicit null for cleared meta`() {
        assertEquals(
            """{"toolCallId":"tc_1","_meta":null}""",
            encode(ToolCallUpdate(toolCallId = ToolCallId("tc_1"), _meta = MaybeUndefined.Null)),
        )
    }

    // Decoding: absent, null, and value are three distinct states

    @Test
    fun `decodes omitted null and value states distinctly`() {
        val update = decode("""{"toolCallId":"tc_1","status":null,"locations":[]}""")

        assertEquals(MaybeUndefined.Undefined, update.title)
        assertEquals(MaybeUndefined.Null, update.status)
        assertEquals(MaybeUndefined.Value(emptyList()), update.locations)
    }

    @Test
    fun `decodes meta omitted vs null vs value`() {
        assertEquals(
            MaybeUndefined.Undefined,
            decode("""{"toolCallId":"tc_1"}""")._meta,
        )
        assertEquals(
            MaybeUndefined.Null,
            decode("""{"toolCallId":"tc_1","_meta":null}""")._meta,
        )
        assertEquals(
            MaybeUndefined.Value(buildJsonObject { put("source", "tool-call") }),
            decode("""{"toolCallId":"tc_1","_meta":{"source":"tool-call"}}""")._meta,
        )
    }

    @Test
    fun `round-trips a fully populated update byte-identically`() {
        val json = """{"toolCallId":"tc_1","title":"Edit","kind":"edit","status":"in_progress",""" +
            """"content":[{"type":"content","content":{"type":"text","text":"ok"}}],""" +
            """"locations":[{"path":"/f.txt","line":3}],"rawInput":{"a":1},"rawOutput":null,"_meta":{"k":"v"}}"""

        assertEquals(json, encode(decode(json)))
    }

    // Open enum leaves stay open

    @Test
    fun `unknown kind string is preserved as an open enum value`() {
        assertEquals(
            MaybeUndefined.Value(ToolKind.Unknown("quantum")),
            decode("""{"toolCallId":"tc_1","kind":"quantum"}""").kind,
        )
    }

    // Graceful degradation (Rust DefaultOnError / VecSkipError)

    @Test
    fun `malformed optional fields degrade to undefined instead of failing`() {
        val update = decode("""{"toolCallId":"tc_1","title":{"a":1},"kind":[],"content":"nope"}""")

        assertEquals(MaybeUndefined.Undefined, update.title)
        assertEquals(MaybeUndefined.Undefined, update.kind)
        assertEquals(MaybeUndefined.Undefined, update.content)
    }

    @Test
    fun `skips malformed list items but keeps valid ones`() {
        val update = decode(
            """{"toolCallId":"tc_1","content":[""" +
                """{"type":"content","content":{"type":"text","text":"ok"}},""" +
                """{"type":"diff","path":"/bad"}],""" +
                """"locations":[{"path":"/ok","line":3},{"line":4}]}"""
        )

        val content = assertIs<MaybeUndefined.Value<List<ToolCallContent>>>(update.content)
        assertEquals(listOf<ToolCallContent>(ToolCallContent.Content(ContentBlock.Text(text = "ok"))), content.value)

        val locations = assertIs<MaybeUndefined.Value<List<ToolCallLocation>>>(update.locations)
        assertEquals(listOf(ToolCallLocation(path = "/ok", line = 3u)), locations.value)
    }

    @Test
    fun `unknown content item types are preserved not skipped`() {
        val update = decode(
            """{"toolCallId":"tc_1","content":[{"type":"terminal","terminalId":"term-1"}]}"""
        )

        val content = assertIs<MaybeUndefined.Value<List<ToolCallContent>>>(update.content)
        val item = assertIs<ToolCallContent.Unknown>(content.value.single())
        assertEquals("terminal", item.type)
    }

    // Strictness: the one required field still fails hard

    @Test
    fun `missing toolCallId fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"title":"Reading configuration"}""")
        }
    }

    // Upsert application

    @Test
    fun `applyUpdate patches stored state and preserves explicit nulls`() {
        val stored = ToolCallUpdate(
            toolCallId = ToolCallId("tc_1"),
            title = MaybeUndefined.Value("Reading configuration"),
            status = MaybeUndefined.Value(ToolCallStatus.InProgress),
            _meta = MaybeUndefined.Value(JsonPrimitive("keep")),
        )

        val patched = stored.applyUpdate(
            ToolCallUpdate(
                toolCallId = ToolCallId("tc_1"),
                status = MaybeUndefined.Value(ToolCallStatus.Completed),
                _meta = MaybeUndefined.Null,
            )
        )

        assertEquals(MaybeUndefined.Value("Reading configuration"), patched.title)
        assertEquals(MaybeUndefined.Value(ToolCallStatus.Completed), patched.status)
        assertEquals(MaybeUndefined.Null, patched._meta)
    }

    @Test
    fun `applyUpdate rejects a different toolCallId`() {
        assertFailsWith<IllegalArgumentException> {
            ToolCallUpdate(toolCallId = ToolCallId("tc_1"))
                .applyUpdate(ToolCallUpdate(toolCallId = ToolCallId("tc_2")))
        }
    }

    @Test
    fun `update resolves patch states against a previous value`() {
        assertEquals("new", MaybeUndefined.Value("new").update("old"))
        assertEquals("old", MaybeUndefined.Undefined.update("old"))
        assertEquals(null, MaybeUndefined.Null.update("old"))
    }

    private fun decode(json: String): ToolCallUpdate =
        ACPJson.decodeFromString(ToolCallUpdate.serializer(), json)

    private fun encode(update: ToolCallUpdate): String =
        ACPJson.encodeToString(ToolCallUpdate.serializer(), update)
}
