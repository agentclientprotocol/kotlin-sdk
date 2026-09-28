package com.agentclientprotocol.client.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.v2.CancelSessionNotification
import com.agentclientprotocol.model.v2.CloseSessionRequest
import com.agentclientprotocol.model.v2.CloseSessionResponse
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.PromptRequest
import com.agentclientprotocol.model.v2.SessionConfigOption
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SetSessionConfigOptionRequest
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

/**
 * **UNSTABLE**
 *
 * A command handle for a v2 session. Incoming updates and permission requests belong to [Client],
 * independently of this handle's lifetime. Multiple handles may refer to the same session.
 *
 * @property sessionId the session addressed by this handle
 */
@UnstableApi
public class ClientSession internal constructor(
    public val sessionId: SessionId,
    private val client: Client,
) {
    /**
     * Sends a prompt and returns once the agent has accepted it.
     *
     * Turn completion arrives through the connection's update callback as
     * [com.agentclientprotocol.model.v2.StateUpdate.Idle], whose stop reason says why the agent stopped.
     */
    public suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null) {
        AcpMethod.AgentMethods.V2.SessionPrompt(client.protocol, PromptRequest(sessionId, content, _meta))
    }

    /**
     * Sets a configuration option, returning the complete option list as it now stands.
     *
     * One choice can affect other options. This response, and subsequent config option updates received
     * by the connection, supersede the configuration from the setup response.
     */
    public suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement? = null,
    ): List<SessionConfigOption> = AcpMethod.AgentMethods.V2.SessionSetConfigOption(
        client.protocol,
        SetSessionConfigOptionRequest(sessionId, configId, value, _meta)
    ).configOptions

    /**
     * Ends this session with `session/close` and returns the agent's response.
     */
    public suspend fun close(_meta: JsonElement? = null): CloseSessionResponse =
        AcpMethod.AgentMethods.V2.SessionClose(client.protocol, CloseSessionRequest(sessionId, _meta))

    /**
     * Asks the agent to stop active work and cancels pending permission handlers for this session.
     *
     * The agent continues reporting updates until it sends an idle update with the `cancelled` stop reason.
     * Encoding and transport failures throw synchronously. Pending local permission handlers are cancelled
     * even if the notification cannot be sent. Future permission requests are unaffected.
     */
    public fun cancel(_meta: JsonElement? = null) {
        try {
            AcpMethod.AgentMethods.V2.SessionCancel(client.protocol, CancelSessionNotification(sessionId, _meta))
        } finally {
            client.cancelPendingPermissions(sessionId)
        }
    }
}
