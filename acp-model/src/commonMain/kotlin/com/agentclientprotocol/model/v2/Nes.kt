@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.NesPosition
import com.agentclientprotocol.model.NesTextEdit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What triggered the suggestion request.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = NesTriggerKindSerializer::class)
public sealed class NesTriggerKind {
    /**
     * The wire-format string for this trigger kind.
     */
    public abstract val value: String

    /**
     * Triggered by user typing or cursor movement.
     */
    public data object Automatic : NesTriggerKind() {
        override val value: String = "automatic"
    }

    /**
     * Triggered by a diagnostic appearing at or near the cursor.
     */
    public data object Diagnostic : NesTriggerKind() {
        override val value: String = "diagnostic"
    }

    /**
     * Triggered by an explicit user action (keyboard shortcut).
     */
    public data object Manual : NesTriggerKind() {
        override val value: String = "manual"
    }

    /**
     * Custom or future suggestion trigger kind.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown trigger kind SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : NesTriggerKind()

    public companion object {
        /**
         * Creates an implementation-specific extension trigger kind.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object NesTriggerKindSerializer : OpenStringEnumSerializer<NesTriggerKind>(
    serialName = "com.agentclientprotocol.model.v2.NesTriggerKind",
    knownValues = listOf(
        NesTriggerKind.Automatic,
        NesTriggerKind.Diagnostic,
        NesTriggerKind.Manual,
    ),
    wireValue = NesTriggerKind::value,
    unknown = NesTriggerKind::Unknown,
)

/**
 * The reason a suggestion was rejected.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = NesRejectReasonSerializer::class)
public sealed class NesRejectReason {
    /**
     * The wire-format string for this reject reason.
     */
    public abstract val value: String

    /**
     * The user explicitly dismissed the suggestion.
     */
    public data object Rejected : NesRejectReason() {
        override val value: String = "rejected"
    }

    /**
     * The suggestion was shown but the user continued editing without interacting.
     */
    public data object Ignored : NesRejectReason() {
        override val value: String = "ignored"
    }

    /**
     * The suggestion was superseded by a newer suggestion.
     */
    public data object Replaced : NesRejectReason() {
        override val value: String = "replaced"
    }

    /**
     * The request was cancelled before the agent returned a response.
     */
    public data object Cancelled : NesRejectReason() {
        override val value: String = "cancelled"
    }

    /**
     * Custom or future rejection reason.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown reject reason SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : NesRejectReason()

    public companion object {
        /**
         * Creates an implementation-specific extension reject reason.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object NesRejectReasonSerializer : OpenStringEnumSerializer<NesRejectReason>(
    serialName = "com.agentclientprotocol.model.v2.NesRejectReason",
    knownValues = listOf(
        NesRejectReason.Rejected,
        NesRejectReason.Ignored,
        NesRejectReason.Replaced,
        NesRejectReason.Cancelled,
    ),
    wireValue = NesRejectReason::value,
    unknown = NesRejectReason::Unknown,
)

/**
 * Severity of a diagnostic.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = NesDiagnosticSeveritySerializer::class)
public sealed class NesDiagnosticSeverity {
    /**
     * The wire-format string for this severity.
     */
    public abstract val value: String

    /**
     * An error.
     */
    public data object Error : NesDiagnosticSeverity() {
        override val value: String = "error"
    }

    /**
     * A warning.
     */
    public data object Warning : NesDiagnosticSeverity() {
        override val value: String = "warning"
    }

    /**
     * An informational message.
     */
    public data object Information : NesDiagnosticSeverity() {
        override val value: String = "information"
    }

    /**
     * A hint.
     */
    public data object Hint : NesDiagnosticSeverity() {
        override val value: String = "hint"
    }

    /**
     * Custom or future diagnostic severity.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown severity SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : NesDiagnosticSeverity()

    public companion object {
        /**
         * Creates an implementation-specific extension severity.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object NesDiagnosticSeveritySerializer : OpenStringEnumSerializer<NesDiagnosticSeverity>(
    serialName = "com.agentclientprotocol.model.v2.NesDiagnosticSeverity",
    knownValues = listOf(
        NesDiagnosticSeverity.Error,
        NesDiagnosticSeverity.Warning,
        NesDiagnosticSeverity.Information,
        NesDiagnosticSeverity.Hint,
    ),
    wireValue = NesDiagnosticSeverity::value,
    unknown = NesDiagnosticSeverity::Unknown,
)

/**
 * A suggestion produced by next-edit-suggestion (NES) processing.
 *
 * Unlike v1, every variant identifies itself with `suggestionId` on the wire
 * (renamed from v1's `id`).
 *
 * This is an open tagged union: an unrecognized `kind` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = NesSuggestionSerializer::class)
public sealed class NesSuggestion {
    /**
     * Unique identifier for accept/reject tracking.
     */
    public abstract val suggestionId: String

    /**
     * A text edit suggestion.
     */
    @Serializable
    public data class Edit(
        override val suggestionId: String,
        val uri: String,
        val edits: List<NesTextEdit>,
        val cursorPosition: NesPosition? = null,
    ) : NesSuggestion()

    /**
     * A jump-to-location suggestion.
     */
    @Serializable
    public data class Jump(
        override val suggestionId: String,
        val uri: String,
        val position: NesPosition,
    ) : NesSuggestion()

    /**
     * A rename symbol suggestion.
     */
    @Serializable
    public data class Rename(
        override val suggestionId: String,
        val uri: String,
        val position: NesPosition,
        val newName: String,
    ) : NesSuggestion()

    /**
     * A search-and-replace suggestion.
     */
    @Serializable
    public data class SearchAndReplace(
        override val suggestionId: String,
        val uri: String,
        val search: String,
        val replace: String,
        val isRegex: Boolean? = null,
    ) : NesSuggestion()

    /**
     * Custom or future NES suggestion.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Even unknown suggestions must carry `suggestionId` — decoding fails otherwise.
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Receivers that do not understand this
     * suggestion kind SHOULD preserve it when storing, replaying, proxying, or forwarding
     * suggestions, and otherwise ignore it or display it generically.
     */
    public data class Unknown(
        val kind: String,
        override val suggestionId: String,
        val rawJson: JsonObject,
    ) : NesSuggestion()
}

@OptIn(UnstableApi::class)
internal object NesSuggestionSerializer : OpenTaggedUnionSerializer<NesSuggestion>(
    serialName = "com.agentclientprotocol.model.v2.NesSuggestion",
    discriminatorKey = "kind",
    known = mapOf(
        "edit" to NesSuggestion.Edit.serializer(),
        "jump" to NesSuggestion.Jump.serializer(),
        "rename" to NesSuggestion.Rename.serializer(),
        "searchAndReplace" to NesSuggestion.SearchAndReplace.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is NesSuggestion.Edit -> "edit"
            is NesSuggestion.Jump -> "jump"
            is NesSuggestion.Rename -> "rename"
            is NesSuggestion.SearchAndReplace -> "searchAndReplace"
            is NesSuggestion.Unknown -> value.kind
        }
    },
    unknown = { kind, rawJson ->
        val suggestionId = (rawJson["suggestionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'suggestionId' in unknown NesSuggestion")
        NesSuggestion.Unknown(kind = kind, suggestionId = suggestionId, rawJson = rawJson)
    },
    rawJson = { (it as? NesSuggestion.Unknown)?.rawJson },
)
