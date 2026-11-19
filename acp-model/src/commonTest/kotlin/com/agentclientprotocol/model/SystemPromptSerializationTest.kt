package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SystemPromptSerializationTest {
    private val prompt = SystemPrompt(SystemPromptMode.APPEND, listOf(ContentBlock.Text("Be concise.")))
    private val promptJson = """{"mode":"append","content":[{"type":"text","text":"Be concise."}]}"""

    @Test
    fun `modes keep their wire value including unknown ones`() {
        assertEquals(SystemPromptMode.OVERRIDE, ACPJson.decodeFromString(SystemPromptMode.serializer(), "\"override\""))
        assertEquals(SystemPromptMode.OVERRIDE, SystemPromptMode("override"))

        val custom = ACPJson.decodeFromString(SystemPromptMode.serializer(), "\"_vendor\"")
        assertEquals("_vendor", custom.value)
        assertEquals("\"_vendor\"", ACPJson.encodeToString(SystemPromptMode.serializer(), custom))
    }

    @Test
    fun `decodes the systemPrompt session capability`() {
        fun decode(json: String) = ACPJson.decodeFromString(SessionCapabilities.serializer(), json).systemPrompt

        assertNull(decode("{}"))
        assertNull(decode("""{"systemPrompt":null}"""))
        assertEquals(SessionSystemPromptCapabilities(), decode("""{"systemPrompt":{}}"""))
        assertNull(decode("""{"systemPrompt":{"mode":{"override":null}}}""")?.mode?.override)
        assertNotNull(decode("""{"systemPrompt":{"mode":{"override":{}}}}""")?.mode?.override)
    }

    @Test
    fun `new session request encodes systemPrompt only when set`() {
        assertEquals(
            """{"cwd":"/work","mcpServers":[],"systemPrompt":$promptJson}""",
            ACPJson.encodeToString(NewSessionRequest.serializer(), NewSessionRequest("/work", emptyList(), systemPrompt = prompt)),
        )
        assertEquals(
            """{"cwd":"/work","mcpServers":[]}""",
            ACPJson.encodeToString(NewSessionRequest.serializer(), NewSessionRequest("/work", emptyList())),
        )
        assertNull(
            ACPJson.decodeFromString(
                NewSessionRequest.serializer(),
                """{"cwd":"/work","mcpServers":[],"systemPrompt":null}""",
            ).systemPrompt,
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `load resume and fork requests round trip systemPrompt`() {
        val sessionId = SessionId("s1")
        val load = LoadSessionRequest(sessionId, "/work", emptyList(), systemPrompt = prompt)
        val resume = ResumeSessionRequest(sessionId, "/work", systemPrompt = prompt)
        val fork = ForkSessionRequest(sessionId, "/work", emptyList(), systemPrompt = prompt)

        assertEquals(load, roundTrip(LoadSessionRequest.serializer(), load))
        assertEquals(resume, roundTrip(ResumeSessionRequest.serializer(), resume))
        assertEquals(fork, roundTrip(ForkSessionRequest.serializer(), fork))
    }

    @Test
    fun `systemPrompt requires mode and content`() {
        for (json in listOf("""{"content":[]}""", """{"mode":"append"}""", """{"mode":null,"content":[]}""")) {
            assertFailsWith<SerializationException>(json) { ACPJson.decodeFromString(SystemPrompt.serializer(), json) }
        }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): T =
        ACPJson.decodeFromString(serializer, ACPJson.encodeToString(serializer, value))
}
