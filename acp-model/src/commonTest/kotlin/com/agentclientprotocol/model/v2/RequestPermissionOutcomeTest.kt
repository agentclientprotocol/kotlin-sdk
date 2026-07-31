@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.PermissionOptionId
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
import com.agentclientprotocol.model.RequestPermissionOutcome as V1RequestPermissionOutcome

class RequestPermissionOutcomeTest {

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(RequestPermissionOutcome.Cancelled, decode("""{"outcome":"cancelled"}"""))
        assertEquals(
            RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once")),
            decode("""{"outcome":"selected","optionId":"allow-once"}"""),
        )
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals("""{"outcome":"cancelled"}""", encode(RequestPermissionOutcome.Cancelled))
        assertEquals(
            """{"outcome":"selected","optionId":"allow-once"}""",
            encode(RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once"))),
        )
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown outcome as Unknown preserving the full payload`() {
        val json = """{"outcome":"deferred","until":"2027-01-01"}"""

        val outcome = decode(json)

        assertIs<RequestPermissionOutcome.Unknown>(outcome)
        assertEquals("deferred", outcome.outcome)
        assertEquals(json, encode(outcome))
    }

    @Test
    fun `underscore-prefixed extension outcome round-trips byte-identically`() {
        val json = """{"outcome":"_vendor_policy","rule":42}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"optionId":"allow-once"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"outcome":"selected"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1`() {
        assertEquals(V1RequestPermissionOutcome.Cancelled, RequestPermissionOutcome.Cancelled.toV1())
        assertEquals(
            V1RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once")),
            RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once")).toV1(),
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            RequestPermissionOutcome.Unknown("deferred", buildJsonObject {}).toV1()
        }

        assertEquals(
            "v2 RequestPermissionOutcome variant `deferred` cannot be represented in v1",
            exception.message,
        )
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(RequestPermissionOutcome.Cancelled, V1RequestPermissionOutcome.Cancelled.toV2())
        assertEquals(
            RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once")),
            V1RequestPermissionOutcome.Selected(optionId = PermissionOptionId("allow-once")).toV2(),
        )
    }

    private fun decode(json: String): RequestPermissionOutcome =
        ACPJson.decodeFromString(RequestPermissionOutcome.serializer(), json)

    private fun encode(outcome: RequestPermissionOutcome): String =
        ACPJson.encodeToString(RequestPermissionOutcome.serializer(), outcome)
}
