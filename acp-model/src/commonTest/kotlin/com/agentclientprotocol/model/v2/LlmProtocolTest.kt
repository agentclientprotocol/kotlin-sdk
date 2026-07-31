@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.LlmProtocol as V1LlmProtocol

class LlmProtocolTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(LlmProtocol.Anthropic, decode("\"anthropic\""))
        assertEquals(LlmProtocol.OpenAi, decode("\"openai\""))
        assertEquals(LlmProtocol.Azure, decode("\"azure\""))
        assertEquals(LlmProtocol.Vertex, decode("\"vertex\""))
        assertEquals(LlmProtocol.Bedrock, decode("\"bedrock\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"anthropic\"", encode(LlmProtocol.Anthropic))
        assertEquals("\"openai\"", encode(LlmProtocol.OpenAi))
        assertEquals("\"azure\"", encode(LlmProtocol.Azure))
        assertEquals("\"vertex\"", encode(LlmProtocol.Vertex))
        assertEquals("\"bedrock\"", encode(LlmProtocol.Bedrock))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown value as Unknown and round-trips byte-identically`() {
        val protocol = decode("\"gemini\"")

        assertIs<LlmProtocol.Unknown>(protocol)
        assertEquals("gemini", protocol.value)
        assertEquals("\"_vendor_protocol\"", encode(decode("\"_vendor_protocol\"")))
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(LlmProtocol.extension("_vendor_protocol"), LlmProtocol.Unknown("_vendor_protocol"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            LlmProtocol.extension("vendor_protocol")
        }
    }

    // v2 <-> v1 conversion (total both ways: v1 is an open string wrapper)

    @Test
    fun `converts all known values to v1`() {
        assertEquals(LlmProtocol.Anthropic.toV1(), V1LlmProtocol.ANTHROPIC)
        assertEquals(LlmProtocol.OpenAi.toV1(), V1LlmProtocol.OPENAI)
        assertEquals(LlmProtocol.Azure.toV1(), V1LlmProtocol.AZURE)
        assertEquals(LlmProtocol.Vertex.toV1(), V1LlmProtocol.VERTEX)
        assertEquals(LlmProtocol.Bedrock.toV1(), V1LlmProtocol.BEDROCK)
    }

    @Test
    fun `converts Unknown to v1 without data loss`() {
        assertEquals(V1LlmProtocol("gemini"), LlmProtocol.Unknown("gemini").toV1())
    }

    @Test
    fun `converts all well-known v1 values to v2`() {
        assertEquals(LlmProtocol.Anthropic, V1LlmProtocol.ANTHROPIC.toV2())
        assertEquals(LlmProtocol.OpenAi, V1LlmProtocol.OPENAI.toV2())
        assertEquals(LlmProtocol.Azure, V1LlmProtocol.AZURE.toV2())
        assertEquals(LlmProtocol.Vertex, V1LlmProtocol.VERTEX.toV2())
        assertEquals(LlmProtocol.Bedrock, V1LlmProtocol.BEDROCK.toV2())
    }

    @Test
    fun `converts custom v1 value to v2 Unknown`() {
        assertEquals(LlmProtocol.Unknown("gemini"), V1LlmProtocol("gemini").toV2())
    }

    private fun decode(json: String): LlmProtocol =
        ACPJson.decodeFromString(LlmProtocol.serializer(), json)

    private fun encode(protocol: LlmProtocol): String =
        ACPJson.encodeToString(LlmProtocol.serializer(), protocol)
}
