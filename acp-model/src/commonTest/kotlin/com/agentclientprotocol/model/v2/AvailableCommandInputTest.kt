@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
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
import com.agentclientprotocol.model.AvailableCommandInput as V1AvailableCommandInput

class AvailableCommandInputTest {

    // Known variants

    @Test
    fun `decodes the text variant`() {
        assertEquals(
            AvailableCommandInput.Text(hint = "file path"),
            decode("""{"type":"text","hint":"file path"}"""),
        )
    }

    @Test
    fun `encodes the text variant with leading discriminator`() {
        assertEquals(
            """{"type":"text","hint":"file path"}""",
            encode(AvailableCommandInput.Text(hint = "file path")),
        )
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown input type as Unknown preserving the full payload`() {
        val json = """{"type":"structured","schema":{"kind":"object"}}"""

        val input = decode(json)

        assertIs<AvailableCommandInput.Unknown>(input)
        assertEquals("structured", input.type)
        assertEquals(json, encode(input))
    }

    @Test
    fun `underscore-prefixed extension input round-trips byte-identically`() {
        val json = """{"type":"_vendor_input","fields":[1,2,3]}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails instead of defaulting to a known variant`() {
        assertFailsWith<SerializationException> {
            decode("""{"hint":"file path"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"text"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts text to v1 unstructured`() {
        assertEquals(
            V1AvailableCommandInput.Unstructured(hint = "file path"),
            AvailableCommandInput.Text(hint = "file path").toV1(),
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            AvailableCommandInput.Unknown("structured", buildJsonObject {}).toV1()
        }

        assertEquals(
            "v2 AvailableCommandInput variant `structured` cannot be represented in v1",
            exception.message,
        )
    }

    @Test
    fun `converts v1 unstructured to v2 text`() {
        assertEquals(
            AvailableCommandInput.Text(hint = "file path"),
            V1AvailableCommandInput.Unstructured(hint = "file path").toV2(),
        )
    }

    private fun decode(json: String): AvailableCommandInput =
        ACPJson.decodeFromString(AvailableCommandInput.serializer(), json)

    private fun encode(input: AvailableCommandInput): String =
        ACPJson.encodeToString(AvailableCommandInput.serializer(), input)
}
