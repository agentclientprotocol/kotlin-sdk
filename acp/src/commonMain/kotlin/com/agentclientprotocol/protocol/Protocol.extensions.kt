package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithSessionId

/**
 * Sends a typed request like [RpcMethodsOperations.sendRequest], and calls [onResponse] with the decoded response,
 * the value this call returns.
 *
 * [onResponse] is the response hook of [Protocol.sendRequestRaw], with its timing and contract. A response that
 * doesn't decode skips it, and this call fails as [RpcMethodsOperations.sendRequest] would.
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
