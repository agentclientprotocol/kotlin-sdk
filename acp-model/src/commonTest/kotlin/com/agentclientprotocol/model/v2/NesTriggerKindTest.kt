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
import com.agentclientprotocol.model.NesTriggerKind as V1NesTriggerKind

class NesTriggerKindTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(NesTriggerKind.Automatic, decode("\"automatic\""))
        assertEquals(NesTriggerKind.Diagnostic, decode("\"diagnostic\""))
        assertEquals(NesTriggerKind.Manual, decode("\"manual\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"automatic\"", encode(NesTriggerKind.Automatic))
        assertEquals("\"diagnostic\"", encode(NesTriggerKind.Diagnostic))
        assertEquals("\"manual\"", encode(NesTriggerKind.Manual))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val kind = decode("\"scheduled\"")

        assertIs<NesTriggerKind.Unknown>(kind)
        assertEquals("scheduled", kind.value)
        assertEquals("\"_vendor_trigger\"", encode(decode("\"_vendor_trigger\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(NesTriggerKind.extension("_vendor_trigger"), NesTriggerKind.Unknown("_vendor_trigger"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            NesTriggerKind.extension("vendor_trigger")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1NesTriggerKind.AUTOMATIC, NesTriggerKind.Automatic.toV1())
        assertEquals(V1NesTriggerKind.DIAGNOSTIC, NesTriggerKind.Diagnostic.toV1())
        assertEquals(V1NesTriggerKind.MANUAL, NesTriggerKind.Manual.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            NesTriggerKind.Unknown("scheduled").toV1()
        }

        assertEquals("v2 NesTriggerKind variant `scheduled` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(NesTriggerKind.Automatic, V1NesTriggerKind.AUTOMATIC.toV2())
        assertEquals(NesTriggerKind.Diagnostic, V1NesTriggerKind.DIAGNOSTIC.toV2())
        assertEquals(NesTriggerKind.Manual, V1NesTriggerKind.MANUAL.toV2())
    }

    private fun decode(json: String): NesTriggerKind =
        ACPJson.decodeFromString(NesTriggerKind.serializer(), json)

    private fun encode(kind: NesTriggerKind): String =
        ACPJson.encodeToString(NesTriggerKind.serializer(), kind)
}
