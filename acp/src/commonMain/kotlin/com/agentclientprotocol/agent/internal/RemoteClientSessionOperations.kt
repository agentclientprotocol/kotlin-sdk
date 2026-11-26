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
 * The client operations of one session, sent to the client for [sessionId]. Root sessions use it as is, and
 * [SubagentClientOperations] adds a subagent's gating and routing.
 */
internal open class RemoteClientSessionOperations(
    protected val rpc: RpcMethodsOperations,
    protected val sessionId: SessionId,
    private val clientCapabilities: ClientCapabilities,
) : ClientSessionOperations {
    /**
     * Throws if nothing may be sent for this session yet. Every operation but [notify] calls it.
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
     * Sends the update of [event] for this root session. Every root path sends through here, the prompt flow and the
     * replay too, so a root's subagents rules are checked once:
     * - a [SessionUpdate.SubagentUpdate] is rejected, so the SDK sees every announcement and grant;
     * - without the `subagents` client capability, the updates that need it are rejected, as ACP requires
     *   (https://agentclientprotocol.com/protocol/v1/subagents#checking-support).
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
 * The client operations of [subagent], allowed only while it is announced and registered, with updates routed by the
 * subagents rule. [subagent] does the sending.
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
