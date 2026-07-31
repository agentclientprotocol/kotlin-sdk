@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationContentValue
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
import com.agentclientprotocol.model.ElicitationAction as V1ElicitationAction

class ElicitationActionTest {

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(
            ElicitationAction.Accept(content = mapOf("name" to ElicitationContentValue.StringValue("Ada"))),
            decode("""{"action":"accept","content":{"name":"Ada"}}"""),
        )
        assertEquals(ElicitationAction.Decline, decode("""{"action":"decline"}"""))
        assertEquals(ElicitationAction.Cancel, decode("""{"action":"cancel"}"""))
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals("""{"action":"decline"}""", encode(ElicitationAction.Decline))
        assertEquals(
            """{"action":"accept","content":{"name":"Ada"}}""",
            encode(ElicitationAction.Accept(content = mapOf("name" to ElicitationContentValue.StringValue("Ada")))),
        )
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown action as Unknown preserving the full payload`() {
        val json = """{"action":"defer","until":"tomorrow"}"""

        val action = decode(json)

        assertIs<ElicitationAction.Unknown>(action)
        assertEquals("defer", action.action)
        assertEquals(json, encode(action))
    }

    @Test
    fun `underscore-prefixed extension action round-trips byte-identically`() {
        val json = """{"action":"_vendor_snooze","minutes":5}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"content":{}}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1`() {
        assertEquals(
            V1ElicitationAction.Accept(content = mapOf("ok" to ElicitationContentValue.BooleanValue(true))),
            ElicitationAction.Accept(content = mapOf("ok" to ElicitationContentValue.BooleanValue(true))).toV1(),
        )
        assertEquals(V1ElicitationAction.Decline, ElicitationAction.Decline.toV1())
        assertEquals(V1ElicitationAction.Cancel, ElicitationAction.Cancel.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ElicitationAction.Unknown("defer", buildJsonObject {}).toV1()
        }

        assertEquals("v2 ElicitationAction variant `defer` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(
            ElicitationAction.Accept(content = null),
            V1ElicitationAction.Accept(content = null).toV2(),
        )
        assertEquals(ElicitationAction.Decline, V1ElicitationAction.Decline.toV2())
        assertEquals(ElicitationAction.Cancel, V1ElicitationAction.Cancel.toV2())
    }

    private fun decode(json: String): ElicitationAction =
        ACPJson.decodeFromString(ElicitationAction.serializer(), json)

    private fun encode(action: ElicitationAction): String =
        ACPJson.encodeToString(ElicitationAction.serializer(), action)
}
