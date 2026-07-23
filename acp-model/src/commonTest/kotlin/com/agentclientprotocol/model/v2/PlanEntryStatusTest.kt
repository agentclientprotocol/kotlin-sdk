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
import com.agentclientprotocol.model.PlanEntryStatus as V1PlanEntryStatus

class PlanEntryStatusTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(PlanEntryStatus.Pending, decode("\"pending\""))
        assertEquals(PlanEntryStatus.InProgress, decode("\"in_progress\""))
        assertEquals(PlanEntryStatus.Completed, decode("\"completed\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"pending\"", encode(PlanEntryStatus.Pending))
        assertEquals("\"in_progress\"", encode(PlanEntryStatus.InProgress))
        assertEquals("\"completed\"", encode(PlanEntryStatus.Completed))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val status = decode("\"skipped\"")

        assertIs<PlanEntryStatus.Unknown>(status)
        assertEquals("skipped", status.value)
        assertEquals("\"_vendor_status\"", encode(decode("\"_vendor_status\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(PlanEntryStatus.extension("_vendor_status"), PlanEntryStatus.Unknown("_vendor_status"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            PlanEntryStatus.extension("vendor_status")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1PlanEntryStatus.PENDING, PlanEntryStatus.Pending.toV1())
        assertEquals(V1PlanEntryStatus.IN_PROGRESS, PlanEntryStatus.InProgress.toV1())
        assertEquals(V1PlanEntryStatus.COMPLETED, PlanEntryStatus.Completed.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            PlanEntryStatus.Unknown("skipped").toV1()
        }

        assertEquals("v2 PlanEntryStatus variant `skipped` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(PlanEntryStatus.Pending, V1PlanEntryStatus.PENDING.toV2())
        assertEquals(PlanEntryStatus.InProgress, V1PlanEntryStatus.IN_PROGRESS.toV2())
        assertEquals(PlanEntryStatus.Completed, V1PlanEntryStatus.COMPLETED.toV2())
    }

    private fun decode(json: String): PlanEntryStatus =
        ACPJson.decodeFromString(PlanEntryStatus.serializer(), json)

    private fun encode(status: PlanEntryStatus): String =
        ACPJson.encodeToString(PlanEntryStatus.serializer(), status)
}
