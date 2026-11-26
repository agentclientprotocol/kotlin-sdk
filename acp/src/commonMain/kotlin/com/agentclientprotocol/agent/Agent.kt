package com.agentclientprotocol.agent

import com.agentclientprotocol.agent.internal.NesSessionWrapper
import com.agentclientprotocol.agent.internal.RemoteClientSessionOperations
import com.agentclientprotocol.agent.internal.RootSessionWrapper
import com.agentclientprotocol.agent.internal.SessionWrapperContextElement
import com.agentclientprotocol.agent.internal.SubagentSessionWrapper
import com.agentclientprotocol.agent.internal.sessionWrapper
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.util.SequenceToPaginatedResponseAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /**
     * The root sessions and subagents of this connection, at every depth, by session id.
     *
     * One snapshot, so each rule that spans roots and subagents is checked and applied in one update.
     */
    private val _sessions = atomic(SessionTable(persistentMapOf(), persistentMapOf()))

    private class SessionTable(
        val roots: PersistentMap<SessionId, RootSessionWrapper>,
        val subagents: PersistentMap<SessionId, SubagentSessionWrapper>,
    ) {
        fun subagentsOf(root: RootSessionWrapper): List<SubagentSessionWrapper> =
            subagents.values.filter { it.root === root }

        fun withoutSubagents(removed: List<SubagentSessionWrapper>): PersistentMap<SessionId, SubagentSessionWrapper> =
            subagents.mutate { map -> removed.forEach { map.remove(it.sessionId) } }
    }

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

        protocol.setRequestHandler(AcpMethod.AgentMethods.V1.SessionClose) { params: CloseSessionRequest ->
            /*
             * Closing is idempotent: an unknown or closed id gets the empty result without calling the app
             * (https://agentclientprotocol.com/protocol/v1/session-setup#closing-active-sessions). A subagent's id is
             * still rejected.
             */
            val session = _sessions.value.roots[params.sessionId] ?: run {
                checkNotSubagent(params.sessionId)
                return@setRequestHandler CloseSessionResponse()
            }
            val response = session.executeWithSession {
                session.agentSession.close(params._meta)
            }
            // Before the response, so no update of a child follows it.
            removeRoot(session)
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
         * Re-opening an open id, as `session/load` and `session/resume` may, replaces its root. The old subtree is
         * cleaned up before the replay, which may announce the same ids again; the old root's turn and AgentSession
         * are left alone.
         */
        var replaced: RootSessionWrapper? = null
        var removed = emptyList<SubagentSessionWrapper>()
        _sessions.update { table ->
            check(sessionWrapper.sessionId !in table.subagents) {
                "Session ${sessionWrapper.sessionId} is already registered on this connection as a subagent"
            }
            replaced = table.roots[sessionWrapper.sessionId]
            removed = replaced?.let { table.subagentsOf(it) }.orEmpty()
            SessionTable(table.roots.put(sessionWrapper.sessionId, sessionWrapper), table.withoutSubagents(removed))
        }
        replaced?.let { cleanUpSubtree(it, removed) }

        if (replayHistory != null) {
            try {
                sessionWrapper.executeWithSession { replayHistory(session) }
            } catch (t: Throwable) {
                discardFailedLoad(sessionWrapper)
                throw t
            }
        }

        return RequestOutcome(
            response = session,
            afterResponse = { sessionWrapper.executeWithSession { session.postInitialize() } },
            onCompletion = {
                // Still replaying here means the load response never reached the transport: the load failed.
                if (sessionWrapper.isReplaying) discardFailedLoad(sessionWrapper)
            },
            /*
             * The client treats what follows the load response as live, so the window closes once the transport has
             * the response, not when afterResponse starts, and the replayed subagents' collections start before a
             * slow postInitialize() could hold them back.
             */
            onResponseQueued = if (replayHistory != null) {
                { sessionWrapper.endReplay() }
            } else {
                null
            },
        )
    }

    /**
     * Drops the root of a failed `session/load` with every subagent registered during it.
     */
    private fun discardFailedLoad(sessionWrapper: RootSessionWrapper) {
        removeRoot(sessionWrapper)
    }

    private fun getSessionOrThrow(sessionId: SessionId): RootSessionWrapper {
        _sessions.value.roots[sessionId]?.let { return it }
        checkNotSubagent(sessionId)
        acpFail("Session $sessionId not found")
    }

    /**
     * Rejects a session method on a subagent's id: the client may only cancel a subagent.
     */
    private fun checkNotSubagent(sessionId: SessionId) {
        if (sessionId in _sessions.value.subagents) acpFail("Session $sessionId is a subagent")
    }

    internal fun subagentOrNull(sessionId: SessionId): SubagentSessionWrapper? = _sessions.value.subagents[sessionId]

    /**
     * Registers [child] under its own id while its root is registered.
     *
     * @throws IllegalArgumentException if the id is already registered on this connection, as a root session or a
     * subagent
     * @throws IllegalStateException if [child]'s root is no longer registered: it was closed, its `session/load`
     * failed, or a re-opening of its id replaced it
     */
    internal fun registerSubagent(child: SubagentSessionWrapper) {
        val root = child.root
        _sessions.update { table ->
            check(table.roots[root.sessionId] === root) { "Session ${root.sessionId} is closed" }
            require(child.sessionId !in table.roots && child.sessionId !in table.subagents) {
                "Session ${child.sessionId} is already registered on this connection"
            }
            SessionTable(table.roots, table.subagents.put(child.sessionId, child))
        }
    }

    /**
     * Removes [child] if it is still the subagent registered under its id.
     */
    internal fun unregisterSubagent(child: SubagentSessionWrapper) {
        _sessions.update { table ->
            if (table.subagents[child.sessionId] !== child) return@update table
            SessionTable(table.roots, table.subagents.remove(child.sessionId))
        }
    }

    /**
     * Removes [root] with every subagent below it, and cleans its subtree up. A root that replaced [root] stays.
     */
    private fun removeRoot(root: RootSessionWrapper) {
        var removed = emptyList<SubagentSessionWrapper>()
        _sessions.update { table ->
            removed = table.subagentsOf(root)
            val roots = if (table.roots[root.sessionId] === root) table.roots.remove(root.sessionId) else table.roots
            SessionTable(roots, table.withoutSubagents(removed))
        }
        cleanUpSubtree(root, removed)
    }

    /**
     * Cancels the collections of [root]'s subagents, [removed] from the table already, and makes their operations
     * fail, without calling app code.
     */
    private fun cleanUpSubtree(root: RootSessionWrapper, removed: List<SubagentSessionWrapper>) {
        root.cancelSubtreeScope()
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
