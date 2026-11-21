package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubagentCapabilitiesSerializationTest {
    private fun encodedJson(value: ClientCapabilities) =
        ACPJson.encodeToJsonElement(ClientCapabilities.serializer(), value) as JsonObject

    private fun decodeClient(payload: String) = ACPJson.decodeFromString(ClientCapabilities.serializer(), payload)

    private fun decodeSession(payload: String) =
        ACPJson.decodeFromString(SubagentSessionCapabilities.serializer(), payload)

    private val meta = buildJsonObject { put("k", "v") }

    @Test
    fun `client capabilities decode subagents marker`() {
        val capabilities = decodeClient("""{"subagents": {}}""")

        assertEquals(SubagentCapabilities(), capabilities.subagents)
    }

    @Test
    fun `client capabilities keep subagents meta and round trip`() {
        val original = ClientCapabilities(subagents = SubagentCapabilities(_meta = meta))

        val encoded = encodedJson(original)

        assertEquals(buildJsonObject { put("_meta", meta) }, encoded["subagents"])
        assertEquals(original, ACPJson.decodeFromJsonElement(ClientCapabilities.serializer(), encoded))
    }

    @Test
    fun `client capabilities subagents is absent null and omitted on encode`() {
        assertNull(decodeClient("{}").subagents)
        assertNull(decodeClient("""{"subagents": null}""").subagents)
        assertEquals(false, encodedJson(ClientCapabilities()).containsKey("subagents"))
    }

    @Test
    fun `client capabilities encode empty subagents marker as empty object`() {
        val encoded = encodedJson(ClientCapabilities(subagents = SubagentCapabilities()))

        assertEquals(JsonObject(emptyMap()), encoded["subagents"])
    }

    @Test
    fun `malformed subagents decodes as absent without failing the enclosing capabilities`() {
        for (malformed in listOf("true", "false", "\"yes\"", "42", "[]")) {
            val capabilities = decodeClient("""{"terminal": true, "subagents": $malformed}""")

            assertNull(capabilities.subagents, malformed)
            assertEquals(true, capabilities.terminal, malformed)
        }
    }

    @Test
    fun `subagents marker meta that is not an object decodes as null`() {
        val capabilities = decodeClient("""{"subagents": {"_meta": false}}""")

        assertEquals(SubagentCapabilities(_meta = null), capabilities.subagents)
    }

    @Test
    fun `subagent session capabilities decode cancel with meta`() {
        val decoded = decodeSession("""{"cancel": {"_meta": {"k": "v"}}, "_meta": {"k": "v"}}""")

        assertEquals(SubagentSessionCapabilities(SessionCancelCapabilities(meta), meta), decoded)
    }

    @Test
    fun `subagent session capabilities round trip and omit absent fields`() {
        val granted = SubagentSessionCapabilities(cancel = SessionCancelCapabilities())

        val grantedJson = ACPJson.encodeToJsonElement(SubagentSessionCapabilities.serializer(), granted)
        val emptyJson = ACPJson.encodeToJsonElement(SubagentSessionCapabilities.serializer(), SubagentSessionCapabilities())

        assertEquals(buildJsonObject { put("cancel", JsonObject(emptyMap())) }, grantedJson)
        assertEquals(JsonObject(emptyMap()), emptyJson)
        assertEquals(granted, ACPJson.decodeFromJsonElement(SubagentSessionCapabilities.serializer(), grantedJson))
    }

    @Test
    fun `subagent session capabilities treat omitted and null cancel the same`() {
        assertNull(decodeSession("{}").cancel)
        assertNull(decodeSession("""{"cancel": null}""").cancel)
    }

    @Test
    fun `malformed cancel decodes as null and keeps the other fields`() {
        for (malformed in listOf("true", "false", "\"yes\"", "1", "[]")) {
            val decoded = decodeSession("""{"cancel": $malformed, "_meta": {"k": "v"}}""")

            assertNull(decoded.cancel, malformed)
            assertEquals(meta, decoded._meta, malformed)
        }
    }

    @Test
    fun `malformed capability meta decodes as null`() {
        val decoded = decodeSession("""{"cancel": {"_meta": 1}, "_meta": "x"}""")

        assertEquals(SubagentSessionCapabilities(SessionCancelCapabilities(), null), decoded)
    }

    @Test
    fun `capabilities that are not objects fail to decode`() {
        assertFailsWith<SerializationException> { decodeSession("true") }
        assertFailsWith<SerializationException> { decodeSession("[]") }
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(SessionCancelCapabilities.serializer(), "\"x\"")
        }
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(SubagentCapabilities.serializer(), "null")
        }
    }

    @Test
    fun `session cancel capabilities round trip with meta`() {
        val original = SessionCancelCapabilities(_meta = meta)

        val decoded = ACPJson.decodeFromString(
            SessionCancelCapabilities.serializer(),
            ACPJson.encodeToString(SessionCancelCapabilities.serializer(), original),
        )

        assertEquals(original, decoded)
        assertNotNull(decoded._meta)
    }
}
