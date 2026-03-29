package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class NesSerializationTest {

    @Test
    fun `NesSuggestion Edit round-trip`() {
        val edit = NesSuggestion.Edit(
            id = "suggestion-1",
            uri = "file:///test.kt",
            edits = listOf(
                NesTextEdit(
                    range = NesRange(
                        start = NesPosition(line = 10u, character = 0u),
                        end = NesPosition(line = 10u, character = 5u)
                    ),
                    newText = "hello"
                )
            ),
            cursorPosition = NesPosition(line = 10u, character = 5u)
        )

        val json = ACPJson.encodeToString(NesSuggestion.serializer(), edit)
        val decoded = ACPJson.decodeFromString(NesSuggestion.serializer(), json)
        assertEquals(edit, decoded)
    }

    @Test
    fun `NesSuggestion Jump round-trip`() {
        val jump = NesSuggestion.Jump(
            id = "suggestion-2",
            uri = "file:///test.kt",
            position = NesPosition(line = 20u, character = 10u)
        )

        val json = ACPJson.encodeToString(NesSuggestion.serializer(), jump)
        val decoded = ACPJson.decodeFromString(NesSuggestion.serializer(), json)
        assertEquals(jump, decoded)
    }

    @Test
    fun `NesSuggestion Rename round-trip`() {
        val rename = NesSuggestion.Rename(
            id = "suggestion-3",
            uri = "file:///test.kt",
            position = NesPosition(line = 5u, character = 4u),
            newName = "newMethodName"
        )

        val json = ACPJson.encodeToString(NesSuggestion.serializer(), rename)
        val decoded = ACPJson.decodeFromString(NesSuggestion.serializer(), json)
        assertEquals(rename, decoded)
    }

    @Test
    fun `NesSuggestion SearchAndReplace round-trip`() {
        val sar = NesSuggestion.SearchAndReplace(
            id = "suggestion-4",
            uri = "file:///test.kt",
            search = "oldName",
            replace = "newName",
            isRegex = true
        )

        val json = ACPJson.encodeToString(NesSuggestion.serializer(), sar)
        val decoded = ACPJson.decodeFromString(NesSuggestion.serializer(), json)
        assertEquals(sar, decoded)
    }

    @Test
    fun `NesSuggestion Edit deserialization from JSON`() {
        val json = """
            {
                "kind": "edit",
                "id": "s1",
                "uri": "file:///a.kt",
                "edits": [
                    {
                        "range": {
                            "start": {"line": 1, "character": 0},
                            "end": {"line": 1, "character": 3}
                        },
                        "newText": "val"
                    }
                ]
            }
        """.trimIndent()

        val suggestion = ACPJson.decodeFromString(NesSuggestion.serializer(), json)
        assertTrue(suggestion is NesSuggestion.Edit)
        val edit = suggestion as NesSuggestion.Edit
        assertEquals("s1", edit.id)
        assertEquals(1, edit.edits.size)
        assertEquals("val", edit.edits[0].newText)
    }

    @Test
    fun `SuggestNesResponse round-trip with mixed suggestion types`() {
        val response = SuggestNesResponse(
            suggestions = listOf(
                NesSuggestion.Edit(
                    id = "e1",
                    uri = "file:///a.kt",
                    edits = listOf(
                        NesTextEdit(
                            range = NesRange(
                                start = NesPosition(0u, 0u),
                                end = NesPosition(0u, 5u)
                            ),
                            newText = "class"
                        )
                    )
                ),
                NesSuggestion.Jump(
                    id = "j1",
                    uri = "file:///b.kt",
                    position = NesPosition(10u, 0u)
                )
            )
        )

        val json = ACPJson.encodeToString(SuggestNesResponse.serializer(), response)
        val decoded = ACPJson.decodeFromString(SuggestNesResponse.serializer(), json)
        assertEquals(response, decoded)
        assertEquals(2, decoded.suggestions.size)
        assertTrue(decoded.suggestions[0] is NesSuggestion.Edit)
        assertTrue(decoded.suggestions[1] is NesSuggestion.Jump)
    }

    @Test
    fun `NesCapabilities round-trip`() {
        val capabilities = NesCapabilities(
            events = NesEventCapabilities(
                document = NesDocumentEventCapabilities(
                    didOpen = NesDocumentDidOpenCapabilities(),
                    didChange = NesDocumentDidChangeCapabilities(syncKind = TextDocumentSyncKind.INCREMENTAL),
                    didClose = NesDocumentDidCloseCapabilities(),
                    didSave = NesDocumentDidSaveCapabilities(),
                    didFocus = NesDocumentDidFocusCapabilities()
                )
            ),
            context = NesContextCapabilities(
                recentFiles = NesRecentFilesCapabilities(maxCount = 10),
                relatedSnippets = NesRelatedSnippetsCapabilities(),
                editHistory = NesEditHistoryCapabilities(maxCount = 50)
            )
        )

        val json = ACPJson.encodeToString(NesCapabilities.serializer(), capabilities)
        val decoded = ACPJson.decodeFromString(NesCapabilities.serializer(), json)
        assertEquals(capabilities, decoded)
    }

    @Test
    fun `StartNesRequest round-trip`() {
        val request = StartNesRequest(
            workspaceUri = "file:///workspace",
            workspaceFolders = listOf(
                WorkspaceFolder(uri = "file:///workspace/src", name = "src")
            ),
            repository = NesRepository(
                name = "my-repo",
                owner = "my-org",
                remoteUrl = "https://github.com/my-org/my-repo"
            )
        )

        val json = ACPJson.encodeToString(StartNesRequest.serializer(), request)
        val decoded = ACPJson.decodeFromString(StartNesRequest.serializer(), json)
        assertEquals(request, decoded)
    }

    @Test
    fun `NesTriggerKind serialization`() {
        val json = ACPJson.encodeToString(NesTriggerKind.serializer(), NesTriggerKind.AUTOMATIC)
        assertEquals("\"automatic\"", json)
        val decoded = ACPJson.decodeFromString(NesTriggerKind.serializer(), json)
        assertEquals(NesTriggerKind.AUTOMATIC, decoded)
    }

    @Test
    fun `NesRejectReason serialization`() {
        val json = ACPJson.encodeToString(NesRejectReason.serializer(), NesRejectReason.REPLACED)
        assertEquals("\"replaced\"", json)
    }

    @Test
    fun `PositionEncodingKind serialization`() {
        val json = ACPJson.encodeToString(PositionEncodingKind.serializer(), PositionEncodingKind.UTF_16)
        assertEquals("\"utf-16\"", json)
    }

    @Test
    fun `AgentCapabilities with NES round-trip`() {
        val capabilities = AgentCapabilities(
            nes = NesCapabilities(
                events = NesEventCapabilities(
                    document = NesDocumentEventCapabilities(
                        didOpen = NesDocumentDidOpenCapabilities()
                    )
                )
            ),
            positionEncoding = PositionEncodingKind.UTF_16
        )

        val json = ACPJson.encodeToString(AgentCapabilities.serializer(), capabilities)
        val decoded = ACPJson.decodeFromString(AgentCapabilities.serializer(), json)
        assertEquals(capabilities, decoded)
    }

    @Test
    fun `ClientCapabilities with NES round-trip`() {
        val capabilities = ClientCapabilities(
            nes = ClientNesCapabilities(
                jump = NesJumpCapabilities(),
                rename = NesRenameCapabilities(),
                searchAndReplace = NesSearchAndReplaceCapabilities()
            ),
            positionEncodings = listOf(PositionEncodingKind.UTF_16, PositionEncodingKind.UTF_8)
        )

        val json = ACPJson.encodeToString(ClientCapabilities.serializer(), capabilities)
        val decoded = ACPJson.decodeFromString(ClientCapabilities.serializer(), json)
        assertEquals(capabilities, decoded)
    }

    @Test
    fun `TextDocumentContentChangeEvent with range`() {
        val event = TextDocumentContentChangeEvent(
            range = NesRange(
                start = NesPosition(0u, 0u),
                end = NesPosition(0u, 5u)
            ),
            text = "hello"
        )

        val json = ACPJson.encodeToString(TextDocumentContentChangeEvent.serializer(), event)
        val decoded = ACPJson.decodeFromString(TextDocumentContentChangeEvent.serializer(), json)
        assertEquals(event, decoded)
    }

    @Test
    fun `TextDocumentContentChangeEvent full content (no range)`() {
        val event = TextDocumentContentChangeEvent(
            range = null,
            text = "full file content"
        )

        val json = ACPJson.encodeToString(TextDocumentContentChangeEvent.serializer(), event)
        val decoded = ACPJson.decodeFromString(TextDocumentContentChangeEvent.serializer(), json)
        assertEquals(event, decoded)
    }
}
