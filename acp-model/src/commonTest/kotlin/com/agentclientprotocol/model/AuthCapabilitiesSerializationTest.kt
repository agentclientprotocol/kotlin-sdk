@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthCapabilitiesSerializationTest {
    @Test
    fun `initialize preserves true false and omission independently of other capabilities`() {
        val serializer = InitializeRequest.serializer()
        val unrelated = """"_meta":{"fixture":true}"""
        val values = listOf(true to """, "terminal": true""", false to """, "terminal": false""", null to "")

        for ((flag, field) in values) {
            val payload = """{"protocolVersion":1,"clientCapabilities":{"terminal":true,"auth":{$unrelated$field}}}"""
            val request = ACPJson.decodeFromString(serializer, payload)
            val auth = request.clientCapabilities.auth

            assertTrue(request.clientCapabilities.terminal)
            assertEquals(flag, auth?.terminal)
            assertEquals(JsonPrimitive(true), auth?._meta?.jsonObject?.get("fixture"))

            val encoded = ACPJson.encodeToJsonElement(serializer, request).jsonObject
            val capabilities = encoded.getValue("clientCapabilities").jsonObject
            val authFields = capabilities.getValue("auth").jsonObject
            assertEquals(flag?.let(::JsonPrimitive), authFields["terminal"])
            assertEquals(JsonPrimitive(true), authFields.getValue("_meta").jsonObject["fixture"])
            assertEquals(JsonPrimitive(true), capabilities["terminal"])
        }
    }

    @Test
    fun `omitted auth and invalid terminal markers do not advertise support`() {
        val serializer = ClientCapabilities.serializer()
        assertNull(ACPJson.decodeFromString(serializer, "{}").auth)

        for (invalid in listOf("null", "{}", "1", "\"true\"", "TRUE", "TrUe")) {
            val decoded = ACPJson.decodeFromString(serializer, """{"auth":{"terminal":$invalid,"_meta":{"fixture":true}}}""")
            assertFalse(decoded.terminal)
            assertNull(decoded.auth?.terminal)
            assertEquals(JsonPrimitive(true), decoded.auth?._meta?.jsonObject?.get("fixture"))
        }
    }
}
