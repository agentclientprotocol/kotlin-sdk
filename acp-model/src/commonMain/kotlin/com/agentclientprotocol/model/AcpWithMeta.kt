package com.agentclientprotocol.model

import kotlinx.serialization.json.JsonElement

/**
 * Interface for ACP model types that support the `_meta` extension point.
 *
 * The `_meta` property allows implementations to include custom metadata
 * without breaking protocol compatibility.
 */
public interface AcpWithMeta {
    /**
     * Extension point for implementations
     */
    @Suppress("PropertyName")
    public val _meta: JsonElement?
}