package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithSessionId

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
