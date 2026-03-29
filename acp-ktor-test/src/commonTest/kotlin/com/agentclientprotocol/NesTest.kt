package com.agentclientprotocol

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.NesAgentSession
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
abstract class NesTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    @Test
    fun `nes session lifecycle - start suggest close`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(
                    clientInfo.protocolVersion,
                    capabilities = AgentCapabilities(
                        nes = NesCapabilities(
                            events = NesEventCapabilities(
                                document = NesDocumentEventCapabilities(
                                    didOpen = NesDocumentDidOpenCapabilities()
                                )
                            )
                        ),
                        positionEncoding = PositionEncodingKind.UTF_16
                    )
                )
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO("Not needed for NES test")
            }

            override suspend fun createNesSession(request: StartNesRequest): NesAgentSession {
                return object : NesAgentSession {
                    override val nesSessionId: SessionId = SessionId("nes-session-1")

                    override suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse {
                        return SuggestNesResponse(
                            suggestions = listOf(
                                NesSuggestion.Edit(
                                    id = "edit-1",
                                    uri = request.uri,
                                    edits = listOf(
                                        NesTextEdit(
                                            range = NesRange(
                                                start = request.position,
                                                end = request.position
                                            ),
                                            newText = "suggested text"
                                        )
                                    )
                                )
                            )
                        )
                    }

                    override suspend fun close(_meta: JsonElement?): CloseNesResponse {
                        return CloseNesResponse()
                    }
                }
            }
        })

        // Initialize
        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    nes = ClientNesCapabilities(
                        jump = NesJumpCapabilities()
                    ),
                    positionEncodings = listOf(PositionEncodingKind.UTF_16)
                )
            )
        )

        // Start NES session
        val nesSession = client.startNesSession(
            workspaceUri = "file:///workspace"
        )
        assertEquals(SessionId("nes-session-1"), nesSession.nesSessionId)

        // Send didOpen (fire-and-forget notification)
        nesSession.didOpen(
            uri = "file:///workspace/test.kt",
            languageId = "kotlin",
            version = 1,
            text = "fun main() {}"
        )

        // Request suggestions
        val suggestResponse = nesSession.suggest(
            uri = "file:///workspace/test.kt",
            version = 1,
            position = NesPosition(line = 0u, character = 14u),
            triggerKind = NesTriggerKind.AUTOMATIC
        )
        assertEquals(1, suggestResponse.suggestions.size)
        val suggestion = suggestResponse.suggestions[0]
        assertTrue(suggestion is NesSuggestion.Edit)
        assertEquals("edit-1", suggestion.id)
        assertEquals("suggested text", suggestion.edits[0].newText)

        // Accept the suggestion (fire-and-forget notification)
        nesSession.accept("edit-1")

        // Close the NES session (request-response)
        val closeResponse = nesSession.close()
        assertEquals(CloseNesResponse(), closeResponse)
    }

    @Test
    fun `nes suggest returns multiple suggestion types`() = testWithProtocols { clientProtocol, agentProtocol ->
        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion, capabilities = AgentCapabilities(nes = NesCapabilities()))
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO()
            }

            override suspend fun createNesSession(request: StartNesRequest): NesAgentSession {
                return object : NesAgentSession {
                    override val nesSessionId: SessionId = SessionId("nes-2")

                    override suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse {
                        return SuggestNesResponse(
                            suggestions = listOf(
                                NesSuggestion.Edit(
                                    id = "s1",
                                    uri = request.uri,
                                    edits = listOf(
                                        NesTextEdit(
                                            range = NesRange(NesPosition(0u, 0u), NesPosition(0u, 3u)),
                                            newText = "val"
                                        )
                                    )
                                ),
                                NesSuggestion.Jump(
                                    id = "s2",
                                    uri = "file:///other.kt",
                                    position = NesPosition(10u, 0u)
                                ),
                                NesSuggestion.Rename(
                                    id = "s3",
                                    uri = request.uri,
                                    position = NesPosition(5u, 4u),
                                    newName = "newName"
                                ),
                                NesSuggestion.SearchAndReplace(
                                    id = "s4",
                                    uri = request.uri,
                                    search = "old",
                                    replace = "new",
                                    isRegex = false
                                )
                            )
                        )
                    }
                }
            }
        })

        client.initialize(ClientInfo())

        val nesSession = client.startNesSession()
        val response = nesSession.suggest(
            uri = "file:///test.kt",
            version = 1,
            position = NesPosition(0u, 0u),
            triggerKind = NesTriggerKind.MANUAL
        )

        assertEquals(4, response.suggestions.size)
        assertTrue(response.suggestions[0] is NesSuggestion.Edit)
        assertTrue(response.suggestions[1] is NesSuggestion.Jump)
        assertTrue(response.suggestions[2] is NesSuggestion.Rename)
        assertTrue(response.suggestions[3] is NesSuggestion.SearchAndReplace)

        val rename = response.suggestions[2] as NesSuggestion.Rename
        assertEquals("newName", rename.newName)

        nesSession.close()
    }

    @Test
    fun `nes session with suggest context`() = testWithProtocols { clientProtocol, agentProtocol ->
        val receivedContext = CompletableDeferred<NesSuggestContext?>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion, capabilities = AgentCapabilities(
                    nes = NesCapabilities(
                        context = NesContextCapabilities(
                            recentFiles = NesRecentFilesCapabilities(maxCount = 5),
                            diagnostics = NesDiagnosticsCapabilities()
                        )
                    )
                ))
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO()
            }

            override suspend fun createNesSession(request: StartNesRequest): NesAgentSession {
                return object : NesAgentSession {
                    override val nesSessionId: SessionId = SessionId("nes-ctx")

                    override suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse {
                        receivedContext.complete(request.context)
                        return SuggestNesResponse(suggestions = emptyList())
                    }
                }
            }
        })

        client.initialize(ClientInfo())

        val nesSession = client.startNesSession()
        val context = NesSuggestContext(
            recentFiles = listOf(
                NesRecentFile(uri = "file:///recent.kt", languageId = "kotlin", text = "fun recent() {}")
            ),
            diagnostics = listOf(
                NesDiagnostic(
                    uri = "file:///test.kt",
                    range = NesRange(NesPosition(0u, 0u), NesPosition(0u, 10u)),
                    severity = NesDiagnosticSeverity.ERROR,
                    message = "Unresolved reference"
                )
            )
        )

        nesSession.suggest(
            uri = "file:///test.kt",
            version = 1,
            position = NesPosition(0u, 5u),
            triggerKind = NesTriggerKind.DIAGNOSTIC,
            context = context
        )

        val received = receivedContext.await()
        assertEquals(1, received?.recentFiles?.size)
        assertEquals("file:///recent.kt", received?.recentFiles?.get(0)?.uri)
        assertEquals(1, received?.diagnostics?.size)
        assertEquals(NesDiagnosticSeverity.ERROR, received?.diagnostics?.get(0)?.severity)

        nesSession.close()
    }

    @Test
    fun `client operations are not available in nes session context`() = testWithProtocols { clientProtocol, agentProtocol ->
        val errorDeferred = CompletableDeferred<Throwable>()

        val client = Client(protocol = clientProtocol)
        Agent(protocol = agentProtocol, agentSupport = object : AgentSupport {
            override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
                return AgentInfo(clientInfo.protocolVersion, capabilities = AgentCapabilities(nes = NesCapabilities()))
            }

            override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
                TODO()
            }

            override suspend fun createNesSession(request: StartNesRequest): NesAgentSession {
                return object : NesAgentSession {
                    override val nesSessionId: SessionId = SessionId("nes-no-client-ops")

                    override suspend fun suggest(request: SuggestNesRequest): SuggestNesResponse {
                        val error = assertFailsWith<IllegalStateException> {
                            currentCoroutineContext().client
                        }
                        errorDeferred.complete(error)
                        return SuggestNesResponse(suggestions = emptyList())
                    }
                }
            }
        })

        client.initialize(ClientInfo())

        val nesSession = client.startNesSession()
        nesSession.suggest(
            uri = "file:///test.kt",
            version = 1,
            position = NesPosition(0u, 0u),
            triggerKind = NesTriggerKind.AUTOMATIC
        )

        val error = errorDeferred.await()
        assertTrue(error.message!!.contains("not available for NES sessions"))

        nesSession.close()
    }
}
