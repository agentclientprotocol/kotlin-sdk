@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

/**
 * **UNSTABLE**
 *
 * Identifier of a configurable LLM provider, for example `main` or `openai`.
 *
 * A distinct type in v2, where v1 passes the same thing around as a bare `String`.
 */
@UnstableApi
@JvmInline
@Serializable
public value class ProviderId(public val value: String) {
    override fun toString(): String = value
}

/**
 * **UNSTABLE**
 *
 * The routing a provider is currently using. Secrets are never part of this.
 */
@UnstableApi
@Serializable
public data class ProviderCurrentConfig(
    val apiType: LlmProtocol,
    val baseUrl: String,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * One configurable provider as reported by `providers/list`.
 *
 * `current` absent or null means the provider is disabled, and `required` true means it cannot be disabled
 * at all — a client must not call `providers/disable` for it.
 */
@UnstableApi
@Serializable
public data class ProviderInfo(
    val providerId: ProviderId,
    val supported: List<LlmProtocol>,
    val required: Boolean,
    val current: ProviderCurrentConfig? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/** Request parameters for the v2 `providers/list` method. */
@UnstableApi
@Serializable
public data class ListProvidersRequest(
    override val _meta: JsonElement? = null
) : AcpRequest

/** Response to the v2 `providers/list` method. */
@UnstableApi
@Serializable
public data class ListProvidersResponse(
    val providers: List<ProviderInfo>,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request parameters for the v2 `providers/set` method: the full configuration for one provider.
 *
 * Note the name is singular here, unlike v1's `SetProvidersRequest`, and it replaces the whole
 * configuration rather than merging into it — [headers] is the complete map.
 */
@UnstableApi
@Serializable
public data class SetProviderRequest(
    val providerId: ProviderId,
    val apiType: LlmProtocol,
    val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    override val _meta: JsonElement? = null
) : AcpRequest

/** Response to the v2 `providers/set` method. */
@UnstableApi
@Serializable
public data class SetProviderResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/** Request parameters for the v2 `providers/disable` method. */
@UnstableApi
@Serializable
public data class DisableProviderRequest(
    val providerId: ProviderId,
    override val _meta: JsonElement? = null
) : AcpRequest

/** Response to the v2 `providers/disable` method. */
@UnstableApi
@Serializable
public data class DisableProviderResponse(
    override val _meta: JsonElement? = null
) : AcpResponse
