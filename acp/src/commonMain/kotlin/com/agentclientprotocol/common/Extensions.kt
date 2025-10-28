package com.agentclientprotocol.common

import com.agentclientprotocol.model.AcpCapabilities
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithSessionId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.jsonRpcMethodNotFound
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

public interface Extension {
    public val name: String
}

public interface HandlerSideExtension<T : Any> : Extension {
    public fun doRegister(registrarContext: RegistrarContext<*>) {
        @Suppress("UNCHECKED_CAST")
        with(registrarContext as RegistrarContext<T>) {
            register()
        }
    }

    public fun RegistrarContext<T>.register()
}

public interface RemoteSideExtension<T : Any> : Extension {
    public fun isSupported(remoteSideCapabilities: AcpCapabilities): Boolean
    public fun createSessionRemote(rpc: RpcMethodsOperations, capabilities: AcpCapabilities, sessionId: SessionId): T
}

internal class RemoteSideExtensionInstantiation(val extensionsMap: Map<RemoteSideExtension<*>, Any>)

// Type parameter is fictive, it's only necessary to make type inference work in the implementations of [HandlerExtension]
public interface RegistrarContext<@Suppress("unused") TInterfaceFictive> {
    public val rpc: RpcMethodsOperations
    public suspend fun <TResult> executeWithSession(sessionId: SessionId, block: suspend (operations: Any) -> TResult): TResult
}

public inline fun<reified TRequest, reified TResponse : AcpResponse, reified TInterface> RegistrarContext<TInterface>.setSessionExtensionRequestHandler(
    method: AcpMethod.AcpSessionRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (operations: TInterface, params: TRequest) -> TResponse
) where TRequest : AcpRequest, TRequest : AcpWithSessionId {
    this.rpc.setRequestHandler(method, additionalContext) { request ->
        val sessionId = request.sessionId
       return@setRequestHandler executeWithSession(sessionId) { operations ->
            operations as? TInterface ?: jsonRpcMethodNotFound(
                message = "Session object '$operations' does not implement extension type ${TInterface::class.simpleName} to handle method '$method'"
            )
            handler(operations, request)
        }
    }
}

public inline fun<reified TNotification, reified TInterface> RegistrarContext<TInterface>.setSessionExtensionNotificationHandler(
    method: AcpMethod.AcpSessionNotificationMethod<TNotification>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (operations: TInterface, params: TNotification) -> Unit
) where TNotification : AcpNotification, TNotification : AcpWithSessionId {
    this.rpc.setNotificationHandler(method, additionalContext) { request ->
        val sessionId = request.sessionId
        return@setNotificationHandler executeWithSession(sessionId) { operations ->
            operations as? TInterface ?: jsonRpcMethodNotFound(
                message = "Session object $operations does not implement extension type ${TInterface::class.simpleName} to handle method '$method'"
            )
            handler(operations, request)
        }
    }
}

internal class SessionExtensionsContextElement(val extensions: RemoteSideExtensionInstantiation) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionExtensionsContextElement>
}

internal fun RemoteSideExtensionInstantiation.asContextElement(): SessionExtensionsContextElement = SessionExtensionsContextElement(this)

public fun <T : Any> CoroutineContext.remoteSessionOperations(extension: RemoteSideExtension<T>): T {
    val extensionsContextElement = this[SessionExtensionsContextElement.Key] ?: error("No extensions context element found in context")
    val extensions = extensionsContextElement.extensions

    return extensions.remoteSessionOperations(extension)
}

internal fun <T : Any> RemoteSideExtensionInstantiation.remoteSessionOperations(
    extension: RemoteSideExtension<T>,
): T {
    @Suppress("UNCHECKED_CAST")
    val operations = extensionsMap[extension] as? T
        ?: error("Remote-side extension '${extension.name}' is either not registered or it's not supported by capabilities of the remote side")
    @Suppress("UNCHECKED_CAST")
    return operations
}