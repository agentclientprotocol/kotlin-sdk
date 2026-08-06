@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapabilitiesTest {

    // Agent capabilities

    @Test
    fun `an empty session group encodes as the session baseline marker`() {
        assertEquals(
            """{"session":{}}""",
            encodeAgent(AgentCapabilities(session = SessionCapabilities())),
        )
    }

    @Test
    fun `an absent session group means the agent has no session surface`() {
        assertEquals("{}", encodeAgent(AgentCapabilities()))
        assertNull(decodeAgent("{}").session)
        assertNull(decodeAgent("""{"session":null}""").session)
    }

    @Test
    fun `session scoped groups are nested under session`() {
        val capabilities = AgentCapabilities(
            session = SessionCapabilities(
                prompt = PromptCapabilities(image = PromptImageCapabilities()),
                mcp = McpCapabilities(stdio = McpStdioCapabilities(), http = McpHttpCapabilities()),
                delete = SessionDeleteCapabilities(),
                additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
            ),
        )

        assertEquals(
            """{"session":{"prompt":{"image":{}},"mcp":{"stdio":{},"http":{}},""" +
                """"delete":{},"additionalDirectories":{}}}""",
            encodeAgent(capabilities),
        )
        assertEquals(capabilities, decodeAgent(encodeAgent(capabilities)))
    }

    @Test
    fun `agent auth capabilities do not encode logout support`() {
        assertEquals("""{"auth":{}}""", encodeAgent(AgentCapabilities(auth = AgentAuthCapabilities())))
        assertEquals(
            AgentCapabilities(auth = AgentAuthCapabilities()),
            decodeAgent("""{"auth":{"logout":{}}}"""),
        )
    }

    // Marker semantics

    @Test
    fun `an explicit null marker means unsupported`() {
        val prompt = decodeAgent("""{"session":{"prompt":{"image":{},"audio":null}}}""").session?.prompt

        assertEquals(PromptImageCapabilities(), prompt?.image)
        assertNull(prompt?.audio)
        assertNull(prompt?.embeddedContext)
    }

    @Test
    fun `markers carry meta`() {
        val meta = buildJsonObject { put("vendor", JsonPrimitive("acme")) }

        assertEquals(
            """{"session":{"mcp":{"http":{"_meta":{"vendor":"acme"}}}}}""",
            encodeAgent(
                AgentCapabilities(
                    session = SessionCapabilities(mcp = McpCapabilities(http = McpHttpCapabilities(_meta = meta))),
                ),
            ),
        )
    }

    // Client capabilities

    @Test
    fun `the client surface carries elicitation only in its stable form`() {
        assertEquals(
            """{"elicitation":{},"positionEncodings":[]}""",
            encodeClient(ClientCapabilities(elicitation = ElicitationCapabilities())),
        )
    }

    @Test
    fun `removed v1 client fields are ignored when decoding`() {
        assertEquals(
            ClientCapabilities(elicitation = ElicitationCapabilities()),
            decodeClient("""{"fs":{"readTextFile":true},"terminal":true,"elicitation":{}}"""),
        )
    }

    private fun encodeAgent(capabilities: AgentCapabilities): String =
        ACPJson.encodeToString(AgentCapabilities.serializer(), capabilities)

    private fun decodeAgent(json: String): AgentCapabilities =
        ACPJson.decodeFromString(AgentCapabilities.serializer(), json)

    private fun encodeClient(capabilities: ClientCapabilities): String =
        ACPJson.encodeToString(ClientCapabilities.serializer(), capabilities)

    private fun decodeClient(json: String): ClientCapabilities =
        ACPJson.decodeFromString(ClientCapabilities.serializer(), json)
}
