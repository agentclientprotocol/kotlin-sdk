package com.agentclientprotocol.rpc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestIdTest {

    @Test
    fun `create Int RequestId using factory method`() {
        val requestId = RequestId.create(42)
        assertEquals(42, requestId.value)
        assertEquals("42", requestId.toString())
    }

    @Test
    fun `create String RequestId using factory method`() {
        val requestId = RequestId.create("test-id-123")
        assertEquals("test-id-123", requestId.value)
        assertEquals("test-id-123", requestId.toString())
    }

    @Test
    fun `serialize Int RequestId to JSON number`() {
        val requestId = RequestId.create(42)
        val request = JsonRpcRequest(
            id = requestId,
            method = MethodName("test.method")
        )

        val json = ACPJson.encodeToString(request)
        assertTrue(json.contains("\"id\":42"), "Expected id to be serialized as number: $json")
    }

    @Test
    fun `serialize String RequestId to JSON string`() {
        val requestId = RequestId.create("test-id-123")
        val request = JsonRpcRequest(
            id = requestId,
            method = MethodName("test.method")
        )

        val json = ACPJson.encodeToString(request)
        assertTrue(json.contains("\"id\":\"test-id-123\""), "Expected id to be serialized as string: $json")
    }

    @Test
    fun `deserialize JSON number to Int RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": 42,
                "method": "test.method"
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString<JsonRpcRequest>(json)
        assertEquals(42, request.id.value)
        assertIs<Int>(request.id.value)
    }

    @Test
    fun `deserialize JSON string to String RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "test-id-123",
                "method": "test.method"
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString<JsonRpcRequest>(json)
        assertEquals("test-id-123", request.id.value)
        assertIs<String>(request.id.value)
    }

    @Test
    fun `round-trip Int RequestId serialization`() {
        val originalId = RequestId.create(999)
        val request = JsonRpcRequest(
            id = originalId,
            method = MethodName("test.method"),
            params = JsonPrimitive("test")
        )

        val json = ACPJson.encodeToString(request)
        val decoded = ACPJson.decodeFromString<JsonRpcRequest>(json)

        assertEquals(originalId.value, decoded.id.value)
        assertEquals(originalId.toString(), decoded.id.toString())
    }

    @Test
    fun `round-trip String RequestId serialization`() {
        val originalId = RequestId.create("uuid-1234-5678")
        val request = JsonRpcRequest(
            id = originalId,
            method = MethodName("test.method"),
            params = JsonPrimitive("test")
        )

        val json = ACPJson.encodeToString(request)
        val decoded = ACPJson.decodeFromString<JsonRpcRequest>(json)

        assertEquals(originalId.value, decoded.id.value)
        assertEquals(originalId.toString(), decoded.id.toString())
    }

    @Test
    fun `JsonRpcResponse with Int RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": 123,
                "result": {"status": "ok"}
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString<JsonRpcResponse>(json)
        assertEquals(123, response.id.value)
        assertIs<Int>(response.id.value)
    }

    @Test
    fun `JsonRpcResponse with String RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "response-abc-123",
                "result": {"status": "ok"}
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString<JsonRpcResponse>(json)
        assertEquals("response-abc-123", response.id.value)
        assertIs<String>(response.id.value)
    }

    @Test
    fun `decodeJsonRpcMessage with Int RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": 42,
                "method": "test.method"
            }
        """.trimIndent()

        val message = decodeJsonRpcMessage(json)
        assertIs<JsonRpcRequest>(message)
        assertEquals(42, message.id.value)
    }

    @Test
    fun `decodeJsonRpcMessage with String RequestId`() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "message-id-xyz",
                "method": "test.method"
            }
        """.trimIndent()

        val message = decodeJsonRpcMessage(json)
        assertIs<JsonRpcRequest>(message)
        assertEquals("message-id-xyz", message.id.value)
    }

    @Test
    fun `multiple Int RequestIds with different values`() {
        val id1 = RequestId.create(1)
        val id2 = RequestId.create(2)
        val id3 = RequestId.create(100)

        assertEquals(1, id1.value)
        assertEquals(2, id2.value)
        assertEquals(100, id3.value)
    }

    @Test
    fun `multiple String RequestIds with different values`() {
        val id1 = RequestId.create("first")
        val id2 = RequestId.create("second")
        val id3 = RequestId.create("third")

        assertEquals("first", id1.value)
        assertEquals("second", id2.value)
        assertEquals("third", id3.value)
    }

    @Test
    fun `RequestId with negative integer`() {
        val requestId = RequestId.create(-1)
        assertEquals(-1, requestId.value)

        val request = JsonRpcRequest(id = requestId, method = MethodName("test"))
        val json = ACPJson.encodeToString(request)
        val decoded = ACPJson.decodeFromString<JsonRpcRequest>(json)

        assertEquals(-1, decoded.id.value)
    }

    @Test
    fun `RequestId with zero`() {
        val requestId = RequestId.create(0)
        assertEquals(0, requestId.value)

        val request = JsonRpcRequest(id = requestId, method = MethodName("test"))
        val json = ACPJson.encodeToString(request)
        val decoded = ACPJson.decodeFromString<JsonRpcRequest>(json)

        assertEquals(0, decoded.id.value)
    }

    @Test
    fun `RequestId with empty string`() {
        val requestId = RequestId.create("")
        assertEquals("", requestId.value)
        assertEquals("", requestId.toString())
    }

    @Test
    fun `RequestId with special characters in string`() {
        val specialId = "test-id_123!@#$%"
        val requestId = RequestId.create(specialId)
        assertEquals(specialId, requestId.value)

        val request = JsonRpcRequest(id = requestId, method = MethodName("test"))
        val json = ACPJson.encodeToString(request)
        val decoded = ACPJson.decodeFromString<JsonRpcRequest>(json)

        assertEquals(specialId, decoded.id.value)
    }
}
