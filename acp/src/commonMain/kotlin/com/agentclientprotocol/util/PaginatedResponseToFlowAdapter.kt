package com.agentclientprotocol.util

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpPaginatedResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapter that converts paginated responses into a [Flow] for convenient iteration on the client side.
 *
 * This adapter lazily fetches pages as the flow is collected, making subsequent requests
 * only when needed. It handles cursor-based pagination automatically.
 *
 * Example usage:
 * ```kotlin
 * val sessionsFlow: Flow<SessionInfo> = PaginatedResponseToFlowAdapter.asFlow(
 *     batchFetcher = { cursor -> client.listSessions(cwd = "/project", cursor = cursor) }
 * )
 *
 * // Consume all sessions across all pages
 * sessionsFlow.collect { session ->
 *     println(session.sessionId)
 * }
 * ```
 */
@UnstableApi
public object PaginatedResponseToFlowAdapter {

    /**
     * Converts paginated responses into a cold [Flow] that automatically fetches subsequent pages.
     * The flow is cold - it doesn't start fetching until collection begins, and each collector
     * gets its own independent execution.
     *
     * @param TItem the type of items in the paginated response
     * @param TResponse the paginated response type
     * @param batchFetcher suspending function to fetch a page by cursor (null for first page)
     * @return a cold [Flow] that lazily iterates through all items across all pages
     */
    public fun <TItem, TResponse : AcpPaginatedResponse<TItem>> asFlow(
        batchFetcher: suspend (cursor: String?) -> TResponse
    ): Flow<TItem> = flow {
        var nextCursor: String? = null

        while (true) {
            val response = batchFetcher(nextCursor)

            for (item in response.getItemsBatch()) {
                emit(item)
            }

            nextCursor = response.nextCursor
            if (nextCursor == null) break
        }
    }
}