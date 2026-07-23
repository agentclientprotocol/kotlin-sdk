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
import com.agentclientprotocol.model.NesRejectReason as V1NesRejectReason

class NesRejectReasonTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(NesRejectReason.Rejected, decode("\"rejected\""))
        assertEquals(NesRejectReason.Ignored, decode("\"ignored\""))
        assertEquals(NesRejectReason.Replaced, decode("\"replaced\""))
        assertEquals(NesRejectReason.Cancelled, decode("\"cancelled\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"rejected\"", encode(NesRejectReason.Rejected))
        assertEquals("\"ignored\"", encode(NesRejectReason.Ignored))
        assertEquals("\"replaced\"", encode(NesRejectReason.Replaced))
        assertEquals("\"cancelled\"", encode(NesRejectReason.Cancelled))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val reason = decode("\"expired\"")

        assertIs<NesRejectReason.Unknown>(reason)
        assertEquals("expired", reason.value)
        assertEquals("\"_vendor_reason\"", encode(decode("\"_vendor_reason\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(NesRejectReason.extension("_vendor_reason"), NesRejectReason.Unknown("_vendor_reason"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            NesRejectReason.extension("vendor_reason")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1NesRejectReason.REJECTED, NesRejectReason.Rejected.toV1())
        assertEquals(V1NesRejectReason.IGNORED, NesRejectReason.Ignored.toV1())
        assertEquals(V1NesRejectReason.REPLACED, NesRejectReason.Replaced.toV1())
        assertEquals(V1NesRejectReason.CANCELLED, NesRejectReason.Cancelled.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            NesRejectReason.Unknown("expired").toV1()
        }

        assertEquals("v2 NesRejectReason variant `expired` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(NesRejectReason.Rejected, V1NesRejectReason.REJECTED.toV2())
        assertEquals(NesRejectReason.Ignored, V1NesRejectReason.IGNORED.toV2())
        assertEquals(NesRejectReason.Replaced, V1NesRejectReason.REPLACED.toV2())
        assertEquals(NesRejectReason.Cancelled, V1NesRejectReason.CANCELLED.toV2())
    }

    private fun decode(json: String): NesRejectReason =
        ACPJson.decodeFromString(NesRejectReason.serializer(), json)

    private fun encode(reason: NesRejectReason): String =
        ACPJson.encodeToString(NesRejectReason.serializer(), reason)
}
