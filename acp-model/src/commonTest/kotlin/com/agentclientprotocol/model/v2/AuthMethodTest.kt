@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AuthEnvVar
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.AuthMethod as V1AuthMethod

class AuthMethodTest {

    // Known variants

    @Test
    fun `decodes known variants`() {
        assertEquals(
            AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "Log in"),
            decode("""{"type":"agent","methodId":"oauth","name":"Log in"}"""),
        )
        assertEquals(
            AuthMethod.EnvVar(
                methodId = AuthMethodId("api-key"),
                name = "API Key",
                vars = listOf(AuthEnvVar(name = "API_KEY")),
            ),
            decode("""{"type":"env_var","methodId":"api-key","name":"API Key","vars":[{"name":"API_KEY"}]}"""),
        )
        assertEquals(
            AuthMethod.Terminal(methodId = AuthMethodId("login"), name = "Terminal login"),
            decode("""{"type":"terminal","methodId":"login","name":"Terminal login"}"""),
        )
    }

    @Test
    fun `encodes known variants with leading discriminator`() {
        assertEquals(
            """{"type":"agent","methodId":"oauth","name":"Log in"}""",
            encode(AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "Log in")),
        )
        assertEquals(
            """{"type":"terminal","methodId":"login","name":"Terminal login","args":[],"env":[]}""",
            encode(AuthMethod.Terminal(methodId = AuthMethodId("login"), name = "Terminal login")),
        )
    }

    @Test
    fun `known variants round-trip`() {
        val envVar = AuthMethod.EnvVar(
            methodId = AuthMethodId("api-key"),
            name = "API Key",
            description = "Provide your key",
            vars = listOf(AuthEnvVar(name = "API_KEY", label = "Key", secret = true, optional = false)),
            link = "https://example.com/keys",
        )

        assertEquals(envVar, decode(encode(envVar)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown method as Unknown with parsed identity and full payload`() {
        val json = """{"type":"oauth","methodId":"m1","name":"OAuth","authUrl":"https://example.com/auth"}"""

        val method = decode(json)

        assertIs<AuthMethod.Unknown>(method)
        assertEquals("oauth", method.type)
        assertEquals(AuthMethodId("m1"), method.methodId)
        assertEquals("OAuth", method.name)
        assertEquals(json, encode(method))
    }

    @Test
    fun `unknown method without methodId fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"oauth","id":"m1","name":"OAuth"}""")
        }
    }

    // Strictness

    @Test
    fun `missing discriminator fails instead of defaulting to agent`() {
        assertFailsWith<SerializationException> {
            decode("""{"methodId":"oauth","name":"Log in"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"env_var","methodId":"api-key","name":"API Key"}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1 mapping methodId to id`() {
        assertEquals(
            V1AuthMethod.AgentAuth(id = AuthMethodId("oauth"), name = "Log in"),
            AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "Log in").toV1(),
        )
        assertEquals(
            V1AuthMethod.TerminalAuth(
                id = AuthMethodId("login"),
                name = "Terminal login",
                args = listOf("--login"),
                env = mapOf("MODE" to "tui"),
            ),
            AuthMethod.Terminal(
                methodId = AuthMethodId("login"),
                name = "Terminal login",
                args = listOf("--login"),
                env = listOf(EnvVariable("MODE", "tui")),
            ).toV1(),
        )
    }

    @Test
    fun `converts empty terminal args and env to absent v1 values`() {
        val v1 = AuthMethod.Terminal(methodId = AuthMethodId("login"), name = "Terminal login").toV1()

        assertEquals(
            V1AuthMethod.TerminalAuth(id = AuthMethodId("login"), name = "Terminal login", args = null, env = null),
            v1,
        )
    }

    @Test
    fun `converting Unknown to v1 fails instead of fabricating a payload`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            AuthMethod.Unknown(
                type = "oauth",
                methodId = AuthMethodId("m1"),
                name = "OAuth",
                rawJson = buildJsonObject { put("type", "oauth") },
            ).toV1()
        }

        assertEquals("v2 AuthMethod variant `oauth` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts v1 variants to v2 mapping id to methodId`() {
        assertEquals(
            AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "Log in"),
            V1AuthMethod.AgentAuth(id = AuthMethodId("oauth"), name = "Log in").toV2(),
        )
        assertEquals(
            AuthMethod.EnvVar(
                methodId = AuthMethodId("api-key"),
                name = "API Key",
                vars = listOf(AuthEnvVar(name = "API_KEY")),
            ),
            V1AuthMethod.EnvVarAuth(
                id = AuthMethodId("api-key"),
                name = "API Key",
                vars = listOf(AuthEnvVar(name = "API_KEY")),
            ).toV2(),
        )
        assertEquals(
            AuthMethod.Terminal(
                methodId = AuthMethodId("login"),
                name = "Terminal login",
                env = listOf(EnvVariable("MODE", "tui")),
            ),
            V1AuthMethod.TerminalAuth(
                id = AuthMethodId("login"),
                name = "Terminal login",
                env = mapOf("MODE" to "tui"),
            ).toV2(),
        )
    }

    @Test
    fun `converting v1 UnknownAuthMethod to v2 fails instead of fabricating a payload`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            V1AuthMethod.UnknownAuthMethod(
                id = AuthMethodId("m1"),
                name = "OAuth",
                type = "oauth",
                rawJson = buildJsonObject { put("type", "oauth") },
            ).toV2()
        }

        assertEquals("v1 AuthMethod variant `oauth` cannot be represented in v2", exception.message)
    }

    private fun decode(json: String): AuthMethod =
        ACPJson.decodeFromString(AuthMethod.serializer(), json)

    private fun encode(method: AuthMethod): String =
        ACPJson.encodeToString(AuthMethod.serializer(), method)
}
