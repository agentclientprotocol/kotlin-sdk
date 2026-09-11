@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RequestPermissionRequestTest {
    private val method = AcpMethod.ClientMethods.V2.SessionRequestPermission
    private val options = listOf(PermissionOption(PermissionOptionId("allow-once"), "Allow once", PermissionOptionKind.AllowOnce))

    @Test
    fun `minimal permission prompt needs no description or subject`() {
        val json = """{"sessionId":"sess_1","title":"Continue?","options":[""" +
            """{"optionId":"allow-once","name":"Allow once","kind":"allow_once"}]}"""
        val request = RequestPermissionRequest(SessionId("sess_1"), "Continue?", options)

        assertEquals(request, decode(json))
        assertEquals(json, encode(request))
        assertEquals(
            request,
            decode(json.dropLast(1) + """, "description":null,"subject":null}"""),
        )
    }

    @Test
    fun `command request uses the v2 wire shape and preserves metadata at each level`() {
        val json = """{
            "sessionId":"sess_1",
            "title":"Run the test suite?",
            "description":"Allow the agent to run cargo test?",
            "subject":{
                "type":"command",
                "command":"cargo test",
                "cwd":"/project",
                "toolCallId":"call_001",
                "terminalId":"term_001",
                "_meta":{"scope":"subject"}
            },
            "options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"}],
            "_meta":{"scope":"request"}
        }"""
        val request = decode(json)

        assertEquals("Run the test suite?", request.title)
        assertEquals("Allow the agent to run cargo test?", request.description)
        assertEquals(options, request.options)
        assertEquals(
            RequestPermissionSubject.Command(
                command = "cargo test",
                cwd = "/project",
                toolCallId = ToolCallId("call_001"),
                terminalId = "term_001",
                _meta = buildJsonObject { put("scope", "subject") },
            ),
            request.subject,
        )
        assertEquals(buildJsonObject { put("scope", "request") }, request._meta)
        assertEquals(ACPJson.parseToJsonElement(json), ACPJson.parseToJsonElement(encode(request)))
    }

    @Test
    fun `prompt text stays separate from tool call upsert fields`() {
        val json = """{
            "sessionId":"sess_1",
            "title":"Approve the edit?",
            "description":"Allow this change?",
            "subject":{
                "type":"tool_call",
                "toolCall":{"toolCallId":"call_001","title":"Editing configuration","content":null,"locations":[]}
            },
            "options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"}]
        }"""
        val request = decode(json)
        val subject = assertIs<RequestPermissionSubject.ToolCall>(request.subject)

        assertEquals("Approve the edit?", request.title)
        assertEquals("Allow this change?", request.description)
        assertEquals(MaybeUndefined.Value("Editing configuration"), subject.toolCall.title)
        assertEquals(MaybeUndefined.Null, subject.toolCall.content)
        assertEquals(MaybeUndefined.Value(emptyList()), subject.toolCall.locations)
        assertEquals(MaybeUndefined.Undefined, subject.toolCall.status)
        assertEquals(ACPJson.parseToJsonElement(json), ACPJson.parseToJsonElement(encode(request)))
    }

    @Test
    fun `session id title and options are required`() {
        val valid = ACPJson.parseToJsonElement(encode(RequestPermissionRequest(SessionId("sess_1"), "Continue?", options)))
            .jsonObject

        for (field in listOf("sessionId", "title", "options")) {
            val missingField = JsonObject(valid.filterKeys { it != field })
            assertFailsWith<SerializationException>(field) { decode(missingField.toString()) }
        }
        assertFailsWith<SerializationException> {
            decode(
                """{"sessionId":"sess_1","toolCall":{"toolCallId":"call_001"},"options":[""" +
                    """{"optionId":"allow-once","name":"Allow once","kind":"allow_once"}]}"""
            )
        }
    }

    @Test
    fun `future subjects and option kinds survive request forwarding`() {
        val json = """{
            "sessionId":"sess_1",
            "title":"Approve network access?",
            "subject":{"type":"_network","host":"example.com"},
            "options":[{"optionId":"scoped","name":"Allow for this host","kind":"_allow_host"}]
        }"""
        val request = decode(json)

        assertIs<RequestPermissionSubject.Unknown>(request.subject)
        assertEquals(PermissionOptionKind.Unknown("_allow_host"), request.options.single().kind)
        assertEquals(ACPJson.parseToJsonElement(json), ACPJson.parseToJsonElement(encode(request)))
    }

    private fun decode(json: String): RequestPermissionRequest =
        ACPJson.decodeFromString(method.requestSerializer, json)

    private fun encode(request: RequestPermissionRequest): String =
        ACPJson.encodeToString(method.requestSerializer, request)
}
