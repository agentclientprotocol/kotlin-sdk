@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionSetupSerializationTest {

    private val verbose = SessionConfigOption(
        configId = SessionConfigId("verbose"),
        name = "Verbose",
        kind = SessionConfigKind.Boolean(currentValue = true),
    )
    private val verboseJson = """{"type":"boolean","configId":"verbose","name":"Verbose","currentValue":true}"""
    private val stdioJson = """{"type":"stdio","name":"tools","command":"/bin/tools"}"""
    private val webCommand = AvailableCommand("web", "Search the web", input = AvailableCommandInput.Text("Query"))
    private val webCommandJson =
        """{"name":"web","description":"Search the web","input":{"type":"text","hint":"Query"}}"""
    private val testCommand = AvailableCommand("test", "Run project tests")
    private val testCommandJson = """{"name":"test","description":"Run project tests"}"""

    // session/new

    @Test
    fun `new session request encodes the shared environment`() {
        assertEquals(
            """{"cwd":"/work","mcpServers":[{"type":"stdio","name":"tools","command":"/bin/tools","args":[],""" +
                """"env":[]}],"additionalDirectories":["/lib"]}""",
            ACPJson.encodeToString(
                NewSessionRequest.serializer(),
                NewSessionRequest(
                    cwd = "/work",
                    mcpServers = listOf(McpServer.Stdio(name = "tools", command = "/bin/tools")),
                    additionalDirectories = listOf("/lib"),
                ),
            ),
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
            ACPJson.encodeToString(ForkSessionRequest.serializer(), ForkSessionRequest(SessionId("s1"), "/work")),
        )
    }

    @Test
    fun `resume request retains nonempty additional directories`() {
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","additionalDirectories":["/lib"],"mcpServers":[]}""",
            encodeResume(ResumeSessionRequest(SessionId("s1"), "/work", additionalDirectories = listOf("/lib"))),
        )
    }

    @Test
    fun `new session request needs only cwd`() {
        assertEquals(
            NewSessionRequest(cwd = "/work"),
            ACPJson.decodeFromString(NewSessionRequest.serializer(), """{"cwd":"/work"}"""),
        )
    }

    @Test
    fun `new session request skips list entries that fail to decode`() {
        val request = ACPJson.decodeFromString(
            NewSessionRequest.serializer(),
            """{"cwd":"/work","mcpServers":[$stdioJson,{"type":"stdio"},{"name":"untyped"},{"type":"_custom"}],""" +
                """"additionalDirectories":["/a",{},"/b"]}""",
        )

        assertEquals(
            listOf(
                McpServer.Stdio(name = "tools", command = "/bin/tools"),
                McpServer.Unknown(type = "_custom", rawJson = JsonObject(mapOf("type" to JsonPrimitive("_custom")))),
            ),
            request.mcpServers,
        )
        assertEquals(listOf("/a", "/b"), request.additionalDirectories)
    }

    @Test
    fun `new session request reads null or non-array lists as empty`() {
        assertEquals(
            NewSessionRequest(cwd = "/work"),
            ACPJson.decodeFromString(
                NewSessionRequest.serializer(),
                """{"cwd":"/work","mcpServers":null,"additionalDirectories":"/lib"}""",
            ),
        )
    }

    @Test
    fun `new session response ignores v1 modes and skips bad config options`() {
        assertEquals(
            NewSessionResponse(sessionId = SessionId("s1"), configOptions = listOf(verbose)),
            ACPJson.decodeFromString(
                NewSessionResponse.serializer(),
                """{"sessionId":"s1","configOptions":[$verboseJson,{"type":"boolean"}],""" +
                    """"modes":{"currentModeId":"ask","availableModes":[]}}""",
            ),
        )
    }

    // session/resume

    @Test
    fun `resume request encodes replayFrom start`() {
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[],""" +
                """"replayFrom":{"type":"start"}}""",
            encodeResume(ResumeSessionRequest(SessionId("s1"), cwd = "/work", replayFrom = ReplayFrom.Start())),
        )
    }

    @Test
    fun `resume request without replayFrom omits the key`() {
        assertEquals(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[]}""",
            encodeResume(ResumeSessionRequest(SessionId("s1"), cwd = "/work")),
        )
    }

    @Test
    fun `resume request reads absent and null replayFrom as no replay`() {
        assertNull(decodeResume("""{"sessionId":"s1","cwd":"/work"}""").replayFrom)
        assertNull(decodeResume("""{"sessionId":"s1","cwd":"/work","replayFrom":null}""").replayFrom)
    }

    @Test
    fun `resume request decodes replayFrom start`() {
        assertEquals(
            ResumeSessionRequest(SessionId("s1"), cwd = "/work", replayFrom = ReplayFrom.Start()),
            decodeResume("""{"sessionId":"s1","cwd":"/work","replayFrom":{"type":"start"}}"""),
        )
    }

    @Test
    fun `resume request keeps an unknown cursor byte-identically`() {
        val json = """{"sessionId":"s1","cwd":"/work","mcpServers":[],""" +
            """"replayFrom":{"type":"_from_message","messageId":"m1"}}"""

        val request = decodeResume(json)

        assertEquals("_from_message", assertIs<ReplayFrom.Unknown>(request.replayFrom).type)
        assertEquals(json, encodeResume(request))
    }

    @Test
    fun `resume request reads a malformed replayFrom as no replay`() {
        for (replayFrom in listOf(""""start"""", """{"messageId":"m1"}""", """{"type":5}""")) {
            val request = decodeResume("""{"sessionId":"s1","cwd":"/work","replayFrom":$replayFrom}""")

            assertNull(request.replayFrom, replayFrom)
        }
    }

    @Test
    fun `resume request skips list entries that fail to decode`() {
        val request = decodeResume(
            """{"sessionId":"s1","cwd":"/work","mcpServers":[{"type":"http"},$stdioJson],""" +
                """"additionalDirectories":null}""",
        )

        assertEquals(listOf(McpServer.Stdio(name = "tools", command = "/bin/tools")), request.mcpServers)
        assertEquals(emptyList(), request.additionalDirectories)
    }

    @Test
    fun `resume response carries config options and ignores v1 modes`() {
        assertEquals(
            ResumeSessionResponse(configOptions = listOf(verbose)),
            ACPJson.decodeFromString(
                ResumeSessionResponse.serializer(),
                """{"configOptions":[$verboseJson,"bad"],"modes":{"currentModeId":"ask","availableModes":[]}}""",
            ),
        )
        assertEquals(ResumeSessionResponse(), ACPJson.decodeFromString(ResumeSessionResponse.serializer(), "{}"))
    }

    // available commands in setup responses

    @Test
    fun `setup responses encode available commands alongside config options`() {
        val commands = listOf(webCommand, testCommand)
        val body = """"configOptions":[$verboseJson],"availableCommands":[$webCommandJson,$testCommandJson]"""
        val newSession = NewSessionResponse(SessionId("s1"), configOptions = listOf(verbose), availableCommands = commands)
        val resume = ResumeSessionResponse(configOptions = listOf(verbose), availableCommands = commands)
        val fork = ForkSessionResponse(SessionId("s2"), configOptions = listOf(verbose), availableCommands = commands)

        val newSessionJson = ACPJson.encodeToString(NewSessionResponse.serializer(), newSession)
        val resumeJson = ACPJson.encodeToString(ResumeSessionResponse.serializer(), resume)
        val forkJson = ACPJson.encodeToString(ForkSessionResponse.serializer(), fork)

        assertEquals("""{"sessionId":"s1",$body}""", newSessionJson)
        assertEquals("""{$body}""", resumeJson)
        assertEquals("""{"sessionId":"s2",$body}""", forkJson)
        assertEquals(newSession, ACPJson.decodeFromString(NewSessionResponse.serializer(), newSessionJson))
        assertEquals(resume, ACPJson.decodeFromString(ResumeSessionResponse.serializer(), resumeJson))
        assertEquals(fork, ACPJson.decodeFromString(ForkSessionResponse.serializer(), forkJson))
    }

    @Test
    fun `setup responses omit empty available commands`() {
        assertEquals(
            """{"sessionId":"s1","configOptions":[]}""",
            ACPJson.encodeToString(NewSessionResponse.serializer(), NewSessionResponse(SessionId("s1"))),
        )
        assertEquals(
            """{"configOptions":[]}""",
            ACPJson.encodeToString(ResumeSessionResponse.serializer(), ResumeSessionResponse()),
        )
        assertEquals(
            """{"sessionId":"s2","configOptions":[]}""",
            ACPJson.encodeToString(ForkSessionResponse.serializer(), ForkSessionResponse(SessionId("s2"))),
        )
    }

    @Test
    fun `setup responses read missing null or malformed available commands as empty`() {
        val cases = listOf(
            "" to emptyList(),
            ""","availableCommands":null""" to emptyList(),
            ""","availableCommands":"oops"""" to emptyList(),
            ""","availableCommands":{}""" to emptyList(),
            ""","availableCommands":[null,{},{"name":"invalid"}]""" to emptyList(),
            ""","availableCommands":[null,$testCommandJson,{"name":"invalid"}]""" to listOf(testCommand),
        )

        for ((commandsField, expected) in cases) {
            val body = """"configOptions":[$verboseJson]$commandsField"""

            assertEquals(
                NewSessionResponse(SessionId("s1"), configOptions = listOf(verbose), availableCommands = expected),
                ACPJson.decodeFromString(NewSessionResponse.serializer(), """{"sessionId":"s1",$body}"""),
                commandsField,
            )
            assertEquals(
                ResumeSessionResponse(configOptions = listOf(verbose), availableCommands = expected),
                ACPJson.decodeFromString(ResumeSessionResponse.serializer(), """{$body}"""),
                commandsField,
            )
            assertEquals(
                ForkSessionResponse(SessionId("s2"), configOptions = listOf(verbose), availableCommands = expected),
                ACPJson.decodeFromString(ForkSessionResponse.serializer(), """{"sessionId":"s2",$body}"""),
                commandsField,
            )
        }
    }

    private fun encodeResume(request: ResumeSessionRequest): String =
        ACPJson.encodeToString(ResumeSessionRequest.serializer(), request)

    private fun decodeResume(json: String): ResumeSessionRequest =
        ACPJson.decodeFromString(ResumeSessionRequest.serializer(), json)
}
