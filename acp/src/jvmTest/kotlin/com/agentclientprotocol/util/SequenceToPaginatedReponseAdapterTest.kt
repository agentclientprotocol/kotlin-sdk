package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(UnstableApi::class)
class SequenceToPaginatedReponseAdapterTest {

    private data class TestRequest(
        override val cursor: String? = null
    ) : AcpPaginatedRequest

    private data class TestResponse(
        val items: List<Int>,
        override val nextCursor: String?
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
}
