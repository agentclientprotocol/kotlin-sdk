package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.ClientInfo
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CancelSessionNotification
import com.agentclientprotocol.model.v2.CloseSessionRequest
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.DeleteSessionRequest
import com.agentclientprotocol.model.v2.DisableProviderRequest
import com.agentclientprotocol.model.v2.ForkSessionRequest
import com.agentclientprotocol.model.v2.ForkSessionResponse
import com.agentclientprotocol.model.v2.InitializeResponse
import com.agentclientprotocol.model.v2.ListProvidersRequest
import com.agentclientprotocol.model.v2.ListSessionsRequest
import com.agentclientprotocol.model.v2.LoginAuthRequest
import com.agentclientprotocol.model.v2.LogoutAuthRequest
import com.agentclientprotocol.model.v2.NewSessionRequest
import com.agentclientprotocol.model.v2.NewSessionResponse
import com.agentclientprotocol.model.v2.PromptRequest
import com.agentclientprotocol.model.v2.PromptResponse
import com.agentclientprotocol.model.v2.ResumeSessionRequest
import com.agentclientprotocol.model.v2.ResumeSessionResponse
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SetProviderRequest
import com.agentclientprotocol.model.v2.SetSessionConfigOptionRequest
import com.agentclientprotocol.model.v2.SetSessionConfigOptionResponse
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.executeAfterCurrentRequest
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.protocol.readProtocolVersionOrNull
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.ACPJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private val logger = KotlinLogging.logger {}

/**
 * **UNSTABLE**
 *
 * An agent that speaks protocol version 2.
 *
 * A connection speaks one version, and which one is decided by the class that was put on it: this one
 * serves the v2 method inventory and refuses an `initialize` for any other version. Nothing is shared with
 * [com.agentclientprotocol.agent.Agent] but the [protocol] underneath — the two never run on the same
 * connection, because both claim the same handler names.
 *
 * ```kotlin
 * val agent = Agent(protocol, myV2Support)
 * ```
 *
 * @property protocol the protocol instance whose handlers this agent installs.
 * @property agentSupport the implementation this agent serves requests from.
 */
@UnstableApi
public class Agent(
    public val protocol: Protocol,
    private val agentSupport: AgentSupport,
) {
    /**
     * One v2 session and the turn currently running in it.
     *
     * A v2 turn is a stream of updates with no response payload, so this only has to forward updates and
     * be cancellable — there is no stop reason to carry back, unlike v1's session wrapper.
     */
    private class SessionWrapper(private val session: AgentSession) {
        private val _activePrompt = atomic(false)

        suspend fun acceptPrompt(protocol: Protocol, content: List<ContentBlock>, _meta: JsonElement?) {
            if (!_activePrompt.compareAndSet(expect = false, update = true)) {
                acpFail("There is already active prompt execution")
            }

            val updates = try {
                session.prompt(content, _meta)
            } catch (t: Throwable) {
                _activePrompt.value = false
                throw t
            }

            currentCoroutineContext().executeAfterCurrentRequest {
                try {
                    updates.collect { update ->
                        AcpMethod.ClientMethods.V2.SessionUpdate(
                            protocol,
                            UpdateSessionNotification(session.sessionId, update, _meta)
                        )
                    }
                } finally {
                    _activePrompt.value = false
                }
            }
        }

        /**
         * Tells the session to stop, and then gets out of the way.
         *
         * Deliberately *not* cancelling the running turn, unlike the v1 wrapper. v2 puts the report of a
         * cancelled turn in the implementation's hands: after aborting its work it MUST send an idle
         * `state_update` with the `cancelled` stop reason, and it MAY send further updates before that
         * one ([prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation)).
         * Killing the flow here would make both impossible.
         */
        suspend fun cancel() {
            session.cancel()
        }

        suspend fun setConfigOption(
            configId: SessionConfigId,
            value: SessionConfigOptionValue,
            _meta: JsonElement?,
        ): List<SessionConfigOption> = session.setConfigOption(configId, value, _meta)
    }

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, SessionWrapper>())

    /**
     * What the connecting client reported in `initialize`.
     *
     * Completes when the client sends `initialize`.
     */
    public val clientInfo: Deferred<ClientInfo>
        get() = _clientInfo

    init {
        setHandlers()
    }

    /**
     * Serves `initialize`.
     *
     * Raw, because the version has to be read before the payload can be trusted: v2's `info` is required,
     * so decoding another version's request with v2 types would fail as a serialization error instead of
     * saying which version this agent speaks. A client that also speaks v1 can retry on the same
     * connection with a v1 agent installed on it.
     */
    private suspend fun initialize(rawParams: JsonElement): JsonElement {
        val requested = readProtocolVersionOrNull(rawParams)
            ?: jsonRpcInvalidParams("initialize is missing the required `protocolVersion` field")
        if (requested != PROTOCOL_VERSION_V2) {
            acpFail("Protocol version $requested is not supported by this agent, which speaks only $PROTOCOL_VERSION_V2")
        }

        val method = AcpMethod.AgentMethods.V2.Initialize
        val params = ACPJson.decodeFromJsonElement(method.requestSerializer, rawParams)
        val clientInfo = ClientInfo(params.protocolVersion, params.info, params.capabilities, params._meta)
        _clientInfo.complete(clientInfo)

        // A repeated initialize cannot move the connection: the version recorded first wins.
        val negotiated = protocol.recordNegotiatedProtocolVersion(PROTOCOL_VERSION_V2)
        if (negotiated != PROTOCOL_VERSION_V2) {
            acpFail("Connection is already initialized with protocol version $negotiated")
        }

        val agentInfo = agentSupport.initialize(clientInfo)
        val response = InitializeResponse(
            protocolVersion = negotiated,
            info = agentInfo.implementation,
            capabilities = agentInfo.capabilities,
            authMethods = agentInfo.authMethods,
            _meta = agentInfo._meta,
        )
        return ACPJson.encodeToJsonElement(method.responseSerializer, response)
    }

    private fun setHandlers() {
        protocol.setRequestHandlerRaw(AcpMethod.AgentMethods.V2.Initialize) { request ->
            initialize(request.params ?: JsonNull)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionNew) { params: NewSessionRequest ->
            // Bound to the session below, so an implementation cannot ask on behalf of another one.
            val clientOperations = RemoteClientOperations(protocol)
            val session = agentSupport.createSession(
                SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta),
                clientOperations,
            )
            clientOperations.bindTo(session.sessionId)
            register(session)
            return@setRequestHandler NewSessionResponse(
                sessionId = session.sessionId,
                configOptions = session.configOptions,
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionPrompt) { params: PromptRequest ->
            val wrapper = getSessionOrThrow(params.sessionId)
            wrapper.acceptPrompt(protocol, params.prompt, params._meta)
            // v2 says nothing about the turn here: how it ended went out as a StateUpdate.Idle update.
            return@setRequestHandler PromptResponse()
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionResume) { params: ResumeSessionRequest ->
            val clientOperations = RemoteClientOperations(protocol)
            val session = agentSupport.resumeSession(
                params.sessionId,
                SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta),
                params.replayFrom,
                clientOperations,
            )
            clientOperations.bindTo(session.sessionId)
            register(session)
            return@setRequestHandler ResumeSessionResponse(configOptions = session.configOptions)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionFork) { params: ForkSessionRequest ->
            val clientOperations = RemoteClientOperations(protocol)
            val session = agentSupport.forkSession(
                params.sessionId,
                SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta),
                clientOperations,
            )
            clientOperations.bindTo(session.sessionId)
            register(session)
            // The new session's id, not the one that was forked from.
            return@setRequestHandler ForkSessionResponse(
                sessionId = session.sessionId,
                configOptions = session.configOptions,
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionSetConfigOption) { params: SetSessionConfigOptionRequest ->
            val wrapper = getSessionOrThrow(params.sessionId)
            return@setRequestHandler SetSessionConfigOptionResponse(
                configOptions = wrapper.setConfigOption(params.configId, params.value, params._meta)
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionClose) { params: CloseSessionRequest ->
            val response = agentSupport.closeSession(params.sessionId, params._meta)
            // A closed session cannot be prompted again, so stop tracking it here rather than leaving a
            // wrapper around that would accept a turn.
            _sessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionDelete) { params: DeleteSessionRequest ->
            val response = agentSupport.deleteSession(params.sessionId, params._meta)
            _sessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.V2.SessionCancel) { params: CancelSessionNotification ->
            _sessions.value[params.sessionId]?.cancel()
                ?: logger.warn { "Received session/cancel for unknown session ${params.sessionId}" }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.SessionList) { params: ListSessionsRequest ->
            return@setRequestHandler agentSupport.listSessions(params.cwd, params.cursor, params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.AuthLogin) { params: LoginAuthRequest ->
            return@setRequestHandler agentSupport.login(params.methodId, params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.AuthLogout) { params: LogoutAuthRequest ->
            return@setRequestHandler agentSupport.logout(params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.ProvidersList) { params: ListProvidersRequest ->
            return@setRequestHandler agentSupport.listProviders(params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.ProvidersSet) { params: SetProviderRequest ->
            return@setRequestHandler agentSupport.setProvider(
                params.providerId,
                params.apiType,
                params.baseUrl,
                params.headers,
                params._meta,
            )
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V2.ProvidersDisable) { params: DisableProviderRequest ->
            return@setRequestHandler agentSupport.disableProvider(params.providerId, params._meta)
        }
    }

    private fun register(session: AgentSession) {
        _sessions.update { it.put(session.sessionId, SessionWrapper(session)) }
    }

    private fun getSessionOrThrow(sessionId: SessionId): SessionWrapper =
        _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")
}
