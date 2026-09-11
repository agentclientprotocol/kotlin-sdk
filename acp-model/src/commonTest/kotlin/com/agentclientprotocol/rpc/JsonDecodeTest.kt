package com.agentclientprotocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class JsonDecodeTest {
    private fun encode(frame: TransportFrame): String = JsonRpcJson.encodeToString(TransportFrame.serializer(), frame)

    @Test
    fun messageResponseAndFrameSerializersUseTheSameWireShape() {
        val messages = listOf(
            JsonRpcRequest(RequestId.create("request"), MethodName("test")),
            JsonRpcNotification(MethodName("notify")),
            JsonRpcSuccessResponse(RequestId.create("request"), JsonPrimitive("ok")),
            JsonRpcSuccessResponse(RequestId.Null, JsonNull),
            JsonRpcErrorResponse(RequestId.Null, JsonRpcError(-32600, "Invalid Request")),
        )
        // Required fields must survive even when defaults and Kotlin nulls are omitted.
        for (json in listOf(ACPJson, JsonRpcJson, Json { encodeDefaults = false; explicitNulls = false })) {
            for (message in messages) {
                val text = json.encodeToString(JsonRpcMessage.serializer(), message)
                assertEquals(message, json.decodeFromString(JsonRpcMessage.serializer(), text))
                val obj = Json.parseToJsonElement(text).jsonObject
                assertEquals(JsonPrimitive("2.0"), obj["jsonrpc"])
                assertFalse("type" in obj)
                val frame: TransportFrame = TransportFrame.Single(message)
                assertEquals(text, json.encodeToString(TransportFrame.serializer(), frame))
                assertEquals(frame, parseTransportFrame(text))
                if (message is JsonRpcResponse) {
                    assertEquals(text, json.encodeToString(JsonRpcResponse.serializer(), message))
                    assertEquals(message, json.decodeFromString(JsonRpcResponse.serializer(), text))
                    assertTrue("id" in obj)
                    assertTrue(("result" in obj) != ("error" in obj))
                }
            }
        }
    }

    @Test
    fun nullIdsAndResultsAreNotOmittedOrConfusedWithNotifications() {
        val request = assertIs<TransportFrame.Single>(parseTransportFrame("""{"jsonrpc":"2.0","id":null,"method":"test"}"""))
        assertEquals(RequestId.Null, assertIs<JsonRpcRequest>(request.message).id)
        assertIs<JsonRpcNotification>(assertIs<TransportFrame.Single>(parseTransportFrame("""{"jsonrpc":"2.0","method":"test"}""")).message)
        val response = TransportFrame.Single(JsonRpcSuccessResponse(RequestId.Null, JsonNull))
        val obj = Json.parseToJsonElement(encode(response)).jsonObject
        assertEquals(setOf("jsonrpc", "id", "result"), obj.keys)
        assertEquals(JsonNull, obj["id"])
        assertEquals(JsonNull, obj["result"])
        assertEquals(response, parseTransportFrame(encode(response)))
    }

    @Test
    fun validBatchesRoundTripWithoutChangingShape() {
        val entry = TransportFrame.Single(JsonRpcNotification(MethodName("test")))
        for (entries in listOf(listOf(entry), listOf(entry, entry))) {
            val batch = TransportFrame.Batch(entries)
            assertIs<JsonArray>(Json.parseToJsonElement(encode(batch)))
            assertEquals(batch, parseTransportFrame(encode(batch)))
        }
    }

    @Test
    fun partialErrorsPreserveSiblingsButCannotBeSerialized() {
        val frame = assertIs<TransportFrame.Batch>(parseTransportFrame(
            """[{"jsonrpc":"2.0","id":1,"method":"test"},42,[],{"jsonrpc":"2.0","method":"notify"}]"""
        ))
        assertEquals(4, frame.entries.size)
        assertIs<JsonRpcRequest>(assertIs<TransportFrame.Single>(frame.entries[0]).message)
        assertEquals(-32600, assertIs<TransportFrame.Malformed>(frame.entries[1]).error.code)
        assertEquals(-32600, assertIs<TransportFrame.Malformed>(frame.entries[2]).error.code)
        assertIs<JsonRpcNotification>(assertIs<TransportFrame.Single>(frame.entries[3]).message)
        assertFailsWith<SerializationException> { encode(frame) }
        assertFailsWith<SerializationException> { encode(frame.entries[1]) }
    }

    @Test
    fun syntaxFailuresIncludingTrailingInputBecomeStandaloneParseErrors() {
        for (text in listOf("", "{", "[", "{} {}", "{} trailing", "\"unfinished", "[{},")) {
            val malformed = assertIs<TransportFrame.Malformed>(parseTransportFrame(text), text)
            assertEquals(-32700, malformed.error.code, text)
            assertFalse(malformed.isResponse, text)
            assertFailsWith<SerializationException> { encode(malformed) }
        }
    }

    @Test
    fun invalidEnvelopesAreMalformedInFramesButThrowInMessageSerializer() {
        for (text in listOf(
            "[]", "null", "42", "{}",
            """{"method":"test"}""",
            """{"jsonrpc":"1.0","method":"test"}""",
            """{"jsonrpc":2.0,"method":"test"}""",
            """{"jsonrpc":"2.0","method":null}""",
            """{"jsonrpc":"2.0","method":42}""",
            """{"jsonrpc":"2.0","method":"test","id":false}""",
            """{"jsonrpc":"2.0","method":"test","id":1.5}""",
            """{"jsonrpc":"2.0","method":"test","params":42}""",
            """{"jsonrpc":"2.0","method":"test","result":{}}""",
            """{"jsonrpc":"2.0","result":{}}""",
            """{"jsonrpc":"2.0","id":1}""",
            """{"jsonrpc":"2.0","id":1,"result":{},"error":null}""",
            """{"jsonrpc":"2.0","id":1,"result":{},"params":{}}""",
            """{"jsonrpc":"2.0","id":1,"error":null}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":"1","message":"bad"}}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":1.5,"message":"bad"}}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":42}}""",
        )) {
            assertEquals(-32600, assertIs<TransportFrame.Malformed>(parseTransportFrame(text), text).error.code, text)
            assertFailsWith<SerializationException>(text) { JsonRpcJson.decodeFromString(JsonRpcMessage.serializer(), text) }
        }
        for (params in listOf("null", "{}", "[]")) {
            assertIs<TransportFrame.Single>(parseTransportFrame("""{"jsonrpc":"2.0","method":"test","params":$params,"extension":true}"""))
        }
    }

    @Test
    fun directResponseSerializerValidatesTheWholeEnvelope() {
        for (text in listOf(
            """{"id":1,"result":null}""",
            """{"jsonrpc":"1.0","id":1,"result":null}""",
            """{"jsonrpc":"2.0","result":null}""",
            """{"jsonrpc":"2.0","id":1}""",
            """{"jsonrpc":"2.0","id":1,"result":null,"error":{}}""",
            """{"jsonrpc":"2.0","id":1,"method":"test","result":null}""",
            """{"jsonrpc":"2.0","id":1,"params":{},"result":null}""",
        )) {
            assertFailsWith<SerializationException>(text) { JsonRpcJson.decodeFromString(JsonRpcResponse.serializer(), text) }
        }
    }

    @Test
    fun encodingValidatesSingleAndBatchEnvelopesConsistently() {
        val invalid = listOf(
            JsonRpcRequest(RequestId.create(1), MethodName("test"), JsonPrimitive(42)),
            JsonRpcNotification(MethodName("test"), JsonPrimitive("invalid")),
            JsonRpcRequest(RequestId.create(1), MethodName("test"), jsonrpc = "1.0"),
            JsonRpcSuccessResponse(RequestId.create(1), JsonNull, jsonrpc = "1.0"),
            JsonRpcErrorResponse(RequestId.Null, JsonRpcError(-1, "error"), jsonrpc = "1.0"),
        )
        for (message in invalid) {
            assertFailsWith<SerializationException> { JsonRpcJson.encodeToString(JsonRpcMessage.serializer(), message) }
            val single = TransportFrame.Single(message)
            assertFailsWith<SerializationException> { encode(single) }
            assertFailsWith<SerializationException> { encode(TransportFrame.Batch(listOf(single))) }
            if (message is JsonRpcResponse) {
                assertFailsWith<SerializationException> { JsonRpcJson.encodeToString(JsonRpcResponse.serializer(), message) }
            }
        }
    }

    @Test
    fun malformedResponseClassification() {
        for (text in listOf("""{"result":null}""", """{"id":1,"error":null}""")) {
            assertTrue(assertIs<TransportFrame.Malformed>(parseTransportFrame(text)).isResponse)
        }
        for (text in listOf("""{"method":null,"result":null}""", """{"id":1}""", "[]")) {
            assertFalse(assertIs<TransportFrame.Malformed>(parseTransportFrame(text)).isResponse)
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
}
