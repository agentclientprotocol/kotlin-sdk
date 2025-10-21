@file:Suppress("unused")

package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.*
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    private val extensions: List<HandlerExtension<*>> = emptyList()
) {
    private val _sessions = atomic(persistentMapOf<SessionId, ClientSessionImpl>())

    init {
        // Set up request handlers for incoming agent requests
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { params: RequestPermissionRequest ->
            val session = getSessionOrThrow(params.sessionId)
            return@setRequestHandler session.handlePermissionResponse(params.toolCall, params.options, params._meta)
        }

        protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { params: SessionNotification ->
            val session = getSessionOrThrow(params.sessionId)
            session.handleNotification(params.update, params._meta)
        }

        val registrarContext = object : RegistrarContext<Any> {
            override val handlers: HandlersOwner
                get() = protocol

            override fun getSessionExtensibleObject(sessionId: SessionId): Any? {
                return getSessionOrThrow(sessionId).operations
            }
        }

        for (ex in extensions) {
            ex.doRegister(registrarContext)
        }

        // should be handled by extensions
//        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile, coroutineContext = remoteAgent.asContextElement()) { params: ReadTextFileRequest ->
//            client.fsReadTextFile(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile, coroutineContext = remoteAgent.asContextElement()) { params: WriteTextFileRequest ->
//            client.fsWriteTextFile(params)
//        }
//
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate, coroutineContext = remoteAgent.asContextElement()) { params: CreateTerminalRequest ->
//            client.terminalCreate(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput, coroutineContext = remoteAgent.asContextElement()) { params: TerminalOutputRequest ->
//            client.terminalOutput(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease, coroutineContext = remoteAgent.asContextElement()) { params: ReleaseTerminalRequest ->
//            client.terminalRelease(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit, coroutineContext = remoteAgent.asContextElement()) { params: WaitForTerminalExitRequest ->
//            client.terminalWaitForExit(params)
//        }
//
//        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill, coroutineContext = remoteAgent.asContextElement()) { params: KillTerminalCommandRequest ->
//            client.terminalKill(params)
//        }
    }

    public suspend fun initialize(clientInfo: ClientInfo, _meta: JsonElement? = null): AgentInfo {
        val initializeResponse = AcpMethod.AgentMethods.Initialize(protocol, InitializeRequest(clientInfo.protocolVersion, clientInfo.capabilities, _meta))
        val agentInfo = AgentInfo(initializeResponse.protocolVersion, initializeResponse.agentCapabilities, initializeResponse.authMethods, initializeResponse._meta)
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

        val session = ClientSessionImpl(newSessionResponse.sessionId, sessionParameters, protocol/*, modeState, modelState*/)
        val sessionApi = clientSupport.createClientSession(session, newSessionResponse._meta)
        session.setApi(sessionApi)
        _sessions.update { it.put(newSessionResponse.sessionId, session) }
        return session
    }

    public suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionParameters): ClientSession {
        val loadSessionResponse = AcpMethod.AgentMethods.SessionLoad(protocol,
            LoadSessionRequest(
                sessionId,
                sessionParameters.cwd,
                sessionParameters.mcpServers,
                sessionParameters._meta
            ))
        val session = ClientSessionImpl(sessionId, sessionParameters, protocol/*, modeState, modelState*/)

        val sessionApi = clientSupport.createClientSession(session, loadSessionResponse._meta)
        session.setApi(sessionApi)
        _sessions.update { it.put(sessionId, session) }
        return session
    }

    public fun getSession(sessionId: SessionId): ClientSession? = _sessions.value[sessionId]

    private fun getSessionOrThrow(sessionId: SessionId): ClientSessionImpl = _sessions.value[sessionId] ?: acpFail("Session $sessionId not found")

}

// TODO: remove?

public inline fun<reified TRequest, reified TResponse : AcpResponse> Client.setSessionRequestHandler(
    method: AcpMethod.AcpSessionRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (ClientSession, TRequest) -> TResponse
) where TRequest : AcpRequest, TRequest : AcpWithSessionId {
    this.protocol.setRequestHandler(method, additionalContext) { request ->
        val sessionId = request.sessionId
        val session = getSession(sessionId) ?: acpFail("Session $sessionId not found")
        return@setRequestHandler handler(session, request)
    }
}

// TODO: remove?
public inline fun<reified TRequest, reified TResponse : AcpResponse, reified TInterface> Client.setSessionExtensionRequestHandler(
    method: AcpMethod.AcpSessionRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (operations: TInterface, params: TRequest) -> TResponse
) where TRequest : AcpRequest, TRequest : AcpWithSessionId {
    this.protocol.setRequestHandler(method, additionalContext) { request ->
        val sessionId = request.sessionId
        val session = getSession(sessionId) ?: acpFail("Session $sessionId not found")
        val operations = session.operations as? TInterface ?: throw JsonRpcException(
            code = JsonRpcErrorCode.METHOD_NOT_FOUND,
            message = "Session $sessionId does not implement extension type ${TInterface::class.simpleName} to handle method '$method'"
        )
        return@setRequestHandler handler(operations, request)
    }
}
