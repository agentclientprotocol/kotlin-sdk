package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.RequestId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class ElicitationSerializationTest {

    // === CreateElicitationRequest Tests ===

    @Test
    fun `form mode request serialization - session scope`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "name" to ElicitationPropertySchema.StringProperty()
            ),
            required = listOf("name")
        )
        val req = CreateElicitationRequest(
            scope = ElicitationScope.Session(SessionId("sess_1")),
            mode = ElicitationMode.Form(schema),
            message = "Please enter your name"
        )

        val json = ACPJson.encodeToString(CreateElicitationRequest.serializer(), req)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("sess_1", jsonObj["sessionId"]?.jsonPrimitive?.content)
        assertNull(jsonObj["toolCallId"])
        assertEquals("form", jsonObj["mode"]?.jsonPrimitive?.content)
        assertEquals("Please enter your name", jsonObj["message"]?.jsonPrimitive?.content)
        assertNotNull(jsonObj["requestedSchema"])
        assertEquals("object", jsonObj["requestedSchema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("string", jsonObj["requestedSchema"]?.jsonObject?.get("properties")?.jsonObject?.get("name")?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationRequest.serializer(), json)
        assertIs<ElicitationScope.Session>(roundtripped.scope)
        assertEquals(SessionId("sess_1"), (roundtripped.scope as ElicitationScope.Session).sessionId)
        assertNull((roundtripped.scope as ElicitationScope.Session).toolCallId)
        assertEquals("Please enter your name", roundtripped.message)
        assertIs<ElicitationMode.Form>(roundtripped.mode)
    }

    @Test
    fun `url mode request serialization - session scope with toolCallId`() {
        val req = CreateElicitationRequest(
            scope = ElicitationScope.Session(SessionId("sess_2"), ToolCallId("tc_1")),
            mode = ElicitationMode.Url(ElicitationId("elic_1"), "https://example.com/auth"),
            message = "Please authenticate"
        )

        val json = ACPJson.encodeToString(CreateElicitationRequest.serializer(), req)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("sess_2", jsonObj["sessionId"]?.jsonPrimitive?.content)
        assertEquals("tc_1", jsonObj["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("url", jsonObj["mode"]?.jsonPrimitive?.content)
        assertEquals("elic_1", jsonObj["elicitationId"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/auth", jsonObj["url"]?.jsonPrimitive?.content)
        assertEquals("Please authenticate", jsonObj["message"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationRequest.serializer(), json)
        assertIs<ElicitationScope.Session>(roundtripped.scope)
        assertEquals(SessionId("sess_2"), (roundtripped.scope as ElicitationScope.Session).sessionId)
        assertEquals(ToolCallId("tc_1"), (roundtripped.scope as ElicitationScope.Session).toolCallId)
        assertIs<ElicitationMode.Url>(roundtripped.mode)
    }

    @Test
    fun `request scope request serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "workspace" to ElicitationPropertySchema.StringProperty()
            ),
            required = listOf("workspace")
        )
        val req = CreateElicitationRequest(
            scope = ElicitationScope.Request(RequestId.create(99)),
            mode = ElicitationMode.Form(schema),
            message = "Enter workspace name"
        )

        val json = ACPJson.encodeToString(CreateElicitationRequest.serializer(), req)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals(99, jsonObj["requestId"]?.jsonPrimitive?.content?.toInt())
        assertNull(jsonObj["sessionId"])

        val roundtripped = ACPJson.decodeFromString(CreateElicitationRequest.serializer(), json)
        assertIs<ElicitationScope.Request>(roundtripped.scope)
    }

    // === CreateElicitationResponse Tests ===

    @Test
    fun `response accept serialization`() {
        val resp = CreateElicitationResponse(
            action = ElicitationAction.Accept(
                content = mapOf("name" to ElicitationContentValue.StringValue("Alice"))
            )
        )

        val json = ACPJson.encodeToString(CreateElicitationResponse.serializer(), resp)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("accept", jsonObj["action"]?.jsonPrimitive?.content)
        assertEquals("Alice", jsonObj["content"]?.jsonObject?.get("name")?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationResponse.serializer(), json)
        assertIs<ElicitationAction.Accept>(roundtripped.action)
        assertNotNull((roundtripped.action as ElicitationAction.Accept).content)
    }

    @Test
    fun `response decline serialization`() {
        val resp = CreateElicitationResponse(action = ElicitationAction.Decline)

        val json = ACPJson.encodeToString(CreateElicitationResponse.serializer(), resp)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("decline", jsonObj["action"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationResponse.serializer(), json)
        assertIs<ElicitationAction.Decline>(roundtripped.action)
    }

    @Test
    fun `response cancel serialization`() {
        val resp = CreateElicitationResponse(action = ElicitationAction.Cancel)

        val json = ACPJson.encodeToString(CreateElicitationResponse.serializer(), resp)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("cancel", jsonObj["action"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationResponse.serializer(), json)
        assertIs<ElicitationAction.Cancel>(roundtripped.action)
    }

    @Test
    fun `response accept with all content value types`() {
        val resp = CreateElicitationResponse(
            action = ElicitationAction.Accept(
                content = mapOf(
                    "name" to ElicitationContentValue.StringValue("Alice"),
                    "age" to ElicitationContentValue.IntegerValue(30),
                    "score" to ElicitationContentValue.NumberValue(9.5),
                    "subscribed" to ElicitationContentValue.BooleanValue(true),
                    "tags" to ElicitationContentValue.StringArrayValue(listOf("rust", "acp"))
                )
            )
        )

        val json = ACPJson.encodeToString(CreateElicitationResponse.serializer(), resp)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("accept", jsonObj["action"]?.jsonPrimitive?.content)
        val content = jsonObj["content"]!!.jsonObject
        assertEquals("Alice", content["name"]?.jsonPrimitive?.content)
        assertEquals("30", content["age"]?.jsonPrimitive?.content)
        assertEquals("9.5", content["score"]?.jsonPrimitive?.content)
        assertEquals("true", content["subscribed"]?.jsonPrimitive?.content)
        assertEquals("rust", content["tags"]?.jsonArray?.get(0)?.jsonPrimitive?.content)
        assertEquals("acp", content["tags"]?.jsonArray?.get(1)?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CreateElicitationResponse.serializer(), json)
        val rtContent = (roundtripped.action as ElicitationAction.Accept).content!!
        assertIs<ElicitationContentValue.StringValue>(rtContent["name"])
        assertIs<ElicitationContentValue.IntegerValue>(rtContent["age"])
        assertIs<ElicitationContentValue.NumberValue>(rtContent["score"])
        assertIs<ElicitationContentValue.BooleanValue>(rtContent["subscribed"])
        assertIs<ElicitationContentValue.StringArrayValue>(rtContent["tags"])
    }

    // === Complete Elicitation Notification ===

    @Test
    fun `completion notification serialization`() {
        val notif = CompleteElicitationNotification(ElicitationId("elic_1"))

        val json = ACPJson.encodeToString(CompleteElicitationNotification.serializer(), notif)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("elic_1", jsonObj["elicitationId"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(CompleteElicitationNotification.serializer(), json)
        assertEquals(ElicitationId("elic_1"), roundtripped.elicitationId)
    }

    // === Capabilities Tests ===

    @Test
    fun `capabilities form only`() {
        val caps = ElicitationCapabilities(form = ElicitationFormCapabilities())

        val json = ACPJson.encodeToString(ElicitationCapabilities.serializer(), caps)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertTrue(jsonObj["form"] is JsonObject)
        assertNull(jsonObj["url"])

        val roundtripped = ACPJson.decodeFromString(ElicitationCapabilities.serializer(), json)
        assertNotNull(roundtripped.form)
        assertNull(roundtripped.url)
    }

    @Test
    fun `capabilities url only`() {
        val caps = ElicitationCapabilities(url = ElicitationUrlCapabilities())

        val json = ACPJson.encodeToString(ElicitationCapabilities.serializer(), caps)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertNull(jsonObj["form"])
        assertTrue(jsonObj["url"] is JsonObject)

        val roundtripped = ACPJson.decodeFromString(ElicitationCapabilities.serializer(), json)
        assertNull(roundtripped.form)
        assertNotNull(roundtripped.url)
    }

    @Test
    fun `capabilities both`() {
        val caps = ElicitationCapabilities(
            form = ElicitationFormCapabilities(),
            url = ElicitationUrlCapabilities()
        )

        val json = ACPJson.encodeToString(ElicitationCapabilities.serializer(), caps)
        val roundtripped = ACPJson.decodeFromString(ElicitationCapabilities.serializer(), json)
        assertNotNull(roundtripped.form)
        assertNotNull(roundtripped.url)
    }

    // === Schema Tests ===

    @Test
    fun `schema builder serialization`() {
        val schema = ElicitationSchema(
            description = "User registration",
            properties = mapOf(
                "name" to ElicitationPropertySchema.StringProperty(),
                "email" to ElicitationPropertySchema.StringProperty(format = StringFormat.EMAIL),
                "age" to ElicitationPropertySchema.IntegerProperty(minimum = 0, maximum = 150),
                "newsletter" to ElicitationPropertySchema.BooleanProperty()
            ),
            required = listOf("name", "email", "age")
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        assertEquals("object", jsonObj["type"]?.jsonPrimitive?.content)
        assertEquals("User registration", jsonObj["description"]?.jsonPrimitive?.content)
        val props = jsonObj["properties"]!!.jsonObject
        assertEquals("string", props["name"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("string", props["email"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("email", props["email"]?.jsonObject?.get("format")?.jsonPrimitive?.content)
        assertEquals("integer", props["age"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("0", props["age"]?.jsonObject?.get("minimum")?.jsonPrimitive?.content)
        assertEquals("150", props["age"]?.jsonObject?.get("maximum")?.jsonPrimitive?.content)
        assertEquals("boolean", props["newsletter"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val required = jsonObj["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("name" in required)
        assertTrue("email" in required)
        assertTrue("age" in required)
        assertFalse("newsletter" in required)

        val roundtripped = ACPJson.decodeFromString(ElicitationSchema.serializer(), json)
        assertEquals(4, roundtripped.properties.size)
        assertTrue(roundtripped.required!!.contains("name"))
    }

    @Test
    fun `schema string enum serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "color" to ElicitationPropertySchema.StringProperty(
                    enumValues = listOf("red", "green", "blue")
                )
            ),
            required = listOf("color")
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val colorProp = jsonObj["properties"]!!.jsonObject["color"]!!.jsonObject
        assertEquals("string", colorProp["type"]?.jsonPrimitive?.content)
        assertEquals(3, colorProp["enum"]?.jsonArray?.size)

        val roundtripped = ACPJson.decodeFromString(ElicitationSchema.serializer(), json)
        val stringProp = roundtripped.properties["color"]
        assertIs<ElicitationPropertySchema.StringProperty>(stringProp)
        assertEquals(3, stringProp.enumValues!!.size)
    }

    @Test
    fun `schema titled enum serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "country" to ElicitationPropertySchema.StringProperty(
                    oneOf = listOf(
                        EnumOption("us", "United States"),
                        EnumOption("uk", "United Kingdom")
                    )
                )
            ),
            required = listOf("country")
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val countryProp = jsonObj["properties"]!!.jsonObject["country"]!!.jsonObject
        assertEquals("string", countryProp["type"]?.jsonPrimitive?.content)
        val oneOf = countryProp["oneOf"]!!.jsonArray
        assertEquals(2, oneOf.size)
        assertEquals("us", oneOf[0].jsonObject["const"]?.jsonPrimitive?.content)
        assertEquals("United States", oneOf[0].jsonObject["title"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(ElicitationSchema.serializer(), json)
        val stringProp = roundtripped.properties["country"]
        assertIs<ElicitationPropertySchema.StringProperty>(stringProp)
        assertEquals(2, stringProp.oneOf!!.size)
    }

    @Test
    fun `schema multi-select serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "colors" to ElicitationPropertySchema.ArrayProperty(
                    minItems = 1,
                    maxItems = 3,
                    items = MultiSelectItems.Untitled(
                        UntitledMultiSelectItems(values = listOf("red", "green", "blue"))
                    )
                )
            )
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val colorsProp = jsonObj["properties"]!!.jsonObject["colors"]!!.jsonObject
        assertEquals("array", colorsProp["type"]?.jsonPrimitive?.content)
        assertEquals("string", colorsProp["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("1", colorsProp["minItems"]?.jsonPrimitive?.content)
        assertEquals("3", colorsProp["maxItems"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(ElicitationSchema.serializer(), json)
        assertIs<ElicitationPropertySchema.ArrayProperty>(roundtripped.properties["colors"])
    }

    @Test
    fun `schema number property serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "rating" to ElicitationPropertySchema.NumberProperty(minimum = 0.0, maximum = 5.0)
            ),
            required = listOf("rating")
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val ratingProp = jsonObj["properties"]!!.jsonObject["rating"]!!.jsonObject
        assertEquals("number", ratingProp["type"]?.jsonPrimitive?.content)
        assertEquals(0.0, ratingProp["minimum"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(5.0, ratingProp["maximum"]?.jsonPrimitive?.content?.toDouble())

        val roundtripped = ACPJson.decodeFromString(ElicitationSchema.serializer(), json)
        val numProp = roundtripped.properties["rating"]
        assertIs<ElicitationPropertySchema.NumberProperty>(numProp)
        assertEquals(0.0, numProp.minimum)
        assertEquals(5.0, numProp.maximum)
    }

    @Test
    fun `schema string format serialization`() {
        val schema = ElicitationSchema(
            properties = mapOf(
                "website" to ElicitationPropertySchema.StringProperty(format = StringFormat.URI),
                "birthday" to ElicitationPropertySchema.StringProperty(format = StringFormat.DATE),
                "updated_at" to ElicitationPropertySchema.StringProperty(format = StringFormat.DATE_TIME)
            ),
            required = listOf("website", "birthday")
        )

        val json = ACPJson.encodeToString(ElicitationSchema.serializer(), schema)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val props = jsonObj["properties"]!!.jsonObject
        assertEquals("uri", props["website"]?.jsonObject?.get("format")?.jsonPrimitive?.content)
        assertEquals("date", props["birthday"]?.jsonObject?.get("format")?.jsonPrimitive?.content)
        assertEquals("date-time", props["updated_at"]?.jsonObject?.get("format")?.jsonPrimitive?.content)

        val required = jsonObj["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("website" in required)
        assertTrue("birthday" in required)
        assertFalse("updated_at" in required)
    }

    // === URL Elicitation Required Error Data ===

    @Test
    fun `url elicitation required data serialization`() {
        val data = UrlElicitationRequiredData(
            elicitations = listOf(
                UrlElicitationRequiredItem(
                    elicitationId = ElicitationId("elic_1"),
                    url = "https://example.com/auth",
                    message = "Please authenticate"
                )
            )
        )

        val json = ACPJson.encodeToString(UrlElicitationRequiredData.serializer(), data)
        val jsonObj = ACPJson.decodeFromString(JsonObject.serializer(), json)

        val items = jsonObj["elicitations"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("url", items[0].jsonObject["mode"]?.jsonPrimitive?.content)
        assertEquals("elic_1", items[0].jsonObject["elicitationId"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/auth", items[0].jsonObject["url"]?.jsonPrimitive?.content)

        val roundtripped = ACPJson.decodeFromString(UrlElicitationRequiredData.serializer(), json)
        assertEquals(1, roundtripped.elicitations.size)
        assertEquals(ElicitationUrlOnlyMode.URL, roundtripped.elicitations[0].mode)
    }

    // === Request tolerates extra fields ===

    @Test
    fun `request tolerates extra fields`() {
        val jsonStr = """
            {
                "sessionId": "sess_1",
                "mode": "form",
                "message": "Enter your name",
                "requestedSchema": {
                    "type": "object",
                    "properties": {
                        "name": { "type": "string", "title": "Name" }
                    },
                    "required": ["name"]
                },
                "unknownStringField": "hello",
                "unknownNumberField": 42
            }
        """.trimIndent()

        val req = ACPJson.decodeFromString(CreateElicitationRequest.serializer(), jsonStr)
        assertIs<ElicitationScope.Session>(req.scope)
        assertEquals(SessionId("sess_1"), (req.scope as ElicitationScope.Session).sessionId)
        assertNull((req.scope as ElicitationScope.Session).toolCallId)
        assertEquals("Enter your name", req.message)
        assertIs<ElicitationMode.Form>(req.mode)
    }

    // === Content value strict array validation ===

    @Test
    fun `content value rejects non-string items in array`() {
        // An array with a boolean should fail - RFD only allows string arrays
        val jsonStr = """{"action":"accept","content":{"tags":[1, true]}}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            ACPJson.decodeFromString(CreateElicitationResponse.serializer(), jsonStr)
        }
    }

    @Test
    fun `content value rejects nested object in array`() {
        val jsonStr = """{"action":"accept","content":{"tags":[{"key":"val"}]}}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            ACPJson.decodeFromString(CreateElicitationResponse.serializer(), jsonStr)
        }
    }

    // === ClientCapabilities with elicitation ===

    @Test
    fun `client capabilities with elicitation round-trip`() {
        val caps = ClientCapabilities(
            elicitation = ElicitationCapabilities(
                form = ElicitationFormCapabilities(),
                url = ElicitationUrlCapabilities()
            )
        )

        val json = ACPJson.encodeToString(ClientCapabilities.serializer(), caps)
        val roundtripped = ACPJson.decodeFromString(ClientCapabilities.serializer(), json)
        assertNotNull(roundtripped.elicitation)
        assertNotNull(roundtripped.elicitation!!.form)
        assertNotNull(roundtripped.elicitation!!.url)
    }
}
