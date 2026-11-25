package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionEvent
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

/**
 * The client operations of one session, sent to the client for [sessionId].
 *
 * This class serves root sessions as is. A subagent's operations subclass it to gate every operation until the
 * subagent's announcement and to apply the subagents routing rule to [notify].
 */
internal open class RemoteClientSessionOperations(
    protected val rpc: RpcMethodsOperations,
    protected val sessionId: SessionId,
    private val clientCapabilities: ClientCapabilities,
) : ClientSessionOperations {
    /**
     * Throws if nothing may be sent for this session yet. Called before every operation except [notify], which
     * subclasses check themselves.
     */
    protected open fun checkCanSend() {}

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        checkCanSend()
        return AcpMethod.ClientMethods.V1.SessionRequestPermission(rpc, RequestPermissionRequest(sessionId, toolCall, permissions, _meta))
    }

    /**
     * Sends the update of [event] for this root session.
     *
     * Every root path sends through here (`client.notify`, the prompt flow and replay), so the subagents rules for a
     * root are checked once, here:
     * - a [SessionUpdate.SubagentUpdate] is always rejected, because the SDK must see every announcement and grant;
     * - without the `subagents` client capability, `session_message`, `session_message_chunk` and `session` tool call
     *   content are rejected. An agent MUST NOT send the messages without it (`docs/protocol/v1/subagents.mdx`,
     *   "Checking support"; `docs/protocol/v1/initialization.mdx`, "Subagents"), and sends `session` content only to
     *   a client that advertised it (`docs/protocol/v1/tool-calls.mdx`, "Sessions").
     *
     * @throws IllegalArgumentException for a [SessionUpdate.SubagentUpdate]
     * @throws IllegalStateException for an update that needs the missing `subagents` capability, or for
     * [SessionEvent.Opened], which only the client runtime delivers
     */
    @OptIn(UnstableApi::class)
    override suspend fun notify(event: SessionEvent) {
        val (notification, _meta) = event.requireUpdate()
        if (notification is SessionUpdate.SubagentUpdate) {
            throw IllegalArgumentException(
                "SubagentUpdate for ${notification.sessionId} cannot be sent through the client operations of " +
                    "session $sessionId: announce subagents through subagents.announce"
            )
        }
        if (clientCapabilities.subagents == null && notification.needsSubagentsCapability()) {
            error("Client does not support subagents capability")
        }
        AcpMethod.ClientMethods.V1.SessionUpdate(rpc, SessionNotification(sessionId, notification, _meta))
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        checkCanSend()
        if (clientCapabilities.fs?.readTextFile != true) error("Client does not support fs.readTextFile capability")
        return AcpMethod.ClientMethods.V1.FsReadTextFile(rpc, ReadTextFileRequest(sessionId, path, line, limit, _meta))
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        checkCanSend()
        if (clientCapabilities.fs?.writeTextFile != true) error("Client does not support fs.writeTextFile capability")
        return AcpMethod.ClientMethods.V1.FsWriteTextFile(rpc, WriteTextFileRequest(sessionId, path, content, _meta))
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        checkCanSend()
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.V1.TerminalCreate(rpc, CreateTerminalRequest(sessionId, command, args, cwd, env, outputByteLimit, _meta))
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        checkCanSend()
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.V1.TerminalOutput(rpc, TerminalOutputRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        checkCanSend()
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.V1.TerminalRelease(rpc, ReleaseTerminalRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
        checkCanSend()
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.V1.TerminalWaitForExit(rpc, WaitForTerminalExitRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        checkCanSend()
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.V1.TerminalKill(rpc, KillTerminalCommandRequest(sessionId, terminalId, _meta))
    }

    @OptIn(UnstableApi::class)
    override suspend fun createElicitation(
        request: CreateElicitationRequest
    ): CreateElicitationResponse {
        checkCanSend()
        val elicitation = clientCapabilities.elicitation
            ?: error("Client does not support elicitation capability")
        when (request.mode) {
            is ElicitationMode.Form -> {
                if (!elicitation.supportsForm) error("Client does not support form-based elicitation")
            }
            is ElicitationMode.Url -> {
                if (!elicitation.supportsUrl) error("Client does not support URL-based elicitation")
            }
        }
        return AcpMethod.ClientMethods.V1.ElicitationCreate(rpc, request)
    }

    /**
     * Convenience method that creates an elicitation request with session scope auto-filled.
     */
    @OptIn(UnstableApi::class)
    public suspend fun createSessionElicitation(
        mode: ElicitationMode,
        message: String,
        toolCallId: ToolCallId? = null,
        _meta: JsonElement? = null
    ): CreateElicitationResponse {
        return createElicitation(CreateElicitationRequest(
            scope = ElicitationScope.Session(sessionId, toolCallId),
            mode = mode,
            message = message,
            _meta = _meta
        ))
    }

    @OptIn(UnstableApi::class)
    override suspend fun completeElicitation(
        notification: CompleteElicitationNotification
    ) {
        checkCanSend()
        AcpMethod.ClientMethods.V1.ElicitationComplete(rpc, notification)
    }

    /**
     * Convenience method to complete a URL-mode elicitation.
     */
    @OptIn(UnstableApi::class)
    public suspend fun completeElicitation(
        elicitationId: ElicitationId,
        _meta: JsonElement? = null
    ) {
        completeElicitation(CompleteElicitationNotification(elicitationId, _meta))
    }
}

/**
 * The client operations of [subagent]: requests for the child's session, allowed only while the child is announced and
 * registered, and the subagents routing rule for updates. The child's send state stays in [subagent], which does the
 * sending.
 */
@OptIn(UnstableApi::class)
internal class SubagentClientOperations(
    protocol: Protocol,
    sessionId: SessionId,
    clientCapabilities: ClientCapabilities,
    private val subagent: SubagentSessionWrapper,
) : RemoteClientSessionOperations(protocol, sessionId, clientCapabilities) {
    override fun checkCanSend() = subagent.checkAnnounced()

    override suspend fun notify(event: SessionEvent) {
        val (notification, _meta) = event.requireUpdate()
        if (notification is SessionUpdate.SubagentUpdate) {
            require(notification.sessionId == sessionId) {
                "SubagentUpdate for ${notification.sessionId} cannot be sent through the client operations of " +
                    "subagent $sessionId: announce nested subagents through subagents.announce"
            }
            subagent.sendOwnUpdate(notification, _meta)
        } else {
            subagent.sendPlainUpdate(notification, _meta)
        }
    }
}

/**
 * Returns the update of an event that agent code sends. [SessionEvent.Opened] has an internal constructor, so only an
 * SDK bug could pass one here.
 *
 * @throws IllegalStateException for [SessionEvent.Opened]
 */
internal fun SessionEvent.requireUpdate(): Pair<SessionUpdate, JsonElement?> = when (this) {
    is SessionEvent.Update -> update to _meta
    is SessionEvent.Opened -> throw IllegalStateException("SessionEvent.Opened is delivered only by the client runtime")
}

/**
 * Whether the v1 subagents pages define this update, so an agent may send it only with the `subagents` client
 * capability (`docs/protocol/v1/subagents.mdx`, "Checking support"). That includes tool call content that references a
 * session (`docs/protocol/v1/tool-calls.mdx`, "Sessions").
 */
@OptIn(UnstableApi::class)
private fun SessionUpdate.needsSubagentsCapability(): Boolean = when (this) {
    is SessionUpdate.SessionMessage, is SessionUpdate.SessionMessageChunk -> true
    is SessionUpdate.ToolCall -> content.any { it is ToolCallContent.Session }
    is SessionUpdate.ToolCallUpdate -> content.orEmpty().any { it is ToolCallContent.Session }
    else -> false
}
