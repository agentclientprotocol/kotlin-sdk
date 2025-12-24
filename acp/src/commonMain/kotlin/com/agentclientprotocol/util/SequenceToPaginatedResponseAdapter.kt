package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.collections.immutable.persistentMapOf
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Adapter that converts a [Sequence] into paginated responses for cursor-based pagination.
 *
 * This class stores iterators internally and uses cursors to track pagination state across
 * multiple requests. Each cursor can only be used once — after retrieving a batch, the cursor
 * is invalidated and a new cursor is returned for the next batch (if more items are available).
 *
 * @param TItem the type of items in the sequence
 * @param TInput the paginated request type containing an optional cursor
 * @param TOutput the paginated response type containing items and the next cursor
 * @param batchSize the number of items to return per page (default: 10)
 */
@OptIn(ExperimentalAtomicApi::class, UnstableApi::class)
public class SequenceToPaginatedResponseAdapter<TItem, TInput : AcpPaginatedRequest, TOutput : AcpPaginatedResponse<TItem>>(
    public val batchSize: Int = 10) {

    private val iterators = AtomicReference(persistentMapOf<String, Iterator<TItem>>())

    /**
     * Creates [TOutput] instance for the next [batchSize] items from the sequence either stored
     * by the provided [params.cursor] or if no cursor is provided a new sequence is created by [sequenceFactory].
     *
     * @throws JsonRpcException with [JsonRpcErrorCode.INVALID_PARAMS] if there is no such cursor.
     */
    public suspend fun next(params: TInput, sequenceFactory: suspend (TInput) -> Sequence<TItem>, resultFactory: (params: TInput, batch: List<TItem>, newCursor: String?) -> TOutput): TOutput {
        val givenCursor = params.cursor
        val iterator = if (givenCursor == null) {
            sequenceFactory(params).iterator()
        }
        else {
            // TODO: really remove now or remove by timeout?
            // TODO: if to keep it when iterating this time, someone can ask iterate this iterator again in parallel
            var iteratorFromMap: Iterator<TItem>? = null
            iterators.update { map ->
                iteratorFromMap = map[givenCursor]
                map.remove(givenCursor)
            }
            iteratorFromMap ?: jsonRpcInvalidParams("No such cursor: $givenCursor")
        }
        val batch = iterator.asSequence().take(batchSize).toList()

        val newCursor = if (iterator.hasNext()) {
            @OptIn(ExperimentalUuidApi::class)
            val newCursor = Uuid.random().toHexDashString()
            iterators.update { map -> map.put(newCursor, iterator) }
            newCursor
        }
        else {
            null
        }

        return resultFactory(params, batch, newCursor)
    }
}
