@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ElicitationModeTest {

    private val sessionScope = ElicitationScope.Session(sessionId = SessionId("s1"))

    // Known variants

    @Test
    fun `decodes form mode with flattened session scope`() {
        assertEquals(
            ElicitationMode.Form(scope = sessionScope, requestedSchema = ElicitationSchema()),
            decode("""{"mode":"form","sessionId":"s1","requestedSchema":{"type":"object"}}"""),
        )
    }

    @Test
    fun `decodes url mode with flattened scope and tool call`() {
        assertEquals(
            ElicitationMode.Url(
                scope = ElicitationScope.Session(sessionId = SessionId("s1"), toolCallId = ToolCallId("t1")),
                elicitationId = ElicitationId("e1"),
                url = "https://example.com/login",
            ),
            decode(
                """{"mode":"url","sessionId":"s1","toolCallId":"t1",""" +
                    """"elicitationId":"e1","url":"https://example.com/login"}""",
            ),
        )
    }

    @Test
    fun `encodes with mode first and scope flattened`() {
        assertEquals(
            """{"mode":"url","sessionId":"s1","elicitationId":"e1","url":"https://example.com/login"}""",
            encode(
                ElicitationMode.Url(
                    scope = sessionScope,
                    elicitationId = ElicitationId("e1"),
                    url = "https://example.com/login",
                ),
            ),
        )
    }

    @Test
    fun `form mode round-trips`() {
        val mode = ElicitationMode.Form(
            scope = sessionScope,
            requestedSchema = ElicitationSchema(
                properties = mapOf("name" to ElicitationPropertySchema.StringProperty(title = "Name")),
                required = listOf("name"),
            ),
        )

        assertEquals(mode, decode(encode(mode)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown mode as Unknown with parsed scope and full payload`() {
        val json = """{"mode":"voice","sessionId":"s1","language":"en-US"}"""

        val mode = decode(json)

        assertIs<ElicitationMode.Unknown>(mode)
        assertEquals("voice", mode.mode)
        assertEquals(sessionScope, mode.scope)
        assertEquals(json, encode(mode))
    }

    @Test
    fun `unknown mode without a resolvable scope fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"mode":"voice","language":"en-US"}""")
        }
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"sessionId":"s1","requestedSchema":{"type":"object"}}""")
        }
    }

    @Test
    fun `form without requestedSchema fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"mode":"form","sessionId":"s1"}""")
        }
    }

    @Test
    fun `ambiguous scope with both sessionId and requestId fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"mode":"form","sessionId":"s1","requestId":1,"requestedSchema":{"type":"object"}}""")
        }
    }

    private fun decode(json: String): ElicitationMode =
        ACPJson.decodeFromString(ElicitationMode.serializer(), json)

    private fun encode(mode: ElicitationMode): String =
        ACPJson.encodeToString(ElicitationMode.serializer(), mode)
}
