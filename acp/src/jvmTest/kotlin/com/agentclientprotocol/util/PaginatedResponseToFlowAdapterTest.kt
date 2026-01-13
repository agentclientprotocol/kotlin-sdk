package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class PaginatedResponseToFlowAdapterTest {

    private data class TestResponse(
        val items: List<Int>,
        override val nextCursor: String?,
        override val _meta: JsonElement? = null
    ) : AcpPaginatedResponse<Int> {
        override fun getItemsBatch(): List<Int> = items
    }

    @Test
    fun `iterates through all pages`() = runBlocking {
        val pages = mapOf(
            null to TestResponse(listOf(1, 2, 3), "cursor1"),
            "cursor1" to TestResponse(listOf(4, 5, 6), "cursor2"),
            "cursor2" to TestResponse(listOf(7, 8), null)
        )

        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor -> pages[cursor]!! }
        )

        val result = flow.toList()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), result)
    }

    @Test
    fun `handles single page without cursor`() = runBlocking {
        val response = TestResponse(listOf(1, 2, 3), null)

        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                if (cursor == null) response else error("Should not be called")
            }
        )

        val result = flow.toList()
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `handles empty first page`() = runBlocking {
        val response = TestResponse(emptyList(), null)

        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                if (cursor == null) response else error("Should not be called")
            }
        )

        val result = flow.toList()
        assertEquals(emptyList(), result)
    }

    @Test
    fun `passes correct cursor to batch fetcher`() = runBlocking {
        val cursors = mutableListOf<String?>()

        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                cursors.add(cursor)
                when (cursor) {
                    null -> TestResponse(listOf(1), "first-cursor")
                    "first-cursor" -> TestResponse(listOf(2), "second-cursor")
                    else -> TestResponse(listOf(3), null)
                }
            }
        )

        flow.toList() // consume all

        assertEquals(listOf(null, "first-cursor", "second-cursor"), cursors)
    }

    @Test
    fun `does not fetch unnecessary pages when taking limited items`() = runBlocking {
        var fetchCount = 0
        val pages = mapOf(
            null to TestResponse(listOf(1, 2, 3), "cursor1"),
            "cursor1" to TestResponse(listOf(4, 5, 6), "cursor2"),
            "cursor2" to TestResponse(listOf(7, 8, 9), null)
        )

        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                fetchCount++
                pages[cursor]!!
            }
        )

        // Take only 5 items - should fetch only first two pages
        val result = flow.take(5).toList()
        
        assertEquals(listOf(1, 2, 3, 4, 5), result)
        assertEquals(2, fetchCount) // Only first two pages should be fetched
    }

    @Test
    fun `verifies laziness - does not prefetch pages`() = runBlocking {
        val fetchLog = mutableListOf<String>()
        
        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                when (cursor) {
                    null -> {
                        fetchLog.add("Fetching first page")
                        TestResponse(listOf(1, 2), "cursor1")
                    }
                    "cursor1" -> {
                        fetchLog.add("Fetching second page")
                        TestResponse(listOf(3, 4), "cursor2")
                    }
                    else -> {
                        fetchLog.add("Fetching third page")
                        TestResponse(listOf(5, 6), null)
                    }
                }
            }
        )

        // Before collecting, nothing should be fetched
        assertEquals(emptyList(), fetchLog)

        // Take only first 4 items - should not fetch third page
        val result = flow.take(4).toList()

        assertEquals(listOf(1, 2, 3, 4), result)
        // Should have fetched only first two pages
        assertEquals(listOf("Fetching first page", "Fetching second page"), fetchLog)
        // Third page should never be fetched
        assertFalse(fetchLog.contains("Fetching third page"))
    }

    @Test
    fun `handles exceptions in batch fetcher gracefully`() = runBlocking {
        var resourceAcquired = false
        var resourceReleased = false
        
        val flow = PaginatedResponseToFlowAdapter.asFlow(
            batchFetcher = { cursor ->
                resourceAcquired = true
                try {
                    when (cursor) {
                        null -> TestResponse(listOf(1, 2), "cursor1")
                        "cursor1" -> throw RuntimeException("Network error")
                        else -> TestResponse(listOf(3, 4), null)
                    }
                } finally {
                    // Always cleanup resources
                    if (cursor == "cursor1") {
                        resourceReleased = true
                    }
                }
            }
        )

        val result = mutableListOf<Int>()
        try {
            flow.collect { value ->
                result.add(value)
            }
        } catch (e: RuntimeException) {
            assertEquals("Network error", e.message)
        }

        assertEquals(listOf(1, 2), result) // Should have collected first page items
        assertTrue(resourceAcquired)
        assertTrue(resourceReleased) // Resources should be cleaned up even on error
    }
}
