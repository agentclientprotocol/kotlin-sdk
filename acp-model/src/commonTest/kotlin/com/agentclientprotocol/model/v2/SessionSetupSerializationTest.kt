@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SessionSetupSerializationTest {
    private val modes = """"modes":{"currentModeId":"ask","availableModes":[]}"""

    private val options = listOf(
        SessionConfigOption(SessionConfigId("enabled"), "Enabled", kind = SessionConfigKind.Boolean(true)),
    )

    private val optionsJson =
        """"configOptions":[{"type":"boolean","configId":"enabled","name":"Enabled","currentValue":true}]"""

    @Test
    fun `new session request needs only cwd`() {
        assertEquals(
            NewSessionRequest("/work"),
            ACPJson.decodeFromString(NewSessionRequest.serializer(), """{"cwd":"/work"}"""),
        )
    }

    @Test
    fun `resume request needs only session id and cwd`() {
        assertEquals(
            ResumeSessionRequest(SessionId("s1"), "/work"),
            ACPJson.decodeFromString(ResumeSessionRequest.serializer(), """{"sessionId":"s1","cwd":"/work"}"""),
        )
    }

    @Test
    fun `empty additional directories are omitted from setup requests`() {
        assertEquals(
            """{"cwd":"/work","mcpServers":[]}""",
            ACPJson.encodeToString(NewSessionRequest.serializer(), NewSessionRequest("/work")),
        )
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[]}""",
            ACPJson.encodeToString(ResumeSessionRequest.serializer(), ResumeSessionRequest(SessionId("s1"), "/work")),
        )
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[]}""",
            ACPJson.encodeToString(ForkSessionRequest.serializer(), ForkSessionRequest(SessionId("s1"), "/work")),
        )
    }

    @Test
    fun `nonempty additional directories are encoded`() {
        assertEquals(
            """{"cwd":"/work","mcpServers":[],"additionalDirectories":["/lib"]}""",
            ACPJson.encodeToString(
                NewSessionRequest.serializer(),
                NewSessionRequest("/work", additionalDirectories = listOf("/lib")),
            ),
        )
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","additionalDirectories":["/lib"],"mcpServers":[]}""",
            ACPJson.encodeToString(
                ResumeSessionRequest.serializer(),
                ResumeSessionRequest(SessionId("s1"), "/work", additionalDirectories = listOf("/lib")),
            ),
        )
    }

    @Test
    fun `resume request encodes replayFrom start`() {
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[],"replayFrom":{"type":"start"}}""",
            ACPJson.encodeToString(
                ResumeSessionRequest.serializer(),
                ResumeSessionRequest(SessionId("s1"), "/work", replayFrom = ReplayFrom.Start()),
            ),
        )
    }

    @Test
    fun `resume request without replayFrom omits the key`() {
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[]}""",
            ACPJson.encodeToString(ResumeSessionRequest.serializer(), ResumeSessionRequest(SessionId("s1"), "/work")),
        )
    }

    @Test
    fun `resume request reads absent and null replayFrom as no replay`() {
        val expected = ResumeSessionRequest(SessionId("s1"), "/work")
        assertEquals(
            expected,
            ACPJson.decodeFromString(ResumeSessionRequest.serializer(), """{"sessionId":"s1","cwd":"/work"}"""),
        )
        assertEquals(
            expected,
            ACPJson.decodeFromString(
                ResumeSessionRequest.serializer(),
                """{"sessionId":"s1","cwd":"/work","replayFrom":null}""",
            ),
        )
    }

    @Test
    fun `resume request decodes replayFrom start`() {
        assertEquals(
            ResumeSessionRequest(SessionId("s1"), "/work", replayFrom = ReplayFrom.Start()),
            ACPJson.decodeFromString(
                ResumeSessionRequest.serializer(),
                """{"sessionId":"s1","cwd":"/work","replayFrom":{"type":"start"}}""",
            ),
        )
    }

    @Test
    fun `resume request keeps an unknown cursor`() {
        val cursor = """{"type":"_from_message","messageId":"m1"}"""
        val json = """{"sessionId":"s1","cwd":"/work","mcpServers":[],"replayFrom":$cursor}"""

        val request = ACPJson.decodeFromString(ResumeSessionRequest.serializer(), json)

        val replayFrom = assertIs<ReplayFrom.Unknown>(request.replayFrom)
        assertEquals("_from_message", replayFrom.type)
        assertEquals(json, ACPJson.encodeToString(ResumeSessionRequest.serializer(), request))
    }

    @Test
    fun `setup responses carry config options and ignore v1 modes`() {
        assertEquals(
            NewSessionResponse(SessionId("s1"), options),
            ACPJson.decodeFromString(
                NewSessionResponse.serializer(),
                """{"sessionId":"s1",$modes,$optionsJson}""",
            ),
        )
        assertEquals(
            ResumeSessionResponse(options),
            ACPJson.decodeFromString(ResumeSessionResponse.serializer(), """{$modes,$optionsJson}"""),
        )
    }

    @Test
    fun `prompt response carries the inserted message id`() {
        val meta = ACPJson.parseToJsonElement("""{"trace":"prompt"}""")
        val response = PromptResponse(MessageId("user-1"), meta)
        val json = ACPJson.parseToJsonElement("""{"messageId":"user-1","_meta":{"trace":"prompt"}}""")

        assertEquals(json, ACPJson.encodeToJsonElement(PromptResponse.serializer(), response))
        assertEquals(response, ACPJson.decodeFromJsonElement(PromptResponse.serializer(), json))
    }

    @Test
    fun `prompt response requires a non-null message id`() {
        for (json in listOf("{}", """{"messageId":null}""")) {
            assertFailsWith<SerializationException>(json) {
                ACPJson.decodeFromString(PromptResponse.serializer(), json)
            }
        }
    }

    @Test
    fun `setup responses carry the initial commands`() {
        val commands = listOf(
            AvailableCommand("plan", "Make a plan", AvailableCommandInput.Text("what to plan")),
            AvailableCommand("review", "Review the change"),
        )
        val commandsJson = ACPJson.parseToJsonElement(
            """[{"name":"plan","description":"Make a plan","input":{"type":"text","hint":"what to plan"}},""" +
                """{"name":"review","description":"Review the change"}]"""
        )

        fun <T> assertCarriesCommands(serializer: KSerializer<T>, response: T) {
            val json = ACPJson.encodeToJsonElement(serializer, response)
            assertEquals(commandsJson, json.jsonObject["availableCommands"])
            assertEquals(response, ACPJson.decodeFromJsonElement(serializer, json))
        }

        val sessionId = SessionId("s")
        assertCarriesCommands(NewSessionResponse.serializer(), NewSessionResponse(sessionId, availableCommands = commands))
        assertCarriesCommands(ResumeSessionResponse.serializer(), ResumeSessionResponse(availableCommands = commands))
        assertCarriesCommands(ForkSessionResponse.serializer(), ForkSessionResponse(sessionId, availableCommands = commands))
    }

    @Test
    fun `setup responses decode a malformed command list leniently`() {
        val review = AvailableCommand("review", "Review the change")
        val cases = listOf<Pair<String, List<AvailableCommand>>>(
            """{"sessionId":"s","availableCommands":null}""" to emptyList(),
            """{"sessionId":"s","availableCommands":{"name":"review"}}""" to emptyList(),
            """{"sessionId":"s","availableCommands":"review"}""" to emptyList(),
            """{"sessionId":"s","availableCommands":[{"name":"broken"},""" +
                """{"name":"review","description":"Review the change"},42]}""" to listOf(review),
        )
        for ((json, expected) in cases) {
            assertEquals(expected, ACPJson.decodeFromString(NewSessionResponse.serializer(), json).availableCommands, json)
            assertEquals(expected, ACPJson.decodeFromString(ResumeSessionResponse.serializer(), json).availableCommands, json)
            assertEquals(expected, ACPJson.decodeFromString(ForkSessionResponse.serializer(), json).availableCommands, json)
        }
    }
}
