@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Categories of tools that can be invoked.
 *
 * Tool kinds help clients choose appropriate icons and optimize how they
 * display tool execution progress.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 *
 * See protocol docs: [Creating](https://agentclientprotocol.com/protocol/tool-calls#creating)
 */
@UnstableApi
@Serializable(with = ToolKindSerializer::class)
public sealed class ToolKind {
    /**
     * The wire-format string for this kind.
     */
    public abstract val value: String

    /**
     * Reading files or data.
     */
    public data object Read : ToolKind() {
        override val value: String = "read"
    }

    /**
     * Modifying files or content.
     */
    public data object Edit : ToolKind() {
        override val value: String = "edit"
    }

    /**
     * Removing files or data.
     */
    public data object Delete : ToolKind() {
        override val value: String = "delete"
    }

    /**
     * Moving or renaming files.
     */
    public data object Move : ToolKind() {
        override val value: String = "move"
    }

    /**
     * Searching for information.
     */
    public data object Search : ToolKind() {
        override val value: String = "search"
    }

    /**
     * Running commands or code.
     */
    public data object Execute : ToolKind() {
        override val value: String = "execute"
    }

    /**
     * Internal reasoning or planning.
     */
    public data object Think : ToolKind() {
        override val value: String = "think"
    }

    /**
     * Retrieving external data.
     */
    public data object Fetch : ToolKind() {
        override val value: String = "fetch"
    }

    /**
     * Switching the current session mode.
     */
    public data object SwitchMode : ToolKind() {
        override val value: String = "switch_mode"
    }

    /**
     * Other tool types (default).
     *
     * This is a *known* catch-all kind, distinct from [Unknown]: senders use it
     * deliberately when no other category fits.
     */
    public data object Other : ToolKind() {
        override val value: String = "other"
    }

    /**
     * Custom or future tool kind.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown kind SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : ToolKind()

    public companion object {
        /**
         * Creates an implementation-specific extension kind.
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
internal object ToolKindSerializer : OpenStringEnumSerializer<ToolKind>(
    serialName = "com.agentclientprotocol.model.v2.ToolKind",
    knownValues = listOf(
        ToolKind.Read,
        ToolKind.Edit,
        ToolKind.Delete,
        ToolKind.Move,
        ToolKind.Search,
        ToolKind.Execute,
        ToolKind.Think,
        ToolKind.Fetch,
        ToolKind.SwitchMode,
        ToolKind.Other,
    ),
    wireValue = ToolKind::value,
    unknown = ToolKind::Unknown,
)

/**
 * Execution status of a tool call.
 *
 * Tool calls progress through different statuses during their lifecycle.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 *
 * See protocol docs: [Status](https://agentclientprotocol.com/protocol/tool-calls#status)
 */
@UnstableApi
@Serializable(with = ToolCallStatusSerializer::class)
public sealed class ToolCallStatus {
    /**
     * The wire-format string for this status.
     */
    public abstract val value: String

    /**
     * The tool call hasn't started running yet because the input is either
     * streaming or awaiting approval.
     */
    public data object Pending : ToolCallStatus() {
        override val value: String = "pending"
    }

    /**
     * The tool call is currently running.
     */
    public data object InProgress : ToolCallStatus() {
        override val value: String = "in_progress"
    }

    /**
     * The tool call completed successfully.
     */
    public data object Completed : ToolCallStatus() {
        override val value: String = "completed"
    }

    /**
     * The tool call failed with an error.
     */
    public data object Failed : ToolCallStatus() {
        override val value: String = "failed"
    }

    /**
     * Custom or future tool call status.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown status SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : ToolCallStatus()

    public companion object {
        /**
         * Creates an implementation-specific extension status.
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
internal object ToolCallStatusSerializer : OpenStringEnumSerializer<ToolCallStatus>(
    serialName = "com.agentclientprotocol.model.v2.ToolCallStatus",
    knownValues = listOf(
        ToolCallStatus.Pending,
        ToolCallStatus.InProgress,
        ToolCallStatus.Completed,
        ToolCallStatus.Failed,
    ),
    wireValue = ToolCallStatus::value,
    unknown = ToolCallStatus::Unknown,
)

/**
 * Text patch format used by [ToolCallContent.Diff.patch].
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = DiffPatchFormatSerializer::class)
public sealed class DiffPatchFormat {
    /**
     * The wire-format string for this patch format.
     */
    public abstract val value: String

    /**
     * Git patch format.
     */
    public data object GitPatch : DiffPatchFormat() {
        override val value: String = "git_patch"
    }

    /**
     * Custom or future diff format.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown format SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : DiffPatchFormat()

    public companion object {
        /**
         * Creates an implementation-specific extension format.
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
internal object DiffPatchFormatSerializer : OpenStringEnumSerializer<DiffPatchFormat>(
    serialName = "com.agentclientprotocol.model.v2.DiffPatchFormat",
    knownValues = listOf(
        DiffPatchFormat.GitPatch,
    ),
    wireValue = DiffPatchFormat::value,
    unknown = DiffPatchFormat::Unknown,
)

/**
 * Kind of file content represented by a diff change.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = DiffFileTypeSerializer::class)
public sealed class DiffFileType {
    /**
     * The wire-format string for this file type.
     */
    public abstract val value: String

    /**
     * Text content.
     */
    public data object Text : DiffFileType() {
        override val value: String = "text"
    }

    /**
     * Binary or otherwise non-text content.
     */
    public data object Binary : DiffFileType() {
        override val value: String = "binary"
    }

    /**
     * Directory entry.
     */
    public data object Directory : DiffFileType() {
        override val value: String = "directory"
    }

    /**
     * Symbolic link.
     */
    public data object Symlink : DiffFileType() {
        override val value: String = "symlink"
    }

    /**
     * Custom or future file type.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown file type SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : DiffFileType()

    public companion object {
        /**
         * Creates an implementation-specific extension file type.
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
internal object DiffFileTypeSerializer : OpenStringEnumSerializer<DiffFileType>(
    serialName = "com.agentclientprotocol.model.v2.DiffFileType",
    knownValues = listOf(
        DiffFileType.Text,
        DiffFileType.Binary,
        DiffFileType.Directory,
        DiffFileType.Symlink,
    ),
    wireValue = DiffFileType::value,
    unknown = DiffFileType::Unknown,
)

/**
 * File operation for a [DiffChange].
 *
 * This is an open union discriminated by the flattened `operation` field: an
 * unrecognized operation deserializes to [Unknown] preserving its extra fields.
 */
@UnstableApi
public sealed class DiffChangeOperation {
    /**
     * A file was added.
     */
    public data class Add(val path: String) : DiffChangeOperation()

    /**
     * A file was deleted.
     */
    public data class Delete(val path: String) : DiffChangeOperation()

    /**
     * A file was modified in place.
     */
    public data class Modify(val path: String) : DiffChangeOperation()

    /**
     * A file was moved or renamed.
     */
    public data class Move(val oldPath: String, val path: String) : DiffChangeOperation()

    /**
     * A file was copied.
     */
    public data class Copy(val oldPath: String, val path: String) : DiffChangeOperation()

    /**
     * Custom or future file operation.
     *
     * Operation values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [fields] holds the operation-specific payload as received, excluding the
     * `operation`, `fileType`, `mimeType`, and `_meta` keys, which belong to the
     * enclosing [DiffChange].
     */
    public data class Unknown(val operation: String, val fields: JsonObject) : DiffChangeOperation()
}

/**
 * One file-level change described by a [ToolCallContent.Diff].
 *
 * Structured change metadata lets clients identify affected files and
 * operations without parsing the text patch. The [operation] payload is
 * flattened into this object on the wire.
 */
@UnstableApi
@Serializable(with = DiffChangeSerializer::class)
public data class DiffChange(
    val operation: DiffChangeOperation,
    val fileType: DiffFileType? = null,
    val mimeType: String? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

@OptIn(UnstableApi::class)
internal object DiffChangeSerializer : KSerializer<DiffChange> {
    private const val OPERATION = "operation"
    private const val FILE_TYPE = "fileType"
    private const val MIME_TYPE = "mimeType"
    private const val META = "_meta"

    private val changeKeys = setOf(OPERATION, FILE_TYPE, MIME_TYPE, META)

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.DiffChange")

    override fun serialize(encoder: Encoder, value: DiffChange) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = buildJsonObject {
            when (val operation = value.operation) {
                is DiffChangeOperation.Add -> {
                    put(OPERATION, JsonPrimitive("add"))
                    put("path", JsonPrimitive(operation.path))
                }
                is DiffChangeOperation.Delete -> {
                    put(OPERATION, JsonPrimitive("delete"))
                    put("path", JsonPrimitive(operation.path))
                }
                is DiffChangeOperation.Modify -> {
                    put(OPERATION, JsonPrimitive("modify"))
                    put("path", JsonPrimitive(operation.path))
                }
                is DiffChangeOperation.Move -> {
                    put(OPERATION, JsonPrimitive("move"))
                    put("oldPath", JsonPrimitive(operation.oldPath))
                    put("path", JsonPrimitive(operation.path))
                }
                is DiffChangeOperation.Copy -> {
                    put(OPERATION, JsonPrimitive("copy"))
                    put("oldPath", JsonPrimitive(operation.oldPath))
                    put("path", JsonPrimitive(operation.path))
                }
                is DiffChangeOperation.Unknown -> {
                    put(OPERATION, JsonPrimitive(operation.operation))
                    operation.fields.forEach { (key, element) ->
                        if (key !in changeKeys) put(key, element)
                    }
                }
            }
            value.fileType?.let { put(FILE_TYPE, JsonPrimitive(it.value)) }
            value.mimeType?.let { put(MIME_TYPE, JsonPrimitive(it)) }
            value._meta?.let { put(META, it) }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): DiffChange {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val operationValue = (jsonObject[OPERATION] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'operation' discriminator in DiffChange")
        val operation = when (operationValue) {
            "add" -> DiffChangeOperation.Add(requirePath(jsonObject, operationValue))
            "delete" -> DiffChangeOperation.Delete(requirePath(jsonObject, operationValue))
            "modify" -> DiffChangeOperation.Modify(requirePath(jsonObject, operationValue))
            "move" -> DiffChangeOperation.Move(
                oldPath = requireString(jsonObject, "oldPath", operationValue),
                path = requirePath(jsonObject, operationValue),
            )
            "copy" -> DiffChangeOperation.Copy(
                oldPath = requireString(jsonObject, "oldPath", operationValue),
                path = requirePath(jsonObject, operationValue),
            )
            else -> DiffChangeOperation.Unknown(
                operation = operationValue,
                fields = JsonObject(jsonObject.filterKeys { it !in changeKeys }),
            )
        }
        val fileType = (jsonObject[FILE_TYPE] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val mimeType = (jsonObject[MIME_TYPE] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return DiffChange(
            operation = operation,
            fileType = fileType?.let { value ->
                jsonDecoder.json.decodeFromJsonElement(DiffFileType.serializer(), JsonPrimitive(value))
            },
            mimeType = mimeType,
            _meta = jsonObject[META],
        )
    }

    private fun requirePath(jsonObject: JsonObject, operation: String): String =
        requireString(jsonObject, "path", operation)

    private fun requireString(jsonObject: JsonObject, key: String, operation: String): String =
        (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing '$key' in '$operation' DiffChange")
}

/**
 * Renderable patch text.
 */
@UnstableApi
@Serializable
public data class DiffPatch(
    val format: DiffPatchFormat = DiffPatchFormat.GitPatch,
    val diff: String,
)

/**
 * Content produced by a tool call.
 *
 * Tool calls can produce different types of content including
 * standard content blocks (text, images) or file diffs.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 *
 * No v1 conversions are provided for this union: v2 diffs (structured changes plus
 * standard patch text) and v1 diffs (`oldText`/`newText`) have no faithful mutual
 * representation, and v1's `terminal` variant was removed in v2. Convert the
 * [Content] payload with the [ContentBlock] conversions where needed.
 *
 * See protocol docs: [Content](https://agentclientprotocol.com/protocol/tool-calls#content)
 */
@UnstableApi
@Serializable(with = ToolCallContentSerializer::class)
public sealed class ToolCallContent {
    /**
     * Standard content block (text, images, resources).
     */
    @Serializable
    public data class Content(
        val content: ContentBlock,
        override val _meta: JsonElement? = null,
    ) : ToolCallContent(), AcpWithMeta

    /**
     * File modification shown as a diff.
     *
     * Unlike v1's single-file `oldText`/`newText`, a v2 diff carries structured
     * [changes] plus optional renderable [patch] text.
     */
    @Serializable
    public data class Diff(
        val changes: List<DiffChange>,
        val patch: DiffPatch? = null,
        override val _meta: JsonElement? = null,
    ) : ToolCallContent(), AcpWithMeta

    /**
     * Custom or future tool call content.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Receivers that do not understand this
     * content type SHOULD preserve it when storing, replaying, proxying, or forwarding
     * tool call output, and otherwise ignore it or display it generically.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : ToolCallContent()
}

@OptIn(UnstableApi::class)
internal object ToolCallContentSerializer : OpenTaggedUnionSerializer<ToolCallContent>(
    serialName = "com.agentclientprotocol.model.v2.ToolCallContent",
    discriminatorKey = "type",
    known = mapOf(
        "content" to ToolCallContent.Content.serializer(),
        "diff" to ToolCallContent.Diff.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is ToolCallContent.Content -> "content"
            is ToolCallContent.Diff -> "diff"
            is ToolCallContent.Unknown -> value.type
        }
    },
    unknown = ToolCallContent::Unknown,
    rawJson = { (it as? ToolCallContent.Unknown)?.rawJson },
)

/**
 * An upsert for a tool call that the language model has requested.
 *
 * Replaces v1's separate `tool_call` / `tool_call_update` variants: creation and update use
 * the same `tool_call_update` session update, keyed by [toolCallId].
 *
 * Only [toolCallId] is required. Other fields have patch semantics via [MaybeUndefined]:
 * an omitted field leaves the existing tool call value unchanged, `null` clears or unsets
 * the value, and a concrete value replaces the previous value. For collection fields,
 * concrete arrays replace the previous collection, and both `null` and `[]` clear it. When
 * a client receives a tool call ID it has not seen before, omitted fields use client
 * defaults.
 *
 * Deserialization degrades gracefully like the Rust schema: a malformed optional field
 * decodes as [MaybeUndefined.Undefined] instead of failing, and malformed [content] or
 * [locations] items are skipped.
 *
 * See protocol docs: [Tool Calls](https://agentclientprotocol.com/protocol/tool-calls)
 */
@UnstableApi
@Serializable(with = ToolCallUpdateSerializer::class)
public data class ToolCallUpdate(
    val toolCallId: ToolCallId,
    val title: MaybeUndefined<String> = MaybeUndefined.Undefined,
    val kind: MaybeUndefined<ToolKind> = MaybeUndefined.Undefined,
    val status: MaybeUndefined<ToolCallStatus> = MaybeUndefined.Undefined,
    val content: MaybeUndefined<List<ToolCallContent>> = MaybeUndefined.Undefined,
    val locations: MaybeUndefined<List<ToolCallLocation>> = MaybeUndefined.Undefined,
    val rawInput: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
    val rawOutput: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
    val _meta: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
) {
    /**
     * Applies a later tool-call patch to this stored tool-call state.
     *
     * Fields set to [MaybeUndefined.Null] are preserved as [MaybeUndefined.Null] so callers
     * can decide how to render an explicitly cleared value.
     *
     * @throws IllegalArgumentException if [update] targets a different [toolCallId]
     */
    public fun applyUpdate(update: ToolCallUpdate): ToolCallUpdate {
        require(toolCallId == update.toolCallId) {
            "Cannot apply update for tool call '${update.toolCallId}' to tool call '$toolCallId'"
        }
        return ToolCallUpdate(
            toolCallId = toolCallId,
            title = update.title.orPrevious(title),
            kind = update.kind.orPrevious(kind),
            status = update.status.orPrevious(status),
            content = update.content.orPrevious(content),
            locations = update.locations.orPrevious(locations),
            rawInput = update.rawInput.orPrevious(rawInput),
            rawOutput = update.rawOutput.orPrevious(rawOutput),
            _meta = update._meta.orPrevious(_meta),
        )
    }
}

@OptIn(UnstableApi::class)
internal object ToolCallUpdateSerializer : KSerializer<ToolCallUpdate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.ToolCallUpdate")

    override fun serialize(encoder: Encoder, value: ToolCallUpdate) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("toolCallId", json.encodeToJsonElement(ToolCallId.serializer(), value.toolCallId))
                putMaybeUndefined(json, "title", value.title, String.serializer())
                putMaybeUndefined(json, "kind", value.kind, ToolKind.serializer())
                putMaybeUndefined(json, "status", value.status, ToolCallStatus.serializer())
                putMaybeUndefined(json, "content", value.content, ListSerializer(ToolCallContent.serializer()))
                putMaybeUndefined(json, "locations", value.locations, ListSerializer(ToolCallLocation.serializer()))
                putMaybeUndefined(json, "rawInput", value.rawInput, JsonElement.serializer())
                putMaybeUndefined(json, "rawOutput", value.rawOutput, JsonElement.serializer())
                putMaybeUndefined(json, "_meta", value._meta, JsonElement.serializer())
            }
        )
    }

    override fun deserialize(decoder: Decoder): ToolCallUpdate {
        val jsonDecoder = decoder as JsonDecoder
        val json = jsonDecoder.json
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val toolCallId = jsonObject["toolCallId"]
            ?: throw SerializationException("Missing 'toolCallId' in ${descriptor.serialName}")
        return ToolCallUpdate(
            toolCallId = json.decodeFromJsonElement(ToolCallId.serializer(), toolCallId),
            title = jsonObject.decodeMaybeUndefined(json, "title", String.serializer()),
            kind = jsonObject.decodeMaybeUndefined(json, "kind", ToolKind.serializer()),
            status = jsonObject.decodeMaybeUndefined(json, "status", ToolCallStatus.serializer()),
            content = jsonObject.decodeMaybeUndefinedList(json, "content", ToolCallContent.serializer()),
            locations = jsonObject.decodeMaybeUndefinedList(json, "locations", ToolCallLocation.serializer()),
            rawInput = jsonObject.decodeMaybeUndefined(json, "rawInput", JsonElement.serializer()),
            rawOutput = jsonObject.decodeMaybeUndefined(json, "rawOutput", JsonElement.serializer()),
            _meta = jsonObject.decodeMaybeUndefined(json, "_meta", JsonElement.serializer()),
        )
    }
}

/**
 * A streamed item of tool-call content.
 *
 * A chunk appends one [ToolCallContent] item to the content already streamed for
 * [toolCallId]. Agents that need to replace the whole collection instead send
 * [ToolCallUpdate.content].
 *
 * Has no v1 counterpart: v1 reports tool call output by resending the full content
 * collection on every `tool_call_update`.
 *
 * See protocol docs: [Tool Calls](https://agentclientprotocol.com/protocol/tool-calls)
 */
@UnstableApi
@Serializable
public data class ToolCallContentChunk(
    val toolCallId: ToolCallId,
    val content: ToolCallContent,
    /**
     * Chunk-scoped metadata; it describes this chunk, not the tool call as a whole.
     */
    override val _meta: JsonElement? = null,
) : AcpWithMeta
