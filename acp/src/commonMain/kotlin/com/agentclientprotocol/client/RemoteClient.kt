package com.agentclientprotocol.client

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.KillTerminalCommandRequest
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalRequest
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.TerminalOutputRequest
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitRequest
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileRequest
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.protocol.sendRequest

public class RemoteClient(private val protocol: Protocol) : Client {
    override suspend fun sessionUpdate(notification: SessionNotification) {
        protocol.sendNotification(AcpMethod.ClientMethods.SessionUpdate, notification)
    }

    override suspend fun sessionRequestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.SessionRequestPermission, request)
    }

    override suspend fun fsReadTextFile(request: ReadTextFileRequest): ReadTextFileResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.FsReadTextFile, request)
    }

    override suspend fun fsWriteTextFile(request: WriteTextFileRequest): WriteTextFileResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.FsWriteTextFile, request)
    }

    override suspend fun terminalCreate(request: CreateTerminalRequest): CreateTerminalResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalCreate, request)
    }

    override suspend fun terminalOutput(request: TerminalOutputRequest): TerminalOutputResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalOutput, request)
    }

    override suspend fun terminalRelease(request: ReleaseTerminalRequest): ReleaseTerminalResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalRelease, request)
    }

    override suspend fun terminalWaitForExit(request: WaitForTerminalExitRequest): WaitForTerminalExitResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalWaitForExit, request)
    }

    override suspend fun terminalKill(request: KillTerminalCommandRequest): KillTerminalCommandResponse {
        return protocol.sendRequest(AcpMethod.ClientMethods.TerminalKill, request)
    }
}