@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AvailableCommandsUpdateTest {

    private val update = AvailableCommandsUpdate(
        availableCommands = listOf(
            AvailableCommand(
                name = "create_plan",
                description = "Draft an implementation plan",
                input = AvailableCommandInput.Text(hint = "goal"),
            ),
            AvailableCommand(name = "research_codebase", description = "Search the codebase"),
        ),
    )

    private val updateJson =
        """{"availableCommands":[""" +
            """{"name":"create_plan","description":"Draft an implementation plan",""" +
            """"input":{"type":"text","hint":"goal"}},""" +
            """{"name":"research_codebase","description":"Search the codebase"}]}"""

    @Test
    fun `decodes commands with and without input`() {
        assertEquals(update, decode(updateJson))
    }

    @Test
    fun `encodes the full command set`() {
        assertEquals(updateJson, encode(update))
    }

    @Test
    fun `preserves unknown command input types as raw json`() {
        val json = """{"availableCommands":[{"name":"c","description":"d","input":{"type":"_vendor","x":1}}]}"""

        val decoded = decode(json)
        val input = decoded.availableCommands.single().input
        assertIs<AvailableCommandInput.Unknown>(input)
        assertEquals("_vendor", input.type)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `decodes an empty command set`() {
        assertEquals(AvailableCommandsUpdate(availableCommands = emptyList()), decode("""{"availableCommands":[]}"""))
    }

    @Test
    fun `requires the command list`() {
        assertFailsWith<SerializationException> { decode("""{}""") }
    }

    @Test
    fun `requires a name and description on each command`() {
        assertFailsWith<SerializationException> {
            decode("""{"availableCommands":[{"name":"create_plan"}]}""")
        }
    }

    private fun decode(json: String): AvailableCommandsUpdate =
        ACPJson.decodeFromString(AvailableCommandsUpdate.serializer(), json)

    private fun encode(update: AvailableCommandsUpdate): String =
        ACPJson.encodeToString(AvailableCommandsUpdate.serializer(), update)
}
