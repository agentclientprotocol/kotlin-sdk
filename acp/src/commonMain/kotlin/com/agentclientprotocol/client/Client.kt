@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.HandlerSideExtension
import com.agentclientprotocol.common.RegistrarContext
import com.agentclientprotocol.common.RemoteSideExtension
import com.agentclientprotocol.common.RemoteSideExtensionInstantiation
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    public val protocol: Protocol,
    private val clientSupport: ClientSupport,
    private val handlerSideExtensions: List<HandlerSideExtension<*>> = emptyList(),
    private val remoteSideExtensions: List<RemoteSideExtension<*>> = emptyList(),
) {
    private val _sessions = atomic(persistentMapOf<SessionId, CompletableDeferred<ClientSessionImpl>>())
    private val _clientInfo = CompletableDeferred<ClientInfo>()
    private val _agentInfo = CompletableDeferred<AgentInfo>()

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.executeWithSession {
                session.handlePermissionResponse(params.toolCall, params.options, params._meta)
            }
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.executeWithSession {
                session.handleNotification(params.update, params._meta)
            }
        }

        val registrarContext = object : RegistrarContext<Any> {
            override val rpc: RpcMethodsOperations
                get() = protocol

            override suspend fun <TResult> executeWithSession(
                sessionId: SessionId,
                block: suspend (operations: Any) -> TResult,
            ): TResult {
                val session = getSessionOrThrow(sessionId)
                return session.executeWithSession {
                    block(session.operations)
                }
            }
        }

        for (ex in handlerSideExtensions) {
            ex.doRegister(registrarContext)
        }
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

    public suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement? = null): AuthenticateResponse {
        return AcpMethod.AgentMethods.Authenticate(protocol, AuthenticateRequest(methodId, _meta))
    }

    public suspend fun newSession(sessionParameters: SessionParameters): ClientSession {
        val newSessionResponse = AcpMethod.AgentMethods.SessionNew(protocol,
            NewSessionRequest(
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            )
        )
        val sessionId = newSessionResponse.sessionId
        return createSession(sessionId, sessionParameters, newSessionResponse._meta)
    }

    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): ClientSession {
        val loadSessionResponse = AcpMethod.AgentMethods.SessionLoad(protocol,
            LoadSessionRequest(
                sessionId,
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            ))

        return createSession(sessionId, sessionParameters, loadSessionResponse._meta)
    }

    private suspend fun createSession(sessionId: SessionId, sessionParameters: SessionParameters, _meta: JsonElement?): ClientSession {
        val sessionDeferred = CompletableDeferred<ClientSessionImpl>()
        _sessions.update { it.put(sessionId, sessionDeferred) }
        val agentInfo = agentInfo
        val extensionsMap =
            remoteSideExtensions.filter { it.isSupported(agentInfo.capabilities) }.associateBy(keySelector = { it }) {
                it.createSessionRemote(
                    rpc = protocol,
                    capabilities = agentInfo.capabilities,
                    sessionId = sessionId
                )
            }
        val session = ClientSessionImpl(this, sessionId, sessionParameters, protocol, RemoteSideExtensionInstantiation(extensionsMap)/*, modeState, modelState*/)
        val sessionApi = clientSupport.createClientSession(session, _meta)
        session.setApi(sessionApi)
        sessionDeferred.complete(session)
        return session
    }

    public fun getSession(sessionId: SessionId): ClientSession {
        val completableDeferred = _sessions.value[sessionId] ?: error("Session $sessionId not found")
        if (!completableDeferred.isCompleted) error("Session $sessionId not initialized yet")
        @OptIn(ExperimentalCoroutinesApi::class)
        return completableDeferred.getCompleted()
    }

    private suspend fun getSessionOrThrow(sessionId: SessionId): ClientSessionImpl = (_sessions.value[sessionId] ?: acpFail("Session $sessionId not found")).await()
}