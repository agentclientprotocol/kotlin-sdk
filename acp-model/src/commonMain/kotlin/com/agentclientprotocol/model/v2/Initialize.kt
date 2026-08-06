@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request parameters for the v2 `initialize` method.
 *
 * Differences from v1 that matter on the wire: `capabilities` and `info` replace
 * `clientCapabilities` and `clientInfo`, and **`info` is required** — v1 leaves it optional.
 *
 * See protocol docs: [Initialization](https://agentclientprotocol.com/protocol/v2/initialization)
 */
@UnstableApi
@Serializable
public data class InitializeRequest(
    val protocolVersion: ProtocolVersion,
    val info: Implementation,
    val capabilities: ClientCapabilities = ClientCapabilities(),
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response to the v2 `initialize` method.
 *
 * As in the request, `capabilities`/`info` replace `agentCapabilities`/`agentInfo`, and `info` is
 * required. `authMethods` stays optional: populating it obliges the agent to implement `auth/login`
 * and `auth/logout`.
 */
@UnstableApi
@Serializable
public data class InitializeResponse(
    val protocolVersion: ProtocolVersion,
    val info: Implementation,
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val authMethods: List<AuthMethod> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpResponse
