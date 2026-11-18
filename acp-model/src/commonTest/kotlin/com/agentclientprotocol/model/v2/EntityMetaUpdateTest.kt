@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityMetaUpdateTest {
    @Test
    fun `independent writers retain omitted keys and replace their own values`() {
        assertSequence(
            """{"acme.dev/progress":1,"proxy.dev/route":"edge"}""" to
                """{"acme.dev/progress":1,"proxy.dev/route":"edge"}""",
            """{"acme.dev/progress":2}""" to
                """{"acme.dev/progress":2,"proxy.dev/route":"edge"}""",
            """{"proxy.dev/route":"local"}""" to
                """{"acme.dev/progress":2,"proxy.dev/route":"local"}""",
        )
    }

    @Test
    fun `omitted metadata and empty objects leave stored keys unchanged`() {
        assertSequence(
            """{"a":1,"b":2}""" to """{"a":1,"b":2}""",
            null to """{"a":1,"b":2}""",
            "{}" to """{"a":1,"b":2}""",
        )
    }

    @Test
    fun `removals consume tombstones including absent keys and the last key`() {
        assertSequence(
            """{"a":1,"b":2}""" to """{"a":1,"b":2}""",
            """{"a":null,"missing":null}""" to """{"b":2}""",
            """{"b":null}""" to "{}",
        )
    }

    @Test
    fun `first upsert consumes removal-only metadata`() {
        assertFirstUpdate("""{"gone":null}""", "{}")
    }

    @Test
    fun `first upsert consumes removals alongside values`() {
        assertFirstUpdate("""{"gone":null,"keep":1}""", """{"keep":1}""")
    }

    @Test
    fun `whole clear survives omission and later objects start empty`() {
        assertSequence(
            """{"stale":1}""" to """{"stale":1}""",
            "null" to "null",
            null to "null",
            """{"gone":null,"fresh":2}""" to """{"fresh":2}""",
        )
        assertSequence("null" to "null", "{}" to "{}")
    }

    @Test
    fun `nested objects and arrays replace whole values and retain nested null data`() {
        assertSequence(
            """{"a":{"x":1,"y":2},"list":[1,2],"keep":true}""" to
                """{"a":{"x":1,"y":2},"list":[1,2],"keep":true}""",
            """{"a":{"x":3,"value":null},"list":[null,3]}""" to
                """{"a":{"x":3,"value":null},"list":[null,3],"keep":true}""",
        )
        assertSequence("""{"a":{"value":null}}""" to """{"a":{"value":null}}""")
    }

    @Test
    fun `replay removes stale keys with tombstones or a clear before current metadata`() {
        assertSequence(
            """{"stale":1,"current":1}""" to """{"stale":1,"current":1}""",
            """{"stale":null,"current":2}""" to """{"current":2}""",
        )
        assertSequence(
            """{"stale":1,"current":1}""" to """{"stale":1,"current":1}""",
            "null" to "null",
            """{"current":2}""" to """{"current":2}""",
        )
    }

    @Test
    fun `wire patches preserve tombstones while applying them leaves both inputs intact`() {
        val patch = """{"gone":null,"keep":{"value":null}}"""
        val toolJson = """{"toolCallId":"tc_1","_meta":$patch}"""
        val terminalJson = """{"terminalId":"term_1","_meta":$patch}"""
        val toolPatch = ACPJson.decodeFromString(ToolCallUpdate.serializer(), toolJson)
        val terminalPatch = ACPJson.decodeFromString(TerminalUpdate.serializer(), terminalJson)
        val oldMeta = metadata("""{"gone":1,"other":2}""")
        val tool = ToolCallUpdate(ToolCallId("tc_1"), _meta = oldMeta)
        val terminal = TerminalUpdate(TerminalId("term_1"), _meta = oldMeta)
        val expected = metadata("""{"other":2,"keep":{"value":null}}""")

        assertEquals(expected, tool.applyUpdate(toolPatch)._meta)
        assertEquals(expected, terminal.applyUpdate(terminalPatch)._meta)
        assertEquals(metadata("""{"gone":1,"other":2}"""), tool._meta)
        assertEquals(metadata("""{"gone":1,"other":2}"""), terminal._meta)
        assertEquals(toolJson, ACPJson.encodeToString(ToolCallUpdate.serializer(), toolPatch))
        assertEquals(terminalJson, ACPJson.encodeToString(TerminalUpdate.serializer(), terminalPatch))
    }

    @Test
    fun `non-object metadata keeps the existing permissive replacement behavior`() {
        assertSequence("42" to "42", "[1,2]" to "[1,2]", """{"a":1}""" to """{"a":1}""")
    }

    private fun assertFirstUpdate(patch: String, expected: String) {
        val toolPatch = ACPJson.decodeFromString(
            ToolCallUpdate.serializer(), """{"toolCallId":"tc_1","title":"Read config","_meta":$patch}""",
        )
        val terminalPatch = ACPJson.decodeFromString(
            TerminalUpdate.serializer(), """{"terminalId":"term_1","command":"cat config","_meta":$patch}""",
        )

        assertEquals(
            toolPatch.copy(_meta = metadata(expected)),
            ToolCallUpdate(toolPatch.toolCallId).applyUpdate(toolPatch),
        )
        assertEquals(
            terminalPatch.copy(_meta = metadata(expected)),
            TerminalUpdate(terminalPatch.terminalId).applyUpdate(terminalPatch),
        )
    }

    private fun assertSequence(vararg steps: Pair<String?, String?>) {
        val toolBase = ToolCallUpdate(
            toolCallId = ToolCallId("tc_1"),
            title = MaybeUndefined.Value("Read config"),
            name = MaybeUndefined.Value("read_file"),
            kind = MaybeUndefined.Value(ToolKind.Read),
            status = MaybeUndefined.Value(ToolCallStatus.InProgress),
            content = MaybeUndefined.Value(listOf(ToolCallContent.Content(ContentBlock.Text("old")))),
            locations = MaybeUndefined.Value(listOf(com.agentclientprotocol.model.ToolCallLocation("/config"))),
            rawInput = metadata("""{"nested":null}"""),
            rawOutput = metadata("""{"result":true}"""),
        )
        val terminalBase = TerminalUpdate(
            terminalId = TerminalId("term_1"),
            command = MaybeUndefined.Value("cat config"),
            cwd = MaybeUndefined.Value("/project"),
            output = MaybeUndefined.Value(TerminalOutput("b2xk", ACPJson.parseToJsonElement("""{"data":null}"""))),
            exitStatus = MaybeUndefined.Value(TerminalExitStatus(exitCode = 0u)),
        )
        var tool = ToolCallUpdate(toolBase.toolCallId).applyUpdate(toolBase)
        var terminal = TerminalUpdate(terminalBase.terminalId).applyUpdate(terminalBase)

        for ((patch, expected) in steps) {
            val field = if (patch == null) "" else ",\"_meta\":$patch"
            tool = tool.applyUpdate(ACPJson.decodeFromString(
                ToolCallUpdate.serializer(), """{"toolCallId":"tc_1"$field}""",
            ))
            terminal = terminal.applyUpdate(ACPJson.decodeFromString(
                TerminalUpdate.serializer(), """{"terminalId":"term_1"$field}""",
            ))

            assertEquals(toolBase.copy(_meta = metadata(expected)), tool, "tool metadata patch $patch")
            assertEquals(terminalBase.copy(_meta = metadata(expected)), terminal, "terminal metadata patch $patch")
        }
    }

    private fun metadata(json: String?): MaybeUndefined<JsonElement> = when (json) {
        null -> MaybeUndefined.Undefined
        else -> when (val value = ACPJson.parseToJsonElement(json)) {
            JsonNull -> MaybeUndefined.Null
            else -> MaybeUndefined.Value(value)
        }
    }
}
