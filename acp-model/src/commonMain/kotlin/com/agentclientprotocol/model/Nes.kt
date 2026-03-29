@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

// === Enums ===

@UnstableApi
@Serializable
public enum class PositionEncodingKind {
    @SerialName("utf-16") UTF_16,
    @SerialName("utf-32") UTF_32,
    @SerialName("utf-8") UTF_8
}

@UnstableApi
@Serializable
public enum class TextDocumentSyncKind {
    @SerialName("full") FULL,
    @SerialName("incremental") INCREMENTAL
}

@UnstableApi
@Serializable
public enum class NesTriggerKind {
    @SerialName("automatic") AUTOMATIC,
    @SerialName("diagnostic") DIAGNOSTIC,
    @SerialName("manual") MANUAL
}

@UnstableApi
@Serializable
public enum class NesRejectReason {
    @SerialName("rejected") REJECTED,
    @SerialName("ignored") IGNORED,
    @SerialName("replaced") REPLACED,
    @SerialName("cancelled") CANCELLED
}

@UnstableApi
@Serializable
public enum class NesDiagnosticSeverity {
    @SerialName("error") ERROR,
    @SerialName("warning") WARNING,
    @SerialName("information") INFORMATION,
    @SerialName("hint") HINT
}

// === Position Primitives ===

@UnstableApi
@Serializable
public data class NesPosition(
    val line: UInt,
    val character: UInt
)

@UnstableApi
@Serializable
public data class NesRange(
    val start: NesPosition,
    val end: NesPosition
)

// === Agent NES Capabilities ===

@UnstableApi
@Serializable
public data class NesCapabilities(
    val events: NesEventCapabilities? = null,
    val context: NesContextCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesEventCapabilities(
    val document: NesDocumentEventCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentEventCapabilities(
    val didOpen: NesDocumentDidOpenCapabilities? = null,
    val didChange: NesDocumentDidChangeCapabilities? = null,
    val didClose: NesDocumentDidCloseCapabilities? = null,
    val didSave: NesDocumentDidSaveCapabilities? = null,
    val didFocus: NesDocumentDidFocusCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentDidOpenCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentDidChangeCapabilities(
    val syncKind: TextDocumentSyncKind,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentDidCloseCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentDidSaveCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDocumentDidFocusCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesContextCapabilities(
    val recentFiles: NesRecentFilesCapabilities? = null,
    val relatedSnippets: NesRelatedSnippetsCapabilities? = null,
    val editHistory: NesEditHistoryCapabilities? = null,
    val userActions: NesUserActionsCapabilities? = null,
    val openFiles: NesOpenFilesCapabilities? = null,
    val diagnostics: NesDiagnosticsCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesRecentFilesCapabilities(
    val maxCount: Int? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesRelatedSnippetsCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesEditHistoryCapabilities(
    val maxCount: Int? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesUserActionsCapabilities(
    val maxCount: Int? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesOpenFilesCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesDiagnosticsCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

// === Client NES Capabilities ===

@UnstableApi
@Serializable
public data class ClientNesCapabilities(
    val jump: NesJumpCapabilities? = null,
    val rename: NesRenameCapabilities? = null,
    val searchAndReplace: NesSearchAndReplaceCapabilities? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesJumpCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesRenameCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

@UnstableApi
@Serializable
public data class NesSearchAndReplaceCapabilities(
    override val _meta: JsonElement? = null
) : AcpWithMeta

// === Session Lifecycle ===

@UnstableApi
@Serializable
public data class WorkspaceFolder(
    val uri: String,
    val name: String
)

@UnstableApi
@Serializable
public data class NesRepository(
    val name: String,
    val owner: String,
    val remoteUrl: String
)

@UnstableApi
@Serializable
public data class StartNesRequest(
    val workspaceUri: String? = null,
    val workspaceFolders: List<WorkspaceFolder>? = null,
    val repository: NesRepository? = null,
    override val _meta: JsonElement? = null
) : AcpRequest

@UnstableApi
@Serializable
public data class StartNesResponse(
    val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpResponse

@UnstableApi
@Serializable
public data class CloseNesRequest(
    override val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

@UnstableApi
@Serializable
public data class CloseNesResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

// === Suggest ===

@UnstableApi
@Serializable
public data class SuggestNesRequest(
    override val sessionId: SessionId,
    val uri: String,
    val version: Long,
    val position: NesPosition,
    val selection: NesRange? = null,
    val triggerKind: NesTriggerKind,
    val context: NesSuggestContext? = null,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

@UnstableApi
@Serializable
public data class SuggestNesResponse(
    val suggestions: List<NesSuggestion>,
    override val _meta: JsonElement? = null
) : AcpResponse

@UnstableApi
@Serializable
public data class NesSuggestContext(
    val recentFiles: List<NesRecentFile>? = null,
    val relatedSnippets: List<NesRelatedSnippet>? = null,
    val editHistory: List<NesEditHistoryEntry>? = null,
    val userActions: List<NesUserAction>? = null,
    val openFiles: List<NesOpenFile>? = null,
    val diagnostics: List<NesDiagnostic>? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

// === Context Detail Types ===

@UnstableApi
@Serializable
public data class NesRecentFile(
    val uri: String,
    val languageId: String,
    val text: String
)

@UnstableApi
@Serializable
public data class NesRelatedSnippet(
    val uri: String,
    val excerpts: List<NesExcerpt>
)

@UnstableApi
@Serializable
public data class NesExcerpt(
    val startLine: UInt,
    val endLine: UInt,
    val text: String
)

@UnstableApi
@Serializable
public data class NesEditHistoryEntry(
    val uri: String,
    val diff: String
)

@UnstableApi
@Serializable
public data class NesUserAction(
    val action: String,
    val uri: String,
    val position: NesPosition,
    val timestampMs: ULong
)

@UnstableApi
@Serializable
public data class NesOpenFile(
    val uri: String,
    val languageId: String,
    val visibleRange: NesRange? = null,
    val lastFocusedMs: ULong? = null
)

@UnstableApi
@Serializable
public data class NesDiagnostic(
    val uri: String,
    val range: NesRange,
    val severity: NesDiagnosticSeverity,
    val message: String
)

// === Suggestion Types ===

@UnstableApi
@Serializable
@JsonClassDiscriminator("kind")
public sealed class NesSuggestion {
    public abstract val id: String

    @Serializable
    @SerialName("edit")
    public data class Edit(
        override val id: String,
        val uri: String,
        val edits: List<NesTextEdit>,
        val cursorPosition: NesPosition? = null
    ) : NesSuggestion()

    @Serializable
    @SerialName("jump")
    public data class Jump(
        override val id: String,
        val uri: String,
        val position: NesPosition
    ) : NesSuggestion()

    @Serializable
    @SerialName("rename")
    public data class Rename(
        override val id: String,
        val uri: String,
        val position: NesPosition,
        val newName: String
    ) : NesSuggestion()

    @Serializable
    @SerialName("searchAndReplace")
    public data class SearchAndReplace(
        override val id: String,
        val uri: String,
        val search: String,
        val replace: String,
        @EncodeDefault val isRegex: Boolean = false
    ) : NesSuggestion()
}

@UnstableApi
@Serializable
public data class NesTextEdit(
    val range: NesRange,
    val newText: String
)

// === Accept/Reject Notifications ===

@UnstableApi
@Serializable
public data class AcceptNesNotification(
    override val sessionId: SessionId,
    val id: String,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

@UnstableApi
@Serializable
public data class RejectNesNotification(
    override val sessionId: SessionId,
    val id: String,
    val reason: NesRejectReason? = null,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

// === Document Event Notifications ===

@UnstableApi
@Serializable
public data class DidOpenDocumentNotification(
    override val sessionId: SessionId,
    val uri: String,
    val languageId: String,
    val version: Long,
    val text: String,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

@UnstableApi
@Serializable
public data class DidChangeDocumentNotification(
    override val sessionId: SessionId,
    val uri: String,
    val version: Long,
    val contentChanges: List<TextDocumentContentChangeEvent>,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

@UnstableApi
@Serializable
public data class TextDocumentContentChangeEvent(
    val range: NesRange? = null,
    val text: String
)

@UnstableApi
@Serializable
public data class DidCloseDocumentNotification(
    override val sessionId: SessionId,
    val uri: String,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

@UnstableApi
@Serializable
public data class DidSaveDocumentNotification(
    override val sessionId: SessionId,
    val uri: String,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

@UnstableApi
@Serializable
public data class DidFocusDocumentNotification(
    override val sessionId: SessionId,
    val uri: String,
    val version: Long,
    val position: NesPosition,
    val visibleRange: NesRange,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId
