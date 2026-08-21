package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.DeleteSessionResponse
import com.agentclientprotocol.model.v2.DisableProviderResponse
import com.agentclientprotocol.model.v2.ListProvidersResponse
import com.agentclientprotocol.model.v2.ListSessionsResponse
import com.agentclientprotocol.model.v2.LlmProtocol
import com.agentclientprotocol.model.v2.LoginAuthResponse
import com.agentclientprotocol.model.v2.LogoutAuthResponse
import com.agentclientprotocol.model.v2.ProviderId
import com.agentclientprotocol.model.v2.ReplayFrom
import com.agentclientprotocol.model.v2.SetProviderResponse
import com.agentclientprotocol.protocol.acpFail
import kotlinx.serialization.json.JsonElement
import com.agentclientprotocol.client.v2.ClientInfo

/**
 * **UNSTABLE**
 *
 * What a v2 [Agent] serves requests from:
 *
 * ```kotlin
 * Agent(protocol, myV2Support)
 * ```
 *
 * The v1 counterpart is [com.agentclientprotocol.agent.AgentSupport], and nothing is converted between
 * them: this interface speaks v2 types only, for a connection that speaks v2 only.
 *
 * A method left unimplemented refuses the request naming the capability it belongs to, rather than
 * answering it with a v1 payload.
 */
@UnstableApi
public interface AgentSupport {
    /**
     * Initializes the agent with the connecting client's v2 information.
     *
     * The negotiated protocol version is filled into the response by the SDK.
     */
    public suspend fun initialize(clientInfo: ClientInfo): AgentInfo

    /**
     * Creates a session for `session/new`.
     *
     * The returned session owns its [AgentSession.sessionId]; the SDK only registers it. [client] is how
     * the session talks back to the client mid-turn — asking for permission, and later eliciting input.
     */
    public suspend fun createSession(
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession

    /**
     * Handles `auth/login`.
     *
     * v2 split v1's single `authenticate` in two. Implementing these is obligatory once the initialize
     * response lists any `authMethods`, and clients must not call them when it lists none — which is why the
     * default refuses rather than pretending to succeed.
     */
    public suspend fun login(methodId: AuthMethodId, _meta: JsonElement? = null): LoginAuthResponse =
        notAdvertised("auth/login", "authMethods in the initialize response")

    /** Handles `auth/logout`. See [login]. */
    public suspend fun logout(_meta: JsonElement? = null): LogoutAuthResponse =
        notAdvertised("auth/logout", "authMethods in the initialize response")

    /**
     * Handles `session/list`, one page at a time.
     *
     * Cursors are the agent's to mint: [ListSessionsResponse.nextCursor] set means there is another page, and
     * the client passes it back as `cursor`.
     */
    public suspend fun listSessions(
        cwd: String? = null,
        cursor: String? = null,
        _meta: JsonElement? = null,
    ): ListSessionsResponse = notAdvertised("session/list", "AgentCapabilities.session")

    /**
     * Handles `session/resume`: brings back an existing session so it can be prompted again.
     *
     * v2 has no `session/load`. [replayFrom] says how much history the client wants replayed as
     * `session/update` notifications; a cursor this implementation does not understand should be rejected
     * rather than guessed at.
     */
    public suspend fun resumeSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replayFrom: ReplayFrom?,
        client: ClientOperations,
    ): AgentSession = notAdvertised("session/resume", "SessionCapabilities.resume")

    /**
     * Handles `session/fork`: start a new session from an existing one's history.
     *
     * The returned session owns the **new** id; the one in the request is what is being forked from.
     */
    public suspend fun forkSession(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        client: ClientOperations,
    ): AgentSession = notAdvertised("session/fork", "SessionCapabilities.fork")

    /** Handles `providers/list`, reporting the providers a client may configure. */
    public suspend fun listProviders(_meta: JsonElement? = null): ListProvidersResponse =
        notAdvertised("providers/list", "AgentCapabilities.providers")

    /**
     * Handles `providers/set`, replacing one provider's whole configuration.
     *
     * [headers] is the complete map, not a patch — anything omitted is gone.
     */
    public suspend fun setProvider(
        providerId: ProviderId,
        apiType: LlmProtocol,
        baseUrl: String,
        headers: Map<String, String>,
        _meta: JsonElement? = null,
    ): SetProviderResponse = notAdvertised("providers/set", "AgentCapabilities.providers")

    /**
     * Handles `providers/disable`.
     *
     * Clients must not call it for a provider reported as `required`, so a request for one is worth refusing
     * rather than honouring.
     */
    public suspend fun disableProvider(
        providerId: ProviderId,
        _meta: JsonElement? = null,
    ): DisableProviderResponse = notAdvertised("providers/disable", "AgentCapabilities.providers")

    /** Handles `session/close`: end the session's active work and release it. */
    public suspend fun closeSession(sessionId: SessionId, _meta: JsonElement? = null): CloseSessionResponse =
        notAdvertised("session/close", "AgentCapabilities.session")

    /**
     * Handles `session/delete`: drop the session from what [listSessions] reports.
     *
     * Gated by `SessionCapabilities.delete`, so the default refuses unless an implementation takes it over.
     */
    public suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement? = null): DeleteSessionResponse =
        notAdvertised("session/delete", "SessionCapabilities.delete")
}

/**
 * Refuses a method the agent never advertised.
 *
 * [acpFail] rather than `NotImplementedError`, because an `Error` thrown from a handler is not caught by the
 * dispatcher and would leave the caller waiting for a response that never arrives.
 */
private fun notAdvertised(method: String, capability: String): Nothing = acpFail(
    "$method is not implemented by this agent. Implement it on the v2 AgentSupport if you advertise $capability"
)
