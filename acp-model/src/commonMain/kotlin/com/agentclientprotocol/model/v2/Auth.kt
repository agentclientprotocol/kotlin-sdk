@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AuthMethodId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request parameters for the v2 `auth/login` method.
 *
 * v2 splits v1's single `authenticate` into `auth/login` and `auth/logout`. Populating `authMethods` in the
 * initialize response obliges an agent to implement both; when it is empty, clients MUST NOT call either.
 */
@UnstableApi
@Serializable
public data class LoginAuthRequest(
    val methodId: AuthMethodId,
    override val _meta: JsonElement? = null
) : AcpRequest

/** Response to the v2 `auth/login` method. */
@UnstableApi
@Serializable
public data class LoginAuthResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/** Request parameters for the v2 `auth/logout` method. */
@UnstableApi
@Serializable
public data class LogoutAuthRequest(
    override val _meta: JsonElement? = null
) : AcpRequest

/** Response to the v2 `auth/logout` method. */
@UnstableApi
@Serializable
public data class LogoutAuthResponse(
    override val _meta: JsonElement? = null
) : AcpResponse
