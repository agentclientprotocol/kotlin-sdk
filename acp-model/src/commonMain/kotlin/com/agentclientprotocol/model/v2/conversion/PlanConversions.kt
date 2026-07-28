@file:Suppress("unused")
@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.PlanEntry as V1PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority as V1PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus as V1PlanEntryStatus
import com.agentclientprotocol.model.PlanVariant as V1PlanVariant
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate
import com.agentclientprotocol.model.v2.PlanEntry
import com.agentclientprotocol.model.v2.PlanEntryPriority
import com.agentclientprotocol.model.v2.PlanEntryStatus
import com.agentclientprotocol.model.v2.PlanId
import com.agentclientprotocol.model.v2.PlanRemoved
import com.agentclientprotocol.model.v2.PlanUpdate
import com.agentclientprotocol.model.v2.PlanUpdateContent

/**
 * The plan ID v1 plans are given when converted to v2.
 *
 * v1 sessions had a single implicit plan carrying no identifier, so one is synthesized.
 * Mirrors Rust's `LEGACY_V1_PLAN_ID`.
 */
@UnstableApi
public val LEGACY_V1_PLAN_ID: PlanId = PlanId("main")

/**
 * Converts this v2 priority to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [PlanEntryPriority.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun PlanEntryPriority.toV1(): V1PlanEntryPriority = when (this) {
    PlanEntryPriority.High -> V1PlanEntryPriority.HIGH
    PlanEntryPriority.Medium -> V1PlanEntryPriority.MEDIUM
    PlanEntryPriority.Low -> V1PlanEntryPriority.LOW
    is PlanEntryPriority.Unknown -> throw unknownV2EnumVariant("PlanEntryPriority", value)
}

/**
 * Converts this v1 priority to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1PlanEntryPriority.toV2(): PlanEntryPriority = when (this) {
    V1PlanEntryPriority.HIGH -> PlanEntryPriority.High
    V1PlanEntryPriority.MEDIUM -> PlanEntryPriority.Medium
    V1PlanEntryPriority.LOW -> PlanEntryPriority.Low
}

/**
 * Converts this v2 status to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [PlanEntryStatus.Unknown] value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun PlanEntryStatus.toV1(): V1PlanEntryStatus = when (this) {
    PlanEntryStatus.Pending -> V1PlanEntryStatus.PENDING
    PlanEntryStatus.InProgress -> V1PlanEntryStatus.IN_PROGRESS
    PlanEntryStatus.Completed -> V1PlanEntryStatus.COMPLETED
    is PlanEntryStatus.Unknown -> throw unknownV2EnumVariant("PlanEntryStatus", value)
}

/**
 * Converts this v1 status to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1PlanEntryStatus.toV2(): PlanEntryStatus = when (this) {
    V1PlanEntryStatus.PENDING -> PlanEntryStatus.Pending
    V1PlanEntryStatus.IN_PROGRESS -> PlanEntryStatus.InProgress
    V1PlanEntryStatus.COMPLETED -> PlanEntryStatus.Completed
}

/**
 * Converts this v2 plan entry to its v1 equivalent.
 *
 * @throws ProtocolConversionException if [priority] or [status] is an `Unknown` value,
 * which cannot be represented in v1 without data loss
 */
@UnstableApi
public fun PlanEntry.toV1(): V1PlanEntry = V1PlanEntry(
    content = content,
    priority = priority.toV1(),
    status = status.toV1(),
    _meta = _meta,
)

/**
 * Converts this v1 plan entry to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1PlanEntry.toV2(): PlanEntry = PlanEntry(
    content = content,
    priority = priority.toV2(),
    status = status.toV2(),
    _meta = _meta,
)

/**
 * Converts this v2 plan update to the v1 session update that carries it.
 *
 * v1 has two plan updates and no feature flags, so the target is chosen by content type:
 * [PlanUpdateContent.Items] becomes v1's `plan` update (which has no plan ID — the ID is
 * dropped), while the file and markdown forms become v1's `plan_update`, whose
 * [V1PlanVariant] keeps the ID.
 *
 * @throws ProtocolConversionException if the content is a [PlanUpdateContent.Unknown]
 * value, or if an entry's priority or status is `Unknown`
 */
@UnstableApi
public fun PlanUpdate.toV1(): V1SessionUpdate = when (val plan = plan) {
    is PlanUpdateContent.Items -> V1SessionUpdate.PlanUpdate(
        entries = plan.entries.map { it.toV1() },
        _meta = _meta ?: plan._meta,
    )

    is PlanUpdateContent.File -> V1SessionUpdate.PlanUpdateV2(
        plan = V1PlanVariant.File(id = plan.planId.value, uri = plan.uri, _meta = plan._meta),
        _meta = _meta,
    )

    is PlanUpdateContent.Markdown -> V1SessionUpdate.PlanUpdateV2(
        plan = V1PlanVariant.Markdown(id = plan.planId.value, content = plan.content, _meta = plan._meta),
        _meta = _meta,
    )

    is PlanUpdateContent.Unknown -> throw unknownV2EnumVariant("PlanUpdateContent", plan.type)
}

/**
 * Converts this v1 `plan` update to its v2 equivalent.
 *
 * v1 plans carry no identifier, so the entries are attributed to [LEGACY_V1_PLAN_ID].
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.PlanUpdate.toV2(): PlanUpdate = PlanUpdate(
    plan = PlanUpdateContent.Items(
        planId = LEGACY_V1_PLAN_ID,
        entries = entries.map { it.toV2() },
    ),
    _meta = _meta,
)

/**
 * Converts this v1 `plan_update` to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.PlanUpdateV2.toV2(): PlanUpdate = PlanUpdate(
    plan = when (val plan = plan) {
        is V1PlanVariant.Items -> PlanUpdateContent.Items(
            planId = PlanId(plan.id),
            entries = plan.entries.map { it.toV2() },
            _meta = plan._meta,
        )

        is V1PlanVariant.File -> PlanUpdateContent.File(
            planId = PlanId(plan.id),
            uri = plan.uri,
            _meta = plan._meta,
        )

        is V1PlanVariant.Markdown -> PlanUpdateContent.Markdown(
            planId = PlanId(plan.id),
            content = plan.content,
            _meta = plan._meta,
        )
    },
    _meta = _meta,
)

/**
 * Converts this v2 plan removal to its v1 equivalent.
 *
 * This conversion is total: every v2 value has a v1 representation.
 */
@UnstableApi
public fun PlanRemoved.toV1(): V1SessionUpdate.PlanRemoved =
    V1SessionUpdate.PlanRemoved(id = planId.value, _meta = _meta)

/**
 * Converts this v1 plan removal to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1SessionUpdate.PlanRemoved.toV2(): PlanRemoved =
    PlanRemoved(planId = PlanId(id), _meta = _meta)
