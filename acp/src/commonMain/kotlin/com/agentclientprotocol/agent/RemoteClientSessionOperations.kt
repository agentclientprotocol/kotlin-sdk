package com.agentclientprotocol.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
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
     * Sends [notification] for this root session.
     *
     * Every root path sends through here: `client.notify`, the prompt flow and replay, so the subagents rules for a
     * root are checked once, here. A [SessionUpdate.SubagentUpdate] is always rejected, because the SDK must see
     * every announcement and grant. The other updates the subagents RFD defines need the `subagents` client
     * capability: a v1 agent MUST NOT send them without it (`docs/rfds/subagents.mdx`, "Capability negotiation").
     */
    @OptIn(UnstableApi::class)
    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        if (notification is SessionUpdate.SubagentUpdate) {
            throw IllegalArgumentException(
                "SubagentUpdate for ${notification.sessionId} cannot be sent through the client operations of " +
                    "session $sessionId: announce subagents through subagents.announce"
            )
        }
        if (clientCapabilities.subagents == null && notification.needsSubagentsCapability()) {
            error("Client does not support subagents capability")
        }
        sendUpdate(sessionId, notification, _meta)
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

    /**
     * Sends [update] on the stream of [target] without any check. The send is accepted into the transport's queue
     * before this returns.
     */
    protected fun sendUpdate(target: SessionId, update: SessionUpdate, _meta: JsonElement?) {
        AcpMethod.ClientMethods.V1.SessionUpdate(rpc, SessionNotification(target, update, _meta))
    }
}

/**
 * Whether the subagents RFD defines this update, so a v1 agent may send it only with the `subagents` client
 * capability. That includes tool call content that references a session.
 */
@OptIn(UnstableApi::class)
private fun SessionUpdate.needsSubagentsCapability(): Boolean = when (this) {
    is SessionUpdate.SessionMessage, is SessionUpdate.SessionMessageChunk -> true
    is SessionUpdate.ToolCall -> content.any { it is ToolCallContent.Session }
    is SessionUpdate.ToolCallUpdate -> content.orEmpty().any { it is ToolCallContent.Session }
    else -> false
}
