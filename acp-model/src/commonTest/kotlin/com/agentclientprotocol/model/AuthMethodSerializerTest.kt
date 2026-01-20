package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class AuthMethodSerializerTest {

    // Backward compatibility tests

    @Test
    fun `decodes AuthMethod without type field as AgentAuth for backward compatibility`() {
        val payload = """
            {
                "id": "auth-1",
                "name": "Default Auth",
                "description": "Agent-managed authentication"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.AgentAuth)
        assertEquals("auth-1", authMethod.id.value)
        assertEquals("Default Auth", authMethod.name)
        assertEquals("Agent-managed authentication", authMethod.description)
    }

    @Test
    fun `decodes AuthMethod with type agent as AgentAuth`() {
        val payload = """
            {
                "id": "auth-2",
                "name": "Agent Auth",
                "type": "agent"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.AgentAuth)
        assertEquals("auth-2", authMethod.id.value)
        assertEquals("Agent Auth", authMethod.name)
    }

    // EnvVarAuth tests

    @Test
    fun `decodes AuthMethod with type env_var as EnvVarAuth`() {
        val payload = """
            {
                "id": "auth-3",
                "name": "API Key",
                "description": "Set API key environment variable",
                "type": "env_var",
                "varName": "API_KEY",
                "link": "https://example.com/get-key"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.EnvVarAuth)
        assertEquals("auth-3", authMethod.id.value)
        assertEquals("API Key", authMethod.name)
        assertEquals("Set API key environment variable", authMethod.description)
        assertEquals("API_KEY", authMethod.varName)
        assertEquals("https://example.com/get-key", authMethod.link)
    }

    @Test
    fun `decodes EnvVarAuth without optional link field`() {
        val payload = """
            {
                "id": "auth-env",
                "name": "OpenAI Key",
                "type": "env_var",
                "varName": "OPENAI_API_KEY"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.EnvVarAuth)
        assertEquals("OPENAI_API_KEY", authMethod.varName)
        assertEquals(null, authMethod.link)
    }

    // TerminalAuth tests

    @Test
    fun `decodes AuthMethod with type terminal as TerminalAuth`() {
        val payload = """
            {
                "id": "auth-4",
                "name": "OAuth Login",
                "type": "terminal",
                "args": ["--setup"],
                "env": {"DISPLAY": ":0", "BROWSER": "chrome"}
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.TerminalAuth)
        assertEquals("auth-4", authMethod.id.value)
        assertEquals("OAuth Login", authMethod.name)
        assertEquals(listOf("--setup"), authMethod.args)
        assertEquals(mapOf("DISPLAY" to ":0", "BROWSER" to "chrome"), authMethod.env)
    }

    @Test
    fun `decodes TerminalAuth without optional args and env fields`() {
        val payload = """
            {
                "id": "auth-term",
                "name": "Terminal Login",
                "type": "terminal"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.TerminalAuth)
        assertEquals(null, authMethod.args)
        assertEquals(null, authMethod.env)
    }

    // UnknownAuthMethod tests

    @Test
    fun `decodes AuthMethod with unknown type as UnknownAuthMethod`() {
        val payload = """
            {
                "id": "auth-5",
                "name": "Future Auth",
                "description": "Some future auth method",
                "type": "biometric"
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.UnknownAuthMethod)
        assertEquals("auth-5", authMethod.id.value)
        assertEquals("Future Auth", authMethod.name)
        assertEquals("Some future auth method", authMethod.description)
        assertEquals("biometric", authMethod.type)
    }

    @Test
    fun `UnknownAuthMethod preserves raw JSON for inspection`() {
        val payload = """
            {
                "id": "auth-future",
                "name": "OAuth 3.0",
                "type": "oauth3",
                "clientId": "abc123",
                "scopes": ["read", "write"]
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.UnknownAuthMethod)
        assertEquals("oauth3", authMethod.type)
        // Raw JSON contains the extra fields
        assertTrue(authMethod.rawJson.containsKey("clientId"))
        assertTrue(authMethod.rawJson.containsKey("scopes"))
    }

    // Round-trip serialization tests

    @Test
    fun `round-trip serialization for AgentAuth includes type discriminator`() {
        val original = AuthMethod.AgentAuth(
            id = AuthMethodId("auth-rt-1"),
            name = "Round Trip Agent",
            description = "Test description"
        )

        val encoded = ACPJson.encodeToString(AuthMethod.serializer(), original)
        assertTrue(encoded.contains("\"type\":\"agent\""))

        val decoded = ACPJson.decodeFromString(AuthMethod.serializer(), encoded)
        assertTrue(decoded is AuthMethod.AgentAuth)
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.description, decoded.description)
    }

    @Test
    fun `round-trip serialization for EnvVarAuth`() {
        val original = AuthMethod.EnvVarAuth(
            id = AuthMethodId("auth-rt-2"),
            name = "Round Trip EnvVar",
            varName = "MY_VAR",
            link = "https://example.com"
        )

        val encoded = ACPJson.encodeToString(AuthMethod.serializer(), original)
        assertTrue(encoded.contains("\"type\":\"env_var\""))
        assertTrue(encoded.contains("\"varName\":\"MY_VAR\""))

        val decoded = ACPJson.decodeFromString(AuthMethod.serializer(), encoded)
        assertTrue(decoded is AuthMethod.EnvVarAuth)
        val decodedEnvVar = decoded as AuthMethod.EnvVarAuth
        assertEquals(original.id, decodedEnvVar.id)
        assertEquals(original.name, decodedEnvVar.name)
        assertEquals(original.varName, decodedEnvVar.varName)
        assertEquals(original.link, decodedEnvVar.link)
    }

    @Test
    fun `round-trip serialization for TerminalAuth`() {
        val original = AuthMethod.TerminalAuth(
            id = AuthMethodId("auth-rt-3"),
            name = "Round Trip Terminal",
            args = listOf("--flag", "--verbose"),
            env = mapOf("KEY" to "value", "DEBUG" to "true")
        )

        val encoded = ACPJson.encodeToString(AuthMethod.serializer(), original)
        assertTrue(encoded.contains("\"type\":\"terminal\""))

        val decoded = ACPJson.decodeFromString(AuthMethod.serializer(), encoded)
        assertTrue(decoded is AuthMethod.TerminalAuth)
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.args, decoded.args)
        assertEquals(original.env, decoded.env)
    }

    // Context tests

    @Test
    fun `decodes AuthMethod list in InitializeResponse`() {
        val payload = """
            {
                "protocolVersion": 1,
                "agentCapabilities": {},
                "authMethods": [
                    {"id": "a1", "name": "Agent Only"},
                    {"id": "a2", "name": "Env Auth", "type": "env_var", "varName": "TOKEN"},
                    {"id": "a3", "name": "Terminal Auth", "type": "terminal"}
                ]
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(InitializeResponse.serializer(), payload)

        assertEquals(3, response.authMethods.size)
        assertTrue(response.authMethods[0] is AuthMethod.AgentAuth)
        assertTrue(response.authMethods[1] is AuthMethod.EnvVarAuth)
        assertTrue(response.authMethods[2] is AuthMethod.TerminalAuth)
    }

    @Test
    fun `decodes mixed auth methods list with unknown type`() {
        val payload = """
            {
                "protocolVersion": 1,
                "agentCapabilities": {},
                "authMethods": [
                    {"id": "a1", "name": "Agent", "type": "agent"},
                    {"id": "a2", "name": "Future", "type": "quantum_auth", "qubits": 256}
                ]
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(InitializeResponse.serializer(), payload)

        assertEquals(2, response.authMethods.size)
        assertTrue(response.authMethods[0] is AuthMethod.AgentAuth)
        assertTrue(response.authMethods[1] is AuthMethod.UnknownAuthMethod)
        assertEquals("quantum_auth", (response.authMethods[1] as AuthMethod.UnknownAuthMethod).type)
    }

    // Metadata tests

    @Test
    fun `preserves _meta field during deserialization`() {
        val payload = """
            {
                "id": "auth-meta",
                "name": "With Meta",
                "type": "agent",
                "_meta": {"custom": "data", "version": 2}
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.AgentAuth)
        assertNotNull(authMethod._meta)
    }

    @Test
    fun `preserves _meta field for EnvVarAuth`() {
        val payload = """
            {
                "id": "auth-env-meta",
                "name": "EnvVar With Meta",
                "type": "env_var",
                "varName": "KEY",
                "_meta": {"source": "config"}
            }
        """.trimIndent()

        val authMethod = ACPJson.decodeFromString(AuthMethod.serializer(), payload)

        assertTrue(authMethod is AuthMethod.EnvVarAuth)
        assertNotNull(authMethod._meta)
    }
}
