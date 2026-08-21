@file:Suppress("unused")

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.AcpWithSessionId
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One choice offered to the user in a permission request.
 */
@UnstableApi
@Serializable
public data class PermissionOption(
    val optionId: PermissionOptionId,
    val name: String,
    val kind: PermissionOptionKind,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * What the permission is being asked about.
 *
 * v2 puts this in its own union instead of v1's single `toolCall` field, so a request can be about
 * something that is not a tool call at all.
 *
 * Open union: an unrecognized `type` deserializes to [Unknown] with the raw payload preserved.
 */
@UnstableApi
@Serializable(with = RequestPermissionSubjectSerializer::class)
public sealed class RequestPermissionSubject {
    /** Permission is requested before executing a tool call. */
    @Serializable
    public data class ToolCall(
        val toolCall: ToolCallUpdate,
        override val _meta: JsonElement? = null,
    ) : RequestPermissionSubject(), AcpWithMeta {
        public companion object {
            internal const val DISCRIMINATOR: String = "tool_call"
        }
    }

    /**
     * Custom or future subject.
     *
     * [rawJson] holds the complete payload as received, including the discriminator and any fields the
     * peer flattened next to it, so re-serializing emits it byte-identically.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : RequestPermissionSubject()
}

@OptIn(UnstableApi::class)
internal object RequestPermissionSubjectSerializer : OpenTaggedUnionSerializer<RequestPermissionSubject>(
    serialName = "com.agentclientprotocol.model.v2.RequestPermissionSubject",
    discriminatorKey = "type",
    known = mapOf(
        RequestPermissionSubject.ToolCall.DISCRIMINATOR to RequestPermissionSubject.ToolCall.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is RequestPermissionSubject.ToolCall -> RequestPermissionSubject.ToolCall.DISCRIMINATOR
            is RequestPermissionSubject.Unknown -> value.type
        }
    },
    unknown = RequestPermissionSubject::Unknown,
    rawJson = { (it as? RequestPermissionSubject.Unknown)?.rawJson },
)

/**
 * Request parameters for the v2 `session/request_permission` method.
 *
 * Sent by the agent while a turn is running. Per the
 * [prompt lifecycle](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle#cancellation), a client
 * that cancels active work MUST answer every pending request of this kind with
 * [RequestPermissionOutcome.Cancelled].
 */
@UnstableApi
@Serializable
public data class RequestPermissionRequest(
    override val sessionId: SessionId,
    val title: String,
    val options: List<PermissionOption>,
    val description: String? = null,
    val subject: RequestPermissionSubject? = null,
    override val _meta: JsonElement? = null
) : AcpRequest, AcpWithSessionId

/**
 * Response to the v2 `session/request_permission` method.
 */
@UnstableApi
@Serializable
public data class RequestPermissionResponse(
    val outcome: RequestPermissionOutcome,
    override val _meta: JsonElement? = null
) : AcpResponse
