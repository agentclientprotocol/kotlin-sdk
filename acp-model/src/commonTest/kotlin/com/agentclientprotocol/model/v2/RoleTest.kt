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
import com.agentclientprotocol.model.Role as V1Role

class RoleTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(Role.Assistant, decode("\"assistant\""))
        assertEquals(Role.User, decode("\"user\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"assistant\"", encode(Role.Assistant))
        assertEquals("\"user\"", encode(Role.User))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val role = decode("\"system\"")

        assertIs<Role.Unknown>(role)
        assertEquals("system", role.value)
        assertEquals("\"_vendor_role\"", encode(decode("\"_vendor_role\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(Role.extension("_vendor_role"), Role.Unknown("_vendor_role"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            Role.extension("vendor_role")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1Role.ASSISTANT, Role.Assistant.toV1())
        assertEquals(V1Role.USER, Role.User.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            Role.Unknown("system").toV1()
        }

        assertEquals("v2 Role variant `system` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(Role.Assistant, V1Role.ASSISTANT.toV2())
        assertEquals(Role.User, V1Role.USER.toV2())
    }

    private fun decode(json: String): Role =
        ACPJson.decodeFromString(Role.serializer(), json)

    private fun encode(role: Role): String =
        ACPJson.encodeToString(Role.serializer(), role)
}
