@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigGroupId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.SessionConfigOption as V1SessionConfigOption
import com.agentclientprotocol.model.SessionConfigSelectGroup as V1SessionConfigSelectGroup
import com.agentclientprotocol.model.SessionConfigSelectOptions as V1SessionConfigSelectOptions

class SessionConfigOptionTest {

    private val fastOption = SessionConfigSelectOption(value = SessionConfigValueId("fast"), name = "Fast")
    private val selectOption = SessionConfigOption(
        configId = SessionConfigId("model"),
        name = "Model",
        category = SessionConfigOptionCategory.Model,
        kind = SessionConfigKind.Select(
            currentValue = SessionConfigValueId("fast"),
            options = SessionConfigSelectOptions.Ungrouped(listOf(fastOption)),
        ),
    )

    // Known kinds

    @Test
    fun `decodes select and boolean kinds`() {
        assertEquals(
            selectOption,
            decode(
                """{"type":"select","configId":"model","name":"Model","category":"model",""" +
                    """"currentValue":"fast","options":[{"value":"fast","name":"Fast"}]}""",
            ),
        )
        assertEquals(
            SessionConfigOption(
                configId = SessionConfigId("verbose"),
                name = "Verbose",
                kind = SessionConfigKind.Boolean(currentValue = true),
            ),
            decode("""{"type":"boolean","configId":"verbose","name":"Verbose","currentValue":true}"""),
        )
    }

    @Test
    fun `encodes with flattened kind and leading discriminator`() {
        assertEquals(
            """{"type":"select","configId":"model","name":"Model","category":"model",""" +
                """"currentValue":"fast","options":[{"value":"fast","name":"Fast"}]}""",
            encode(selectOption),
        )
    }

    @Test
    fun `grouped options round-trip and are detected by groupId`() {
        val option = SessionConfigOption(
            configId = SessionConfigId("model"),
            name = "Model",
            kind = SessionConfigKind.Select(
                currentValue = SessionConfigValueId("fast"),
                options = SessionConfigSelectOptions.Grouped(
                    listOf(
                        SessionConfigSelectGroup(
                            groupId = SessionConfigGroupId("speed"),
                            name = "Speed",
                            options = listOf(fastOption),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(option, decode(encode(option)))
    }

    // Unknown kinds (forward compatibility)

    @Test
    fun `decodes unknown kind preserving its extra fields`() {
        val option = decode(
            """{"type":"slider","configId":"temp","name":"Temperature","min":0,"max":2,"currentValue":0.7}""",
        )

        val kind = option.kind
        assertIs<SessionConfigKind.Unknown>(kind)
        assertEquals("slider", kind.type)
        assertEquals(SessionConfigId("temp"), option.configId)
        assertEquals(option, decode(encode(option)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"configId":"model","name":"Model","currentValue":"fast"}""")
        }
    }

    @Test
    fun `select without options fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"select","configId":"model","name":"Model","currentValue":"fast"}""")
        }
    }

    @Test
    fun `unknown kind still requires configId and name`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"slider","name":"Temperature"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts select to v1 mapping configId to id`() {
        assertEquals(
            V1SessionConfigOption.Select(
                id = SessionConfigId("model"),
                name = "Model",
                category = com.agentclientprotocol.model.SessionConfigOptionCategory.MODEL,
                currentValue = SessionConfigValueId("fast"),
                options = V1SessionConfigSelectOptions.Flat(listOf(fastOption)),
            ),
            selectOption.toV1(),
        )
    }

    @Test
    fun `converting unknown kind to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            SessionConfigOption(
                configId = SessionConfigId("temp"),
                name = "Temperature",
                kind = SessionConfigKind.Unknown("slider", kotlinx.serialization.json.buildJsonObject {}),
            ).toV1()
        }

        assertEquals("v2 SessionConfigKind variant `slider` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts v1 options to v2 mapping group to groupId`() {
        val v2 = V1SessionConfigOption.Select(
            id = SessionConfigId("model"),
            name = "Model",
            currentValue = SessionConfigValueId("fast"),
            options = V1SessionConfigSelectOptions.Grouped(
                listOf(V1SessionConfigSelectGroup(group = SessionConfigGroupId("speed"), options = listOf(fastOption))),
            ),
        ).toV2()

        assertEquals(
            SessionConfigKind.Select(
                currentValue = SessionConfigValueId("fast"),
                options = SessionConfigSelectOptions.Grouped(
                    listOf(
                        SessionConfigSelectGroup(
                            groupId = SessionConfigGroupId("speed"),
                            name = "",
                            options = listOf(fastOption),
                        ),
                    ),
                ),
            ),
            v2.kind,
        )
    }

    @Test
    fun `converts v1 boolean option to v2`() {
        assertEquals(
            SessionConfigOption(
                configId = SessionConfigId("verbose"),
                name = "Verbose",
                kind = SessionConfigKind.Boolean(currentValue = true),
            ),
            V1SessionConfigOption.BooleanOption(
                id = SessionConfigId("verbose"),
                name = "Verbose",
                currentValue = true,
            ).toV2(),
        )
    }

    private fun decode(json: String): SessionConfigOption =
        ACPJson.decodeFromString(SessionConfigOption.serializer(), json)

    private fun encode(option: SessionConfigOption): String =
        ACPJson.encodeToString(SessionConfigOption.serializer(), option)
}
