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
import com.agentclientprotocol.model.PlanEntryPriority as V1PlanEntryPriority

class PlanEntryPriorityTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(PlanEntryPriority.High, decode("\"high\""))
        assertEquals(PlanEntryPriority.Medium, decode("\"medium\""))
        assertEquals(PlanEntryPriority.Low, decode("\"low\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"high\"", encode(PlanEntryPriority.High))
        assertEquals("\"medium\"", encode(PlanEntryPriority.Medium))
        assertEquals("\"low\"", encode(PlanEntryPriority.Low))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val priority = decode("\"critical\"")

        assertIs<PlanEntryPriority.Unknown>(priority)
        assertEquals("critical", priority.value)
        assertEquals("\"_vendor_priority\"", encode(decode("\"_vendor_priority\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(PlanEntryPriority.extension("_vendor_priority"), PlanEntryPriority.Unknown("_vendor_priority"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            PlanEntryPriority.extension("vendor_priority")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1PlanEntryPriority.HIGH, PlanEntryPriority.High.toV1())
        assertEquals(V1PlanEntryPriority.MEDIUM, PlanEntryPriority.Medium.toV1())
        assertEquals(V1PlanEntryPriority.LOW, PlanEntryPriority.Low.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            PlanEntryPriority.Unknown("critical").toV1()
        }

        assertEquals("v2 PlanEntryPriority variant `critical` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(PlanEntryPriority.High, V1PlanEntryPriority.HIGH.toV2())
        assertEquals(PlanEntryPriority.Medium, V1PlanEntryPriority.MEDIUM.toV2())
        assertEquals(PlanEntryPriority.Low, V1PlanEntryPriority.LOW.toV2())
    }

    private fun decode(json: String): PlanEntryPriority =
        ACPJson.decodeFromString(PlanEntryPriority.serializer(), json)

    private fun encode(priority: PlanEntryPriority): String =
        ACPJson.encodeToString(PlanEntryPriority.serializer(), priority)
}
