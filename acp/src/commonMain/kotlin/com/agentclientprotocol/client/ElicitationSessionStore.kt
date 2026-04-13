package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.SessionId
import kotlinx.atomicfu.atomic

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
    private val entries: MutableMap<ElicitationId, Entry> = mutableMapOf()

    operator fun get(elicitationId: ElicitationId): SessionId? = entries[elicitationId]?.sessionId

    fun put(elicitationId: ElicitationId, sessionId: SessionId) {
        entries[elicitationId] = Entry(sessionId, counter.getAndIncrement())
        if (entries.size > maxCapacity) {
            evictOldestHalf()
        }
    }

    fun remove(elicitationId: ElicitationId): SessionId? {
        return entries.remove(elicitationId)?.sessionId
    }

    fun removeBySession(sessionId: SessionId) {
        entries.entries.removeAll { it.value.sessionId == sessionId }
    }

    private fun evictOldestHalf() {
        val toRemove = entries.entries
            .sortedBy { it.value.ordinal }
            .take(entries.size / 2)
            .map { it.key }
        if (entries.size > maxCapacity) {
            for (key in toRemove) {
                entries.remove(key)
            }
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
