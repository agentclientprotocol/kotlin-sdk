@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.EnumOption
import com.agentclientprotocol.model.TitledMultiSelectItems
import com.agentclientprotocol.model.UntitledMultiSelectItems
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.ElicitationPropertySchema as V1ElicitationPropertySchema
import com.agentclientprotocol.model.MultiSelectItems as V1MultiSelectItems

class ElicitationPropertySchemaTest {

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(
            ElicitationPropertySchema.StringProperty(title = "Name", format = StringFormat.Email),
            decode("""{"type":"string","title":"Name","format":"email"}"""),
        )
        assertEquals(
            ElicitationPropertySchema.IntegerProperty(minimum = 0, maximum = 10),
            decode("""{"type":"integer","minimum":0,"maximum":10}"""),
        )
        assertEquals(
            ElicitationPropertySchema.ArrayProperty(
                items = MultiSelectItems.StringItems(values = listOf("a", "b")),
            ),
            decode("""{"type":"array","items":{"type":"string","enum":["a","b"]}}"""),
        )
    }

    @Test
    fun `encodes with leading discriminator`() {
        assertEquals(
            """{"type":"boolean","title":"Verbose","default":true}""",
            encode(ElicitationPropertySchema.BooleanProperty(title = "Verbose", default = true)),
        )
    }

    @Test
    fun `titled multi-select items are untagged and round-trip`() {
        val schema = ElicitationPropertySchema.ArrayProperty(
            items = MultiSelectItems.Titled(options = listOf(EnumOption(value = "a", title = "Option A"))),
        )

        assertEquals(
            """{"type":"array","items":{"anyOf":[{"const":"a","title":"Option A"}]}}""",
            encode(schema),
        )
        assertEquals(schema, decode(encode(schema)))
    }

    @Test
    fun `unknown multi-select items type is preserved`() {
        val schema = decode("""{"type":"array","items":{"type":"file","extensions":[".kt"]}}""")

        assertIs<ElicitationPropertySchema.ArrayProperty>(schema)
        val items = schema.items
        assertIs<MultiSelectItems.Unknown>(items)
        assertEquals("file", items.type)
        assertEquals(schema, decode(encode(schema)))
    }

    @Test
    fun `multi-select items without type or anyOf fail`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"array","items":{"enum":["a"],"unrelated":true}}""".replace(""""enum"""", """"values""""))
        }
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown property type as Unknown preserving the full payload`() {
        val json = """{"type":"date_range","from":"2026-01-01","to":"2026-12-31"}"""

        val schema = decode(json)

        assertIs<ElicitationPropertySchema.Unknown>(schema)
        assertEquals("date_range", schema.type)
        assertEquals(json, encode(schema))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"title":"Name"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"array","title":"Tags"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1`() {
        assertEquals(
            V1ElicitationPropertySchema.StringProperty(
                title = "Name",
                format = com.agentclientprotocol.model.StringFormat.EMAIL,
            ),
            ElicitationPropertySchema.StringProperty(title = "Name", format = StringFormat.Email).toV1(),
        )
        assertEquals(
            V1ElicitationPropertySchema.ArrayProperty(
                items = V1MultiSelectItems.Untitled(UntitledMultiSelectItems(values = listOf("a"))),
            ),
            ElicitationPropertySchema.ArrayProperty(
                items = MultiSelectItems.StringItems(values = listOf("a")),
            ).toV1(),
        )
    }

    @Test
    fun `converting unknown format to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ElicitationPropertySchema.StringProperty(format = StringFormat.Unknown("hostname")).toV1()
        }

        assertEquals("v2 StringFormat variant `hostname` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ElicitationPropertySchema.Unknown("date_range", buildJsonObject {}).toV1()
        }

        assertEquals("v2 ElicitationPropertySchema variant `date_range` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 variants to v2`() {
        assertEquals(
            ElicitationPropertySchema.NumberProperty(minimum = 0.5, maximum = 1.5),
            V1ElicitationPropertySchema.NumberProperty(minimum = 0.5, maximum = 1.5).toV2(),
        )
        assertEquals(
            ElicitationPropertySchema.ArrayProperty(
                items = MultiSelectItems.Titled(options = listOf(EnumOption(value = "a", title = "A"))),
            ),
            V1ElicitationPropertySchema.ArrayProperty(
                items = V1MultiSelectItems.Titled(
                    TitledMultiSelectItems(options = listOf(EnumOption(value = "a", title = "A"))),
                ),
            ).toV2(),
        )
    }

    private fun decode(json: String): ElicitationPropertySchema =
        ACPJson.decodeFromString(ElicitationPropertySchema.serializer(), json)

    private fun encode(schema: ElicitationPropertySchema): String =
        ACPJson.encodeToString(ElicitationPropertySchema.serializer(), schema)
}
