@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmInline

/**
 * Unique identifier for an agent-owned terminal within a session.
 *
 * The agent generates the ID. It is unique within a session, stable for the terminal's
 * lifetime, and MUST NOT be reused for another terminal in that session.
 *
 * Terminal IDs and [com.agentclientprotocol.model.ToolCallId]s are separate domains. An
 * agent may happen to use the same string for both, but clients MUST NOT rely on that.
 */
@UnstableApi
@JvmInline
@Serializable
public value class TerminalId(public val value: String) {
    override fun toString(): String = value
}

/**
 * An upsert for the stored state of an agent-owned terminal.
 *
 * Only [terminalId] is required. Other fields have patch semantics via [MaybeUndefined]:
 * an omitted field leaves the stored value unchanged, `null` clears it, and a concrete
 * value replaces it. On a first-seen terminal, omitted fields start unknown or empty.
 *
 * A concrete [output] is a replacement snapshot rather than an append, which is what makes
 * session replay possible without resending every historical [TerminalOutputChunk].
 *
 * Deserialization degrades gracefully like the Rust schema: a malformed optional field
 * decodes as [MaybeUndefined.Undefined] instead of failing.
 *
 * See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/v2/prompt-turn#3-agent-reports-output)
 *
 * @property command the command being run
 * @property cwd the working directory of the command; MUST be an absolute path on the agent's system
 */
@UnstableApi
@Serializable(with = TerminalUpdateSerializer::class)
public data class TerminalUpdate(
    val terminalId: TerminalId,
    val command: MaybeUndefined<String> = MaybeUndefined.Undefined,
    val cwd: MaybeUndefined<String> = MaybeUndefined.Undefined,
    val output: MaybeUndefined<TerminalOutput> = MaybeUndefined.Undefined,
    val exitStatus: MaybeUndefined<TerminalExitStatus> = MaybeUndefined.Undefined,
    val _meta: MaybeUndefined<JsonElement> = MaybeUndefined.Undefined,
) {
    /**
     * Applies a later terminal patch to this stored terminal state.
     *
     * Fields set to [MaybeUndefined.Null] are preserved as [MaybeUndefined.Null] so callers
     * can decide how to render an explicitly cleared value.
     *
     * A concrete [output] in [update] replaces this snapshot outright; it is never merged
     * with the bytes it supersedes.
     *
     * @throws IllegalArgumentException if [update] targets a different [terminalId]
     */
    public fun applyUpdate(update: TerminalUpdate): TerminalUpdate {
        require(terminalId == update.terminalId) {
            "Cannot apply update for terminal '${update.terminalId}' to terminal '$terminalId'"
        }
        return TerminalUpdate(
            terminalId = terminalId,
            command = update.command.orElse(command),
            cwd = update.cwd.orElse(cwd),
            output = update.output.orElse(output),
            exitStatus = update.exitStatus.orElse(exitStatus),
            _meta = update._meta.orElse(_meta),
        )
    }
}

@OptIn(UnstableApi::class)
internal object TerminalUpdateSerializer : KSerializer<TerminalUpdate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.TerminalUpdate")

    override fun serialize(encoder: Encoder, value: TerminalUpdate) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("terminalId", json.encodeToJsonElement(TerminalId.serializer(), value.terminalId))
                putMaybeUndefined(json, "command", value.command, String.serializer())
                putMaybeUndefined(json, "cwd", value.cwd, String.serializer())
                putMaybeUndefined(json, "output", value.output, TerminalOutput.serializer())
                putMaybeUndefined(json, "exitStatus", value.exitStatus, TerminalExitStatus.serializer())
                putMaybeUndefined(json, "_meta", value._meta, JsonElement.serializer())
            }
        )
    }

    override fun deserialize(decoder: Decoder): TerminalUpdate {
        val jsonDecoder = decoder as JsonDecoder
        val json = jsonDecoder.json
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val terminalId = jsonObject["terminalId"]
            ?: throw SerializationException("Missing 'terminalId' in ${descriptor.serialName}")
        return TerminalUpdate(
            terminalId = json.decodeFromJsonElement(TerminalId.serializer(), terminalId),
            command = jsonObject.decodeMaybeUndefined(json, "command", String.serializer()),
            cwd = jsonObject.decodeMaybeUndefined(json, "cwd", String.serializer()),
            output = jsonObject.decodeMaybeUndefined(json, "output", TerminalOutput.serializer()),
            exitStatus = jsonObject.decodeMaybeUndefined(json, "exitStatus", TerminalExitStatus.serializer()),
            _meta = jsonObject.decodeMaybeUndefined(json, "_meta", JsonElement.serializer()),
        )
    }
}

/**
 * An authoritative replacement snapshot of terminal output bytes.
 *
 * A snapshot is the complete byte sequence the agent wants the client to retain for the
 * terminal at that point. It replaces all previously stored bytes; clients MUST NOT merge
 * or splice bytes from the previous snapshot into it.
 *
 * @property data base64-encoded (RFC 4648) replacement terminal output bytes, kept encoded like the
 * other v2 base64 payloads because decoding belongs to the consumer that renders them
 * @property _meta metadata scoped to this replacement snapshot; omitted and `null` are equivalent
 */
@UnstableApi
@Serializable
public data class TerminalOutput(
    val data: String,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Exit information for an agent-owned terminal.
 *
 * The presence of this object marks the terminal as exited, even when neither an exit code
 * nor a signal is known.
 *
 * @property exitCode process exit code, when known; omitted and `null` are equivalent
 * @property signal signal that terminated the process, when known, under the conventional platform
 * signal name — POSIX examples include `SIGTERM`, `SIGKILL`, and `SIGINT`; omitted and `null` are
 * equivalent
 * @property _meta metadata scoped to this exit information; omitted and `null` are equivalent
 */
@UnstableApi
@Serializable
public data class TerminalExitStatus(
    val exitCode: UInt? = null,
    val signal: String? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * A chunk of bytes appended to an agent-owned terminal's output.
 *
 * Each chunk's [data] is independently base64-encoded: decode every chunk separately, then
 * append the resulting bytes in received order. Do not concatenate encoded strings before
 * decoding.
 *
 * Chunk boundaries may split UTF-8 code points and ANSI escape sequences, so text and
 * terminal parsers must retain state between chunks. A later [TerminalUpdate.output]
 * snapshot replaces all bytes accumulated so far, and subsequent chunks append to that
 * replacement.
 *
 * @property data independently base64-encoded (RFC 4648) terminal output bytes
 * @property _meta chunk-scoped metadata; it describes this chunk, not the terminal as a whole, and
 * omitted and `null` are equivalent
 */
@UnstableApi
@Serializable
public data class TerminalOutputChunk(
    val terminalId: TerminalId,
    val data: String,
    override val _meta: JsonElement? = null,
) : AcpWithMeta
