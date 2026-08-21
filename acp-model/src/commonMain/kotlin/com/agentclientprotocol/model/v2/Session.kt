@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.AcpWithSessionId
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request parameters for the v2 `session/new` method.
 *
 * `additionalDirectories` is gated by the agent's
 * [SessionAdditionalDirectoriesCapabilities]; a client must not send a non-empty list unless the agent
 * advertised it.
 */
@UnstableApi
@Serializable
public data class NewSessionRequest(
    val cwd: String,
    val mcpServers: List<McpServer> = emptyList(),
    val additionalDirectories: List<String> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response to the v2 `session/new` method.
 *
 * Unlike v1 there are no separate `modes` and `models` fields: everything configurable arrives as
 * [SessionConfigOption]s.
 */
@UnstableApi
@Serializable
public data class NewSessionResponse(
    val sessionId: SessionId,
    val configOptions: List<SessionConfigOption> = emptyList(),
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request parameters for the v2 `session/prompt` method.
 */
@UnstableApi
@Serializable
public data class PromptRequest(
    override val sessionId: SessionId,
    val prompt: List<ContentBlock>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Response to the v2 `session/prompt` method.
 *
 * Deliberately empty: v2 moved the outcome of a turn out of the prompt response and into a session
 * update, [StateUpdate.Idle], whose `stopReason` says why the agent stopped. A client that needs the
 * stop reason reads it from the updates, not from here.
 */
@UnstableApi
@Serializable
public data class PromptResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * The v2 `session/update` notification: one update for one session.
 */
@UnstableApi
@Serializable
public data class UpdateSessionNotification(
    override val sessionId: SessionId,
    val update: SessionUpdate,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

/**
 * The v2 `session/cancel` notification.
 *
 * Carries only the session: what the agent should report about the interrupted turn goes out as a
 * [StateUpdate.Idle] update, not as a response to this.
 */
@UnstableApi
@Serializable
public data class CancelSessionNotification(
    override val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

/**
 * Information about an existing session, as reported by v2 `session/list`.
 *
 * Note `additionalDirectories`: the schema has it as a plain array with no null, unlike the v1 type where it
 * is nullable, so it defaults to empty here rather than to `null`.
 */
@UnstableApi
@Serializable
public data class SessionInfo(
    val sessionId: SessionId,
    val cwd: String,
    val additionalDirectories: List<String> = emptyList(),
    val title: String? = null,
    val updatedAt: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/** Request parameters for the v2 `session/list` method. */
@UnstableApi
@Serializable
public data class ListSessionsRequest(
    val cwd: String? = null,
    val cursor: String? = null,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response to the v2 `session/list` method.
 *
 * `nextCursor` present means there is another page; pagination is the agent's to drive, which is why it
 * appears in the surface rather than being hidden behind a flow the way v1 does it.
 */
@UnstableApi
@Serializable
public data class ListSessionsResponse(
    val sessions: List<SessionInfo>,
    val nextCursor: String? = null,
    override val _meta: JsonElement? = null
) : AcpResponse

/** Request parameters for the v2 `session/close` method. */
@UnstableApi
@Serializable
public data class CloseSessionRequest(
    override val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/** Response to the v2 `session/close` method. */
@UnstableApi
@Serializable
public data class CloseSessionResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request parameters for the v2 `session/delete` method.
 *
 * Deleting removes a session from what `session/list` reports; closing only ends the active one.
 */
@UnstableApi
@Serializable
public data class DeleteSessionRequest(
    override val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/** Response to the v2 `session/delete` method. */
@UnstableApi
@Serializable
public data class DeleteSessionResponse(
    override val _meta: JsonElement? = null
) : AcpResponse
