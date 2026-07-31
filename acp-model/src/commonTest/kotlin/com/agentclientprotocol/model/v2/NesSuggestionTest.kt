@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.NesPosition
import com.agentclientprotocol.model.NesRange
import com.agentclientprotocol.model.NesTextEdit
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
import com.agentclientprotocol.model.NesSuggestion as V1NesSuggestion

class NesSuggestionTest {

    private val position = NesPosition(line = 1u, character = 2u)
    private val edit = NesTextEdit(
        range = NesRange(start = position, end = position),
        newText = "fixed",
    )

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(
            NesSuggestion.Jump(suggestionId = "s1", uri = "file:///main.rs", position = position),
            decode("""{"kind":"jump","suggestionId":"s1","uri":"file:///main.rs","position":{"line":1,"character":2}}"""),
        )
        assertEquals(
            NesSuggestion.Rename(suggestionId = "s2", uri = "file:///main.rs", position = position, newName = "newName"),
            decode(
                """{"kind":"rename","suggestionId":"s2","uri":"file:///main.rs",""" +
                    """"position":{"line":1,"character":2},"newName":"newName"}""",
            ),
        )
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals(
            """{"kind":"searchAndReplace","suggestionId":"s3","uri":"file:///main.rs","search":"a","replace":"b"}""",
            encode(
                NesSuggestion.SearchAndReplace(
                    suggestionId = "s3",
                    uri = "file:///main.rs",
                    search = "a",
                    replace = "b",
                ),
            ),
        )
    }

    @Test
    fun `edit variant round-trips`() {
        val suggestion = NesSuggestion.Edit(
            suggestionId = "s4",
            uri = "file:///main.rs",
            edits = listOf(edit),
            cursorPosition = position,
        )

        assertEquals(suggestion, decode(encode(suggestion)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown kind as Unknown with parsed identity and full payload`() {
        val json = """{"kind":"refactor","suggestionId":"s5","strategy":"extract-method"}"""

        val suggestion = decode(json)

        assertIs<NesSuggestion.Unknown>(suggestion)
        assertEquals("refactor", suggestion.kind)
        assertEquals("s5", suggestion.suggestionId)
        assertEquals(json, encode(suggestion))
    }

    @Test
    fun `unknown suggestion without suggestionId fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"kind":"refactor","id":"s5"}""")
        }
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"suggestionId":"s1","uri":"file:///main.rs"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"kind":"jump","suggestionId":"s1"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1 mapping suggestionId to id`() {
        assertEquals(
            V1NesSuggestion.Jump(id = "s1", uri = "file:///main.rs", position = position),
            NesSuggestion.Jump(suggestionId = "s1", uri = "file:///main.rs", position = position).toV1(),
        )
        assertEquals(
            V1NesSuggestion.SearchAndReplace(id = "s3", uri = "file:///a", search = "a", replace = "b", isRegex = false),
            NesSuggestion.SearchAndReplace(suggestionId = "s3", uri = "file:///a", search = "a", replace = "b").toV1(),
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            NesSuggestion.Unknown(kind = "refactor", suggestionId = "s5", rawJson = buildJsonObject {}).toV1()
        }

        assertEquals("v2 NesSuggestion variant `refactor` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 variants to v2`() {
        assertEquals(
            NesSuggestion.Edit(suggestionId = "s4", uri = "file:///a", edits = listOf(edit), cursorPosition = position),
            V1NesSuggestion.Edit(id = "s4", uri = "file:///a", edits = listOf(edit), cursorPosition = position).toV2(),
        )
        assertEquals(
            NesSuggestion.Rename(suggestionId = "s2", uri = "file:///a", position = position, newName = "n"),
            V1NesSuggestion.Rename(id = "s2", uri = "file:///a", position = position, newName = "n").toV2(),
        )
        assertEquals(
            NesSuggestion.SearchAndReplace(suggestionId = "s3", uri = "file:///a", search = "a", replace = "b", isRegex = true),
            V1NesSuggestion.SearchAndReplace(id = "s3", uri = "file:///a", search = "a", replace = "b", isRegex = true).toV2(),
        )
    }

    private fun decode(json: String): NesSuggestion =
        ACPJson.decodeFromString(NesSuggestion.serializer(), json)

    private fun encode(suggestion: NesSuggestion): String =
        ACPJson.encodeToString(NesSuggestion.serializer(), suggestion)
}
