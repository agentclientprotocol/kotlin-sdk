package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.DisableProvidersResponse
import com.agentclientprotocol.model.ListProvidersResponse
import com.agentclientprotocol.model.LlmProtocol
import com.agentclientprotocol.model.LogoutResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SetProvidersResponse
import com.agentclientprotocol.model.StartNesRequest
import kotlinx.serialization.json.JsonElement

public interface AgentSupport {
    /**
     * Initializes the agent with the client information.
     *
     * Called when a client connects and sends an initialize request.
     *
     * @param clientInfo information about the connecting client
     * @return an [AgentInfo] containing the agent's capabilities and information
     */
    public suspend fun initialize(clientInfo: ClientInfo): AgentInfo

    /**
     * Performs authentication using the specified authentication method.
     *
     * @param methodId the id of the authentication method to use
     * @param _meta optional metadata
     * @return an [AuthenticateResponse] indicating the authentication result
     */
    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse = AuthenticateResponse()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Logs out of the current authenticated state.
     *
     * After a successful logout, all new sessions will require authentication.
     * There is no guarantee about the behavior of already running sessions.
     *
     * @param _meta optional metadata
     * @return a [LogoutResponse] indicating the logout result
     */
    @UnstableApi
    public suspend fun logout(_meta: JsonElement?): LogoutResponse {
        throw NotImplementedError("logout is not implemented. The capability is declared in AgentCapabilities.auth.logout")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Lists providers that can be configured by the client.
     *
     * @param _meta optional metadata
     * @return a [ListProvidersResponse] with configurable providers and current routing info
     */
    @UnstableApi
    public suspend fun listProviders(_meta: JsonElement?): ListProvidersResponse {
        throw NotImplementedError("listProviders is not implemented. The capability is declared in AgentCapabilities.providers")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Replaces the full configuration for one provider id.
     *
     * @param id provider id to configure
     * @param apiType protocol type for this provider
     * @param baseUrl base URL for requests sent through this provider
     * @param headers optional headers map for this provider
     * @param _meta optional metadata
     * @return a [SetProvidersResponse] indicating the update result
     */
    @UnstableApi
    public suspend fun setProvider(
        id: String,
        apiType: LlmProtocol,
        baseUrl: String,
        headers: Map<String, String>?,
        _meta: JsonElement?
    ): SetProvidersResponse {
        throw NotImplementedError("setProvider is not implemented. The capability is declared in AgentCapabilities.providers")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Disables a provider.
     *
     * @param id provider id to disable
     * @param _meta optional metadata
     * @return a [DisableProvidersResponse] indicating the disable result
     */
    @UnstableApi
    public suspend fun disableProvider(id: String, _meta: JsonElement?): DisableProvidersResponse {
        throw NotImplementedError("disableProvider is not implemented. The capability is declared in AgentCapabilities.providers")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Lists existing sessions with optional filtering and pagination.
     * Pagination is automatically handled by [com.agentclientprotocol.util.SequenceToPaginatedResponseAdapter].
     *
     * @param cwd optional current working directory filter
     * @param additionalDirectories optional additional directories filter
     * @param _meta optional metadata
     * @return a sequence of [SessionInfo]
     */
    @UnstableApi
    public suspend fun listSessions(cwd: String?, additionalDirectories: List<String>?, _meta: JsonElement?): Sequence<SessionInfo> {
        throw NotImplementedError("listSessions is not implemented. The capability is declared in AgentCapabilities.sessionCapabilities.list")
    }

    /**
     * Creates a new session with the specified parameters.
     *
     * @param sessionParameters parameters for creating the session
     * @return an [AgentSession] instance for the new session
     */
    public suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession

    /**
     * Loads an existing session with the specified id and parameters.
     *
     * @param sessionId the id of the session to load
     * @param sessionParameters parameters for loading the session
     * @return an [AgentSession] instance for the loaded session
     */
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        throw NotImplementedError("loadSession is not implemented. The capability is declared in AgentCapabilities.loadSession")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Forks an existing session, creating a new session based on the existing session's context.
     *
     * @param sessionId the id of the session to fork
     * @param sessionParameters parameters for the forked session
     * @return an [AgentSession] instance for the forked session
     */
    @UnstableApi
    public suspend fun forkSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        throw NotImplementedError("forkSession is not implemented. The capability is declared in AgentCapabilities.sessionCapabilities.fork")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Resumes an existing session without replaying message history.
     *
     * @param sessionId the id of the session to resume
     * @param sessionParameters parameters for resuming the session
     * @return an [AgentSession] instance for the resumed session
     */
    @UnstableApi
    public suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        throw NotImplementedError("resumeSession is not implemented. The capability is declared in AgentCapabilities.sessionCapabilities.resume")
    }

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Creates a new NES (Next Edit Suggestions) session.
     *
     * NES sessions are independent from chat sessions and have their own lifecycle.
     *
     * @param request the request containing workspace information for the NES session
     * @return a [NesAgentSession] instance for the new NES session
     */
    @UnstableApi
    public suspend fun createNesSession(request: StartNesRequest): NesAgentSession {
        throw NotImplementedError("createNesSession is not implemented. The capability is declared in AgentCapabilities.nes")
    }
}
