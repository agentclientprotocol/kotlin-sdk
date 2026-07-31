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
import com.agentclientprotocol.model.PermissionOptionKind as V1PermissionOptionKind

class PermissionOptionKindTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(PermissionOptionKind.AllowOnce, decode("\"allow_once\""))
        assertEquals(PermissionOptionKind.AllowAlways, decode("\"allow_always\""))
        assertEquals(PermissionOptionKind.RejectOnce, decode("\"reject_once\""))
        assertEquals(PermissionOptionKind.RejectAlways, decode("\"reject_always\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"allow_once\"", encode(PermissionOptionKind.AllowOnce))
        assertEquals("\"allow_always\"", encode(PermissionOptionKind.AllowAlways))
        assertEquals("\"reject_once\"", encode(PermissionOptionKind.RejectOnce))
        assertEquals("\"reject_always\"", encode(PermissionOptionKind.RejectAlways))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val kind = decode("\"allow_scoped\"")

        assertIs<PermissionOptionKind.Unknown>(kind)
        assertEquals("allow_scoped", kind.value)
        assertEquals("\"_vendor_kind\"", encode(decode("\"_vendor_kind\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(PermissionOptionKind.extension("_vendor_kind"), PermissionOptionKind.Unknown("_vendor_kind"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            PermissionOptionKind.extension("vendor_kind")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.AllowOnce.toV1())
        assertEquals(V1PermissionOptionKind.ALLOW_ALWAYS, PermissionOptionKind.AllowAlways.toV1())
        assertEquals(V1PermissionOptionKind.REJECT_ONCE, PermissionOptionKind.RejectOnce.toV1())
        assertEquals(V1PermissionOptionKind.REJECT_ALWAYS, PermissionOptionKind.RejectAlways.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            PermissionOptionKind.Unknown("allow_scoped").toV1()
        }

        assertEquals("v2 PermissionOptionKind variant `allow_scoped` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(PermissionOptionKind.AllowOnce, V1PermissionOptionKind.ALLOW_ONCE.toV2())
        assertEquals(PermissionOptionKind.AllowAlways, V1PermissionOptionKind.ALLOW_ALWAYS.toV2())
        assertEquals(PermissionOptionKind.RejectOnce, V1PermissionOptionKind.REJECT_ONCE.toV2())
        assertEquals(PermissionOptionKind.RejectAlways, V1PermissionOptionKind.REJECT_ALWAYS.toV2())
    }

    private fun decode(json: String): PermissionOptionKind =
        ACPJson.decodeFromString(PermissionOptionKind.serializer(), json)

    private fun encode(kind: PermissionOptionKind): String =
        ACPJson.encodeToString(PermissionOptionKind.serializer(), kind)
}
