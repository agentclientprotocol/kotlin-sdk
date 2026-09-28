@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.agentclientprotocol.model.v2.AgentAuthCapabilities as V2AgentAuthCapabilities
import com.agentclientprotocol.model.v2.AgentCapabilities as V2AgentCapabilities
import com.agentclientprotocol.model.v2.StatusAuthRequest as V2StatusAuthRequest
import com.agentclientprotocol.model.v2.StatusAuthResponse as V2StatusAuthResponse

class AuthStatusSerializationTest {
    private val meta = buildJsonObject { put("source", JsonPrimitive("test")) }

    @Test
    fun `v1 auth status request and response have the expected wire shape`() {
        assertEquals("{}", ACPJson.encodeToString(AuthStatusRequest.serializer(), AuthStatusRequest()))
        assertEquals(AuthStatusRequest(), ACPJson.decodeFromString(AuthStatusRequest.serializer(), "{}"))
        assertEquals(
            """{"_meta":{"source":"test"}}""",
            ACPJson.encodeToString(AuthStatusRequest.serializer(), AuthStatusRequest(_meta = meta)),
        )
        assertEquals(
            AuthStatusRequest(_meta = meta),
            ACPJson.decodeFromString(AuthStatusRequest.serializer(), """{"_meta":{"source":"test"}}"""),
        )

        assertEquals(
            """{"authenticated":false}""",
            ACPJson.encodeToString(AuthStatusResponse.serializer(), AuthStatusResponse(false)),
        )
        assertEquals(
            AuthStatusResponse(true),
            ACPJson.decodeFromString(AuthStatusResponse.serializer(), """{"authenticated":true}"""),
        )
        assertEquals(
            """{"authenticated":true,"message":"Credentials configured","_meta":{"source":"test"}}""",
            ACPJson.encodeToString(AuthStatusResponse.serializer(), AuthStatusResponse(true, "Credentials configured", meta)),
        )
        assertEquals(
            AuthStatusResponse(true, "Credentials configured", meta),
            ACPJson.decodeFromString(
                AuthStatusResponse.serializer(),
                """{"authenticated":true,"message":"Credentials configured","_meta":{"source":"test"}}""",
            ),
        )
        assertEquals(
            AuthStatusResponse(false),
            ACPJson.decodeFromString(AuthStatusResponse.serializer(), """{"authenticated":false,"message":null}"""),
        )
        assertFailsWith<SerializationException> { ACPJson.decodeFromString(AuthStatusResponse.serializer(), "{}") }
        assertEquals("auth/status", AcpMethod.AgentMethods.V1.AuthStatus.methodName.name)
    }

    @Test
    fun `v2 auth status request and response have the expected wire shape`() {
        assertEquals("{}", ACPJson.encodeToString(V2StatusAuthRequest.serializer(), V2StatusAuthRequest()))
        assertEquals(V2StatusAuthRequest(), ACPJson.decodeFromString(V2StatusAuthRequest.serializer(), "{}"))
        assertEquals(
            """{"_meta":{"source":"test"}}""",
            ACPJson.encodeToString(V2StatusAuthRequest.serializer(), V2StatusAuthRequest(_meta = meta)),
        )
        assertEquals(
            V2StatusAuthRequest(_meta = meta),
            ACPJson.decodeFromString(V2StatusAuthRequest.serializer(), """{"_meta":{"source":"test"}}"""),
        )

        assertEquals(
            """{"authenticated":false}""",
            ACPJson.encodeToString(V2StatusAuthResponse.serializer(), V2StatusAuthResponse(false)),
        )
        assertEquals(
            V2StatusAuthResponse(true),
            ACPJson.decodeFromString(V2StatusAuthResponse.serializer(), """{"authenticated":true}"""),
        )
        assertEquals(
            """{"authenticated":true,"message":"Credentials configured","_meta":{"source":"test"}}""",
            ACPJson.encodeToString(V2StatusAuthResponse.serializer(), V2StatusAuthResponse(true, "Credentials configured", meta)),
        )
        assertEquals(
            V2StatusAuthResponse(true, "Credentials configured", meta),
            ACPJson.decodeFromString(
                V2StatusAuthResponse.serializer(),
                """{"authenticated":true,"message":"Credentials configured","_meta":{"source":"test"}}""",
            ),
        )
        assertEquals(
            V2StatusAuthResponse(false),
            ACPJson.decodeFromString(V2StatusAuthResponse.serializer(), """{"authenticated":false,"message":null}"""),
        )
        assertFailsWith<SerializationException> { ACPJson.decodeFromString(V2StatusAuthResponse.serializer(), "{}") }
        assertEquals("auth/status", AcpMethod.AgentMethods.V2.AuthStatus.methodName.name)
    }

    @Test
    fun `status capabilities encode absent false and true in their versioned agent paths`() {
        assertEquals("{}", ACPJson.encodeToString(AgentAuthCapabilities.serializer(), AgentAuthCapabilities()))
        assertEquals("{}", ACPJson.encodeToString(V2AgentAuthCapabilities.serializer(), V2AgentAuthCapabilities()))

        for (status in listOf(false, true)) {
            val authJson = """{"status":$status,"_meta":{"source":"test"}}"""
            assertEquals(
                authJson,
                ACPJson.encodeToString(AgentAuthCapabilities.serializer(), AgentAuthCapabilities(status = status, _meta = meta)),
            )
            assertEquals(
                authJson,
                ACPJson.encodeToString(V2AgentAuthCapabilities.serializer(), V2AgentAuthCapabilities(status = status, _meta = meta)),
            )
            assertEquals(
                AgentAuthCapabilities(status = status, _meta = meta),
                ACPJson.decodeFromString(AgentAuthCapabilities.serializer(), authJson),
            )
            assertEquals(
                V2AgentAuthCapabilities(status = status, _meta = meta),
                ACPJson.decodeFromString(V2AgentAuthCapabilities.serializer(), authJson),
            )
            assertEquals(
                status,
                ACPJson.decodeFromString(
                    AgentCapabilities.serializer(),
                    ACPJson.encodeToString(
                        AgentCapabilities.serializer(),
                        AgentCapabilities(auth = AgentAuthCapabilities(status = status)),
                    ),
                ).auth.status,
            )
            assertEquals(
                V2AgentCapabilities(auth = V2AgentAuthCapabilities(status = status)),
                ACPJson.decodeFromString(V2AgentCapabilities.serializer(), """{"auth":{"status":$status}}"""),
            )
        }
    }
}