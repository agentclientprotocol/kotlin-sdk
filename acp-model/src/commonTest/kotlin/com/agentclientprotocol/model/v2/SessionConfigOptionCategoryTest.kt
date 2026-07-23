@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.SessionConfigOptionCategory as V1SessionConfigOptionCategory

class SessionConfigOptionCategoryTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(SessionConfigOptionCategory.Mode, decode("\"mode\""))
        assertEquals(SessionConfigOptionCategory.Model, decode("\"model\""))
        assertEquals(SessionConfigOptionCategory.ModelConfig, decode("\"model_config\""))
        assertEquals(SessionConfigOptionCategory.ThoughtLevel, decode("\"thought_level\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"mode\"", encode(SessionConfigOptionCategory.Mode))
        assertEquals("\"model\"", encode(SessionConfigOptionCategory.Model))
        assertEquals("\"model_config\"", encode(SessionConfigOptionCategory.ModelConfig))
        assertEquals("\"thought_level\"", encode(SessionConfigOptionCategory.ThoughtLevel))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val category = decode("\"toolchain\"")

        assertIs<SessionConfigOptionCategory.Unknown>(category)
        assertEquals("toolchain", category.value)
        assertEquals("\"_vendor_category\"", encode(decode("\"_vendor_category\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(
            SessionConfigOptionCategory.extension("_vendor_category"),
            SessionConfigOptionCategory.Unknown("_vendor_category"),
        )
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            SessionConfigOptionCategory.extension("vendor_category")
        }
    }

    // v2 <-> v1 conversion (total both ways: v1 is an open string wrapper)

    @Test
    fun `converts all known values to v1`() {
        assertEquals(SessionConfigOptionCategory.Mode.toV1(), V1SessionConfigOptionCategory.MODE)
        assertEquals(SessionConfigOptionCategory.Model.toV1(), V1SessionConfigOptionCategory.MODEL)
        assertEquals(SessionConfigOptionCategory.ModelConfig.toV1(), V1SessionConfigOptionCategory("model_config"))
        assertEquals(SessionConfigOptionCategory.ThoughtLevel.toV1(), V1SessionConfigOptionCategory.THOUGHT_LEVEL)
    }

    @Test
    fun `converts Unknown to v1 without data loss`() {
        assertEquals(
            V1SessionConfigOptionCategory("toolchain"),
            SessionConfigOptionCategory.Unknown("toolchain").toV1(),
        )
    }

    @Test
    fun `converts all well-known v1 values to v2`() {
        assertEquals(SessionConfigOptionCategory.Mode, V1SessionConfigOptionCategory.MODE.toV2())
        assertEquals(SessionConfigOptionCategory.Model, V1SessionConfigOptionCategory.MODEL.toV2())
        assertEquals(SessionConfigOptionCategory.ModelConfig, V1SessionConfigOptionCategory("model_config").toV2())
        assertEquals(SessionConfigOptionCategory.ThoughtLevel, V1SessionConfigOptionCategory.THOUGHT_LEVEL.toV2())
    }

    @Test
    fun `converts custom v1 value to v2 Unknown`() {
        assertEquals(
            SessionConfigOptionCategory.Unknown("toolchain"),
            V1SessionConfigOptionCategory("toolchain").toV2(),
        )
    }

    private fun decode(json: String): SessionConfigOptionCategory =
        ACPJson.decodeFromString(SessionConfigOptionCategory.serializer(), json)

    private fun encode(category: SessionConfigOptionCategory): String =
        ACPJson.encodeToString(SessionConfigOptionCategory.serializer(), category)
}
