package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Interface for paginated requests that include a cursor for pagination.
 */
@Deprecated("The only subtype is v1.ListSessionRequest, and v2 doesn't use this interface. Will be removed in the future.")
@UnstableApi
public interface AcpPaginatedRequest : AcpRequest {
    public val cursor: String?
}
