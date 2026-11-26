@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.JsonRpcError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The work state of a subagent session, as reported in `subagent_update.state`.
 *
 * This is an open union discriminated by `state`. Each known state keeps only its own fields, and extra
 * fields beside it are dropped. [Unknown] is the real `unknown` state, reported when the agent lost
 * observability of the session's work. [Other] holds a state this SDK does not recognize. This differs
 * from the v2 `StateUpdate`, whose `Unknown` means unrecognized.
 *
 * Decoding fails for a value that is not a JSON object and for a missing or non-string `state`, and
 * `subagent_update` treats such a state as absent. A known state always decodes as that state: a malformed field
 * of it decodes as `null`.
 */
@Serializable(with = StateUpdateSerializer::class)
public sealed class StateUpdate {
    /**
     * The subagent is doing foreground work.
     *
     * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
     */
    public data class Running(
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * The subagent is not doing foreground work.
     *
     * @property stopReason why the work stopped; omitted, `null` and non-string values decode as `null`, meaning no
     * stop reason is reported
     * @property usage token usage of the completed work; omitted, `null` and malformed values decode as `null`;
     * unstable: not part of the stable v1 schema
     * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
     */
    @OptIn(UnstableApi::class)
    public data class Idle(
        val stopReason: IdleStopReason? = null,
        @property:UnstableApi
        val usage: Usage? = null,
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * The subagent is waiting on user action before it can continue.
     *
     * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
     */
    public data class RequiresAction(
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * The agent cannot observe the subagent's work, so its state is not known.
     *
     * @property _meta extension metadata; a value that is not a JSON object decodes as `null`
     */
    public data class Unknown(
        override val _meta: JsonElement? = null,
    ) : StateUpdate(), AcpWithMeta

    /**
     * A state this SDK does not recognize.
     *
     * Clients should preserve it when storing, replaying or forwarding session history. It is encoded back
     * as received. Decoding never produces it for a recognized state, so [state] should not be one of
     * `running`, `idle`, `requires_action` or `unknown`.
     *
     * @property state the unrecognized discriminator
     * @property fields every other field as received, including `_meta`
     */
    public data class Other(
        val state: String,
        val fields: JsonObject = JsonObject(emptyMap()),
    ) : StateUpdate()
}

/**
 * Why a subagent went idle, as reported in [StateUpdate.Idle.stopReason].
 *
 * This is an open union discriminated by `stopReason`. It has no wire form of its own: its fields sit
 * beside `state`, `usage` and `_meta` in the idle snapshot. The first five values match [StopReason]. A known
 * stop reason keeps only its own fields, and extra fields beside it are dropped.
 */
public sealed class IdleStopReason {
    /**
     * The work finished normally.
     */
    public data object EndTurn : IdleStopReason()

    /**
     * The work hit the token limit.
     */
    public data object MaxTokens : IdleStopReason()

    /**
     * The work hit the limit of model requests per turn.
     */
    public data object MaxTurnRequests : IdleStopReason()

    /**
     * The model refused to continue.
     */
    public data object Refusal : IdleStopReason()

    /**
     * The work was cancelled.
     */
    public data object Cancelled : IdleStopReason()

    /**
     * The work failed.
     *
     * @property error the JSON-RPC error describing the failure, which agents should include; omitted, `null` and
     * malformed values decode as `null`
     */
    public data class Error(
        val error: JsonRpcError? = null,
    ) : IdleStopReason()

    /**
     * A stop reason this SDK does not recognize.
     *
     * Clients should preserve it when storing, replaying or forwarding session history. It is encoded back
     * as received. Decoding never produces it for a recognized stop reason, so [stopReason] should not be one
     * of the known values.
     *
     * @property stopReason the unrecognized discriminator
     * @property fields every other field as received, except `state`, `stopReason`, `usage` and `_meta`, which
     * belong to the idle snapshot
     */
    public data class Other(
        val stopReason: String,
        val fields: JsonObject = JsonObject(emptyMap()),
    ) : IdleStopReason()
}

private const val STATE_KEY = "state"
private const val STOP_REASON_KEY = "stopReason"
private const val USAGE_KEY = "usage"
private const val META_KEY = "_meta"
private const val ERROR_KEY = "error"

/**
 * Fields of an idle snapshot that [IdleStopReason.Other] never holds.
 */
private val IDLE_SNAPSHOT_KEYS = setOf(STATE_KEY, STOP_REASON_KEY, USAGE_KEY, META_KEY)

@OptIn(UnstableApi::class)
internal object StateUpdateSerializer : JsonObjectSerializer<StateUpdate>(
    "com.agentclientprotocol.model.StateUpdate",
) {
    override fun decode(json: Json, obj: JsonObject): StateUpdate {
        val state = obj[STATE_KEY].stringOrNull()
            ?: throw SerializationException("'$STATE_KEY' discriminator must be a string in StateUpdate")

        return when (state) {
            "running" -> StateUpdate.Running(obj.lenientMeta())
            "idle" -> StateUpdate.Idle(
                stopReason = decodeIdleStopReason(json, obj),
                usage = obj.decodeOrNull(json, USAGE_KEY, Usage.serializer()),
                _meta = obj.lenientMeta(),
            )

            "requires_action" -> StateUpdate.RequiresAction(obj.lenientMeta())
            "unknown" -> StateUpdate.Unknown(obj.lenientMeta())
            else -> StateUpdate.Other(state, JsonObject(obj - STATE_KEY))
        }
    }

    override fun encode(json: Json, value: StateUpdate): JsonObject = buildJsonObject {
        when (value) {
            is StateUpdate.Running -> putState("running", value._meta)
            is StateUpdate.RequiresAction -> putState("requires_action", value._meta)
            is StateUpdate.Unknown -> putState("unknown", value._meta)
            is StateUpdate.Idle -> {
                put(STATE_KEY, JsonPrimitive("idle"))
                value.stopReason?.let { encodeIdleStopReason(json, it) }
                value.usage?.let { put(USAGE_KEY, json.encodeToJsonElement(Usage.serializer(), it)) }
                value._meta?.let { put(META_KEY, it) }
            }

            is StateUpdate.Other -> {
                put(STATE_KEY, JsonPrimitive(value.state))
                value.fields.forEach { (key, element) -> if (key != STATE_KEY) put(key, element) }
            }
        }
    }

    private fun JsonObjectBuilder.putState(state: String, meta: JsonElement?) {
        put(STATE_KEY, JsonPrimitive(state))
        meta?.let { put(META_KEY, it) }
    }

    /*
     * A missing, `null` or non-string `stopReason` means none is reported. Rust gets this by flattening an
     * optional field, so any failure to read the tagged union falls back to `None`.
     */
    private fun decodeIdleStopReason(json: Json, obj: JsonObject): IdleStopReason? {
        val stopReason = obj[STOP_REASON_KEY].stringOrNull() ?: return null

        return when (stopReason) {
            "end_turn" -> IdleStopReason.EndTurn
            "max_tokens" -> IdleStopReason.MaxTokens
            "max_turn_requests" -> IdleStopReason.MaxTurnRequests
            "refusal" -> IdleStopReason.Refusal
            "cancelled" -> IdleStopReason.Cancelled
            "error" -> IdleStopReason.Error(obj.decodeOrNull(json, ERROR_KEY, JsonRpcError.serializer()))
            else -> IdleStopReason.Other(stopReason, JsonObject(obj - IDLE_SNAPSHOT_KEYS))
        }
    }

    private fun JsonObjectBuilder.encodeIdleStopReason(json: Json, stopReason: IdleStopReason) {
        val discriminator = when (stopReason) {
            IdleStopReason.EndTurn -> "end_turn"
            IdleStopReason.MaxTokens -> "max_tokens"
            IdleStopReason.MaxTurnRequests -> "max_turn_requests"
            IdleStopReason.Refusal -> "refusal"
            IdleStopReason.Cancelled -> "cancelled"
            is IdleStopReason.Error -> "error"
            is IdleStopReason.Other -> stopReason.stopReason
        }
        put(STOP_REASON_KEY, JsonPrimitive(discriminator))

        when (stopReason) {
            is IdleStopReason.Error ->
                stopReason.error?.let { put(ERROR_KEY, json.encodeToJsonElement(JsonRpcError.serializer(), it)) }

            is IdleStopReason.Other ->
                stopReason.fields.forEach { (key, element) -> if (key !in IDLE_SNAPSHOT_KEYS) put(key, element) }

            else -> {}
        }
    }
}
