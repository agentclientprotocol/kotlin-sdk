package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Interface for paginated requests that include a cursor for pagination.
 */
@UnstableApi
public interface AcpPaginatedRequest {
    public val cursor: String?
}
