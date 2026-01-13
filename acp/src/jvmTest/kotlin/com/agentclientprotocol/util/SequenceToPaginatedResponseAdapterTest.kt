package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class SequenceToPaginatedResponseAdapterTest {

    private data class TestRequest(
        override val cursor: String? = null, override val _meta: JsonElement? = null
    ) : AcpPaginatedRequest

    private data class TestResponse(
        val items: List<Int>,
        override val nextCursor: String?, override val _meta: JsonElement? = null
    ) : AcpPaginatedResponse<Int> {
        override fun getItemsBatch(): List<Int> = items
    }

    @Test
    fun `returns first batch without cursor`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)
        val sequence = sequenceOf(1, 2, 3, 4, 5)

        val result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(listOf(1, 2, 3), result.items)
        assertNotNull(result.nextCursor)
    }

    @Test
    fun `returns next batch with valid cursor`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)
        val sequence = sequenceOf(1, 2, 3, 4, 5)

        val firstResult = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val cursor = firstResult.nextCursor
        assertNotNull(cursor)

        val secondResult = storage.next(
            params = TestRequest(cursor = cursor),
            sequenceFactory = { error("Should not be called") },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(listOf(4, 5), secondResult.items)
        assertNull(secondResult.nextCursor)
    }

    @Test
    fun `returns null cursor when sequence exhausted`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 5)
        val sequence = sequenceOf(1, 2, 3)

        val result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(listOf(1, 2, 3), result.items)
        assertNull(result.nextCursor)
    }

    @Test
    fun `returns empty batch for empty sequence`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)
        val sequence = emptySequence<Int>()

        val result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(emptyList(), result.items)
        assertNull(result.nextCursor)
    }

    @Test
    fun `throws exception for invalid cursor`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)

        val exception = assertFailsWith<JsonRpcException> {
            storage.next(
                params = TestRequest(cursor = "invalid-cursor"),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, exception.code)
    }

    @Test
    fun `cursor can only be used once`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 2)
        val sequence = sequenceOf(1, 2, 3, 4, 5, 6)

        val firstResult = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val cursor = firstResult.nextCursor
        assertNotNull(cursor)

        // Use cursor once
        storage.next(
            params = TestRequest(cursor = cursor),
            sequenceFactory = { error("Should not be called") },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        // Try to use the same cursor again - should fail
        val exception = assertFailsWith<JsonRpcException> {
            storage.next(
                params = TestRequest(cursor = cursor),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, exception.code)
    }

    @Test
    fun `iterates through entire sequence in batches`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)
        val sequence = sequenceOf(1, 2, 3, 4, 5, 6, 7, 8)
        val allItems = mutableListOf<Int>()

        var result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )
        allItems.addAll(result.items)

        while (result.nextCursor != null) {
            result = storage.next(
                params = TestRequest(cursor = result.nextCursor),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
            allItems.addAll(result.items)
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), allItems)
    }

    @Test
    fun `uses default batch size of 10`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>()
        val sequence = (1..15).asSequence()

        val result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(10, result.items.size)
        assertEquals((1..10).toList(), result.items)
        assertNotNull(result.nextCursor)
    }

    @Test
    fun `passes params to result factory`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(batchSize = 3)
        val sequence = sequenceOf(1, 2, 3)
        val originalParams = TestRequest(cursor = null)
        var receivedParams: TestRequest? = null

        storage.next(
            params = originalParams,
            sequenceFactory = { sequence },
            resultFactory = { params, batch, cursor ->
                receivedParams = params
                TestResponse(batch, cursor)
            }
        )

        assertEquals(originalParams, receivedParams)
    }

    @Test
    fun `iterator is removed after timeout`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 3,
            orphanedIteratorsEvictionTimeout = 500.milliseconds
        )
        val sequence = sequenceOf(1, 2, 3, 4, 5, 6, 7, 8)

        val firstResult = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val cursor = firstResult.nextCursor
        assertNotNull(cursor)

        // Wait for timeout to expire
        delay(700.milliseconds)

        // Try to use the expired cursor - should fail
        val exception = assertFailsWith<JsonRpcException> {
            storage.next(
                params = TestRequest(cursor = cursor),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, exception.code)
    }

    @Test
    fun `each cursor has its own timeout`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 2,
            orphanedIteratorsEvictionTimeout = 500.milliseconds
        )
        val sequence = sequenceOf(1, 2, 3, 4, 5, 6)

        val firstResult = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val firstCursor = firstResult.nextCursor
        assertNotNull(firstCursor)

        // Use the first cursor immediately
        val secondResult = storage.next(
            params = TestRequest(cursor = firstCursor),
            sequenceFactory = { error("Should not be called") },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(listOf(3, 4), secondResult.items)
        val secondCursor = secondResult.nextCursor
        assertNotNull(secondCursor)

        // The second cursor has its own timeout, independent of the first
        // Wait less than the timeout
        delay(300.milliseconds)

        // The second cursor should still be valid 
        val thirdResult = storage.next(
            params = TestRequest(cursor = secondCursor),
            sequenceFactory = { error("Should not be called") },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        assertEquals(listOf(5, 6), thirdResult.items)
        assertNull(thirdResult.nextCursor)
    }

    @Test
    fun `multiple iterators can have independent timeouts`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 2,
            orphanedIteratorsEvictionTimeout = 600.milliseconds
        )

        // Create first iterator
        val sequence1 = sequenceOf(1, 2, 3, 4)
        val result1 = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence1 },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )
        val cursor1 = result1.nextCursor
        assertNotNull(cursor1)

        // Wait a bit
        delay(200.milliseconds)

        // Create second iterator
        val sequence2 = sequenceOf(10, 20, 30, 40)
        val result2 = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence2 },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )
        val cursor2 = result2.nextCursor
        assertNotNull(cursor2)

        // Wait for first cursor to expire but not second
        delay(500.milliseconds)

        // First cursor should be expired
        assertFailsWith<JsonRpcException> {
            storage.next(
                params = TestRequest(cursor = cursor1),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }

        // Second cursor should still work
        val result2Next = storage.next(
            params = TestRequest(cursor = cursor2),
            sequenceFactory = { error("Should not be called") },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )
        assertEquals(listOf(30, 40), result2Next.items)
    }

    @Test
    fun `concurrent access to same cursor fails for second request`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 2,
            orphanedIteratorsEvictionTimeout = 2.seconds
        )
        val sequence = sequenceOf(1, 2, 3, 4, 5, 6)

        val firstResult = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val cursor = firstResult.nextCursor
        assertNotNull(cursor)

        // Launch two concurrent requests with the same cursor
        val job1 = launch {
            delay(20.milliseconds) // Small delay to ensure consistent ordering
            storage.next(
                params = TestRequest(cursor = cursor),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }

        val job2 = launch {
            delay(50.milliseconds) // Slightly larger delay
            assertFailsWith<JsonRpcException> {
                storage.next(
                    params = TestRequest(cursor = cursor),
                    sequenceFactory = { error("Should not be called") },
                    resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
                )
            }
        }

        job1.join()
        job2.join()
    }

    @Test
    fun `very short timeout should work`(): Unit = runBlocking {
        // Test with very short timeout
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 2,
            orphanedIteratorsEvictionTimeout = 1.milliseconds
        )
        val sequence = sequenceOf(1, 2, 3, 4)

        val result = storage.next(
            params = TestRequest(),
            sequenceFactory = { sequence },
            resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
        )

        val cursor = result.nextCursor
        assertNotNull(cursor)

        // Even a small delay should trigger timeout
        delay(50.milliseconds)

        assertFailsWith<JsonRpcException> {
            storage.next(
                params = TestRequest(cursor = cursor),
                sequenceFactory = { error("Should not be called") },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
        }
    }

    @Test
    fun `zero or negative timeout should throw exception`() {
        // Zero timeout should be rejected
        assertFailsWith<IllegalArgumentException> {
            SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
                batchSize = 2,
                orphanedIteratorsEvictionTimeout = 0.milliseconds
            )
        }

        // Negative timeout should be rejected
        assertFailsWith<IllegalArgumentException> {
            SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
                batchSize = 2,
                orphanedIteratorsEvictionTimeout = (-100).milliseconds
            )
        }
    }

    @Test
    fun `large number of iterators with timeouts`(): Unit = runBlocking {
        val storage = SequenceToPaginatedResponseAdapter<Int, TestRequest, TestResponse>(
            batchSize = 2,
            orphanedIteratorsEvictionTimeout = 300.milliseconds
        )

        val cursors = mutableListOf<String>()

        // Create many iterators
        repeat(100) { i ->
            val sequence = sequenceOf(i * 10, i * 10 + 1, i * 10 + 2, i * 10 + 3)
            val result = storage.next(
                params = TestRequest(),
                sequenceFactory = { sequence },
                resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
            )
            result.nextCursor?.let { cursors.add(it) }
        }

        assertEquals(100, cursors.size)

        // Wait for all to expire
        delay(500.milliseconds)

        // All cursors should be invalid now
        cursors.forEach { cursor ->
            assertFailsWith<JsonRpcException> {
                storage.next(
                    params = TestRequest(cursor = cursor),
                    sequenceFactory = { error("Should not be called") },
                    resultFactory = { _, batch, cursor -> TestResponse(batch, cursor) }
                )
            }
        }
    }
}
