package com.agentclientprotocol.agent.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import com.agentclientprotocol.model.v2.ElicitationAction
import com.agentclientprotocol.model.v2.ElicitationMode
import com.agentclientprotocol.model.v2.PermissionOption
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.RequestPermissionSubject
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * What a v2 session can ask of the client it is talking to.
 *
 * Implemented by the SDK; a session receives it in
 * [AgentSupport.createSession] rather than reaching for a coroutine-context element, so it is visible
 * in the signature of anything that needs it.
 */
@UnstableApi
public interface ClientOperations {
    /**
     * Sends `session/update` for this session, including while no prompt is running.
     *
     * Available once the session has an id: from anywhere for `session/new` and `session/fork` once creation
     * has returned, and inside `AgentSupport.resumeSession` as well, so history can be replayed before
     * `session/resume` answers. For configuration changes, send
     * [SessionUpdate.ConfigOptionUpdate] with the complete current option list, including dependent
     * changes. Keep [AgentSession.configOptions] consistent with that list for subsequent session setup.
     *
     * Throws synchronously if serialization fails or the transport cannot accept the notification.
     */
    public fun notify(update: SessionUpdate, _meta: JsonElement? = null)

    /**
     * Asks the user for permission through the client and waits for the answer.
     *
     * While waiting, the session SHOULD report [StateUpdate.RequiresAction], and
     * [StateUpdate.Running] again once it resumes
     * ([prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle)).
     *
     * The answer can be [RequestPermissionOutcome.Cancelled] — that is what a client sends for pending
     * requests when it cancels the turn — or an [RequestPermissionOutcome.Unknown] outcome, which MUST NOT
     * be treated as approval.
     */
    public suspend fun requestPermission(
        title: String,
        options: List<PermissionOption>,
        subject: RequestPermissionSubject? = null,
        description: String? = null,
        _meta: JsonElement? = null,
    ): RequestPermissionResponse

    /**
     * Asks the user for structured input through the client and waits for the answer.
     *
     * The [mode] carries its own scope, so an elicitation can belong to this session, to a tool call within
     * it, or to a request outside any session — that is why it is spelled out rather than implied.
     *
     * The answer can be [ElicitationAction.Decline] or [ElicitationAction.Cancel]; an
     * [ElicitationAction.Unknown] one MUST NOT be read as acceptance.
     */
    public suspend fun createElicitation(
        message: String,
        mode: ElicitationMode,
        _meta: JsonElement? = null,
    ): CreateElicitationResponse

    /**
     * Tells the client that a URL-based elicitation has finished.
     *
     * Only meaningful for [ElicitationMode.Url], where the user acts outside the client and it would
     * otherwise have no way to know the flow is over.
     */
    public fun completeElicitation(elicitationId: ElicitationId, _meta: JsonElement? = null)
}
