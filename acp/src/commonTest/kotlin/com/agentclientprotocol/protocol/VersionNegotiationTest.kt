package com.agentclientprotocol.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionNegotiationTest {
    @Test
    fun `reads an integer protocol version`() {
        for (version in listOf(Int.MIN_VALUE, 0, 1, 2, Int.MAX_VALUE)) {
            assertEquals(version, readProtocolVersionOrNull(payload(JsonPrimitive(version))))
        }
    }

    @Test
    fun `returns null when the protocol version is absent`() {
        assertNull(readProtocolVersionOrNull(null))
        assertNull(readProtocolVersionOrNull(JsonNull))
        assertNull(readProtocolVersionOrNull(buildJsonObject {}))
    }

    @Test
    fun `returns null when the protocol version is not a JSON integer`() {
        val invalidValues = listOf(
            JsonNull,
            JsonPrimitive("2"),
            JsonPrimitive("not-a-version"),
            JsonPrimitive(true),
            JsonPrimitive(2.5),
            JsonPrimitive(Int.MAX_VALUE.toLong() + 1),
            buildJsonObject { put("nested", 2) },
            buildJsonArray { add(JsonPrimitive(2)) },
        )

        for (value in invalidValues) {
            assertNull(readProtocolVersionOrNull(payload(value)), "value=$value")
        }
    }

    private fun payload(value: JsonElement) = buildJsonObject {
        put("protocolVersion", value)
    }
}
