@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Priority levels for plan entries.
 *
 * Used to indicate the relative importance or urgency of different
 * tasks in the execution plan.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@Serializable
public enum class PlanEntryPriority {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW
}

/**
 * Status of a plan entry in the execution flow.
 *
 * Tracks the lifecycle of each task from planning through completion.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@Serializable
public enum class PlanEntryStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED
}

/**
 * A single entry in the execution plan.
 *
 * Represents a task or goal that the assistant intends to accomplish
 * as part of fulfilling the user's request.
 *
 * See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/agent-plan#plan-entries)
 */
@Serializable
public data class PlanEntry(
    val content: String,
    val priority: PlanEntryPriority,
    val status: PlanEntryStatus,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * An execution plan for accomplishing complex tasks.
 *
 * Plans consist of multiple entries representing individual tasks or goals.
 * Agents report plans to clients to provide visibility into their execution strategy.
 * Plans can evolve during execution as the agent discovers new requirements or completes tasks.
 *
 * See protocol docs: [Agent Plan](https://agentclientprotocol.com/protocol/agent-plan)
 */
@Serializable
public data class Plan(
    val entries: List<PlanEntry>,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * A plan variant that supports multiple formats and plan identity.
 *
 * Used with the `plan_update` session update type to provide richer plan
 * representations including structured items, file references, and markdown.
 * Each variant carries a required `id` for tracking multiple concurrent plans.
 */
@UnstableApi
@Serializable
@JsonClassDiscriminator(TYPE_DISCRIMINATOR)
public sealed class PlanVariant {
    public abstract val id: String
    public abstract val _meta: JsonElement?

    /**
     * Structured plan entries (same semantics as the existing `plan` session update).
     */
    @Serializable
    @SerialName("items")
    public data class Items(
        override val id: String,
        val entries: List<PlanEntry>,
        override val _meta: JsonElement? = null
    ) : PlanVariant(), AcpWithMeta

    /**
     * A plan provided as a file URI.
     */
    @Serializable
    @SerialName("file")
    public data class File(
        override val id: String,
        val uri: String,
        override val _meta: JsonElement? = null
    ) : PlanVariant(), AcpWithMeta

    /**
     * A plan provided as raw markdown text.
     */
    @Serializable
    @SerialName("markdown")
    public data class Markdown(
        override val id: String,
        val content: String,
        override val _meta: JsonElement? = null
    ) : PlanVariant(), AcpWithMeta
}