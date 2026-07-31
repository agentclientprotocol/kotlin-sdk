@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.StopReason as V1StopReason

class StopReasonTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(StopReason.EndTurn, decode("\"end_turn\""))
        assertEquals(StopReason.MaxTokens, decode("\"max_tokens\""))
        assertEquals(StopReason.MaxTurnRequests, decode("\"max_turn_requests\""))
        assertEquals(StopReason.Refusal, decode("\"refusal\""))
        assertEquals(StopReason.Cancelled, decode("\"cancelled\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"end_turn\"", encode(StopReason.EndTurn))
        assertEquals("\"max_tokens\"", encode(StopReason.MaxTokens))
        assertEquals("\"max_turn_requests\"", encode(StopReason.MaxTurnRequests))
        assertEquals("\"refusal\"", encode(StopReason.Refusal))
        assertEquals("\"cancelled\"", encode(StopReason.Cancelled))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val reason = decode("\"max_cost\"")

        assertIs<StopReason.Unknown>(reason)
        assertEquals("max_cost", reason.value)
        assertEquals("\"_vendor_reason\"", encode(decode("\"_vendor_reason\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(StopReason.extension("_vendor_reason"), StopReason.Unknown("_vendor_reason"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            StopReason.extension("vendor_reason")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1StopReason.END_TURN, StopReason.EndTurn.toV1())
        assertEquals(V1StopReason.MAX_TOKENS, StopReason.MaxTokens.toV1())
        assertEquals(V1StopReason.MAX_TURN_REQUESTS, StopReason.MaxTurnRequests.toV1())
        assertEquals(V1StopReason.REFUSAL, StopReason.Refusal.toV1())
        assertEquals(V1StopReason.CANCELLED, StopReason.Cancelled.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            StopReason.Unknown("max_cost").toV1()
        }

        assertEquals("v2 StopReason variant `max_cost` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(StopReason.EndTurn, V1StopReason.END_TURN.toV2())
        assertEquals(StopReason.MaxTokens, V1StopReason.MAX_TOKENS.toV2())
        assertEquals(StopReason.MaxTurnRequests, V1StopReason.MAX_TURN_REQUESTS.toV2())
        assertEquals(StopReason.Refusal, V1StopReason.REFUSAL.toV2())
        assertEquals(StopReason.Cancelled, V1StopReason.CANCELLED.toV2())
    }

    private fun decode(json: String): StopReason =
        ACPJson.decodeFromString(StopReason.serializer(), json)

    private fun encode(reason: StopReason): String =
        ACPJson.encodeToString(StopReason.serializer(), reason)
}
