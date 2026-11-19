package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.util.SequenceToPaginatedResponseAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Represents an Agent that handles protocol requests and manages sessions.
 *
 * The `Agent` class is responsible for setting up request and notification handlers
 * using the provided `protocol`. It handles session creation, loading, and operations
 * based on client requests. Additionally, it manages client-specific information and
 * ensures proper session lifecycle management.
 *
 * This class serves protocol version 1. Version 2 is a separate
 * [com.agentclientprotocol.agent.v2.Agent]: a connection speaks one version, decided by which of the two
 * was put on it, and the two never share a connection because they claim the same handler names.
 *
 * @property protocol The protocol instance used to set up communication handlers.
 * @property agentSupport An `AgentSupport` instance used for executing core agent operations such as session creation and authentication.
 */
public class Agent(
    public val protocol: Protocol,
    private val agentSupport: AgentSupport,
) {

    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _sessions = atomic(persistentMapOf<SessionId, RootSessionWrapper>())

    /**
     * The subagents registered on this connection, at every depth, by their own session id.
     */
    private val _subagents = atomic(persistentMapOf<SessionId, SubagentSessionWrapper>())

    private val _nesSessions = atomic(persistentMapOf<SessionId, NesSessionWrapper>())

    internal fun getClientInfoOrThrow(): ClientInfo {
        if (!_clientInfo.isCompleted) error("Agent is not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return _clientInfo.getCompleted()
    }

    init {
        setHandlers(protocol)
    }


    /**
     * Serves `initialize`.
     *
     * A request for another version is answered with v1 all the same: the payload is read with v1 types,
     * the only shape this class has ever accepted, and negotiation answers with the version this runtime
     * speaks
     * ([version negotiation](https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation)).
     * A client that cannot speak the answer closes the connection, or retries with an agent for its own
     * version installed on the same one.
     */
    private suspend fun initialize(params: InitializeRequest): InitializeResponse {
        val clientInfo = ClientInfo(params.protocolVersion, params.clientCapabilities, params.clientInfo, params._meta)
        _clientInfo.complete(clientInfo)

        val negotiatedVersion = recordNegotiated(protocol, LATEST_PROTOCOL_VERSION)
        val agentInfo = agentSupport.initialize(clientInfo)
        if (agentInfo.protocolVersion != negotiatedVersion) {
            logger.debug {
                "AgentSupport returned protocol version ${agentInfo.protocolVersion}, but $negotiatedVersion was " +
                    "negotiated with the client (requested ${params.protocolVersion}); the negotiated version is used"
            }
        }
        return InitializeResponse(negotiatedVersion, agentInfo.capabilities, agentInfo.authMethods, agentInfo.implementation, agentInfo._meta)
    }

    /** A repeated `initialize` cannot move the connection: the version recorded first wins. */
    private fun recordNegotiated(protocol: Protocol, negotiated: ProtocolVersion): ProtocolVersion {
        val recorded = protocol.recordNegotiatedProtocolVersion(negotiated)
        if (recorded != negotiated) {
            logger.warn {
                "Repeated initialize request would negotiate $negotiated, but this connection already speaks " +
                    "protocol version $recorded; keeping $recorded"
            }
        }
        return recorded
    }

    @OptIn(UnstableApi::class)
    private fun setHandlers(protocol: Protocol) {
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Initialize) { params: InitializeRequest ->
            return@setRequestHandler initialize(params)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Authenticate) { params: AuthenticateRequest ->
            return@setRequestHandler agentSupport.authenticate(params.methodId, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.Logout) { params: LogoutRequest ->
            return@setRequestHandler agentSupport.logout(params._meta)
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.AuthStatus) { params: AuthStatusRequest ->
            return@setRequestHandler agentSupport.authStatus(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersList) { params: ListProvidersRequest ->
            return@setRequestHandler agentSupport.listProviders(params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersSet) { params: SetProvidersRequest ->
            return@setRequestHandler agentSupport.setProvider(params.id, params.apiType, params.baseUrl, params.headers, params._meta)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.ProvidersDisable) { params: DisableProvidersRequest ->
            return@setRequestHandler agentSupport.disableProvider(params.id, params._meta)
        }

        // TODO: move batchSize to some global agent/client settings
        val sessionListAdapter = SequenceToPaginatedResponseAdapter<SessionInfo, ListSessionsRequest, ListSessionsResponse>(batchSize = 10)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionList) { params: ListSessionsRequest ->
            try {
                return@setRequestHandler agentSupport.listSessions(params.cwd, params.additionalDirectories, params.cursor, params._meta)
            } catch (_: SessionListPagesNotImplemented) {
                return@setRequestHandler sessionListAdapter.next(
                    params = params,
                    sequenceFactory = { p -> agentSupport.listSessions(p.cwd, p.additionalDirectories, p._meta) },
                    resultFactory = { _, batch, newCursor -> ListSessionsResponse(batch, newCursor) }
                )
            }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionDelete) { params: DeleteSessionRequest ->
            checkNotSubagent(params.sessionId)
            return@setRequestHandler agentSupport.deleteSession(params.sessionId, params._meta)
        }

        protocol.setRequestOutcomeHandler(AcpMethod.AgentMethods.V1.SessionNew) { params: NewSessionRequest ->
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val created = createSession(sessionParameters) { agentSupport.createSession(it) }

            @OptIn(UnstableApi::class)
            return@setRequestOutcomeHandler created.mapResponse { session ->
                NewSessionResponse(
                    sessionId = session.sessionId,
                    modes = session.asModeState(),
                    models = session.asModelState(),
                    configOptions = session.asConfigOptionsState()
                )
            }
        }

        protocol.setRequestOutcomeHandler(AcpMethod.AgentMethods.V1.SessionLoad) { params: LoadSessionRequest ->
            checkNotSubagent(params.sessionId)
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val created = createSession(
                sessionParameters,
                replayHistory = { session -> session.replayHistory() },
            ) { agentSupport.loadSession(params.sessionId, sessionParameters) }
            @OptIn(UnstableApi::class)
            return@setRequestOutcomeHandler created.mapResponse { session ->
                LoadSessionResponse(
                    // maybe unify result of these two methods to have sessionId in both
    //                sessionId = session.sessionId,
                    modes = session.asModeState(),
                    models = session.asModelState(),
                    configOptions = session.asConfigOptionsState()
                )
            }
        }

        protocol.setRequestOutcomeHandler(AcpMethod.AgentMethods.V1.SessionResume) { params: ResumeSessionRequest ->
            checkNotSubagent(params.sessionId)
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val created = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestOutcomeHandler created.mapResponse { session ->
                ResumeSessionResponse(
                    modes = session.asModeState(),
                    models = session.asModelState()
                )
            }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetMode) { params: SetSessionModeRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setMode(params.modeId, params._meta)
            }
        }
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetModel) { params: SetSessionModelRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setModel(params.modelId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionPrompt) { params: PromptRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.prompt(params.prompt, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.SessionCancel) { params: CancelNotification ->
            val child = subagentOrNull(params.sessionId)
            if (child != null) {
                child.cancel()
                return@setNotificationHandler
            }
            val session = getSessionOrThrow(params.sessionId)
            session.executeWithSession {
                session.cancel()
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionClose) { params: CloseSessionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            val response = session.executeWithSession {
                session.agentSession.close(params._meta)
            }
            _sessions.update { it.remove(params.sessionId) }
            /*
             * Clean the subtree up before answering, so no update for the children reaches the client after the
             * response. Requests the children have in flight, and their $/cancel_request, may still follow it.
             * If close() threw, the session and its children stay registered.
             */
            closeSubtree(session)
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestOutcomeHandler(AcpMethod.AgentMethods.V1.SessionFork) { params: ForkSessionRequest ->
            checkNotSubagent(params.sessionId)
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val created = createSession(sessionParameters) { agentSupport.forkSession(params.sessionId, sessionParameters) }
            return@setRequestOutcomeHandler created.mapResponse { session ->
                ForkSessionResponse(
                    sessionId = session.sessionId,
                    modes = session.asModeState(),
                    models = session.asModelState(),
                    configOptions = session.asConfigOptionsState()
                )
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestOutcomeHandler(AcpMethod.AgentMethods.V1.SessionResume) { params: ResumeSessionRequest ->
            checkNotSubagent(params.sessionId)
            val sessionParameters = SessionCreationParameters(params.cwd, params.mcpServers, params.additionalDirectories, params._meta)
            val created = createSession(sessionParameters) { agentSupport.resumeSession(params.sessionId, sessionParameters) }
            return@setRequestOutcomeHandler created.mapResponse { session ->
                ResumeSessionResponse(
                    modes = session.asModeState(),
                    models = session.asModelState(),
                    configOptions = session.asConfigOptionsState()
                )
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionSetConfigOption) { params: SetSessionConfigOptionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.agentSession.setConfigOption(params.configId, params.value, params._meta)
            }
        }

        // NES handlers
        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesStart) { params: StartNesRequest ->
            val nesSession = agentSupport.createNesSession(params)
            val wrapper = NesSessionWrapper(
                this@Agent,
                nesSession,
                protocol
            )
            _nesSessions.update { it.put(nesSession.nesSessionId, wrapper) }
            return@setRequestHandler StartNesResponse(nesSession.nesSessionId)
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesSuggest) { params: SuggestNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            return@setRequestHandler wrapper.executeWithSession {
                wrapper.nesSession.suggest(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.NesClose) { params: CloseNesRequest ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            val response = wrapper.executeWithSession {
                wrapper.nesSession.close(params._meta)
            }
            _nesSessions.update { it.remove(params.sessionId) }
            return@setRequestHandler response
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.NesAccept) { params: AcceptNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.accept(params.id, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.NesReject) { params: RejectNesNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.reject(params.id, params.reason, params._meta)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidOpen) { params: DidOpenDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didOpen(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidChange) { params: DidChangeDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didChange(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidClose) { params: DidCloseDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didClose(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidSave) { params: DidSaveDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didSave(params)
            }
        }

        @OptIn(UnstableApi::class)
        protocol.setNotificationHandler(AcpMethod.AgentMethods.V1.DocumentDidFocus) { params: DidFocusDocumentNotification ->
            val wrapper = getNesSessionOrThrow(params.sessionId)
            wrapper.executeWithSession {
                wrapper.nesSession.didFocus(params)
            }
        }
    }

    /**
     * Opens a root session. [replayHistory] is given only for `session/load`: it runs before the response, in the
     * replay window of the new session.
     */
    @OptIn(UnstableApi::class)
    private suspend fun createSession(
        sessionParameters: SessionCreationParameters,
        replayHistory: (suspend (AgentSession) -> Unit)? = null,
        sessionFactory: suspend (SessionCreationParameters) -> AgentSession,
    ): RequestOutcome<AgentSession> {
        val session = sessionFactory(sessionParameters)
        val clientInfo = getClientInfoOrThrow()

        val sessionWrapper = RootSessionWrapper(
            this,
            session,
            RemoteClientSessionOperations(protocol, session.sessionId, clientInfo.capabilities),
            protocol,
            subagentsSupported = clientInfo.capabilities.subagents != null,
            replaying = replayHistory != null,
        )

        /*
         * A root that is already open with this id is replaced, as `session/load` and `session/resume` may re-open
         * it. Its subtree is cleaned up before the replay, so the replay can announce the same ids again, and no
         * update for an old subagent follows the response. The old root's own turn and AgentSession are left as they
         * are. If this load fails later, the old root isn't restored.
         */
        val replaced = _sessions.getAndUpdate { it.put(session.sessionId, sessionWrapper) }[session.sessionId]
        replaced?.let { closeSubtree(it) }

        if (replayHistory != null) {
            try {
                sessionWrapper.executeWithSession { replayHistory(session) }
            } catch (t: Throwable) {
                discardFailedLoad(sessionWrapper)
                throw t
            }
            /*
             * The client treats what follows the load response on the wire as live, so the replay window closes as
             * soon as the transport has accepted the response, not a dispatch later when afterResponse starts. This
             * also starts the replayed subagents' collections before postInitialize(): a slow one would otherwise
             * keep every child's state unconfirmed and its cancel disabled on the client.
             */
            currentCoroutineContext().invokeOnResponseQueued { sessionWrapper.endReplay() }
        }

        return RequestOutcome(
            response = session,
            afterResponse = { sessionWrapper.executeWithSession { session.postInitialize() } },
            onCompletion = {
                // Still replaying here means the load response never reached the transport: the load failed.
                if (sessionWrapper.isReplaying) discardFailedLoad(sessionWrapper)
            },
        )
    }

    /**
     * Drops the root session of a failed `session/load` with every subagent registered under it during the attempt:
     * no response confirmed them.
     */
    private fun discardFailedLoad(sessionWrapper: RootSessionWrapper) {
        val sessionId = sessionWrapper.sessionId
        _sessions.update { sessions ->
            if (sessions[sessionId] === sessionWrapper) sessions.remove(sessionId) else sessions
        }
        closeSubtree(sessionWrapper)
    }

    private fun getSessionOrThrow(sessionId: SessionId): RootSessionWrapper {
        _sessions.value[sessionId]?.let { return it }
        checkNotSubagent(sessionId)
        acpFail("Session $sessionId not found")
    }

    /**
     * Rejects a session method on a subagent's id. The client may only cancel a subagent: every other method is
     * reserved for root sessions.
     */
    private fun checkNotSubagent(sessionId: SessionId) {
        if (sessionId in _subagents.value) acpFail("Session $sessionId is a subagent")
    }

    internal fun subagentOrNull(sessionId: SessionId): SubagentSessionWrapper? = _subagents.value[sessionId]

    /**
     * Registers [child] under its own id.
     *
     * A subagent whose root's subtree is closed is about to be removed by that subtree's cleanup, so its id counts as
     * free: [child] replaces it, and it is removed here.
     *
     * @throws IllegalArgumentException if the id is already registered on this connection, as a root session or a
     * subagent
     * @throws IllegalStateException if the subtree of [child]'s root is closed
     */
    internal fun registerSubagent(child: SubagentSessionWrapper) {
        var replaced: SubagentSessionWrapper? = null
        _subagents.update { subagents ->
            val registered = subagents[child.sessionId]
            replaced = registered?.takeIf { it.subtreeRoot.isSubtreeClosed }
            require((registered == null || replaced != null) && child.sessionId !in _sessions.value) {
                "Session ${child.sessionId} is already registered on this connection"
            }
            /*
             * The root's subtree may have been closed after the caller checked it. The cleanup marks it closed before
             * its own update of the table, so either the cleanup sees this entry, or this check sees the closed
             * subtree.
             */
            check(!child.subtreeRoot.isSubtreeClosed) { "Session ${child.subtreeRoot.sessionId} is closed" }
            subagents.put(child.sessionId, child)
        }
        replaced?.remove()
    }

    internal fun unregisterSubagent(child: SubagentSessionWrapper) {
        _subagents.update { subagents ->
            if (subagents[child.sessionId] === child) subagents.remove(child.sessionId) else subagents
        }
    }

    /**
     * Subtree cleanup for [root]: cancels the collections of every subagent below it, at every depth, removes them
     * from the child table, and makes their operations fail. No app code is called for them: stopping their work
     * belongs to the root's [AgentSession.close].
     */
    private fun closeSubtree(root: RootSessionWrapper) {
        root.closeSubtree()

        var removed = emptyList<SubagentSessionWrapper>()
        _subagents.update { subagents ->
            removed = subagents.values.filter { it.subtreeRoot === root }
            subagents.mutate { table -> removed.forEach { table.remove(it.sessionId) } }
        }
        removed.forEach { it.remove() }
    }

    private fun getNesSessionOrThrow(sessionId: SessionId): NesSessionWrapper = _nesSessions.value[sessionId] ?: acpFail("NES session $sessionId not found")
}

public val CoroutineContext.agent: Agent
    get() = this[SessionWrapperContextElement.Key]?.sessionWrapper?.agent ?: error("No agent data found in context")
/**
 * Returns client info associated with the current protocol. Throws an exception if the agent is still not initialized from the client side.
 */
public val CoroutineContext.clientInfo: ClientInfo
    get() = agent.getClientInfoOrThrow()

/**
 * Returns a remote client connected to the counterpart via the current protocol.
 * Only available for chat sessions. NES sessions do not have access to client operations.
 * In a subagent's context, these are the subagent's operations (see [AgentSubagentContext.client]).
 *
 * @throws IllegalStateException if called from a NES session context or outside a session context
 */
public val CoroutineContext.client: ClientSessionOperations
    get() = sessionWrapper("Client operations").clientOperations
