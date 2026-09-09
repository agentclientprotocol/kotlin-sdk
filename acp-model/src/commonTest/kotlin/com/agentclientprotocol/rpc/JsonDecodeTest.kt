package com.agentclientprotocol.rpc

import kotlinx.serialization.json.*
import kotlin.test.*

class JsonDecodeTest {
    @Test
    fun messageSerializersAndWireFramesRoundTrip() {
        val messages: List<JsonRpcMessage> = listOf(
            JsonRpcRequest(RequestId.create("request"), MethodName("test")),
            JsonRpcNotification(MethodName("notify")),
            JsonRpcResponse(RequestId.create("request"), JsonPrimitive("ok")),
        )
        for (message in messages) {
            val serializedMessage = ACPJson.encodeToString(JsonRpcMessage.serializer(), message)
            assertEquals(message, ACPJson.decodeFromString(JsonRpcMessage.serializer(), serializedMessage))
            val frame = TransportFrame.Single(message)
            assertEquals(frame, TransportFrame.parse(frame.toJson()))
            assertFalse("type" in Json.parseToJsonElement(frame.toJson()).jsonObject)
        }
    }

    @Test
    fun singlesAndNulls() {
        val request = assertIs<TransportFrame.Single>(TransportFrame.parse("""{"jsonrpc":"2.0","id":null,"method":"test"}"""))
        assertEquals(RequestId.Null, assertIs<JsonRpcRequest>(request.message).id)
        assertIs<JsonRpcNotification>(assertIs<TransportFrame.Single>(TransportFrame.parse("""{"jsonrpc":"2.0","method":"test"}""")).message)
        val response = TransportFrame.Single(JsonRpcResponse(RequestId.Null))
        assertEquals("""{"jsonrpc":"2.0","id":null,"result":null}""", response.toJson())
        assertEquals(response.toJson(), TransportFrame.parse(response.toJson()).toJson())
        val error = TransportFrame.Single(JsonRpcResponse(RequestId.Null, error = JsonRpcError(-32600, "Invalid Request")))
        val obj = Json.parseToJsonElement(error.toJson()).jsonObject
        assertEquals(JsonNull, obj["id"])
        assertFalse("result" in obj)
        assertTrue("error" in obj)
        assertFalse("type" in obj)
    }

    @Test
    fun partialErrorsAndRoundTrip() {
        val raw = """[{"jsonrpc":"2.0","id":1,"method":"test"},42,[],{"jsonrpc":"2.0","method":"notify"}]"""
        val frame = assertIs<TransportFrame.Batch>(TransportFrame.parse(raw))
        assertEquals(4, frame.entries.size)
        assertIs<TransportFrame.Single>(frame.entries[0])
        assertIs<TransportFrame.Malformed>(frame.entries[1])
        assertIs<TransportFrame.Malformed>(frame.entries[2])
        assertIs<TransportFrame.Single>(frame.entries[3])
        assertEquals(frame, TransportFrame.parse(frame.toJson()))
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(frame.toJson()))
    }

    @Test
    fun envelopeValidation() {
        for (raw in listOf(
            "[]", "null", "42", "{}",
            """{"jsonrpc":"1.0","method":"test"}""",
            """{"jsonrpc":2.0,"method":"test"}""",
            """{"jsonrpc":"2.0","method":null}""",
            """{"jsonrpc":"2.0","method":42}""",
            """{"jsonrpc":"2.0","method":"test","id":false}""",
            """{"jsonrpc":"2.0","method":"test","id":1.5}""",
            """{"jsonrpc":"2.0","method":"test","params":42}""",
            """{"jsonrpc":"2.0","method":"test","result":{}}""",
            """{"jsonrpc":"2.0","id":1,"result":{},"error":null}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":"1","message":"bad"}}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":42}}""",
        )) {
            assertEquals(-32600, assertIs<TransportFrame.Malformed>(TransportFrame.parse(raw), raw).error.code, raw)
        }
        assertIs<TransportFrame.Single>(TransportFrame.parse("""{"jsonrpc":"2.0","method":"test","extension":true}"""))
    }

    @Test
    fun malformedResponseClassification() {
        for (raw in listOf("""{"result":null}""", """{"id":1,"error":null}""")) {
            assertTrue(assertIs<TransportFrame.Malformed>(TransportFrame.parse(raw)).isResponse)
        }
        for (raw in listOf("""{"method":null,"result":null}""", """{"id":1}""", "[]")) {
            assertFalse(assertIs<TransportFrame.Malformed>(TransportFrame.parse(raw)).isResponse)
        }
    }

    @Test
    fun batchesCannotBeEmptyOrChangedByTheirInputList() {
        assertFailsWith<IllegalArgumentException> { TransportFrame.Batch(emptyList()) }
        val entries = mutableListOf<TransportFrame.Entry>(TransportFrame.Single(JsonRpcNotification(MethodName("test"))))
        val batch = TransportFrame.Batch(entries)
        entries.clear()
        assertEquals(1, batch.entries.size)
    }

    @Test
    fun responseCannotEncodeBothResultAndError() {
        assertFailsWith<IllegalArgumentException> {
            TransportFrame.Single(JsonRpcResponse(RequestId.create(1), JsonNull, JsonRpcError(-1, "error"))).toJson()
        }
    }
}
