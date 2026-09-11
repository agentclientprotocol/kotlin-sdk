@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.client.v2.Client
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class V2ClientAuthenticationTest {
    @Test
    fun `omitted auth methods prevent login and logout requests`() = withClient(
        """{
            "protocolVersion":2,
            "info":{"name":"agent","version":"1"},
            "capabilities":{"auth":{}}
        }""",
    ) { client, transport ->
        assertAuthenticationUnavailable(client, transport)
    }

    @Test
    fun `empty auth methods prevent login and logout requests`() = withClient(
        """{
            "protocolVersion":2,
            "info":{"name":"agent","version":"1"},
            "capabilities":{"auth":{}},
            "authMethods":[]
        }""",
    ) { client, transport ->
        assertAuthenticationUnavailable(client, transport)
    }

    @Test
    fun `authentication before initialization fails locally`() = withClient(
        AUTHENTICATED_AGENT,
        initialize = false,
    ) { client, transport ->
        val loginFailure = assertFailsWith<AcpExpectedError> { client.login(AuthMethodId("oauth")) }
        assertTrue(loginFailure.message.contains("initialization"))
        assertTrue(transport.sent.isEmpty())

        val logoutFailure = assertFailsWith<AcpExpectedError> { client.logout() }
        assertTrue(logoutFailure.message.contains("initialization"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `advertised authentication uses v2 method names and preserves metadata`() = withClient(
        AUTHENTICATED_AGENT,
    ) { client, transport ->
        val requestMeta = ACPJson.parseToJsonElement("""{"trace":"auth-test"}""")
        val responseMeta = ACPJson.parseToJsonElement("""{"source":"agent"}""")

        assertEquals(responseMeta, client.login(AuthMethodId("oauth"), requestMeta)._meta)
        assertEquals(responseMeta, client.logout(requestMeta)._meta)

        val requests = transport.sent.filterIsInstance<JsonRpcRequest>()
        assertEquals(listOf("initialize", "auth/login", "auth/logout"), requests.map { it.method.name })
        assertEquals(
            ACPJson.parseToJsonElement("""{"methodId":"oauth","_meta":{"trace":"auth-test"}}"""),
            requests[1].params,
        )
        assertEquals(
            ACPJson.parseToJsonElement("""{"_meta":{"trace":"auth-test"}}"""),
            requests[2].params,
        )
    }

    @Test
    fun `a methodId outside the advertised list prevents the login request`() = withClient(
        AUTHENTICATED_AGENT,
    ) { client, transport ->
        val sentBefore = transport.sent.toList()

        val failure = assertFailsWith<AcpExpectedError> { client.login(AuthMethodId("api-key")) }
        assertTrue(failure.message.contains("auth/login"), "unexpected failure: ${failure.message}")
        assertTrue(failure.message.contains("api-key"), "unexpected failure: ${failure.message}")
        assertTrue(failure.message.contains("oauth"), "unexpected failure: ${failure.message}")
        assertEquals(sentBefore, transport.sent, "An unadvertised methodId must not send a message")

        // The advertised method still works, so a rejected id is not a poisoned connection.
        client.login(AuthMethodId("oauth"))
        assertEquals(
            listOf("initialize", "auth/login"),
            transport.sent.filterIsInstance<JsonRpcRequest>().map { it.method.name },
        )
    }

    private suspend fun assertAuthenticationUnavailable(client: Client, transport: AuthTransport) {
        val sentBefore = transport.sent.toList()

        val loginFailure = assertFailsWith<AcpExpectedError> { client.login(AuthMethodId("oauth")) }
        assertTrue(loginFailure.message.contains("auth/login"))
        assertTrue(loginFailure.message.contains("authMethods"))
        assertEquals(sentBefore, transport.sent, "Unsupported login must not send a message")

        val logoutFailure = assertFailsWith<AcpExpectedError> { client.logout() }
        assertTrue(logoutFailure.message.contains("auth/logout"))
        assertTrue(logoutFailure.message.contains("authMethods"))
        assertEquals(sentBefore, transport.sent, "Unsupported logout must not send a message")
    }

    private fun withClient(
        initializeResponse: String,
        initialize: Boolean = true,
        block: suspend (Client, AuthTransport) -> Unit,
    ) = runBlocking {
        val transport = AuthTransport(ACPJson.parseToJsonElement(initializeResponse))
        val protocol = Protocol(this, transport)
        val client = Client(protocol)
        protocol.start()
        try {
            withTimeout(10.seconds) {
                if (initialize) {
                    client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("client", "1")))
                }
                block(client, transport)
            }
        } finally {
            protocol.close()
        }
    }

    /** Replies using literal wire names and JSON so both SDK sides cannot hide a naming regression. */
    private class AuthTransport(private val initializeResponse: JsonElement) : BaseTransport() {
        val sent = mutableListOf<JsonRpcMessage>()

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun close() {
            _state.value = Transport.State.CLOSED
            fireClose()
        }

        override fun send(message: JsonRpcMessage) {
            sent += message
            if (message !is JsonRpcRequest) return
            val response = when (message.method.name) {
                "initialize" -> JsonRpcResponse(message.id, result = initializeResponse)
                "auth/login", "auth/logout" -> JsonRpcResponse(
                    message.id,
                    result = ACPJson.parseToJsonElement("""{"_meta":{"source":"agent"}}"""),
                )
                else -> JsonRpcResponse(
                    message.id,
                    error = JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND.code, "Unexpected method"),
                )
            }
            fireMessage(response)
        }
    }

    private companion object {
        const val AUTHENTICATED_AGENT = """{
            "protocolVersion":2,
            "info":{"name":"agent","version":"1"},
            "authMethods":[{"type":"agent","methodId":"oauth","name":"OAuth"}]
        }"""
    }
}
