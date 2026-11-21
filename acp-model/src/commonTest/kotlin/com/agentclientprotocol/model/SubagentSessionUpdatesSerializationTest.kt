@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SubagentSessionUpdatesSerializationTest {
    private fun decode(payload: String): SessionUpdate = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

    private fun encode(value: SessionUpdate): JsonElement = ACPJson.encodeToJsonElement(SessionUpdate.serializer(), value)

    private fun json(payload: String): JsonElement = ACPJson.parseToJsonElement(payload)

    private fun assertRoundTrips(payload: String, expected: SessionUpdate) {
        val decoded = decode(payload)

        assertEquals(expected, decoded)
        assertEquals(json(payload), encode(decoded))
    }

    private fun subagent(fields: String = ""): String =
        """{"sessionUpdate":"subagent_update","sessionId":"child"${if (fields.isEmpty()) "" else ",$fields"}}"""

    private fun message(fields: String = ""): String =
        """{"sessionUpdate":"session_message","messageId":"m1"${if (fields.isEmpty()) "" else ",$fields"}}"""

    private fun chunk(fields: String = ""): String =
        """{"sessionUpdate":"session_message_chunk","messageId":"m1"${if (fields.isEmpty()) "" else ",$fields"}}"""

    private val meta = buildJsonObject { put("k", "v") }
    private val text = ContentBlock.Text("hi")
    private val textJson = """{"type":"text","text":"hi"}"""
    private val child = SessionId("child")

    // SubagentUpdate

    @Test
    fun `subagent update with only a session id leaves every patch field undefined`() {
        assertRoundTrips(subagent(), SessionUpdate.SubagentUpdate(child))
    }

    @Test
    fun `every subagent update patch field distinguishes absent null and value`() {
        assertRoundTrips(
            subagent(
                """"title":null,"description":null,"capabilities":null,"state":null,"cwd":null,""" +
                    """"additionalDirectories":null,"_meta":null"""
            ),
            SessionUpdate.SubagentUpdate(
                sessionId = child,
                title = MaybeUndefined.Null,
                description = MaybeUndefined.Null,
                capabilities = MaybeUndefined.Null,
                state = MaybeUndefined.Null,
                cwd = MaybeUndefined.Null,
                additionalDirectories = MaybeUndefined.Null,
                _meta = MaybeUndefined.Null,
            ),
        )
        assertRoundTrips(
            subagent(
                """"title":"T","description":"D","capabilities":{"cancel":{}},"state":{"state":"running"},""" +
                    """"cwd":"/work","additionalDirectories":["/a","/b"],"_meta":{"k":"v"}"""
            ),
            SessionUpdate.SubagentUpdate(
                sessionId = child,
                title = MaybeUndefined.Value("T"),
                description = MaybeUndefined.Value("D"),
                capabilities = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities())),
                state = MaybeUndefined.Value(StateUpdate.Running()),
                cwd = MaybeUndefined.Value("/work"),
                additionalDirectories = MaybeUndefined.Value(listOf("/a", "/b")),
                _meta = MaybeUndefined.Value(meta),
            ),
        )
    }

    @Test
    fun `each subagent update patch field round trips alone`() {
        val cases = mapOf(
            "title" to SessionUpdate.SubagentUpdate(child, title = MaybeUndefined.Null),
            "description" to SessionUpdate.SubagentUpdate(child, description = MaybeUndefined.Null),
            "capabilities" to SessionUpdate.SubagentUpdate(child, capabilities = MaybeUndefined.Null),
            "state" to SessionUpdate.SubagentUpdate(child, state = MaybeUndefined.Null),
            "cwd" to SessionUpdate.SubagentUpdate(child, cwd = MaybeUndefined.Null),
            "additionalDirectories" to SessionUpdate.SubagentUpdate(child, additionalDirectories = MaybeUndefined.Null),
            "_meta" to SessionUpdate.SubagentUpdate(child, _meta = MaybeUndefined.Null),
        )

        for ((key, expected) in cases) {
            assertRoundTrips(subagent(""""$key":null"""), expected)
        }
    }

    @Test
    fun `empty additional directories list differs from null`() {
        assertRoundTrips(
            subagent(""""additionalDirectories":[]"""),
            SessionUpdate.SubagentUpdate(child, additionalDirectories = MaybeUndefined.Value(emptyList())),
        )
    }

    @Test
    fun `capabilities are replaced as a whole`() {
        val cases = mapOf(
            """"capabilities":{}""" to MaybeUndefined.Value(SubagentSessionCapabilities()),
            """"capabilities":{"cancel":{}}""" to
                MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities())),
            """"capabilities":null""" to MaybeUndefined.Null,
        )

        for ((field, expected) in cases) {
            assertRoundTrips(subagent(field), SessionUpdate.SubagentUpdate(child, capabilities = expected))
        }
    }

    @Test
    fun `capabilities with a null cancel decode as empty capabilities`() {
        val decoded = decode(subagent(""""capabilities":{"cancel":null}"""))

        assertEquals(
            SessionUpdate.SubagentUpdate(child, capabilities = MaybeUndefined.Value(SubagentSessionCapabilities())),
            decoded,
        )
        assertEquals(json(subagent(""""capabilities":{}""")), encode(decoded))
    }

    @Test
    fun `malformed lenient subagent update fields decode as absent`() {
        val payloads = listOf(
            """"title":false""",
            """"title":42""",
            """"description":false""",
            """"description":{"a":1}""",
            """"capabilities":false""",
            """"capabilities":"x"""",
            """"state":"running"""",
            """"state":{}""",
            """"state":{"state":4}""",
            """"_meta":false""",
            """"_meta":[1]""",
        )

        for (field in payloads) {
            val decoded = decode(subagent(field))

            assertEquals(SessionUpdate.SubagentUpdate(child), decoded, field)
            assertEquals(json(subagent()), encode(decoded), field)
        }
    }

    @Test
    fun `malformed lenient fields do not disturb valid siblings`() {
        assertEquals(
            SessionUpdate.SubagentUpdate(child, description = MaybeUndefined.Value("D")),
            decode(subagent(""""title":false,"description":"D"""")),
        )
    }

    @Test
    fun `malformed cwd fails the subagent update`() {
        for (field in listOf(""""cwd":false""", """"cwd":42""", """"cwd":{}""", """"cwd":["/a"]""")) {
            assertFailsWith<SerializationException>(field) { decode(subagent(field)) }
        }
    }

    @Test
    fun `malformed additional directories fail the subagent update`() {
        val payloads = listOf(
            """"additionalDirectories":"/tmp/wt"""",
            """"additionalDirectories":false""",
            """"additionalDirectories":["/tmp/wt",false]""",
            """"additionalDirectories":[42]""",
            """"additionalDirectories":[null]""",
        )

        for (field in payloads) {
            assertFailsWith<SerializationException>(field) { decode(subagent(field)) }
        }
    }

    @Test
    fun `subagent update without a valid session id fails`() {
        assertFailsWith<SerializationException> { decode("""{"sessionUpdate":"subagent_update"}""") }
        assertFailsWith<SerializationException> { decode("""{"sessionUpdate":"subagent_update","sessionId":null}""") }
        assertFailsWith<SerializationException> { decode("""{"sessionUpdate":"subagent_update","sessionId":42}""") }
    }

    @Test
    fun `unrecognized state round trips through a subagent update`() {
        val payload = subagent(""""state":{"state":"paused","retryAt":42,"_meta":{"k":"v"}}""")

        val decoded = assertIs<SessionUpdate.SubagentUpdate>(decode(payload))

        val state = assertIs<StateUpdate.Other>(decoded.state.valueOrNull())
        assertEquals("paused", state.state)
        assertEquals(json(payload), encode(decoded))
    }

    @Test
    fun `unrecognized stop reason round trips through a subagent update`() {
        val payload = subagent(
            """"state":{"state":"idle","stopReason":"_paused","resumeAfter":30,"_meta":{"k":"v"}}"""
        )

        val decoded = assertIs<SessionUpdate.SubagentUpdate>(decode(payload))

        val state = assertIs<StateUpdate.Idle>(decoded.state.valueOrNull())
        assertEquals("_paused", assertIs<IdleStopReason.Other>(state.stopReason).stopReason)
        assertEquals(meta, state._meta)
        assertEquals(json(payload), encode(decoded))
    }

    @Test
    fun `idle state with an error stop reason decodes inside a subagent update`() {
        val error = JsonRpcError(code = -32000, message = "boom")
        val payload = subagent(
            """"state":{"state":"idle","stopReason":"error","error":{"code":-32000,"message":"boom"}}"""
        )

        assertRoundTrips(
            payload,
            SessionUpdate.SubagentUpdate(
                child,
                state = MaybeUndefined.Value(StateUpdate.Idle(stopReason = IdleStopReason.Error(error))),
            ),
        )
    }

    @Test
    fun `malformed known state decodes as an absent state and never as other`() {
        for (state in listOf(""""running"""", "{}", """{"state":4}""", "42", "[]")) {
            val decoded = decode(subagent(""""state":$state"""))

            assertEquals(MaybeUndefined.Undefined, assertIs<SessionUpdate.SubagentUpdate>(decoded).state, state)
        }
    }

    @Test
    fun `known state with a malformed meta keeps the state without meta`() {
        assertEquals(
            SessionUpdate.SubagentUpdate(child, state = MaybeUndefined.Value(StateUpdate.Unknown())),
            decode(subagent(""""state":{"state":"unknown","_meta":false}""")),
        )
    }

    // SessionMessage

    @Test
    fun `session message with only a message id leaves every patch field undefined`() {
        assertRoundTrips(message(), SessionUpdate.SessionMessage(MessageId("m1")))
    }

    @Test
    fun `every session message patch field distinguishes null and value`() {
        assertRoundTrips(
            message(
                """"senderSessionId":null,"recipientSessionId":null,"senderMessageId":null,""" +
                    """"recipientMessageId":null,"content":null,"_meta":null"""
            ),
            SessionUpdate.SessionMessage(
                messageId = MessageId("m1"),
                senderSessionId = MaybeUndefined.Null,
                recipientSessionId = MaybeUndefined.Null,
                senderMessageId = MaybeUndefined.Null,
                recipientMessageId = MaybeUndefined.Null,
                content = MaybeUndefined.Null,
                _meta = MaybeUndefined.Null,
            ),
        )
        assertRoundTrips(
            message(
                """"senderSessionId":"a","recipientSessionId":"b","senderMessageId":"sm","""" +
                    """recipientMessageId":"rm","content":[$textJson],"_meta":{"k":"v"}"""
            ),
            SessionUpdate.SessionMessage(
                messageId = MessageId("m1"),
                senderSessionId = MaybeUndefined.Value(SessionId("a")),
                recipientSessionId = MaybeUndefined.Value(SessionId("b")),
                senderMessageId = MaybeUndefined.Value(MessageId("sm")),
                recipientMessageId = MaybeUndefined.Value(MessageId("rm")),
                content = MaybeUndefined.Value(listOf(text)),
                _meta = MaybeUndefined.Value(meta),
            ),
        )
    }

    @Test
    fun `each session message patch field round trips alone`() {
        val cases = mapOf(
            "senderSessionId" to SessionUpdate.SessionMessage(MessageId("m1"), senderSessionId = MaybeUndefined.Null),
            "recipientSessionId" to
                SessionUpdate.SessionMessage(MessageId("m1"), recipientSessionId = MaybeUndefined.Null),
            "senderMessageId" to SessionUpdate.SessionMessage(MessageId("m1"), senderMessageId = MaybeUndefined.Null),
            "recipientMessageId" to
                SessionUpdate.SessionMessage(MessageId("m1"), recipientMessageId = MaybeUndefined.Null),
            "content" to SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Null),
            "_meta" to SessionUpdate.SessionMessage(MessageId("m1"), _meta = MaybeUndefined.Null),
        )

        for ((key, expected) in cases) {
            assertRoundTrips(message(""""$key":null"""), expected)
        }
    }

    @Test
    fun `session message content null and empty list stay distinct`() {
        assertRoundTrips(
            message(""""content":null"""),
            SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Null),
        )
        assertRoundTrips(
            message(""""content":[]"""),
            SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Value(emptyList())),
        )
    }

    @Test
    fun `session message skips unparseable content blocks`() {
        val decoded = decode(message(""""content":[{"type":"bogus"},$textJson,{"type":"text"},42]"""))

        assertEquals(
            SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Value(listOf(text))),
            decoded,
        )
        assertEquals(json(message(""""content":[$textJson]""")), encode(decoded))
    }

    @Test
    fun `session message with all invalid content blocks decodes as an empty list`() {
        val decoded = decode(message(""""content":[{"type":"bogus"},42]"""))

        assertEquals(
            SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Value(emptyList())),
            decoded,
        )
        assertEquals(json(message(""""content":[]""")), encode(decoded))
    }

    @Test
    fun `malformed session message patch fields decode as absent`() {
        val payloads = listOf(
            """"senderSessionId":42""",
            """"senderSessionId":false""",
            """"recipientSessionId":{}""",
            """"senderMessageId":42""",
            """"recipientMessageId":["x"]""",
            """"content":false""",
            """"content":{}""",
            """"_meta":false""",
            """"_meta":"x"""",
        )

        for (field in payloads) {
            val decoded = decode(message(field))

            assertEquals(SessionUpdate.SessionMessage(MessageId("m1")), decoded, field)
            assertEquals(json(message()), encode(decoded), field)
        }
    }

    @Test
    fun `session message without a valid message id fails`() {
        for (id in listOf("", ""","messageId":null""", ""","messageId":42""")) {
            assertFailsWith<SerializationException>(id) {
                decode("""{"sessionUpdate":"session_message"$id}""")
            }
        }
    }

    // SessionMessageChunk

    @Test
    fun `session message chunk round trips`() {
        assertRoundTrips(
            chunk(""""content":$textJson"""),
            SessionUpdate.SessionMessageChunk(MessageId("m1"), text),
        )
        assertRoundTrips(
            chunk(""""content":$textJson,"_meta":{"k":"v"}"""),
            SessionUpdate.SessionMessageChunk(MessageId("m1"), text, meta),
        )
    }

    @Test
    fun `session message chunk null and malformed meta decode as null`() {
        for (field in listOf(""""_meta":null""", """"_meta":false""", """"_meta":"x"""", """"_meta":[1]""")) {
            val decoded = decode(chunk(""""content":$textJson,$field"""))

            assertEquals(SessionUpdate.SessionMessageChunk(MessageId("m1"), text), decoded, field)
            assertEquals(json(chunk(""""content":$textJson""")), encode(decoded), field)
        }
    }

    @Test
    fun `session message chunk ignores participant fields`() {
        val decoded = decode(chunk(""""content":$textJson,"senderSessionId":"a","recipientMessageId":"r""""))

        assertEquals(json(chunk(""""content":$textJson""")), encode(decoded))
    }

    @Test
    fun `session message chunk without valid required fields fails`() {
        val payloads = listOf(
            """{"sessionUpdate":"session_message_chunk","content":$textJson}""",
            """{"sessionUpdate":"session_message_chunk","messageId":null,"content":$textJson}""",
            """{"sessionUpdate":"session_message_chunk","messageId":42,"content":$textJson}""",
            """{"sessionUpdate":"session_message_chunk","messageId":"m1"}""",
            """{"sessionUpdate":"session_message_chunk","messageId":"m1","content":null}""",
            """{"sessionUpdate":"session_message_chunk","messageId":"m1","content":42}""",
            """{"sessionUpdate":"session_message_chunk","messageId":"m1","content":{"type":"bogus"}}""",
        )

        for (payload in payloads) {
            assertFailsWith<SerializationException>(payload) { decode(payload) }
        }
    }

    // ToolCallContent.Session

    private val toolCallContentCases = listOf(
        """{"type":"session","sessionId":"child"}""" to ToolCallContent.Session(child),
        """{"type":"session","sessionId":"child","_meta":{"k":"v"}}""" to ToolCallContent.Session(child, meta),
    )

    @Test
    fun `session tool call content round trips on its own`() {
        for ((payload, expected) in toolCallContentCases) {
            val decoded = ACPJson.decodeFromString(ToolCallContent.serializer(), payload)

            assertEquals(expected, decoded)
            assertEquals(json(payload), ACPJson.encodeToJsonElement(ToolCallContent.serializer(), decoded))
        }
    }

    @Test
    fun `session tool call content round trips inside tool call and tool call update`() {
        for ((contentJson, expected) in toolCallContentCases) {
            val toolCall = decode("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"x","content":[$contentJson]}""")
            assertEquals(listOf<ToolCallContent>(expected), assertIs<SessionUpdate.ToolCall>(toolCall).content)
            assertEquals(json("[$contentJson]"), encode(toolCall).jsonObject["content"])

            val toolCallUpdate = decode("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","content":[$contentJson]}""")
            assertEquals(
                listOf<ToolCallContent>(expected),
                assertIs<SessionUpdate.ToolCallUpdate>(toolCallUpdate).content,
            )
            assertEquals(json("[$contentJson]"), encode(toolCallUpdate).jsonObject["content"])
        }
    }

    @Test
    fun `session tool call content with malformed meta decodes without meta`() {
        for (metaJson in listOf("false", "\"x\"", "[1]", "null")) {
            val payload = """{"sessionUpdate":"tool_call_update","toolCallId":"t1","content":""" +
                """[{"type":"session","sessionId":"child","_meta":$metaJson}]}"""

            val decoded = assertIs<SessionUpdate.ToolCallUpdate>(decode(payload))

            assertEquals(listOf<ToolCallContent>(ToolCallContent.Session(child)), decoded.content, metaJson)
            assertEquals(
                json("""[{"type":"session","sessionId":"child"}]"""),
                encode(decoded).jsonObject["content"],
                metaJson,
            )
        }
    }

    @Test
    fun `session tool call content without a string session id fails`() {
        for (id in listOf("", ""","sessionId":null""", ""","sessionId":{}""")) {
            assertFailsWith<SerializationException>(id) {
                ACPJson.decodeFromString(ToolCallContent.serializer(), """{"type":"session"$id}""")
            }
        }
    }

    // Full notifications

    @Test
    fun `the three discriminators decode inside a full session notification`() {
        val cases = listOf(
            subagent(""""title":"T"""") to SessionUpdate.SubagentUpdate(child, title = MaybeUndefined.Value("T")),
            message(""""content":[$textJson]""") to
                SessionUpdate.SessionMessage(MessageId("m1"), content = MaybeUndefined.Value(listOf(text))),
            chunk(""""content":$textJson""") to SessionUpdate.SessionMessageChunk(MessageId("m1"), text),
        )

        for ((update, expected) in cases) {
            val payload = """{"sessionId":"root","update":$update}"""

            val notification = ACPJson.decodeFromString(SessionNotification.serializer(), payload)

            assertEquals(SessionNotification(SessionId("root"), expected), notification)
            assertEquals(json(payload), ACPJson.encodeToJsonElement(SessionNotification.serializer(), notification))
        }
    }

    @Test
    fun `an invalid known subagent update fails the notification instead of becoming unknown`() {
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(
                SessionNotification.serializer(),
                """{"sessionId":"root","update":${subagent(""""cwd":false""")}}""",
            )
        }
    }

    @Test
    fun `subagent update encodes only the defined patch fields`() {
        val encoded: JsonObject = encode(
            SessionUpdate.SubagentUpdate(child, title = MaybeUndefined.Null, cwd = MaybeUndefined.Value("/w"))
        ).jsonObject

        assertEquals(json("""{"sessionUpdate":"subagent_update","sessionId":"child","title":null,"cwd":"/w"}"""), encoded)
    }
}
