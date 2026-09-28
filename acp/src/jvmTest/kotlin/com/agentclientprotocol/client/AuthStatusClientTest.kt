@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.TransportFrame
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
import com.agentclientprotocol.client.v2.Client as V2Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo

class AuthStatusClientTest {
    @Test
    fun `v1 status refuses absent and false capabilities without wire dispatch`() {
        for (status in listOf(null, false)) {
            withV1Client(status) { client, transport ->
                val sentBefore = transport.sent.toList()
                val failure = assertFailsWith<AcpExpectedError> { client.authStatus() }
                assertTrue(failure.message.contains("auth/status"))
                assertTrue(failure.message.contains("auth.status"))
                assertEquals(sentBefore, transport.sent)
            }
        }
    }

    @Test
    fun `v2 status refuses absent and false capabilities without wire dispatch`() {
        for (status in listOf(null, false)) {
            withV2Client(status) { client, transport ->
                val sentBefore = transport.sent.toList()
                val failure = assertFailsWith<AcpExpectedError> { client.authStatus() }
                assertTrue(failure.message.contains("auth/status"))
                assertTrue(failure.message.contains("auth.status"))
                assertEquals(sentBefore, transport.sent)
            }
        }
    }

    @Test
    fun `status before initialization fails locally in both versions`() {
        withV1Client(true, initialize = false) { client, transport ->
            val failure = assertFailsWith<AcpExpectedError> { client.authStatus() }
            assertTrue(failure.message.contains("initialization"))
            assertTrue(transport.sent.isEmpty())
        }
        withV2Client(true, initialize = false) { client, transport ->
            val failure = assertFailsWith<AcpExpectedError> { client.authStatus() }
            assertTrue(failure.message.contains("initialization"))
            assertTrue(transport.sent.isEmpty())
        }
    }

    @Test
    fun `v1 advertised status can be queried repeatedly without a session`() = withV1Client(true) { client, transport ->
        val meta = ACPJson.parseToJsonElement("""{"trace":"v1"}""")
        assertEquals(false, client.authStatus(meta).authenticated)
        val second = client.authStatus()
        assertEquals(true, second.authenticated)
        assertEquals("Credentials configured", second.message)
        assertEquals(ACPJson.parseToJsonElement("""{"source":"agent"}"""), second._meta)
        assertEquals(listOf("initialize", "auth/status", "auth/status"), transport.requests.map { it.method.name })
        assertEquals(ACPJson.parseToJsonElement("""{"_meta":{"trace":"v1"}}"""), transport.requests[1].params)
        assertEquals(ACPJson.parseToJsonElement("{}"), transport.requests[2].params)
    }

    @Test
    fun `v2 advertised status works without auth methods or a session`() = withV2Client(true) { client, transport ->
        val meta = ACPJson.parseToJsonElement("""{"trace":"v2"}""")
        assertEquals(false, client.authStatus(meta).authenticated)
        val second = client.authStatus()
        assertEquals(true, second.authenticated)
        assertEquals("Credentials configured", second.message)
        assertEquals(ACPJson.parseToJsonElement("""{"source":"agent"}"""), second._meta)
        assertEquals(listOf("initialize", "auth/status", "auth/status"), transport.requests.map { it.method.name })
        assertEquals(ACPJson.parseToJsonElement("""{"_meta":{"trace":"v2"}}"""), transport.requests[1].params)
        assertEquals(ACPJson.parseToJsonElement("{}"), transport.requests[2].params)
    }

    @Test
    fun `advertised status reports remote method not found in both versions`() {
        withV1Client(true, statusImplemented = false) { client, transport ->
            val failure = assertFailsWith<JsonRpcException> { client.authStatus() }
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, failure.code)
            assertEquals(listOf("initialize", "auth/status"), transport.requests.map { it.method.name })
        }
        withV2Client(true, statusImplemented = false) { client, transport ->
            val failure = assertFailsWith<JsonRpcException> { client.authStatus() }
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, failure.code)
            assertEquals(listOf("initialize", "auth/status"), transport.requests.map { it.method.name })
        }
    }

    private fun withV1Client(
        status: Boolean?,
        initialize: Boolean = true,
        statusImplemented: Boolean = true,
        block: suspend (Client, StatusTransport) -> Unit,
    ) = runBlocking {
        val capabilities = if (status == null) "{}" else """{"auth":{"status":$status}}"""
        val transport = StatusTransport(
            ACPJson.parseToJsonElement("""{"protocolVersion":1,"agentCapabilities":$capabilities}"""),
            statusImplemented,
        )
        val protocol = Protocol(this, transport)
        val client = Client(protocol)
        protocol.start()
        try {
            withTimeout(10.seconds) {
                if (initialize) client.initialize(ClientInfo())
                block(client, transport)
            }
        } finally {
            protocol.close()
        }
    }

    private fun withV2Client(
        status: Boolean?,
        initialize: Boolean = true,
        statusImplemented: Boolean = true,
        block: suspend (V2Client, StatusTransport) -> Unit,
    ) = runBlocking {
        val capabilities = if (status == null) "{}" else """{"auth":{"status":$status}}"""
        val transport = StatusTransport(
            ACPJson.parseToJsonElement(
                """{"protocolVersion":2,"info":{"name":"agent","version":"1"},"capabilities":$capabilities}"""
            ),
            statusImplemented,
        )
        val protocol = Protocol(this, transport)
        val client = V2Client(protocol)
        protocol.start()
        try {
            withTimeout(10.seconds) {
                if (initialize) client.initialize(V2ClientInfo(PROTOCOL_VERSION_V2, Implementation("client", "1")))
                block(client, transport)
            }
        } finally {
            protocol.close()
        }
    }

    private class StatusTransport(
        private val initialization: JsonElement,
        private val statusImplemented: Boolean,
    ) : BaseTransport() {
        val sent = mutableListOf<JsonRpcMessage>()
        val requests: List<JsonRpcRequest> get() = sent.filterIsInstance<JsonRpcRequest>()
        private var queries = 0

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun close() {
            _state.value = Transport.State.CLOSED
            fireClose()
        }

        override fun send(frame: TransportFrame) {
            val request = (frame as TransportFrame.Single).message as JsonRpcRequest
            sent += request
            val response = when (request.method.name) {
                "initialize" -> JsonRpcSuccessResponse(request.id, initialization)
                "auth/status" -> if (statusImplemented) {
                    queries++
                    JsonRpcSuccessResponse(
                        request.id,
                        ACPJson.parseToJsonElement(
                            """{"authenticated":${queries > 1},"message":"Credentials configured","_meta":{"source":"agent"}}"""
                        ),
                    )
                } else {
                    JsonRpcErrorResponse(
                        request.id,
                        JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND.code, "auth/status is not implemented"),
                    )
                }
                else -> error("Unexpected method ${request.method.name}")
            }
            fireFrame(TransportFrame.Single(response))
        }
    }
}