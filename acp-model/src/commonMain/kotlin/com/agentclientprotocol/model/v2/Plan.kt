@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmInline

/**
 * Priority levels for plan entries.
 *
 * Used to indicate the relative importance or urgency of different
 * tasks in the execution plan.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@UnstableApi
@Serializable(with = PlanEntryPrioritySerializer::class)
public sealed class PlanEntryPriority {
    /**
     * The wire-format string for this priority.
     */
    public abstract val value: String

    /**
     * High priority task - critical to the overall goal.
     */
    public data object High : PlanEntryPriority() {
        override val value: String = "high"
    }

    /**
     * Medium priority task - important but not critical.
     */
    public data object Medium : PlanEntryPriority() {
        override val value: String = "medium"
    }

    /**
     * Low priority task - nice to have but not essential.
     */
    public data object Low : PlanEntryPriority() {
        override val value: String = "low"
    }

    /**
     * Custom or future plan entry priority.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown priority SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : PlanEntryPriority()

    public companion object {
        /**
         * Creates an implementation-specific extension priority.
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
internal object PlanEntryPrioritySerializer : OpenStringEnumSerializer<PlanEntryPriority>(
    serialName = "com.agentclientprotocol.model.v2.PlanEntryPriority",
    knownValues = listOf(
        PlanEntryPriority.High,
        PlanEntryPriority.Medium,
        PlanEntryPriority.Low,
    ),
    wireValue = PlanEntryPriority::value,
    unknown = PlanEntryPriority::Unknown,
)

/**
 * Status of a plan entry in the execution flow.
 *
 * Tracks the lifecycle of each task from planning through completion.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@UnstableApi
@Serializable(with = PlanEntryStatusSerializer::class)
public sealed class PlanEntryStatus {
    /**
     * The wire-format string for this status.
     */
    public abstract val value: String

    /**
     * The task has not started yet.
     */
    public data object Pending : PlanEntryStatus() {
        override val value: String = "pending"
    }

    /**
     * The task is currently being worked on.
     */
    public data object InProgress : PlanEntryStatus() {
        override val value: String = "in_progress"
    }

    /**
     * The task has been successfully completed.
     */
    public data object Completed : PlanEntryStatus() {
        override val value: String = "completed"
    }

    /**
     * Custom or future plan entry status.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown status SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : PlanEntryStatus()

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
internal object PlanEntryStatusSerializer : OpenStringEnumSerializer<PlanEntryStatus>(
    serialName = "com.agentclientprotocol.model.v2.PlanEntryStatus",
    knownValues = listOf(
        PlanEntryStatus.Pending,
        PlanEntryStatus.InProgress,
        PlanEntryStatus.Completed,
    ),
    wireValue = PlanEntryStatus::value,
    unknown = PlanEntryStatus::Unknown,
)

/**
 * Unique identifier for a plan within a session.
 *
 * New in v2: v1 sessions had a single implicit plan, so its updates carried no ID.
 */
@UnstableApi
@JvmInline
@Serializable
public value class PlanId(public val value: String) {
    override fun toString(): String = value
}

/**
 * A single entry in the execution plan.
 *
 * Represents a task or goal that the agent intends to accomplish as part of fulfilling the
 * user's request. Same shape as v1's entry, but [priority] and [status] reference the v2
 * open enums.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@UnstableApi
@Serializable
public data class PlanEntry(
    /**
     * Human-readable description of what this task aims to accomplish.
     */
    val content: String,
    /**
     * The relative importance of this task.
     */
    val priority: PlanEntryPriority,
    /**
     * Current execution status of this task.
     */
    val status: PlanEntryStatus,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Updated content for a plan.
 *
 * This is an open tagged union discriminated by `type`: an unrecognized type deserializes
 * to [Unknown] with the full raw JSON preserved. Every variant — including [Unknown] —
 * identifies its plan through [planId].
 */
@UnstableApi
@Serializable(with = PlanUpdateContentSerializer::class)
public sealed class PlanUpdateContent {
    /**
     * The plan this content updates.
     */
    public abstract val planId: PlanId

    /**
     * A plan represented as structured entries.
     *
     * When updating an item-based plan, the agent sends the complete list of entries with
     * their current status; the client replaces the plan with each update.
     */
    @Serializable
    public data class Items(
        override val planId: PlanId,
        val entries: List<PlanEntry>,
        override val _meta: JsonElement? = null,
    ) : PlanUpdateContent(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * A URI pointing to a file containing the plan.
     */
    @Serializable
    public data class File(
        override val planId: PlanId,
        /**
         * The URI of the file containing the plan.
         */
        val uri: String,
        override val _meta: JsonElement? = null,
    ) : PlanUpdateContent(), AcpWithMeta

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Raw markdown content for the plan.
     */
    @Serializable
    public data class Markdown(
        override val planId: PlanId,
        /**
         * Markdown content for the plan.
         */
        val content: String,
        override val _meta: JsonElement? = null,
    ) : PlanUpdateContent(), AcpWithMeta

    /**
     * Custom or future plan update content.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * Even unknown content must carry `planId` — decoding fails otherwise. [rawJson] holds
     * the complete payload as received (including the discriminator), so re-serializing
     * emits it byte-identically. Receivers that do not understand this content type SHOULD
     * preserve it when storing, replaying, proxying, or forwarding plans, and otherwise
     * ignore it or display it generically.
     */
    public data class Unknown(
        val type: String,
        override val planId: PlanId,
        val rawJson: JsonObject,
    ) : PlanUpdateContent()
}

@OptIn(UnstableApi::class)
internal object PlanUpdateContentSerializer : OpenTaggedUnionSerializer<PlanUpdateContent>(
    serialName = "com.agentclientprotocol.model.v2.PlanUpdateContent",
    discriminatorKey = "type",
    known = mapOf(
        "items" to PlanUpdateContent.Items.serializer(),
        "file" to PlanUpdateContent.File.serializer(),
        "markdown" to PlanUpdateContent.Markdown.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is PlanUpdateContent.Items -> "items"
            is PlanUpdateContent.File -> "file"
            is PlanUpdateContent.Markdown -> "markdown"
            is PlanUpdateContent.Unknown -> value.type
        }
    },
    unknown = { type, rawJson ->
        val planId = (rawJson["planId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Missing 'planId' in unknown PlanUpdateContent")
        PlanUpdateContent.Unknown(type = type, planId = PlanId(planId), rawJson = rawJson)
    },
    rawJson = { (it as? PlanUpdateContent.Unknown)?.rawJson },
)

/**
 * A content update for a plan identified by ID.
 *
 * v1's plan update carried the entries directly; v2 wraps them in [PlanUpdateContent] so a
 * session can track several plans and describe them in more than one form.
 *
 * See protocol docs: [Agent Plan](https://agentclientprotocol.com/protocol/agent-plan)
 */
@UnstableApi
@Serializable
public data class PlanUpdate(
    /**
     * The updated plan content.
     */
    val plan: PlanUpdateContent,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Removal notice for a plan identified by ID.
 */
@UnstableApi
@Serializable
public data class PlanRemoved(
    /**
     * The plan ID to remove.
     */
    val planId: PlanId,
    override val _meta: JsonElement? = null,
) : AcpWithMeta
