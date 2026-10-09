@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionResponsesTest {
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
