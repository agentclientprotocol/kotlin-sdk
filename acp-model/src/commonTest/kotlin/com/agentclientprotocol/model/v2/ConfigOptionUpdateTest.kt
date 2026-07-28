@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigOptionUpdateTest {

    private val update = ConfigOptionUpdate(
        configOptions = listOf(
            SessionConfigOption(
                configId = SessionConfigId("model"),
                name = "Model",
                category = SessionConfigOptionCategory.Model,
                kind = SessionConfigKind.Select(
                    currentValue = SessionConfigValueId("fast"),
                    options = SessionConfigSelectOptions.Ungrouped(
                        listOf(SessionConfigSelectOption(value = SessionConfigValueId("fast"), name = "Fast")),
                    ),
                ),
            ),
            SessionConfigOption(
                configId = SessionConfigId("verbose"),
                name = "Verbose",
                kind = SessionConfigKind.Boolean(currentValue = true),
            ),
        ),
    )

    private val updateJson =
        """{"configOptions":[""" +
            """{"type":"select","configId":"model","name":"Model","category":"model",""" +
            """"currentValue":"fast","options":[{"value":"fast","name":"Fast"}]},""" +
            """{"type":"boolean","configId":"verbose","name":"Verbose","currentValue":true}]}"""

    @Test
    fun `decodes the full set of config options`() {
        assertEquals(update, decode(updateJson))
    }

    @Test
    fun `encodes the full set of config options`() {
        assertEquals(updateJson, encode(update))
    }

    @Test
    fun `decodes an empty option set`() {
        assertEquals(ConfigOptionUpdate(configOptions = emptyList()), decode("""{"configOptions":[]}"""))
    }

    @Test
    fun `requires the option list`() {
        assertFailsWith<SerializationException> { decode("""{}""") }
    }

    private fun decode(json: String): ConfigOptionUpdate =
        ACPJson.decodeFromString(ConfigOptionUpdate.serializer(), json)

    private fun encode(update: ConfigOptionUpdate): String =
        ACPJson.encodeToString(ConfigOptionUpdate.serializer(), update)
}
