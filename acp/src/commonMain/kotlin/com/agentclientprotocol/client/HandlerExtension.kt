package com.agentclientprotocol.client

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithSessionId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.HandlersOwner
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

public interface HandlerExtension<T> {
    public fun doRegister(registrarContext: RegistrarContext<*>) {
        @Suppress("UNCHECKED_CAST")
        with(registrarContext as RegistrarContext<T>) {
            register()
        }
    }

    public fun RegistrarContext<T>.register()
}

// Type parameter is fictive, it's only necessary to make type inference work in the implementations of [HandlerExtension]
@Suppress("unused")
public interface RegistrarContext<T> {
    public val handlers: HandlersOwner
    public fun getSessionExtensibleObject(sessionId: SessionId): Any?
}

public inline fun<reified TRequest, reified TResponse : AcpResponse, reified TInterface> RegistrarContext<TInterface>.setSessionExtensionRequestHandler(
    method: AcpMethod.AcpSessionRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (operations: TInterface, params: TRequest) -> TResponse
) where TRequest : AcpRequest, TRequest : AcpWithSessionId {
    this.handlers.setRequestHandler(method, additionalContext) { request ->
        val sessionId = request.sessionId
//        val session = client.getSession(sessionId) ?: acpFail("Session $sessionId not found")
        val operations = getSessionExtensibleObject(sessionId) as? TInterface ?: throw JsonRpcException(
            code = JsonRpcErrorCode.METHOD_NOT_FOUND,
            message = "Session $sessionId does not implement extension type ${TInterface::class.simpleName} to handle method '$method'"
        )
        return@setRequestHandler handler(operations, request)
    }
}
