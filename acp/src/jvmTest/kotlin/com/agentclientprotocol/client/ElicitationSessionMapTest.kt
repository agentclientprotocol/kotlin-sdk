package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(UnstableApi::class)
class ElicitationSessionMapTest {

    private val session1 = SessionId("session-1")
    private val session2 = SessionId("session-2")
    private val elicitation1 = ElicitationId("elicit-1")
    private val elicitation2 = ElicitationId("elicit-2")
    private val elicitation3 = ElicitationId("elicit-3")

    private fun createMap(maxCapacity: Int = 10_000) =
        ElicitationSessionStore(maxCapacity = maxCapacity)

    @Test
    fun `get returns null for unknown elicitation`() {
        val map = createMap()
        assertNull(map[elicitation1])
    }

    @Test
    fun `put and get a single entry`() {
        val map = createMap()
        map.put(elicitation1, session1)
        assertEquals(session1, map[elicitation1])
    }

    @Test
    fun `put multiple entries and retrieve each`() {
        val map = createMap()
        map.put(elicitation1, session1)
        map.put(elicitation2, session2)
        map.put(elicitation3, session1)

        assertEquals(session1, map[elicitation1])
        assertEquals(session2, map[elicitation2])
        assertEquals(session1, map[elicitation3])
    }

    @Test
    fun `put overwrites value for existing key`() {
        val map = createMap()
        map.put(elicitation1, session1)
        map.put(elicitation2, session1)
        map.put(elicitation1, session2)

        assertEquals(session2, map[elicitation1])
        assertEquals(session1, map[elicitation2])
    }

    @Test
    fun `remove deletes the entry`() {
        val map = createMap()
        map.put(elicitation1, session1)
        map.put(elicitation2, session2)
        map.remove(elicitation1)

        assertNull(map[elicitation1])
        assertEquals(session2, map[elicitation2])
    }

    @Test
    fun `removeBySession removes all entries for that session`() {
        val map = createMap()
        map.put(elicitation1, session1)
        map.put(elicitation2, session1)
        map.put(elicitation3, session2)
        map.removeBySession(session1)

        assertNull(map[elicitation1])
        assertNull(map[elicitation2])
        assertEquals(session2, map[elicitation3])
    }

    @Test
    fun `evicts oldest half when capacity is exceeded`() {
        val map = createMap(maxCapacity = 4)
        val ids = (1..5).map { ElicitationId("e-$it") }

        // Fill to capacity
        for (id in ids.take(4)) map.put(id, session1)

        // 5th insert exceeds capacity → evicts oldest half (2 entries)
        map.put(ids[4], session1)

        // e-1 and e-2 (oldest) should be evicted
        assertNull(map[ids[0]])
        assertNull(map[ids[1]])
        // e-3, e-4, e-5 should remain
        assertEquals(session1, map[ids[2]])
        assertEquals(session1, map[ids[3]])
        assertEquals(session1, map[ids[4]])
    }

    @Test
    fun `evicts oldest half with odd count`() {
        val map = createMap(maxCapacity = 3)
        val ids = (1..4).map { ElicitationId("e-$it") }

        for (id in ids.take(3)) map.put(id, session1)

        // 4th insert exceeds capacity → 4 entries, evicts 4/2=2
        map.put(ids[3], session1)

        assertNull(map[ids[0]])
        assertNull(map[ids[1]])
        assertEquals(session1, map[ids[2]])
        assertEquals(session1, map[ids[3]])
    }

    @Test
    fun `overwriting existing key does not trigger eviction`() {
        val map = createMap(maxCapacity = 2)
        map.put(elicitation1, session1)
        map.put(elicitation2, session1)
        // Overwrite — still 2 entries, no eviction
        map.put(elicitation1, session2)

        assertEquals(session2, map[elicitation1])
        assertEquals(session1, map[elicitation2])
    }

    @Test
    fun `remove frees capacity for new entries`() {
        val map = createMap(maxCapacity = 2)
        map.put(elicitation1, session1)
        map.put(elicitation2, session1)
        map.remove(elicitation1)
        map.put(elicitation3, session2)

        assertNull(map[elicitation1])
        assertEquals(session1, map[elicitation2])
        assertEquals(session2, map[elicitation3])
    }

    @Test
    fun `eviction order is based on insertion timestamp`() {
        val map = createMap(maxCapacity = 4)

        map.put(elicitation1, session1) // timestamp 0
        map.put(elicitation2, session1) // timestamp 1
        map.put(elicitation3, session1) // timestamp 2

        // Overwrite elicitation1 — gets new timestamp (3)
        map.put(elicitation1, session2)

        // Fill past capacity
        val e4 = ElicitationId("e-4")
        val e5 = ElicitationId("e-5")
        map.put(e4, session1) // timestamp 4 → size=4, at capacity
        map.put(e5, session1) // timestamp 5 → size=5, evict oldest 2

        // elicitation2 (ts=1) and elicitation3 (ts=2) should be evicted
        assertNull(map[elicitation2])
        assertNull(map[elicitation3])
        // elicitation1 (ts=3), e4 (ts=4), e5 (ts=5) remain
        assertEquals(session2, map[elicitation1])
        assertEquals(session1, map[e4])
        assertEquals(session1, map[e5])
    }

    @Test
    fun `multiple eviction cycles work correctly`() {
        val map = createMap(maxCapacity = 2)
        val ids = (1..6).map { ElicitationId("e-$it") }

        map.put(ids[0], session1) // ts=0
        map.put(ids[1], session1) // ts=1
        // 3rd insert → 3 entries, evict 1 (3/2=1)
        map.put(ids[2], session1) // ts=2

        // After eviction: ids[1] (ts=1) and ids[2] (ts=2) remain
        assertNull(map[ids[0]])
        assertEquals(session1, map[ids[1]])
        assertEquals(session1, map[ids[2]])

        map.put(ids[3], session1) // ts=3 → 3 entries, evict 1
        assertNull(map[ids[1]])
        assertEquals(session1, map[ids[2]])
        assertEquals(session1, map[ids[3]])
    }

    @Test
    fun `capacity of 1 evicts the only entry on overflow`() {
        val map = createMap(maxCapacity = 1)
        map.put(elicitation1, session1) // ts=0
        map.put(elicitation2, session2) // ts=1 → 2 entries, evict 2/2=1

        assertNull(map[elicitation1])
        assertEquals(session2, map[elicitation2])
    }
}
