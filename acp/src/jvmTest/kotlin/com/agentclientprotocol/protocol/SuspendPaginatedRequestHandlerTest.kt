package com.agentclientprotocol.protocol

import com.agentclientprotocol.agent.TestTransport
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class SuspendPaginatedRequestHandlerTest {

    @Serializable
    private data class TestRequest(
        override val cursor: String? = null,
        override val _meta: JsonElement? = null,
    ) : AcpPaginatedRequest

    @Serializable
    private data class TestResponse(
        val items: List<Int>,
        override val nextCursor: String?,
        override val _meta: JsonElement? = null,
    ) : AcpPaginatedResponse<Int> {
        override fun getItemsBatch(): List<Int> = items
    }

    private object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>(
        "test/paginate",
        TestRequest.serializer(),
        TestResponse.serializer(),
    )

    private suspend fun TestTransport.testPaginate(request: TestRequest): TestResponse {
        val received = fireTestRequest(TestMethod.methodName, ACPJson.encodeToJsonElement(TestMethod.requestSerializer, request))
        val response = received.last() as JsonRpcResponse
        return ACPJson.decodeFromJsonElement(TestMethod.responseSerializer, requireNotNull(response.result))
    }

    @Test
    fun `pageFactory receives null cursor for the first request`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        var receivedCursor: String? = "not called"

        protocol.setSuspendPaginatedRequestHandler(
            TestMethod,
            resultFactory = { _, batch, newCursor -> TestResponse(batch, newCursor) },
            pageFactory = { request ->
                receivedCursor = request.cursor
                listOf(1, 2, 3) to null
            },
        )
        protocol.start()

        transport.testPaginate(TestRequest())

        assertNull(receivedCursor)
        protocol.close()
    }

    @Test
    fun `pageFactory receives exactly the cursor the client sends back`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        var receivedCursor: String? = null

        protocol.setSuspendPaginatedRequestHandler(
            TestMethod,
            resultFactory = { _, batch, newCursor -> TestResponse(batch, newCursor) },
            pageFactory = { request ->
                receivedCursor = request.cursor
                listOf(4, 5) to null
            },
        )
        protocol.start()

        transport.testPaginate(TestRequest(cursor = "db-keyset-position-42"))

        assertEquals("db-keyset-position-42", receivedCursor)
        protocol.close()
    }

    @Test
    fun `batch and cursor from pageFactory flow through to the response`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)

        protocol.setSuspendPaginatedRequestHandler(
            TestMethod,
            resultFactory = { _, batch, newCursor -> TestResponse(batch, newCursor) },
            pageFactory = { listOf(1, 2, 3) to "next-page-cursor" },
        )
        protocol.start()

        val response = transport.testPaginate(TestRequest())

        assertEquals(listOf(1, 2, 3), response.items)
        assertEquals("next-page-cursor", response.nextCursor)
        protocol.close()
    }

    @Test
    fun `a cursor can be reused, unlike a single-use iterator cursor`() = runBlocking {
        val transport = TestTransport(5.seconds)
        val protocol = Protocol(this, transport)
        // No state kept by the handler itself: the same cursor always maps to the same page, because the
        // caller (not the SDK) owns what the cursor encodes.
        val pages = mapOf(null to (listOf(1, 2) to "page-2"), "page-2" to (listOf(3, 4) to null))

        protocol.setSuspendPaginatedRequestHandler(
            TestMethod,
            resultFactory = { _, batch, newCursor -> TestResponse(batch, newCursor) },
            pageFactory = { request -> pages.getValue(request.cursor) },
        )
        protocol.start()

        val first = transport.testPaginate(TestRequest(cursor = "page-2"))
        val second = transport.testPaginate(TestRequest(cursor = "page-2"))

        assertEquals(first, second)
        protocol.close()
    }
}
