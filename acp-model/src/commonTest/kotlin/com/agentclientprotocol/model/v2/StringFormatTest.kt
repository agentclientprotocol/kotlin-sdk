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
import com.agentclientprotocol.model.StringFormat as V1StringFormat

class StringFormatTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(StringFormat.Email, decode("\"email\""))
        assertEquals(StringFormat.Uri, decode("\"uri\""))
        assertEquals(StringFormat.Date, decode("\"date\""))
        assertEquals(StringFormat.DateTime, decode("\"date-time\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"email\"", encode(StringFormat.Email))
        assertEquals("\"uri\"", encode(StringFormat.Uri))
        assertEquals("\"date\"", encode(StringFormat.Date))
        assertEquals("\"date-time\"", encode(StringFormat.DateTime))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val format = decode("\"hostname\"")

        assertIs<StringFormat.Unknown>(format)
        assertEquals("hostname", format.value)
        assertEquals("\"_vendor_format\"", encode(decode("\"_vendor_format\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(StringFormat.extension("_vendor_format"), StringFormat.Unknown("_vendor_format"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            StringFormat.extension("vendor_format")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1StringFormat.EMAIL, StringFormat.Email.toV1())
        assertEquals(V1StringFormat.URI, StringFormat.Uri.toV1())
        assertEquals(V1StringFormat.DATE, StringFormat.Date.toV1())
        assertEquals(V1StringFormat.DATE_TIME, StringFormat.DateTime.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            StringFormat.Unknown("hostname").toV1()
        }

        assertEquals("v2 StringFormat variant `hostname` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(StringFormat.Email, V1StringFormat.EMAIL.toV2())
        assertEquals(StringFormat.Uri, V1StringFormat.URI.toV2())
        assertEquals(StringFormat.Date, V1StringFormat.DATE.toV2())
        assertEquals(StringFormat.DateTime, V1StringFormat.DATE_TIME.toV2())
    }

    private fun decode(json: String): StringFormat =
        ACPJson.decodeFromString(StringFormat.serializer(), json)

    private fun encode(format: StringFormat): String =
        ACPJson.encodeToString(StringFormat.serializer(), format)
}
