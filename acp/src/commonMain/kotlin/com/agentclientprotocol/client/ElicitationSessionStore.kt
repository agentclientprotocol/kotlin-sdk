package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.SessionId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf

/**
 * A mutable, bounded map from [ElicitationId] to [SessionId] with bulk eviction.
 *
 * When [maxCapacity] is reached, the oldest half of entries is evicted.
 * Entries can also be removed individually or bulk-removed by session
 * when a session is torn down.
 */
@UnstableApi
internal class ElicitationSessionStore(
    private val maxCapacity: Int = MAX_ELICITATION_ENTRIES,
) {
    private data class Entry(val sessionId: SessionId, val ordinal: Long)

    private val counter = atomic(0L)
    private val entries = atomic(persistentMapOf<ElicitationId, Entry>())

    operator fun get(elicitationId: ElicitationId): SessionId? = entries.value[elicitationId]?.sessionId

    fun put(elicitationId: ElicitationId, sessionId: SessionId) {
        entries.update { it.put(elicitationId, Entry(sessionId, counter.getAndIncrement())) }
        if (entries.value.size > maxCapacity) {
            evictOldestHalf()
        }
    }

    fun remove(elicitationId: ElicitationId): SessionId? {
        val sessionId = entries.value[elicitationId]?.sessionId
        entries.update { it.remove(elicitationId) }
        return sessionId
    }

    fun removeBySession(sessionId: SessionId) {
        val toRemove = entries.value.filter { it.value.sessionId == sessionId }.map { it.key }
        entries.update {
            toRemove.fold(it) { acc, elicitationId -> acc.remove(elicitationId) }
        }
    }

    private fun evictOldestHalf() {
        val value = entries.value
        val toRemove = value.entries
            .sortedBy { it.value.ordinal }
            .take(value.size / 2)
            .map { it.key }
        entries.update {
            toRemove.fold(it) { acc, elicitationId -> acc.remove(elicitationId) }
        }
    }

    companion object {
        /**
         * Maximum number of tracked URL-mode elicitation→session mappings.
         * Entries are removed on elicitation/complete and on session teardown;
         * this cap is a safety net for cases where completions never arrive.
         */
        const val MAX_ELICITATION_ENTRIES = 10_000
    }
}
