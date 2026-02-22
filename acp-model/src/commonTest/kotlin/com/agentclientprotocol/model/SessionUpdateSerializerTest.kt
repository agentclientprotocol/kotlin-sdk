package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class SessionUpdateSerializerTest {

    // Known type tests

    @Test
    fun `decodes known SessionUpdate types correctly`() {
        val payload = """
            {
                "sessionUpdate": "agent_message_chunk",
                "content": {
                    "type": "text",
                    "text": "Hello world"
                }
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.AgentMessageChunk)
        assertTrue(update.content is ContentBlock.Text)
        assertEquals("Hello world", (update.content as ContentBlock.Text).text)
    }

    @Test
    fun `decodes usage_update correctly`() {
        val payload = """
            {
                "sessionUpdate": "usage_update",
                "used": 100,
                "size": 200000,
                "cost": {
                    "amount": 0.05,
                    "currency": "USD"
                }
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UsageUpdate)
        assertEquals(100L, update.used)
        assertEquals(200000L, update.size)
        assertEquals(0.05, update.cost?.amount)
        assertEquals("USD", update.cost?.currency)
    }

    // UnknownSessionUpdate tests

    @Test
    fun `decodes unknown SessionUpdate type as UnknownSessionUpdate`() {
        val payload = """
            {
                "sessionUpdate": "future_update_type",
                "someField": "someValue",
                "anotherField": 42
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UnknownSessionUpdate)
        assertEquals("future_update_type", update.sessionUpdateType)
        assertTrue(update.rawJson.containsKey("someField"))
        assertTrue(update.rawJson.containsKey("anotherField"))
    }

    @Test
    fun `UnknownSessionUpdate preserves raw JSON for inspection`() {
        val payload = """
            {
                "sessionUpdate": "custom_update",
                "customField1": "value1",
                "customField2": [1, 2, 3],
                "customField3": {"nested": "object"}
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UnknownSessionUpdate)
        assertEquals("custom_update", update.sessionUpdateType)
        
        // Verify all custom fields are preserved in rawJson
        assertTrue(update.rawJson.containsKey("customField1"))
        assertTrue(update.rawJson.containsKey("customField2"))
        assertTrue(update.rawJson.containsKey("customField3"))
        assertTrue(update.rawJson.containsKey("sessionUpdate"))
    }

    @Test
    fun `UnknownSessionUpdate preserves _meta field`() {
        val payload = """
            {
                "sessionUpdate": "experimental_update",
                "data": "test",
                "_meta": {"version": 2, "experimental": true}
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UnknownSessionUpdate)
        assertNotNull(update._meta)
    }

    // Round-trip serialization tests

    @Test
    fun `round-trip serialization for known types`() {
        val original = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text("Test message")
        )

        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), original)
        assertTrue(encoded.contains("\"sessionUpdate\":\"agent_message_chunk\""))

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertTrue(decoded is SessionUpdate.AgentMessageChunk)
        assertEquals("Test message", (decoded.content as ContentBlock.Text).text)
    }

    @Test
    fun `round-trip serialization for UsageUpdate`() {
        val original = SessionUpdate.UsageUpdate(
            used = 150L,
            size = 100000L,
            cost = Cost(amount = 0.02, currency = "EUR")
        )

        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), original)
        assertTrue(encoded.contains("\"sessionUpdate\":\"usage_update\""))

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertTrue(decoded is SessionUpdate.UsageUpdate)
        assertEquals(150L, decoded.used)
        assertEquals(100000L, decoded.size)
        assertEquals(0.02, decoded.cost?.amount)
        assertEquals("EUR", decoded.cost?.currency)
    }

    @Test
    fun `round-trip serialization for UnknownSessionUpdate preserves type and fields`() {
        val payload = """
            {
                "sessionUpdate": "beta_feature",
                "featureFlag": "enabled",
                "betaVersion": 3
            }
        """.trimIndent()

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)
        assertTrue(decoded is SessionUpdate.UnknownSessionUpdate)

        // Re-encode
        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), decoded)
        
        // Decode again
        val reDecoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertTrue(reDecoded is SessionUpdate.UnknownSessionUpdate)
        assertEquals("beta_feature", reDecoded.sessionUpdateType)
        assertTrue(reDecoded.rawJson.containsKey("featureFlag"))
        assertTrue(reDecoded.rawJson.containsKey("betaVersion"))
    }

    // Context tests (SessionNotification)

    @Test
    fun `decodes SessionNotification with known update type`() {
        val payload = """
            {
                "sessionId": "session-123",
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call-1",
                    "title": "Execute command"
                }
            }
        """.trimIndent()

        val notification = ACPJson.decodeFromString(SessionNotification.serializer(), payload)

        assertEquals("session-123", notification.sessionId.value)
        assertTrue(notification.update is SessionUpdate.ToolCall)
        assertEquals("call-1", (notification.update as SessionUpdate.ToolCall).toolCallId.value)
    }

    @Test
    fun `decodes SessionNotification with unknown update type`() {
        val payload = """
            {
                "sessionId": "session-456",
                "update": {
                    "sessionUpdate": "future_feature",
                    "futureData": "some value"
                }
            }
        """.trimIndent()

        val notification = ACPJson.decodeFromString(SessionNotification.serializer(), payload)

        assertEquals("session-456", notification.sessionId.value)
        assertTrue(notification.update is SessionUpdate.UnknownSessionUpdate)
        assertEquals("future_feature", (notification.update as SessionUpdate.UnknownSessionUpdate).sessionUpdateType)
    }

    @Test
    fun `handles mixed known and unknown updates in sequence`() {
        val knownPayload = """
            {"sessionUpdate": "current_mode_update", "currentModeId": "mode-1"}
        """.trimIndent()
        
        val unknownPayload = """
            {"sessionUpdate": "v2_update", "newField": "newValue"}
        """.trimIndent()

        val knownUpdate = ACPJson.decodeFromString(SessionUpdate.serializer(), knownPayload)
        val unknownUpdate = ACPJson.decodeFromString(SessionUpdate.serializer(), unknownPayload)

        assertTrue(knownUpdate is SessionUpdate.CurrentModeUpdate)
        assertTrue(unknownUpdate is SessionUpdate.UnknownSessionUpdate)
    }

    // Error handling

    @Test
    fun `throws exception when sessionUpdate discriminator is missing`() {
        val payload = """
            {
                "content": {
                    "type": "text",
                    "text": "Missing discriminator"
                }
            }
        """.trimIndent()

        try {
            ACPJson.decodeFromString(SessionUpdate.serializer(), payload)
            kotlin.test.fail("Should have thrown SerializationException")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("sessionUpdate") == true)
        }
    }

    @Test
    fun `decodes all standard update types without error`() {
        val testCases = listOf(
            """{"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": "test"}}""",
            """{"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "test"}}""",
            """{"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "test"}}""",
            """{"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "test"}""",
            """{"sessionUpdate": "tool_call_update", "toolCallId": "t1"}""",
            """{"sessionUpdate": "plan", "entries": []}""",
            """{"sessionUpdate": "available_commands_update", "availableCommands": []}""",
            """{"sessionUpdate": "current_mode_update", "currentModeId": "m1"}""",
            """{"sessionUpdate": "config_option_update", "configOptions": []}""",
            """{"sessionUpdate": "session_info_update"}""",
            """{"sessionUpdate": "usage_update", "used": 0, "size": 1000}"""
        )

        testCases.forEach { payload ->
            val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)
            assertNotNull(update, "Failed to decode: $payload")
        }
    }
}
