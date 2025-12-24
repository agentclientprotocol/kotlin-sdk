package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Interface for paginated responses that include a cursor for the next page.
 */
@UnstableApi
public interface AcpPaginatedResponse<TItem> : AcpResponse{
    public val nextCursor: String?
    public fun getItemsBatch(): List<TItem>
}
