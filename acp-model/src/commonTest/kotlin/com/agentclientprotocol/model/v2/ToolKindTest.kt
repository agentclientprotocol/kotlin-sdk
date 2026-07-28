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
import com.agentclientprotocol.model.ToolKind as V1ToolKind

class ToolKindTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(ToolKind.Read, decode("\"read\""))
        assertEquals(ToolKind.Edit, decode("\"edit\""))
        assertEquals(ToolKind.Delete, decode("\"delete\""))
        assertEquals(ToolKind.Move, decode("\"move\""))
        assertEquals(ToolKind.Search, decode("\"search\""))
        assertEquals(ToolKind.Execute, decode("\"execute\""))
        assertEquals(ToolKind.Think, decode("\"think\""))
        assertEquals(ToolKind.Fetch, decode("\"fetch\""))
        assertEquals(ToolKind.SwitchMode, decode("\"switch_mode\""))
        assertEquals(ToolKind.Other, decode("\"other\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"read\"", encode(ToolKind.Read))
        assertEquals("\"edit\"", encode(ToolKind.Edit))
        assertEquals("\"delete\"", encode(ToolKind.Delete))
        assertEquals("\"move\"", encode(ToolKind.Move))
        assertEquals("\"search\"", encode(ToolKind.Search))
        assertEquals("\"execute\"", encode(ToolKind.Execute))
        assertEquals("\"think\"", encode(ToolKind.Think))
        assertEquals("\"fetch\"", encode(ToolKind.Fetch))
        assertEquals("\"switch_mode\"", encode(ToolKind.SwitchMode))
        assertEquals("\"other\"", encode(ToolKind.Other))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val kind = decode("\"compress\"")

        assertIs<ToolKind.Unknown>(kind)
        assertEquals("compress", kind.value)
        assertEquals("\"_vendor_kind\"", encode(decode("\"_vendor_kind\"")))
    }

    @Test
    fun `unknown value 'other' decodes to the known Other variant not Unknown`() {
        assertEquals(ToolKind.Other, decode("\"other\""))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(ToolKind.extension("_vendor_kind"), ToolKind.Unknown("_vendor_kind"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            ToolKind.extension("vendor_kind")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1ToolKind.READ, ToolKind.Read.toV1())
        assertEquals(V1ToolKind.EDIT, ToolKind.Edit.toV1())
        assertEquals(V1ToolKind.DELETE, ToolKind.Delete.toV1())
        assertEquals(V1ToolKind.MOVE, ToolKind.Move.toV1())
        assertEquals(V1ToolKind.SEARCH, ToolKind.Search.toV1())
        assertEquals(V1ToolKind.EXECUTE, ToolKind.Execute.toV1())
        assertEquals(V1ToolKind.THINK, ToolKind.Think.toV1())
        assertEquals(V1ToolKind.FETCH, ToolKind.Fetch.toV1())
        assertEquals(V1ToolKind.SWITCH_MODE, ToolKind.SwitchMode.toV1())
        assertEquals(V1ToolKind.OTHER, ToolKind.Other.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ToolKind.Unknown("compress").toV1()
        }

        assertEquals("v2 ToolKind variant `compress` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(ToolKind.Read, V1ToolKind.READ.toV2())
        assertEquals(ToolKind.Edit, V1ToolKind.EDIT.toV2())
        assertEquals(ToolKind.Delete, V1ToolKind.DELETE.toV2())
        assertEquals(ToolKind.Move, V1ToolKind.MOVE.toV2())
        assertEquals(ToolKind.Search, V1ToolKind.SEARCH.toV2())
        assertEquals(ToolKind.Execute, V1ToolKind.EXECUTE.toV2())
        assertEquals(ToolKind.Think, V1ToolKind.THINK.toV2())
        assertEquals(ToolKind.Fetch, V1ToolKind.FETCH.toV2())
        assertEquals(ToolKind.SwitchMode, V1ToolKind.SWITCH_MODE.toV2())
        assertEquals(ToolKind.Other, V1ToolKind.OTHER.toV2())
    }

    private fun decode(json: String): ToolKind =
        ACPJson.decodeFromString(ToolKind.serializer(), json)

    private fun encode(kind: ToolKind): String =
        ACPJson.encodeToString(ToolKind.serializer(), kind)
}
