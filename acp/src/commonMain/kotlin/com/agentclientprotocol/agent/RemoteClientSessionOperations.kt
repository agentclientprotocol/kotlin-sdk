package com.agentclientprotocol.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

internal class RemoteClientSessionOperations(private val rpc: RpcMethodsOperations, private val sessionId: SessionId, private val clientCapabilities: ClientCapabilities) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return AcpMethod.ClientMethods.SessionRequestPermission(rpc, RequestPermissionRequest(sessionId, toolCall, permissions, _meta))
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        return AcpMethod.ClientMethods.SessionUpdate(rpc, SessionNotification(sessionId, notification, _meta))
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        if (clientCapabilities.fs?.readTextFile != true) error("Client does not support fs.readTextFile capability")
        return AcpMethod.ClientMethods.FsReadTextFile(rpc, ReadTextFileRequest(sessionId, path, line, limit, _meta))
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        if (clientCapabilities.fs?.writeTextFile != true) error("Client does not support fs.writeTextFile capability")
        return AcpMethod.ClientMethods.FsWriteTextFile(rpc, WriteTextFileRequest(sessionId, path, content, _meta))
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.TerminalCreate(rpc, CreateTerminalRequest(sessionId, command, args, cwd, env, outputByteLimit, _meta))
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.TerminalOutput(rpc, TerminalOutputRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.TerminalRelease(rpc, ReleaseTerminalRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.TerminalWaitForExit(rpc, WaitForTerminalExitRequest(sessionId, terminalId, _meta))
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        if (!clientCapabilities.terminal) error("Client does not support terminal capability")
        return AcpMethod.ClientMethods.TerminalKill(rpc, KillTerminalCommandRequest(sessionId, terminalId, _meta))
    }
}