package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithSessionId
import kotlin.coroutines.CoroutineContext

/**
 * Sends a typed request like [RpcMethodsOperations.sendRequest], and calls [onResponse] with the decoded response
 * when it arrives.
 *
 * The response is decoded once, in the read loop: [onResponse] gets the value this call returns. It runs with the
 * timing and the contract of the `onResponse` hook of [Protocol.sendRequestRaw], so it must not suspend, block, or
 * throw. A response that does not decode skips [onResponse], and this call fails with the same error as
 * [RpcMethodsOperations.sendRequest].
 */
internal suspend fun <TRequest : AcpRequest, TResponse : AcpResponse> Protocol.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?,
    onResponse: (TResponse) -> Unit,
): TResponse {
    val sessionId = (request as? AcpWithSessionId)?.sessionId
    var decoded: Result<TResponse>? = null

    sendRequestRaw(method.methodName, method.encodeRequestParams(request), sessionId) { result ->
        val response = runCatching { method.decodeResponseResult(result) }
        decoded = response
        response.onSuccess(onResponse)
    }

    // A normal return means a success response completed the call, and the hook always runs before that.
    return checkNotNull(decoded) { "The response of ${method.methodName} was not observed" }.getOrThrow()
}

/**
 * Calls [hook] once the transport has accepted the frame carrying a success response to the incoming request this
 * context serves.
 *
 * [hook] runs synchronously in the coroutine that queues the frame, right after the transport accepted it: before
 * the request's after-response work starts, and before any other work waiting for that frame resumes. So a message
 * sent after [hook] runs follows the response on the wire. A message another thread sends in the few instructions
 * between the hand-off and [hook] follows it too, although it was sent before [hook] ran.
 *
 * [hook] is not called if the request is answered with an error, or if the response frame does not reach the
 * transport. It must not suspend, block, or throw; a hook that throws is logged. A request has at most one hook.
 *
 * @throws IllegalStateException outside a request handler, or if the request already has a hook
 */
internal fun CoroutineContext.invokeOnResponseQueued(hook: () -> Unit) {
    val element = this[JsonRpcRequestContextElement.Key] ?: error("There is no active incoming request in this context")
    element.slot.invokeOnSuccessQueued(hook)
}
