package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CompleteElicitationNotification
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import com.agentclientprotocol.model.v2.ElicitationMode
import com.agentclientprotocol.model.v2.PermissionOption
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.RequestPermissionSubject
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonElement

/**
 * [ClientOperations] over the wire: the agent's side of the client-facing v2 methods.
 *
 * For `session/new` and `session/fork` the session id arrives after construction, because the implementation
 * chooses it inside the call and the operations object has to exist before that call to be handed to it.
 * `session/resume` is the other way round: the client names the session, so [sessionId] is passed in and the
 * implementation can replay history while it is still resuming — which is where ACP wants that replay, before
 * the response.
 */
@UnstableApi
internal class RemoteClientOperations(
    private val rpc: RpcMethodsOperations,
    sessionId: SessionId? = null,
) : ClientOperations {
    private val _sessionId = atomic(sessionId)

    private val sessionId: SessionId
        get() = _sessionId.value ?: error("The session is not registered yet, so it cannot talk to the client")

    fun bindTo(sessionId: SessionId) {
        _sessionId.value = sessionId
    }

    override fun notify(update: SessionUpdate, _meta: JsonElement?) {
        AcpMethod.ClientMethods.V2.SessionUpdate(rpc, UpdateSessionNotification(sessionId, update, _meta))
    }

    override suspend fun createElicitation(
        message: String,
        mode: ElicitationMode,
        _meta: JsonElement?,
    ): CreateElicitationResponse = AcpMethod.ClientMethods.V2.ElicitationCreate(
        rpc,
        CreateElicitationRequest(message = message, mode = mode, _meta = _meta)
    )

    override fun completeElicitation(elicitationId: ElicitationId, _meta: JsonElement?) {
        AcpMethod.ClientMethods.V2.ElicitationComplete(
            rpc,
            CompleteElicitationNotification(elicitationId, _meta)
        )
    }

    override suspend fun requestPermission(
        title: String,
        options: List<PermissionOption>,
        subject: RequestPermissionSubject?,
        description: String?,
        _meta: JsonElement?,
    ): RequestPermissionResponse = AcpMethod.ClientMethods.V2.SessionRequestPermission(
        rpc,
        RequestPermissionRequest(
            sessionId = sessionId,
            title = title,
            options = options,
            description = description,
            subject = subject,
            _meta = _meta,
        )
    )
}
