package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedRequest
import com.agentclientprotocol.model.AcpPaginatedResponse
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
 * @param orphanedIteratorsEvictionTimeout how long an unused cursor remains valid (default: 1 minute)
 * @param timeoutJobScope scope used to evict unused cursors; must stay active while the adapter is in use
 */
@OptIn(ExperimentalAtomicApi::class, UnstableApi::class)
public class SequenceToPaginatedResponseAdapter<TItem, TInput : AcpPaginatedRequest, TOutput : AcpPaginatedResponse<TItem>>(
    public val batchSize: Int = 10,
    public val orphanedIteratorsEvictionTimeout: Duration = 1.minutes,
    // All timeout jobs eventually finish, so the default scope does not need a separate close method.
    private val timeoutJobScope: CoroutineScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("SequenceToPaginatedResponseAdapter.timeoutJobScope")
    ),
) {
    init {
        require(orphanedIteratorsEvictionTimeout > Duration.ZERO) { "orphanedIteratorsEvictionTimeout must be positive" }
    }

    private class IteratorState<TItem>(val iterator: Iterator<TItem>, val timeoutJob: Job)
    private val iterators = AtomicReference(persistentMapOf<String, IteratorState<TItem>>())

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
            var iteratorFromMap: Iterator<TItem>? = null
            var timeoutJob: Job? = null
            iterators.update { map ->
                iteratorFromMap = map[givenCursor]?.iterator
                timeoutJob = map[givenCursor]?.timeoutJob
                map.remove(givenCursor)
            }
            timeoutJob?.cancel()
            iteratorFromMap ?: jsonRpcInvalidParams("No such cursor: $givenCursor")
        }
        val batch = iterator.asSequence().take(batchSize).toList()

        val newCursor = if (iterator.hasNext()) {
            @OptIn(ExperimentalUuidApi::class)
            val newCursor = Uuid.random().toHexDashString()
            // Store the iterator before launching the timeout job to avoid a race condition
            // where a very short timeout fires before the entry is in the map.
            // We use a lazy-start timeout job so it doesn't run until after the map is updated.
            val timeoutJob = timeoutJobScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                delay(orphanedIteratorsEvictionTimeout)
                iterators.update { map -> map.remove(newCursor) }
            }
            iterators.update { map -> map.put(newCursor, IteratorState(iterator, timeoutJob)) }
            timeoutJob.start()
            newCursor
        }
        else {
            null
        }

        return resultFactory(params, batch, newCursor)
    }
}
