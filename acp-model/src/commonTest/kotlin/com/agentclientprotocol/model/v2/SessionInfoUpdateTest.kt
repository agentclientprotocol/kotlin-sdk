@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionInfoUpdateTest {

    // Encoding: Undefined is omitted, Null is an explicit null, Value is encoded

    @Test
    fun `encodes an empty update as an empty object`() {
        assertEquals("{}", encode(SessionInfoUpdate()))
    }

    @Test
    fun `encodes only set fields`() {
        assertEquals(
            """{"title":"Refactor the parser"}""",
            encode(SessionInfoUpdate(title = MaybeUndefined.Value("Refactor the parser"))),
        )
    }

    @Test
    fun `encodes an explicit null for a cleared title`() {
        assertEquals(
            """{"title":null,"updatedAt":"2026-07-24T10:00:00Z"}""",
            encode(
                SessionInfoUpdate(
                    title = MaybeUndefined.Null,
                    updatedAt = MaybeUndefined.Value("2026-07-24T10:00:00Z"),
                ),
            ),
        )
    }

    @Test
    fun `encodes an explicit null for cleared meta`() {
        assertEquals("""{"_meta":null}""", encode(SessionInfoUpdate(_meta = MaybeUndefined.Null)))
    }

    // Decoding: absent, null, and value are three distinct states

    @Test
    fun `decodes omitted null and value states distinctly`() {
        val update = decode("""{"title":null,"updatedAt":"2026-07-24T10:00:00Z"}""")

        assertEquals(MaybeUndefined.Null, update.title)
        assertEquals(MaybeUndefined.Value("2026-07-24T10:00:00Z"), update.updatedAt)
        assertEquals(MaybeUndefined.Undefined, update._meta)
    }

    @Test
    fun `decodes an empty object as an all-Undefined update`() {
        assertEquals(SessionInfoUpdate(), decode("{}"))
    }

    @Test
    fun `round-trips meta`() {
        val update = SessionInfoUpdate(
            _meta = MaybeUndefined.Value(buildJsonObject { put("pinned", JsonPrimitive(true)) }),
        )
        val json = """{"_meta":{"pinned":true}}"""

        assertEquals(json, encode(update))
        assertEquals(update, decode(json))
    }

    @Test
    fun `degrades a malformed title to Undefined`() {
        // DefaultOnError parity: a structured value where a string is expected is dropped
        // rather than failing the whole update.
        assertEquals(MaybeUndefined.Undefined, decode("""{"title":{"nested":true}}""").title)
    }

    private fun decode(json: String): SessionInfoUpdate =
        ACPJson.decodeFromString(SessionInfoUpdate.serializer(), json)

    private fun encode(update: SessionInfoUpdate): String =
        ACPJson.encodeToString(SessionInfoUpdate.serializer(), update)
}
