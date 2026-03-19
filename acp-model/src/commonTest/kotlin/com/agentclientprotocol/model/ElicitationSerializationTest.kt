package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(UnstableApi::class)
class ElicitationSerializationTest {

    @Test
    fun `form mode request serializes and deserializes`() {
        val request = ElicitationRequest(
            sessionId = SessionId("sess_1"),
            mode = ElicitationMode.Form(
                ElicitationSchema(
                    properties = mapOf(
                        "name" to ElicitationPropertySchema.StringValue()
                    ),
                    required = listOf("name")
                )
            ),
            message = "Please enter your name"
        )

        val json = ACPJson.encodeToJsonElement(ElicitationRequest.serializer(), request).jsonObject
        assertEquals("sess_1", json["sessionId"]?.jsonPrimitive?.content)
        assertEquals("form", json["mode"]?.jsonPrimitive?.content)
        assertEquals("Please enter your name", json["message"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            json["requestedSchema"]?.jsonObject
                ?.get("properties")?.jsonObject
                ?.get("name")?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
        )

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationRequest.serializer(), json)
        assertEquals(SessionId("sess_1"), roundtrip.sessionId)
        assertTrue(roundtrip.mode is ElicitationMode.Form)
    }

    @Test
    fun `url mode request serializes and deserializes`() {
        val request = ElicitationRequest(
            sessionId = SessionId("sess_2"),
            mode = ElicitationMode.Url(
                elicitationId = ElicitationId("elic_1"),
                url = "https://example.com/auth"
            ),
            message = "Please authenticate"
        )

        val json = ACPJson.encodeToJsonElement(ElicitationRequest.serializer(), request).jsonObject
        assertEquals("sess_2", json["sessionId"]?.jsonPrimitive?.content)
        assertEquals("url", json["mode"]?.jsonPrimitive?.content)
        assertEquals("elic_1", json["elicitationId"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/auth", json["url"]?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationRequest.serializer(), json)
        assertTrue(roundtrip.mode is ElicitationMode.Url)
    }

    @Test
    fun `response accept serializes and deserializes`() {
        val response = ElicitationResponse(
            action = ElicitationAction.Accept(
                content = mapOf(
                    "name" to ElicitationContentValue.StringValue("Alice"),
                    "age" to ElicitationContentValue.IntegerValue(30)
                )
            )
        )

        val json = ACPJson.encodeToJsonElement(ElicitationResponse.serializer(), response).jsonObject
        assertEquals("accept", json["action"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
        assertEquals("Alice", json["action"]?.jsonObject?.get("content")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("30", json["action"]?.jsonObject?.get("content")?.jsonObject?.get("age")?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationResponse.serializer(), json)
        assertTrue(roundtrip.action is ElicitationAction.Accept)
        val content = (roundtrip.action as ElicitationAction.Accept).content
        val name = content?.get("name") as? ElicitationContentValue.StringValue
        assertEquals("Alice", name?.value)
    }

    @Test
    fun `response decline serializes and deserializes`() {
        val response = ElicitationResponse(ElicitationAction.Decline)

        val json = ACPJson.encodeToJsonElement(ElicitationResponse.serializer(), response).jsonObject
        assertEquals("decline", json["action"]?.jsonObject?.get("action")?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationResponse.serializer(), json)
        assertTrue(roundtrip.action is ElicitationAction.Decline)
    }

    @Test
    fun `response cancel serializes and deserializes`() {
        val response = ElicitationResponse(ElicitationAction.Cancel)

        val json = ACPJson.encodeToJsonElement(ElicitationResponse.serializer(), response).jsonObject
        assertEquals("cancel", json["action"]?.jsonObject?.get("action")?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationResponse.serializer(), json)
        assertTrue(roundtrip.action is ElicitationAction.Cancel)
    }

    @Test
    fun `completion notification serializes and deserializes`() {
        val notification = ElicitationCompleteNotification(
            elicitationId = ElicitationId("elic_1")
        )

        val json = ACPJson.encodeToJsonElement(ElicitationCompleteNotification.serializer(), notification).jsonObject
        assertEquals("elic_1", json["elicitationId"]?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationCompleteNotification.serializer(), json)
        assertEquals(ElicitationId("elic_1"), roundtrip.elicitationId)
    }

    @Test
    fun `capabilities form only serializes and deserializes`() {
        val capabilities = ElicitationCapabilities(
            form = ElicitationFormCapabilities()
        )

        val json = ACPJson.encodeToJsonElement(ElicitationCapabilities.serializer(), capabilities).jsonObject
        assertNotNull(json["form"])
        assertEquals(null, json["url"])

        val roundtrip = ACPJson.decodeFromJsonElement(ElicitationCapabilities.serializer(), json)
        assertNotNull(roundtrip.form)
        assertEquals(null, roundtrip.url)
    }

    @Test
    fun `url elicitation required data serializes and deserializes`() {
        val data = UrlElicitationRequiredData(
            elicitations = listOf(
                UrlElicitationRequiredItem(
                    elicitationId = ElicitationId("elic_1"),
                    url = "https://example.com/auth",
                    message = "Authentication required"
                )
            )
        )

        val json = ACPJson.encodeToJsonElement(UrlElicitationRequiredData.serializer(), data).jsonObject
        val first = json["elicitations"]!!.jsonArray.first().jsonObject
        assertEquals("url", first["mode"]?.jsonPrimitive?.content)
        assertEquals("elic_1", first["elicitationId"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/auth", first["url"]?.jsonPrimitive?.content)

        val roundtrip = ACPJson.decodeFromJsonElement(UrlElicitationRequiredData.serializer(), json)
        assertEquals(1, roundtrip.elicitations.size)
        assertEquals(ElicitationUrlOnlyMode.URL, roundtrip.elicitations.first().mode)
    }

    @Test
    fun `property schema array variants serialize and deserialize`() {
        val untitled = ElicitationPropertySchema.ArrayValue(
            MultiSelectPropertySchema(
                items = MultiSelectItems.Untitled(values = listOf("a", "b"))
            )
        )
        val untitledJson = ACPJson.encodeToJsonElement(ElicitationPropertySchema.serializer(), untitled).jsonObject
        assertEquals("array", untitledJson["type"]?.jsonPrimitive?.content)
        assertNotNull(untitledJson["items"]?.jsonObject?.get("enum"))
        val untitledRoundtrip = ACPJson.decodeFromJsonElement(ElicitationPropertySchema.serializer(), untitledJson)
        assertTrue(untitledRoundtrip is ElicitationPropertySchema.ArrayValue)
        assertTrue(untitledRoundtrip.schema.items is MultiSelectItems.Untitled)

        val titled = ElicitationPropertySchema.ArrayValue(
            MultiSelectPropertySchema(
                items = MultiSelectItems.Titled(
                    options = listOf(EnumOption("x", "X"))
                )
            )
        )
        val titledJson = ACPJson.encodeToJsonElement(ElicitationPropertySchema.serializer(), titled).jsonObject
        assertEquals("array", titledJson["type"]?.jsonPrimitive?.content)
        assertNotNull(titledJson["items"]?.jsonObject?.get("anyOf"))
        val titledRoundtrip = ACPJson.decodeFromJsonElement(ElicitationPropertySchema.serializer(), titledJson)
        assertTrue(titledRoundtrip is ElicitationPropertySchema.ArrayValue)
        assertTrue(titledRoundtrip.schema.items is MultiSelectItems.Titled)
    }

    @Test
    fun `elicitation content value deserializes primitives and arrays`() {
        val intValue = ACPJson.decodeFromString(ElicitationContentValue.serializer(), "42")
        assertTrue(intValue is ElicitationContentValue.IntegerValue)
        assertEquals(42L, intValue.value)

        val numberValue = ACPJson.decodeFromString(ElicitationContentValue.serializer(), "42.5")
        assertTrue(numberValue is ElicitationContentValue.NumberValue)
        assertEquals(42.5, numberValue.value)

        val boolValue = ACPJson.decodeFromString(ElicitationContentValue.serializer(), "true")
        assertTrue(boolValue is ElicitationContentValue.BooleanValue)
        assertEquals(true, boolValue.value)

        val stringValue = ACPJson.decodeFromString(ElicitationContentValue.serializer(), "\"hello\"")
        assertTrue(stringValue is ElicitationContentValue.StringValue)
        assertEquals("hello", stringValue.value)

        val stringArrayValue = ACPJson.decodeFromString(ElicitationContentValue.serializer(), "[\"a\",\"b\"]")
        assertTrue(stringArrayValue is ElicitationContentValue.StringArrayValue)
        assertEquals(listOf("a", "b"), stringArrayValue.value)
    }

    @Test
    fun `elicitation content value rejects non-string array entries`() {
        try {
            ACPJson.decodeFromString(ElicitationContentValue.serializer(), "[1,2]")
            fail("Expected deserialization to fail for non-string array")
        } catch (_: Exception) {
        }
    }
}
