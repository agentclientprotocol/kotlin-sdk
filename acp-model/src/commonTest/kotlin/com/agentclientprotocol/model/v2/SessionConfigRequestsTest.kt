@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SessionConfigRequestsTest {
    @Test
    fun `setter flattens id and boolean values alongside configId and metadata`() {
        val meta = ACPJson.parseToJsonElement("""{"trace":"config"}""")
        val cases = listOf(
            SessionConfigOptionValue.Id(SessionConfigValueId("code")) to """"type":"id","value":"code"""",
            SessionConfigOptionValue.Boolean(true) to """"type":"boolean","value":true""",
        )
        for ((value, fields) in cases) {
            val request = SetSessionConfigOptionRequest(SessionId("session"), SessionConfigId("mode"), value, meta)
            val json = ACPJson.parseToJsonElement(
                """{"sessionId":"session","configId":"mode",$fields,"_meta":{"trace":"config"}}"""
            )
            assertEquals(json, ACPJson.encodeToJsonElement(SetSessionConfigOptionRequest.serializer(), request))
            assertEquals(request, ACPJson.decodeFromJsonElement(SetSessionConfigOptionRequest.serializer(), json))
        }
    }

    @Test
    fun `setter preserves unknown value types and extra payload fields`() {
        val json = ACPJson.parseToJsonElement(
            """{"sessionId":"session","configId":"budget","type":"_budget","value":{"tokens":100},"unit":"tokens","_meta":{"trace":1}}"""
        )
        val request = ACPJson.decodeFromJsonElement(SetSessionConfigOptionRequest.serializer(), json)
        assertEquals(json, ACPJson.encodeToJsonElement(SetSessionConfigOptionRequest.serializer(), request))
    }

    @Test
    fun `setter requires string identifiers and explicitly tagged values`() {
        val valid = ACPJson.parseToJsonElement(
            """{"sessionId":"session","configId":"mode","type":"id","value":"code"}"""
        ).jsonObject
        for (key in listOf("sessionId", "configId", "type", "value")) {
            assertFailsWith<SerializationException>("missing $key") {
                ACPJson.decodeFromJsonElement(SetSessionConfigOptionRequest.serializer(), JsonObject(valid - key))
            }
        }
        for (key in listOf("sessionId", "configId")) {
            assertFailsWith<SerializationException>("non-string $key") {
                ACPJson.decodeFromJsonElement(
                    SetSessionConfigOptionRequest.serializer(), JsonObject(valid + (key to JsonPrimitive(123)))
                )
            }
        }
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromJsonElement(
                SetSessionConfigOptionRequest.serializer(),
                JsonObject(valid + ("id" to valid.getValue("configId")) - "configId"),
            )
        }
    }

    @Test
    fun `session responses carry options without dedicated modes`() {
        val options = listOf(
            SessionConfigOption(SessionConfigId("enabled"), "Enabled", kind = SessionConfigKind.Boolean(true)),
        )
        val new = NewSessionResponse(SessionId("session"), options)
        val resume = ResumeSessionResponse(options)
        val fork = ForkSessionResponse(SessionId("fork"), options)
        val responses = listOf(
            ACPJson.encodeToJsonElement(NewSessionResponse.serializer(), new),
            ACPJson.encodeToJsonElement(ResumeSessionResponse.serializer(), resume),
            ACPJson.encodeToJsonElement(ForkSessionResponse.serializer(), fork),
        )
        for (json in responses) {
            assertFalse("modes" in json.jsonObject)
            assertFalse("models" in json.jsonObject)
            assertEquals(
                options,
                ACPJson.decodeFromJsonElement(
                    ListSerializer(SessionConfigOption.serializer()), json.jsonObject.getValue("configOptions")
                ),
            )
        }
        assertEquals(new, ACPJson.decodeFromJsonElement(NewSessionResponse.serializer(), responses[0]))
        assertEquals(resume, ACPJson.decodeFromJsonElement(ResumeSessionResponse.serializer(), responses[1]))
        assertEquals(fork, ACPJson.decodeFromJsonElement(ForkSessionResponse.serializer(), responses[2]))
        assertEquals(
            emptyList(),
            ACPJson.decodeFromString(NewSessionResponse.serializer(), """{"sessionId":"session"}""").configOptions,
        )
        assertEquals(emptyList(), ACPJson.decodeFromString(ResumeSessionResponse.serializer(), "{}").configOptions)
    }

    @Test
    fun `setter response requires a complete list including an explicit empty list`() {
        assertEquals(
            SetSessionConfigOptionResponse(emptyList()),
            ACPJson.decodeFromString(SetSessionConfigOptionResponse.serializer(), """{"configOptions":[]}"""),
        )
        assertEquals(
            ACPJson.parseToJsonElement("""{"configOptions":[]}"""),
            ACPJson.encodeToJsonElement(SetSessionConfigOptionResponse.serializer(), SetSessionConfigOptionResponse(emptyList())),
        )
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(SetSessionConfigOptionResponse.serializer(), "{}")
        }
    }
}
