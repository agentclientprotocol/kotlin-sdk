@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.RequestId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Type discriminator key for AuthMethod serialization.
 */
private const val AUTH_METHOD_TYPE_DISCRIMINATOR = "type"

/**
 * Type discriminator values for AuthMethod serialization.
 */
public object AuthMethodType {
    public const val AGENT: String = "agent"
    public const val ENV_VAR: String = "env_var"
    public const val TERMINAL: String = "terminal"
}

/**
 * Describes an available authentication method.
 */
@Serializable(with = AuthMethodSerializer::class)
public sealed class AuthMethod : AcpWithMeta {
    public abstract val id: AuthMethodId
    public abstract val name: String
    public abstract val description: String?

    /**
     * Agent-based authentication (default).
     * Agent handles the auth itself. This is the default type for backward compatibility.
     */
    @Serializable
    @SerialName(AuthMethodType.AGENT)
    public data class AgentAuth(
        override val id: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        override val _meta: JsonElement? = null
    ) : AuthMethod()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Environment variable-based authentication.
     * A user can enter a key and a client will pass it to the agent as an env variable.
     */
    @UnstableApi
    @Serializable
    @SerialName(AuthMethodType.ENV_VAR)
    public data class EnvVarAuth(
        override val id: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val varName: String,
        val link: String? = null,
        override val _meta: JsonElement? = null
    ) : AuthMethod()

    /**
     * **UNSTABLE**
     *
     * This capability is not part of the spec yet, and may be removed or changed at any point.
     *
     * Terminal-based authentication.
     * The client runs an interactive terminal for the user to login via a TUI.
     */
    @UnstableApi
    @Serializable
    @SerialName(AuthMethodType.TERMINAL)
    public data class TerminalAuth(
        override val id: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val args: List<String>? = null,
        val env: Map<String, String>? = null,
        override val _meta: JsonElement? = null
    ) : AuthMethod()

    /**
     * Unknown authentication method for forward compatibility.
     * Captures any auth method type not recognized by this SDK version.
     */
    @Serializable
    public data class UnknownAuthMethod(
        override val id: AuthMethodId,
        override val name: String,
        override val description: String? = null,
        val type: String,
        val rawJson: JsonObject,
        override val _meta: JsonElement? = null
    ) : AuthMethod()
}

/**
 * Serializer for AuthMethod that handles:
 * - Missing type field → AgentAuth (backward compatibility)
 * - type="agent" → AgentAuth
 * - type="env_var" → EnvVarAuth
 * - type="terminal" → TerminalAuth
 * - Unknown type → UnknownAuthMethod
 */
@OptIn(UnstableApi::class)
internal object AuthMethodSerializer : KSerializer<AuthMethod> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AuthMethod")

    override fun serialize(encoder: Encoder, value: AuthMethod) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is AuthMethod.AgentAuth -> {
                val base = ACPJson.encodeToJsonElement(AuthMethod.AgentAuth.serializer(), value).jsonObject
                buildJsonObject {
                    put(AUTH_METHOD_TYPE_DISCRIMINATOR, AuthMethodType.AGENT)
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is AuthMethod.EnvVarAuth -> {
                val base = ACPJson.encodeToJsonElement(AuthMethod.EnvVarAuth.serializer(), value).jsonObject
                buildJsonObject {
                    put(AUTH_METHOD_TYPE_DISCRIMINATOR, AuthMethodType.ENV_VAR)
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is AuthMethod.TerminalAuth -> {
                val base = ACPJson.encodeToJsonElement(AuthMethod.TerminalAuth.serializer(), value).jsonObject
                buildJsonObject {
                    put(AUTH_METHOD_TYPE_DISCRIMINATOR, AuthMethodType.TERMINAL)
                    base.forEach { (k, v) -> put(k, v) }
                }
            }
            is AuthMethod.UnknownAuthMethod -> {
                // For unknown, use the stored type and raw JSON
                buildJsonObject {
                    put(AUTH_METHOD_TYPE_DISCRIMINATOR, value.type)
                    value.rawJson.forEach { (k, v) ->
                        if (k != AUTH_METHOD_TYPE_DISCRIMINATOR) put(k, v)
                    }
                }
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): AuthMethod {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val explicitType = jsonObject[AUTH_METHOD_TYPE_DISCRIMINATOR]?.jsonPrimitive?.content

        return when (explicitType) {
            null, AuthMethodType.AGENT -> ACPJson.decodeFromJsonElement(
                AuthMethod.AgentAuth.serializer(),
                jsonObject
            )
            AuthMethodType.ENV_VAR -> ACPJson.decodeFromJsonElement(
                AuthMethod.EnvVarAuth.serializer(),
                jsonObject
            )
            AuthMethodType.TERMINAL -> ACPJson.decodeFromJsonElement(
                AuthMethod.TerminalAuth.serializer(),
                jsonObject
            )
            else -> {
                val id = jsonObject["id"] ?: throw SerializationException("Missing 'id' field")
                val name = jsonObject["name"] ?: throw SerializationException("Missing 'name' field")
                AuthMethod.UnknownAuthMethod(
                    id = ACPJson.decodeFromJsonElement(AuthMethodId.serializer(), id),
                    name = name.jsonPrimitive.content,
                    description = jsonObject["description"]?.jsonPrimitive?.contentOrNull,
                    type = explicitType,
                    rawJson = jsonObject,
                    _meta = jsonObject["_meta"]
                )
            }
        }
    }
}

/**
 * An environment variable to set when launching an MCP server.
 */
@Serializable
public data class EnvVariable(
    val name: String,
    val value: String,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * An HTTP header to set when making requests to the MCP server.
 */
@Serializable
public data class HttpHeader(
    val name: String,
    val value: String,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Configuration for connecting to an MCP (Model Context Protocol) server.
 *
 * MCP servers provide tools and context that the agent can use when
 * processing prompts.
 *
 * See protocol docs: [MCP Servers](https://agentclientprotocol.com/protocol/session-setup#mcp-servers)
 */
@Serializable
public sealed class McpServer {
    public abstract val name: String

    /**
     * Stdio transport configuration
     *
     * All Agents MUST support this transport.
     */
    @Serializable
    @SerialName("stdio")
    public data class Stdio(
        override val name: String,
        val command: String,
        val args: List<String>,
        val env: List<EnvVariable>
    ) : McpServer()

    /**
     * HTTP transport configuration
     *
     * Only available when the Agent capabilities indicate `mcp_capabilities.http` is `true`.
     */
    @Serializable
    @SerialName("http")
    public data class Http(
        override val name: String,
        val url: String,
        val headers: List<HttpHeader>
    ) : McpServer()

    /**
     * SSE transport configuration
     *
     * Only available when the Agent capabilities indicate `mcp_capabilities.sse` is `true`.
     */
    @Serializable
    @SerialName("sse")
    public data class Sse(
        override val name: String,
        val url: String,
        val headers: List<HttpHeader>
    ) : McpServer()
}

/**
 * Reasons why an agent stops processing a prompt turn.
 *
 * See protocol docs: [Stop Reasons](https://agentclientprotocol.com/protocol/prompt-turn#stop-reasons)
 */
@Serializable
public enum class StopReason {
    @SerialName("end_turn") END_TURN,
    @SerialName("max_tokens") MAX_TOKENS,
    @SerialName("max_turn_requests") MAX_TURN_REQUESTS,
    @SerialName("refusal") REFUSAL,
    @SerialName("cancelled") CANCELLED
}

/**
 * The type of permission option being presented to the user.
 *
 * Helps clients choose appropriate icons and UI treatment.
 */
@Serializable
public enum class PermissionOptionKind {
    @SerialName("allow_once") ALLOW_ONCE,
    @SerialName("allow_always") ALLOW_ALWAYS,
    @SerialName("reject_once") REJECT_ONCE,
    @SerialName("reject_always") REJECT_ALWAYS
}

/**
 * An option presented to the user when requesting permission.
 */
@Serializable
public data class PermissionOption(
    val optionId: PermissionOptionId,
    val name: String,
    val kind: PermissionOptionKind,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * The outcome of a permission request.
 */
@Serializable
@JsonClassDiscriminator("outcome")
public sealed class RequestPermissionOutcome {
    /**
     * The prompt turn was cancelled before the user responded.
     */
    @Serializable
    @SerialName("cancelled")
    public object Cancelled : RequestPermissionOutcome()

    /**
     * The user selected one of the provided options.
     */
    @Serializable
    @SerialName("selected")
    public data class Selected(
        val optionId: PermissionOptionId
    ) : RequestPermissionOutcome()
}

// === Request Types ===

/**
 * Request parameters for the initialize method.
 *
 * Sent by the client to establish connection and negotiate capabilities.
 *
 * See protocol docs: [Initialization](https://agentclientprotocol.com/protocol/initialization)
 */
@Serializable
public data class InitializeRequest(
    val protocolVersion: ProtocolVersion,
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: Implementation? = null,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Request parameters for the authenticate method.
 *
 * Specifies which authentication method to use.
 */
@Serializable
public data class AuthenticateRequest(
    val methodId: AuthMethodId,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Request parameters for creating a new session.
 *
 * See protocol docs: [Creating a Session](https://agentclientprotocol.com/protocol/session-setup#creating-a-session)
 */
@Serializable
public data class NewSessionRequest(
    val cwd: String,
    val mcpServers: List<@Polymorphic McpServer>,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Request parameters for loading an existing session.
 *
 * Only available if the agent supports the `loadSession` capability.
 *
 * See protocol docs: [Loading Sessions](https://agentclientprotocol.com/protocol/session-setup#loading-sessions)
 */
@Serializable
public data class LoadSessionRequest(
    override val sessionId: SessionId,
    val cwd: String,
    val mcpServers: List<@Polymorphic McpServer>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId


/**
 * Request parameters for sending a user prompt to the agent.
 *
 * Contains the user's message and any additional context.
 *
 * See protocol docs: [User Message](https://agentclientprotocol.com/protocol/prompt-turn#1-user-message)
 */
@Serializable
public data class PromptRequest(
    override val sessionId: SessionId,
    val prompt: List<ContentBlock>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Request to read content from a text file.
 *
 * Only available if the client supports the `fs.readTextFile` capability.
 */
@Serializable
public data class ReadTextFileRequest(
    override val sessionId: SessionId,
    val path: String,
    val line: UInt? = null,
    val limit: UInt? = null,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Request to write content to a text file.
 *
 * Only available if the client supports the `fs.writeTextFile` capability.
 */
@Serializable
public data class WriteTextFileRequest(
    override val sessionId: SessionId,
    val path: String,
    val content: String,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Request for user permission to execute a tool call.
 *
 * Sent when the agent needs authorization before performing a sensitive operation.
 *
 * See protocol docs: [Requesting Permission](https://agentclientprotocol.com/protocol/tool-calls#requesting-permission)
 */
@Serializable
public data class RequestPermissionRequest(
    override val sessionId: SessionId,
    val toolCall: SessionUpdate.ToolCallUpdate,
    val options: List<PermissionOption>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Request parameters for setting a session mode.
 */
@Serializable
public data class SetSessionModeRequest(
    override val sessionId: SessionId,
    val modeId: SessionModeId,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request parameters for setting a session model.
 */
@UnstableApi
@Serializable
public data class SetSessionModelRequest(
    override val sessionId: SessionId,
    val modelId: ModelId,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

// === Response Types ===

/**
 * Response from the initialize method.
 *
 * Contains the negotiated protocol version and agent capabilities.
 *
 * See protocol docs: [Initialization](https://agentclientprotocol.com/protocol/initialization)
 */
@Serializable
public data class InitializeResponse(
    val protocolVersion: ProtocolVersion,
    val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    val authMethods: List<AuthMethod> = emptyList(),
    val agentInfo: Implementation? = null,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * A mode the agent can operate in.
 *
 * See protocol docs: [Session Modes](https://agentclientprotocol.com/protocol/session-modes)
 */
@Serializable
public data class SessionMode(
    val id: SessionModeId,
    val name: String,
    val description: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * The set of modes and the one currently active.
 */
@Serializable
public data class SessionModeState(
    val currentModeId: SessionModeId,
    val availableModes: List<SessionMode>,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Information about a selectable model.
 */
@UnstableApi
@Serializable
public data class ModelInfo(
    val modelId: ModelId,
    val name: String,
    val description: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * The set of models and the one currently active.
 */
@UnstableApi
@Serializable
public data class SessionModelState(
    val currentModelId: ModelId,
    val availableModels: List<ModelInfo>,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Response from creating a new session.
 *
 * See protocol docs: [Creating a Session](https://agentclientprotocol.com/protocol/session-setup#creating-a-session)
 */
@OptIn(UnstableApi::class)
@Serializable
public data class NewSessionResponse(
    override val sessionId: SessionId,
    override val modes: SessionModeState? = null,
    @property:UnstableApi
    override val models: SessionModelState? = null,
    @property:UnstableApi
    override val configOptions: List<SessionConfigOption>? = null,
    override val _meta: JsonElement? = null
) : AcpCreatedSessionResponse, AcpResponse, AcpWithSessionId

/**
 * Response from processing a user prompt.
 *
 * See protocol docs: [Check for Completion](https://agentclientprotocol.com/protocol/prompt-turn#4-check-for-completion)
 */
@Serializable
public data class PromptResponse(
    val stopReason: StopReason,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response from loading an existing session.
 */
@OptIn(UnstableApi::class)
@Serializable
public data class LoadSessionResponse(
    override val modes: SessionModeState? = null,
    @property:UnstableApi
    override val models: SessionModelState? = null,
    @property:UnstableApi
    override val configOptions: List<SessionConfigOption>? = null,
    override val _meta: JsonElement? = null
) : AcpCreatedSessionResponse, AcpResponse

/**
 * Response containing the contents of a text file.
 */
@Serializable
public data class ReadTextFileResponse(
    val content: String,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response to a permission request.
 */
@Serializable
public data class RequestPermissionResponse(
    val outcome: RequestPermissionOutcome,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response to authenticate method
 */
@Serializable
public data class AuthenticateResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response to `session/set_mode` method.
 */
@Serializable
public data class SetSessionModeResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response to `session/set_model` method.
 */
@UnstableApi
@Serializable
public data class SetSessionModelResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response to `fs/write_text_file`
 */
@Serializable
public data class WriteTextFileResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Response to terminal/release method
 */
@Serializable
public data class ReleaseTerminalResponse(
    override val _meta: JsonElement? = null
) : AcpResponse

// === Unstable Request/Response Types ===

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request to fork a session, creating a new session based on an existing session's context.
 */
@UnstableApi
@Serializable
public data class ForkSessionRequest(
    override val sessionId: SessionId,
    val cwd: String,
    val mcpServers: List<McpServer>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response from forking a session.
 */
@UnstableApi
@Serializable
public data class ForkSessionResponse(
    override val sessionId: SessionId,
    override val modes: SessionModeState? = null,
    @property:UnstableApi
    override val models: SessionModelState? = null,
    @property:UnstableApi
    override val configOptions: List<SessionConfigOption>? = null,
    override val _meta: JsonElement? = null
) : AcpCreatedSessionResponse, AcpResponse, AcpWithSessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request to list existing sessions with optional filtering and pagination.
 */
@UnstableApi
@Serializable
public data class ListSessionsRequest(
    val cwd: String? = null,
    override val cursor: String? = null,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpPaginatedRequest

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response from listing sessions.
 */
@UnstableApi
@Serializable
public data class ListSessionsResponse(
    val sessions: List<SessionInfo>,
    override val nextCursor: String? = null,
    override val _meta: JsonElement? = null
) : AcpResponse, AcpPaginatedResponse<SessionInfo> {
    override fun getItemsBatch(): List<SessionInfo> = sessions
}

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request to resume a session without replaying message history.
 */
@UnstableApi
@Serializable
public data class ResumeSessionRequest(
    override val sessionId: SessionId,
    val cwd: String,
    val mcpServers: List<McpServer>,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response from resuming a session.
 */
@UnstableApi
@Serializable
public data class ResumeSessionResponse(
    override val modes: SessionModeState? = null,
    @property:UnstableApi
    override val models: SessionModelState? = null,
    @property:UnstableApi
    override val configOptions: List<SessionConfigOption>? = null,
    override val _meta: JsonElement? = null
) : AcpCreatedSessionResponse, AcpResponse

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Request to set a configuration option for a session.
 */
@UnstableApi
@Serializable
public data class SetSessionConfigOptionRequest(
    override val sessionId: SessionId,
    val configId: SessionConfigId,
    val value: SessionConfigOptionValue,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 *
 * Response from setting a configuration option.
 */
@UnstableApi
@Serializable
public data class SetSessionConfigOptionResponse(
    val configOptions: List<SessionConfigOption>,
    override val _meta: JsonElement? = null
) : AcpResponse

// === Notification Types ===

/**
 * Notification to cancel ongoing operations for a session.
 *
 * See protocol docs: [Cancellation](https://agentclientprotocol.com/protocol/prompt-turn#cancellation)
 */
@Serializable
public data class CancelNotification(
    override val sessionId: SessionId,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

/**
 * Notification containing a session update from the agent.
 *
 * Used to stream real-time progress and results during prompt processing.
 *
 * See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/prompt-turn#3-agent-reports-output)
 */
@Serializable
public data class SessionNotification(
    override val sessionId: SessionId,
    val update: SessionUpdate,
    override val _meta: JsonElement? = null
) : AcpNotification, AcpWithSessionId

/**
 * Notification used to cancel a running request with [requestId] on a counterpart side.
 *
 * (The same method is used in LSP)
 */
@Serializable
public class CancelRequestNotification(
    public val requestId: RequestId,
    public val message: String?,
    override val _meta: JsonElement? = null,
) : AcpNotification
