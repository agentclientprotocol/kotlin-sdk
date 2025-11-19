@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

@Deprecated("Use Client instead", ReplaceWith("Client"))
public typealias ClientInstance = Client

/**
 * A client-side connection to an agent.
 *
 * This class provides the client's view of an ACP connection, allowing
 * clients (such as code editors) to communicate with agents. It implements
 * the {@link Agent} to provide methods for initializing sessions, sending
 * prompts, and managing the agent lifecycle.
 *
 * See protocol docs: [Client](https://agentclientprotocol.com/protocol/overview#client)
 */
public class Client(
    public val protocol: Protocol
) {
    private val _sessions = atomic(persistentMapOf<SessionId, CompletableDeferred<ClientSessionImpl>>())
    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _agentInfo = CompletableDeferred<AgentInfo>()
    private val _currentlyInitializingSessionsCount = MutableStateFlow(0)

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.handlePermissionResponse(params.toolCall, params.options, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val fs = session.operations as? FileSystemOperations
                ?: sessionMethodNotFound<FileSystemOperations>(AcpMethod.ClientMethods.FsReadTextFile)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession fs.fsReadTextFile(params.path, params.line, params.limit, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val fs = session.operations as? FileSystemOperations
                ?: sessionMethodNotFound<FileSystemOperations>(AcpMethod.ClientMethods.FsWriteTextFile)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession fs.fsWriteTextFile(params.path, params.content, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.TerminalCreate)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalCreate(params.command, params.args, params.cwd, params.env, params.outputByteLimit, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.TerminalKill)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalKill(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.TerminalOutput)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalOutput(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.TerminalRelease)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalRelease(params.terminalId, params._meta)
            }
        }

        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { params ->
            val session = getSessionOrThrow(params.sessionId)
            val terminal = session.operations as? TerminalOperations
                ?: sessionMethodNotFound<TerminalOperations>(AcpMethod.ClientMethods.TerminalWaitForExit)
            return@setRequestHandler session.executeWithSession {
                return@executeWithSession terminal.terminalWaitForExit(params.terminalId, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.executeWithSession {
                session.handleNotification(params.update, params._meta)
            }
        }
    }

    public val clientInfo: ClientInfo
        get() {
            if (!_clientInfo.isCompleted) error("Client is not initialized yet")
            @OptIn(ExperimentalCoroutinesApi::class)
            return _clientInfo.getCompleted()
        }

    public val agentInfo: AgentInfo
        get() {
            if (!_agentInfo.isCompleted) error("Agent is not initialized yet")
            @OptIn(ExperimentalCoroutinesApi::class)
            return _agentInfo.getCompleted()
        }

    public suspend fun initialize(clientInfo: ClientInfo, _meta: JsonElement? = null): AgentInfo {
        _clientInfo.complete(clientInfo)
        val initializeResponse = AcpMethod.AgentMethods.Initialize(protocol, InitializeRequest(clientInfo.protocolVersion, clientInfo.capabilities, _meta))
        val agentInfo = AgentInfo(initializeResponse.protocolVersion, initializeResponse.agentCapabilities, initializeResponse.authMethods, initializeResponse._meta)
        _agentInfo.complete(agentInfo)
        return agentInfo
    }

    /**
     * Performs authentication of the agent with the specified [methodId].
     * The method may throw an exception if the authentication fails.
     */
    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement? = null): AuthenticateResponse {
        return AcpMethod.AgentMethods.Authenticate(protocol, AuthenticateRequest(methodId, _meta))
    }

    /**
     * Creates a new session with specified [sessionParameters].
     *
     * @param sessionParameters parameters for creating a new session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the new session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the new session
     */
    public suspend fun newSession(sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withInitializingSession {
            val newSessionResponse = AcpMethod.AgentMethods.SessionNew(
                protocol,
                NewSessionRequest(
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters._meta
                )
            )
            val sessionId = newSessionResponse.sessionId
            return@withInitializingSession createSession(sessionId, sessionParameters, newSessionResponse, operationsFactory)
        }
    }

    /**
     * Load an existing session with specified [sessionId] and [sessionParameters].
     *
     * @param sessionId the id of the existing session to load
     * @param sessionParameters parameters for creating a new session
     * @param operationsFactory a factory for creating [com.agentclientprotocol.common.ClientSessionOperations] for the new session.
     * A created object must also implement the necessary interfaces in the case when the client declares extra capabilities like file system or terminal support.
     * See [ClientOperationsFactory.createClientOperations] for more details.
     * @return a [ClientSession] instance for the new session
     */
    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, operationsFactory: ClientOperationsFactory): ClientSession {
        return withInitializingSession {
            val loadSessionResponse = AcpMethod.AgentMethods.SessionLoad(
                protocol,
                LoadSessionRequest(
                    sessionId,
                    sessionParameters.cwd,
                    sessionParameters.mcpServers,
                    sessionParameters._meta
                )
            )
            return@withInitializingSession createSession(sessionId, sessionParameters, loadSessionResponse, operationsFactory)
        }
    }

    private suspend fun createSession(sessionId: SessionId, sessionParameters: SessionCreationParameters, sessionResponse: AcpCreatedSessionResponse, factory: ClientOperationsFactory): ClientSession {
        val sessionDeferred = CompletableDeferred<ClientSessionImpl>()
        return runCatching {
            _sessions.update { it.put(sessionId, sessionDeferred) }

            val operations = factory.createClientOperations(sessionId, sessionResponse)

            val session = ClientSessionImpl(this, sessionId, sessionParameters, operations, sessionResponse, protocol)
            sessionDeferred.complete(session)
            session
        }.getOrElse {
            sessionDeferred.completeExceptionally(IllegalStateException("Failed to create session $sessionId", it))
            _sessions.update { it.remove(sessionId) }
            throw it
        }
    }

    public fun getSession(sessionId: SessionId): ClientSession {
        val completableDeferred = _sessions.value[sessionId] ?: error("Session $sessionId not found")
        if (!completableDeferred.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return completableDeferred.getCompleted()
    }

    private suspend fun getSessionOrThrow(sessionId: SessionId): ClientSessionImpl {
        _sessions.value[sessionId]?.let {
            return it.await()
        }
        // try to wait for all pending sessions to initialize
        _currentlyInitializingSessionsCount.first { it == 0 }
        // try to get the session again
        _sessions.value[sessionId]?.let {
            return it.await()
        }
        acpFail("Session $sessionId not found")
    }

    private suspend fun<T> withInitializingSession(block: suspend () -> T): T {
        _currentlyInitializingSessionsCount.update { it + 1 }
        try {
            return block()
        } finally {
            _currentlyInitializingSessionsCount.update { it - 1 }
        }
    }
}

private inline fun <reified TInterface> sessionMethodNotFound(method: AcpMethod): Nothing {
    jsonRpcMethodNotFound("Session object does not implement ${TInterface::class.simpleName} to handle method $method")
}