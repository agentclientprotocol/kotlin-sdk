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
import com.agentclientprotocol.model.NesDiagnosticSeverity as V1NesDiagnosticSeverity

class NesDiagnosticSeverityTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(NesDiagnosticSeverity.Error, decode("\"error\""))
        assertEquals(NesDiagnosticSeverity.Warning, decode("\"warning\""))
        assertEquals(NesDiagnosticSeverity.Information, decode("\"information\""))
        assertEquals(NesDiagnosticSeverity.Hint, decode("\"hint\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"error\"", encode(NesDiagnosticSeverity.Error))
        assertEquals("\"warning\"", encode(NesDiagnosticSeverity.Warning))
        assertEquals("\"information\"", encode(NesDiagnosticSeverity.Information))
        assertEquals("\"hint\"", encode(NesDiagnosticSeverity.Hint))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val severity = decode("\"deprecation\"")

        assertIs<NesDiagnosticSeverity.Unknown>(severity)
        assertEquals("deprecation", severity.value)
        assertEquals("\"_vendor_severity\"", encode(decode("\"_vendor_severity\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(
            NesDiagnosticSeverity.extension("_vendor_severity"),
            NesDiagnosticSeverity.Unknown("_vendor_severity"),
        )
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            NesDiagnosticSeverity.extension("vendor_severity")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1NesDiagnosticSeverity.ERROR, NesDiagnosticSeverity.Error.toV1())
        assertEquals(V1NesDiagnosticSeverity.WARNING, NesDiagnosticSeverity.Warning.toV1())
        assertEquals(V1NesDiagnosticSeverity.INFORMATION, NesDiagnosticSeverity.Information.toV1())
        assertEquals(V1NesDiagnosticSeverity.HINT, NesDiagnosticSeverity.Hint.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            NesDiagnosticSeverity.Unknown("deprecation").toV1()
        }

        assertEquals("v2 NesDiagnosticSeverity variant `deprecation` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(NesDiagnosticSeverity.Error, V1NesDiagnosticSeverity.ERROR.toV2())
        assertEquals(NesDiagnosticSeverity.Warning, V1NesDiagnosticSeverity.WARNING.toV2())
        assertEquals(NesDiagnosticSeverity.Information, V1NesDiagnosticSeverity.INFORMATION.toV2())
        assertEquals(NesDiagnosticSeverity.Hint, V1NesDiagnosticSeverity.HINT.toV2())
    }

    private fun decode(json: String): NesDiagnosticSeverity =
        ACPJson.decodeFromString(NesDiagnosticSeverity.serializer(), json)

    private fun encode(severity: NesDiagnosticSeverity): String =
        ACPJson.encodeToString(NesDiagnosticSeverity.serializer(), severity)
}
