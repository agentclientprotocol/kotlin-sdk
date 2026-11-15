package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SubagentStateSerializationTest {
    private fun decode(payload: String): StateUpdate = ACPJson.decodeFromString(StateUpdate.serializer(), payload)

    private fun encode(value: StateUpdate): JsonElement = ACPJson.encodeToJsonElement(StateUpdate.serializer(), value)

    private fun json(payload: String): JsonElement = ACPJson.parseToJsonElement(payload)

    private fun assertRoundTrips(payload: String, expected: StateUpdate) {
        val decoded = decode(payload)

        assertEquals(expected, decoded)
        assertEquals(json(payload), encode(decoded))
    }

    private val meta = buildJsonObject { put("k", "v") }

    @Test
    fun `known states round trip`() {
        assertRoundTrips("""{"state":"running"}""", StateUpdate.Running())
        assertRoundTrips("""{"state":"running","_meta":{"k":"v"}}""", StateUpdate.Running(meta))
        assertRoundTrips("""{"state":"requires_action"}""", StateUpdate.RequiresAction())
        assertRoundTrips("""{"state":"requires_action","_meta":{"k":"v"}}""", StateUpdate.RequiresAction(meta))
        assertRoundTrips("""{"state":"unknown"}""", StateUpdate.Unknown())
        assertRoundTrips("""{"state":"unknown","_meta":{"k":"v"}}""", StateUpdate.Unknown(meta))
        assertRoundTrips("""{"state":"idle"}""", StateUpdate.Idle())
    }

    @Test
    fun `every known stop reason round trips on an idle snapshot`() {
        val cases = mapOf(
            "end_turn" to IdleStopReason.EndTurn,
            "max_tokens" to IdleStopReason.MaxTokens,
            "max_turn_requests" to IdleStopReason.MaxTurnRequests,
            "refusal" to IdleStopReason.Refusal,
            "cancelled" to IdleStopReason.Cancelled,
        )

        for ((wire, expected) in cases) {
            assertRoundTrips("""{"state":"idle","stopReason":"$wire"}""", StateUpdate.Idle(stopReason = expected))
        }
    }

    @Test
    fun `real unknown state differs from an unrecognized state`() {
        assertEquals(StateUpdate.Unknown(), decode("""{"state":"unknown"}"""))

        val other = decode("""{"state":"paused"}""")

        assertEquals(StateUpdate.Other("paused"), other)
    }

    @Test
    fun `unrecognized state round trips unchanged and keeps meta`() {
        val payload = """{"state":"paused","retryAt":42,"_meta":{"k":"v"},"nested":{"a":[1,2]}}"""

        val decoded = decode(payload)

        val other = assertIs<StateUpdate.Other>(decoded)
        assertEquals("paused", other.state)
        assertEquals(setOf("retryAt", "_meta", "nested"), other.fields.keys)
        assertEquals(json(payload), encode(decoded))
    }

    @Test
    fun `unrecognized state with a malformed meta is preserved as received`() {
        assertRoundTrips(
            """{"state":"paused","_meta":false}""",
            StateUpdate.Other("paused", buildJsonObject { put("_meta", JsonPrimitive(false)) }),
        )
    }

    @Test
    fun `extra fields beside a known state are dropped`() {
        assertEquals(StateUpdate.Running(), decode("""{"state":"running","foo":1}"""))
        assertEquals(json("""{"state":"running"}"""), encode(decode("""{"state":"running","foo":1}""")))
        assertEquals(StateUpdate.Unknown(), decode("""{"state":"unknown","foo":{"a":1}}"""))
    }

    @Test
    fun `malformed state meta decodes as null`() {
        for (state in listOf("running", "requires_action", "unknown", "idle")) {
            for (malformed in listOf("false", "1", "\"x\"", "[]", "null")) {
                val decoded = decode("""{"state":"$state","_meta":$malformed}""")

                assertNull((decoded as AcpWithMeta)._meta, "$state $malformed")
            }
        }
    }

    @Test
    fun `non object value is a decode failure`() {
        for (malformed in listOf("\"running\"", "42", "true", "[]", "null")) {
            assertFailsWith<SerializationException>(malformed) { decode(malformed) }
        }
    }

    @Test
    fun `missing or non string state is a decode failure not an unrecognized state`() {
        for (malformed in listOf("{}", """{"state":4}""", """{"state":null}""", """{"state":["running"]}""")) {
            assertFailsWith<SerializationException>(malformed) { decode(malformed) }
        }
    }

    @Test
    fun `idle with error stop reason round trips`() {
        val payload = """{"state":"idle","stopReason":"error","error":{"code":-32603,"message":"boom","data":{"a":1}}}"""
        val expected = StateUpdate.Idle(
            stopReason = IdleStopReason.Error(
                JsonRpcError(code = -32603, message = "boom", data = buildJsonObject { put("a", 1) }),
            ),
        )

        assertRoundTrips(payload, expected)
    }

    @Test
    fun `error stop reason without error round trips`() {
        assertRoundTrips(
            """{"state":"idle","stopReason":"error"}""",
            StateUpdate.Idle(stopReason = IdleStopReason.Error()),
        )
    }

    @Test
    fun `omitted null and malformed error decode as no error`() {
        for (error in listOf("", ""","error":null""", ""","error":"failed"""", ""","error":{"code":"x"}""", ""","error":{}""")) {
            val decoded = decode("""{"state":"idle","stopReason":"error"$error}""")

            assertEquals(StateUpdate.Idle(stopReason = IdleStopReason.Error()), decoded, error)
        }
    }

    @Test
    fun `error beside a non error stop reason is dropped`() {
        val decoded = decode("""{"state":"idle","stopReason":"end_turn","error":{"code":1,"message":"m"}}""")

        assertEquals(StateUpdate.Idle(stopReason = IdleStopReason.EndTurn), decoded)
        assertEquals(json("""{"state":"idle","stopReason":"end_turn"}"""), encode(decoded))
    }

    @Test
    fun `idle with usage round trips`() {
        val payload = """{"state":"idle","stopReason":"end_turn","usage":{"inputTokens":1,"outputTokens":2,"totalTokens":3}}"""

        assertRoundTrips(
            payload,
            StateUpdate.Idle(IdleStopReason.EndTurn, Usage(inputTokens = 1, outputTokens = 2, totalTokens = 3)),
        )
    }

    @Test
    fun `omitted null and malformed usage decode as null without failing the snapshot`() {
        val malformedUsages = listOf(
            "null",
            "false",
            "\"many\"",
            "[]",
            """{"inputTokens":1}""",
            """{"inputTokens":1,"outputTokens":[],"totalTokens":3}""",
        )

        assertEquals(StateUpdate.Idle(IdleStopReason.EndTurn), decode("""{"state":"idle","stopReason":"end_turn"}"""))
        for (usage in malformedUsages) {
            val decoded = decode("""{"state":"idle","stopReason":"end_turn","usage":$usage,"_meta":{"k":"v"}}""")

            assertEquals(StateUpdate.Idle(IdleStopReason.EndTurn, usage = null, _meta = meta), decoded, usage)
        }
    }

    @Test
    fun `idle without a stop reason omits it on encode`() {
        val decoded = decode("""{"state":"idle","_meta":{"k":"v"}}""")

        assertEquals(StateUpdate.Idle(_meta = meta), decoded)
        assertEquals(json("""{"state":"idle","_meta":{"k":"v"}}"""), encode(decoded))
    }

    @Test
    fun `absent null malformed and non string stop reasons decode as no stop reason`() {
        val cases = listOf("", ""","stopReason":null""", ""","stopReason":true""", ""","stopReason":false""",
            ""","stopReason":4""", ""","stopReason":["end_turn"]""", ""","stopReason":{"a":1}""")

        for (stopReason in cases) {
            val decoded = decode("""{"state":"idle","_meta":{"k":"v"}$stopReason}""")

            assertEquals(StateUpdate.Idle(stopReason = null, _meta = meta), decoded, stopReason)
        }
    }

    @Test
    fun `unrecognized stop reason keeps its fields but not the snapshot fields`() {
        val payload = """
            {"state":"idle","stopReason":"_paused","resumeAfter":30,"nested":{"a":1},
             "usage":{"inputTokens":1,"outputTokens":2,"totalTokens":3},"_meta":{"k":"v"}}
        """.trimIndent()

        val decoded = decode(payload)

        val idle = assertIs<StateUpdate.Idle>(decoded)
        val other = assertIs<IdleStopReason.Other>(idle.stopReason)
        assertEquals("_paused", other.stopReason)
        assertEquals(setOf("resumeAfter", "nested"), other.fields.keys)
        assertEquals(Usage(inputTokens = 1, outputTokens = 2, totalTokens = 3), idle.usage)
        assertEquals(meta, idle._meta)
        assertEquals(json(payload), encode(decoded))
    }

    @Test
    fun `unrecognized stop reason without extra fields round trips`() {
        assertRoundTrips(
            """{"state":"idle","stopReason":"_paused"}""",
            StateUpdate.Idle(stopReason = IdleStopReason.Other("_paused")),
        )
    }

    @Test
    fun `encoding an unrecognized stop reason never lets its fields override the snapshot`() {
        val stopReason = IdleStopReason.Other(
            "_paused",
            buildJsonObject {
                put("state", "running")
                put("usage", 1)
                put("_meta", 2)
                put("stopReason", "end_turn")
                put("keep", 3)
            },
        )

        val encoded = encode(StateUpdate.Idle(stopReason, _meta = meta))

        assertEquals(
            json("""{"state":"idle","stopReason":"_paused","keep":3,"_meta":{"k":"v"}}"""),
            encoded,
        )
    }

    @Test
    fun `stop reason fields are not shared with the unrecognized state fields`() {
        val decoded = decode("""{"state":"idle","stopReason":"_paused","extra":1}""")

        assertEquals(
            JsonObject(mapOf("extra" to JsonPrimitive(1))),
            (assertIs<StateUpdate.Idle>(decoded).stopReason as IdleStopReason.Other).fields,
        )
    }
}
