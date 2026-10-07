@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SessionResponsesTest {
    @Test
    fun `prompt response carries the inserted message id`() {
        val meta = ACPJson.parseToJsonElement("""{"trace":"prompt"}""")
        val response = PromptResponse(MessageId("user-1"), meta)
        val json = ACPJson.parseToJsonElement("""{"messageId":"user-1","_meta":{"trace":"prompt"}}""")

        assertEquals(json, ACPJson.encodeToJsonElement(PromptResponse.serializer(), response))
        assertEquals(response, ACPJson.decodeFromJsonElement(PromptResponse.serializer(), json))
        assertEquals(
            ACPJson.parseToJsonElement("""{"messageId":"user-1"}"""),
            ACPJson.encodeToJsonElement(PromptResponse.serializer(), PromptResponse(MessageId("user-1"))),
        )
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
    fun `setup responses omit an empty command list`() {
        val encoded = listOf(
            ACPJson.encodeToJsonElement(NewSessionResponse.serializer(), NewSessionResponse(SessionId("s"))),
            ACPJson.encodeToJsonElement(ResumeSessionResponse.serializer(), ResumeSessionResponse()),
            ACPJson.encodeToJsonElement(ForkSessionResponse.serializer(), ForkSessionResponse(SessionId("s"))),
        )
        for (json in encoded) {
            assertFalse("availableCommands" in json.jsonObject, "$json")
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

        val new = NewSessionResponse(SessionId("s"), availableCommands = commands)
        val newJson = ACPJson.encodeToJsonElement(NewSessionResponse.serializer(), new)
        assertEquals(commandsJson, newJson.jsonObject["availableCommands"])
        assertEquals(new, ACPJson.decodeFromJsonElement(NewSessionResponse.serializer(), newJson))

        val resume = ResumeSessionResponse(availableCommands = commands)
        val resumeJson = ACPJson.encodeToJsonElement(ResumeSessionResponse.serializer(), resume)
        assertEquals(commandsJson, resumeJson.jsonObject["availableCommands"])
        assertEquals(resume, ACPJson.decodeFromJsonElement(ResumeSessionResponse.serializer(), resumeJson))

        val fork = ForkSessionResponse(SessionId("s"), availableCommands = commands)
        val forkJson = ACPJson.encodeToJsonElement(ForkSessionResponse.serializer(), fork)
        assertEquals(commandsJson, forkJson.jsonObject["availableCommands"])
        assertEquals(fork, ACPJson.decodeFromJsonElement(ForkSessionResponse.serializer(), forkJson))
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
