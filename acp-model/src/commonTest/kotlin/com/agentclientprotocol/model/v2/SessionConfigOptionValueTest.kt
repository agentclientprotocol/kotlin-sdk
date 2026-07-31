@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.SessionConfigOptionValue as V1SessionConfigOptionValue

class SessionConfigOptionValueTest {

    // Known variants

    @Test
    fun `decodes tagged id and boolean values`() {
        assertEquals(
            SessionConfigOptionValue.Id(SessionConfigValueId("fast")),
            decode("""{"type":"id","value":"fast"}"""),
        )
        assertEquals(
            SessionConfigOptionValue.Boolean(true),
            decode("""{"type":"boolean","value":true}"""),
        )
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals("""{"type":"id","value":"fast"}""", encode(SessionConfigOptionValue.Id(SessionConfigValueId("fast"))))
        assertEquals("""{"type":"boolean","value":true}""", encode(SessionConfigOptionValue.Boolean(true)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown value type as Unknown preserving the full payload`() {
        val json = """{"type":"number","value":0.7,"scale":"linear"}"""

        val value = decode(json)

        assertIs<SessionConfigOptionValue.Unknown>(value)
        assertEquals("number", value.type)
        assertEquals(JsonPrimitive(0.7), value.value)
        assertEquals(json, encode(value))
    }

    @Test
    fun `unknown value type without a value payload fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"number","scale":"linear"}""")
        }
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"value":"fast"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"id"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1 raw values`() {
        assertEquals(
            V1SessionConfigOptionValue.StringValue("fast"),
            SessionConfigOptionValue.Id(SessionConfigValueId("fast")).toV1(),
        )
        assertEquals(
            V1SessionConfigOptionValue.BoolValue(true),
            SessionConfigOptionValue.Boolean(true).toV1(),
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            SessionConfigOptionValue.Unknown(
                type = "number",
                value = JsonPrimitive(0.7),
                rawJson = kotlinx.serialization.json.buildJsonObject {},
            ).toV1()
        }

        assertEquals("v2 SessionConfigOptionValue variant `number` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts v1 raw values to v2 tagged values`() {
        assertEquals(
            SessionConfigOptionValue.Id(SessionConfigValueId("fast")),
            V1SessionConfigOptionValue.StringValue("fast").toV2(),
        )
        assertEquals(
            SessionConfigOptionValue.Boolean(false),
            V1SessionConfigOptionValue.BoolValue(false).toV2(),
        )
    }

    @Test
    fun `converting v1 UnknownValue to v2 fails instead of fabricating a payload`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            V1SessionConfigOptionValue.UnknownValue(JsonPrimitive(42)).toV2()
        }

        assertEquals("v1 SessionConfigOptionValue variant `UnknownValue` cannot be represented in v2", exception.message)
    }

    private fun decode(json: String): SessionConfigOptionValue =
        ACPJson.decodeFromString(SessionConfigOptionValue.serializer(), json)

    private fun encode(value: SessionConfigOptionValue): String =
        ACPJson.encodeToString(SessionConfigOptionValue.serializer(), value)
}
