package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class ProvidersSerializationTest {

    @Test
    fun `LlmProtocol known values serialize and deserialize correctly`() {
        val knownProtocols = listOf(
            LlmProtocol.ANTHROPIC,
            LlmProtocol.OPENAI,
            LlmProtocol.AZURE,
            LlmProtocol.VERTEX,
            LlmProtocol.BEDROCK
        )

        for (protocol in knownProtocols) {
            val encoded = ACPJson.encodeToString(LlmProtocol.serializer(), protocol)
            assertEquals("\"${protocol.value}\"", encoded)

            val decoded = ACPJson.decodeFromString(LlmProtocol.serializer(), encoded)
            assertEquals(protocol, decoded)
        }
    }

    @Test
    fun `unknown LlmProtocol value round-trips`() {
        val unknown = LlmProtocol("_custom")

        val encoded = ACPJson.encodeToString(LlmProtocol.serializer(), unknown)
        val decoded = ACPJson.decodeFromString(LlmProtocol.serializer(), encoded)

        assertEquals("\"_custom\"", encoded)
        assertEquals(unknown, decoded)
    }

    @Test
    fun `ProviderInfo with enabled current config round-trips`() {
        val info = ProviderInfo(
            id = "main",
            supported = listOf(LlmProtocol.OPENAI, LlmProtocol.ANTHROPIC),
            required = false,
            current = ProviderCurrentConfig(
                apiType = LlmProtocol.OPENAI,
                baseUrl = "https://api.openai.com/v1"
            )
        )

        val encoded = ACPJson.encodeToString(ProviderInfo.serializer(), info)
        val decoded = ACPJson.decodeFromString(ProviderInfo.serializer(), encoded)

        assertEquals(info, decoded)
    }

    @Test
    fun `ProviderInfo with omitted current round-trips as disabled`() {
        val info = ProviderInfo(
            id = "main",
            supported = listOf(LlmProtocol.OPENAI),
            required = false
        )

        val encoded = ACPJson.encodeToString(ProviderInfo.serializer(), info)
        val decoded = ACPJson.decodeFromString(ProviderInfo.serializer(), encoded)

        assertFalse(encoded.contains("\"current\""))
        assertNull(decoded.current)
        assertEquals(info, decoded)
    }

    @Test
    fun `incoming JSON with current null is accepted and mapped to disabled`() {
        val payload = """
            {
              "id": "main",
              "supported": ["openai"],
              "required": false,
              "current": null
            }
        """.trimIndent()

        val decoded = ACPJson.decodeFromString(ProviderInfo.serializer(), payload)
        val encoded = ACPJson.encodeToString(ProviderInfo.serializer(), decoded)

        assertEquals("main", decoded.id)
        assertNull(decoded.current)
        assertFalse(encoded.contains("\"current\""))
    }

    @Test
    fun `SetProvidersRequest handles optional headers correctly`() {
        val withoutHeaders = SetProvidersRequest(
            id = "main",
            apiType = LlmProtocol.OPENAI,
            baseUrl = "https://api.openai.com/v1"
        )
        val withoutHeadersJson = ACPJson.encodeToString(SetProvidersRequest.serializer(), withoutHeaders)
        val withoutHeadersDecoded = ACPJson.decodeFromString(SetProvidersRequest.serializer(), withoutHeadersJson)

        assertFalse(withoutHeadersJson.contains("\"headers\""))
        assertNull(withoutHeadersDecoded.headers)

        val withHeaders = SetProvidersRequest(
            id = "main",
            apiType = LlmProtocol.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            headers = mapOf("Authorization" to "Bearer token")
        )
        val withHeadersJson = ACPJson.encodeToString(SetProvidersRequest.serializer(), withHeaders)
        val withHeadersDecoded = ACPJson.decodeFromString(SetProvidersRequest.serializer(), withHeadersJson)

        assertTrue(withHeadersJson.contains("\"headers\""))
        assertEquals(withHeaders, withHeadersDecoded)
    }

    @Test
    fun `AgentCapabilities providers empty object round-trips`() {
        val payload = """
            {
              "providers": {}
            }
        """.trimIndent()

        val decoded = ACPJson.decodeFromString(AgentCapabilities.serializer(), payload)
        val encoded = ACPJson.encodeToString(AgentCapabilities.serializer(), decoded)
        val roundTripped = ACPJson.decodeFromString(AgentCapabilities.serializer(), encoded)

        assertNotNull(decoded.providers)
        assertNotNull(roundTripped.providers)
        assertEquals(decoded.providers, roundTripped.providers)
    }
}
