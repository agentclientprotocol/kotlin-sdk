package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse

/**
 * **UNSTABLE**
 *
 * What a client provides to a v2 session so the agent can reach the user.
 *
 * The v1 counterpart is [com.agentclientprotocol.common.ClientSessionOperations], which also carries `fs`
 * and `terminal`; v2 has neither — file and terminal access moves to MCP-over-ACP — so this interface stays
 * small and will grow only with what v2 actually asks of a client.
 *
 * A session created without operations refuses these requests with a clear error instead of leaving the
 * agent waiting.
 */
@UnstableApi
public interface ClientSessionOperations {
    /**
     * Asks the user about [request] and returns the outcome.
     *
     * Returning [RequestPermissionOutcome.Cancelled] is allowed but usually unnecessary: when the client
     * cancels the turn, the SDK answers whatever request is in flight with `cancelled` on its own, so this
     * can simply keep waiting for the user.
     */
    public suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse
}
