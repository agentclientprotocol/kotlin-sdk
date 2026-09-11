@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RequestPermissionSubjectTest {
    @Test
    fun `command requires no association with an existing tool call or terminal`() {
        val json = """{"type":"command","command":"cargo test","cwd":"/home/user/project"}"""
        val subject = RequestPermissionSubject.Command(command = "cargo test", cwd = "/home/user/project")

        assertEquals(subject, decode(json))
        assertEquals(json, encode(subject))
    }

    @Test
    fun `command preserves optional associations and metadata`() {
        val json = """{"type":"command","command":"cargo test","cwd":"/home/user/project",""" +
            """"toolCallId":"call_001","terminalId":"term_001","_meta":{"source":"agent"}}"""
        val subject = RequestPermissionSubject.Command(
            command = "cargo test",
            cwd = "/home/user/project",
            toolCallId = ToolCallId("call_001"),
            terminalId = "term_001",
            _meta = buildJsonObject { put("source", "agent") },
        )

        assertEquals(subject, decode(json))
        assertEquals(json, encode(subject))
    }

    @Test
    fun `omitted and null optional command fields are equivalent`() {
        assertEquals(
            decode("""{"type":"command","command":"cargo test","cwd":"/project"}"""),
            decode(
                """{"type":"command","command":"cargo test","cwd":"/project",""" +
                    """"toolCallId":null,"terminalId":null,"_meta":null}"""
            ),
        )
    }

    @Test
    fun `command paths are preserved independently of the receiving platform`() {
        for (cwd in listOf("/project", "C:\\project", "C:/project", "\\\\server\\share\\project")) {
            val subject = RequestPermissionSubject.Command(command = "gradlew test", cwd = cwd)

            assertEquals(subject, decode(encode(subject)))
        }
    }

    @Test
    fun `tool call subject accepts an upsert containing only the id`() {
        val json = """{"type":"tool_call","toolCall":{"toolCallId":"call_001"}}"""
        val subject = RequestPermissionSubject.ToolCall(ToolCallUpdate(ToolCallId("call_001")))

        assertEquals(subject, decode(json))
        assertEquals(json, encode(subject))
    }

    @Test
    fun `known subjects with missing or null required fields fail instead of becoming Unknown`() {
        val malformed = listOf(
            """{"type":"command","cwd":"/project"}""",
            """{"type":"command","command":"cargo test"}""",
            """{"type":"command","command":null,"cwd":"/project"}""",
            """{"type":"command","command":"cargo test","cwd":null}""",
            """{"type":"tool_call"}""",
            """{"type":"tool_call","toolCall":{}}""",
        )

        for (json in malformed) {
            assertFailsWith<SerializationException>(json) { decode(json) }
        }
    }

    @Test
    fun `subject discriminator must be present and a string`() {
        for (json in listOf("{}", """{"type":null}""", """{"type":42}""", """{"type":{}}""")) {
            assertFailsWith<SerializationException>(json) { decode(json) }
        }
    }

    @Test
    fun `future and extension subjects preserve the full payload`() {
        for (type in listOf("network", "_vendor_subject")) {
            val json = """{"type":"$type","hosts":["example.com"],"policy":{"expires":null}}"""
            val subject = assertIs<RequestPermissionSubject.Unknown>(decode(json))

            assertEquals(type, subject.type)
            assertEquals(json, encode(subject))
        }
    }

    private fun decode(json: String): RequestPermissionSubject =
        ACPJson.decodeFromString(RequestPermissionSubject.serializer(), json)

    private fun encode(subject: RequestPermissionSubject): String =
        ACPJson.encodeToString(RequestPermissionSubject.serializer(), subject)
}
