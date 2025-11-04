package com.agentclientprotocol.common

import com.agentclientprotocol.model.AcpCapabilities
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.WriteTextFileRequest
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

public interface FileSystemOperations {
    public companion object : HandlerSideExtension<FileSystemOperations>, RemoteSideExtension<FileSystemOperations> {
        override fun RegistrarContext<FileSystemOperations>.register() {
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { operations, params ->
                operations.fsReadTextFile(params.path, params.line, params.limit, params._meta)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { operations, params ->
                operations.fsWriteTextFile(params.path, params.content, params._meta)
            }
        }

        override fun isSupported(remoteSideCapabilities: AcpCapabilities): Boolean {
            // TODO check both flags
            return remoteSideCapabilities is ClientCapabilities && remoteSideCapabilities.fs?.readTextFile == true
        }

        override fun createSessionRemote(
            rpc: RpcMethodsOperations,
            capabilities: AcpCapabilities,
            sessionId: SessionId,
        ): FileSystemOperations {
            // TODO check read/write availability
            return object : FileSystemOperations {
                override suspend fun fsReadTextFile(
                    path: String,
                    line: UInt?,
                    limit: UInt?,
                    _meta: JsonElement?,
                ): ReadTextFileResponse {
                    return AcpMethod.ClientMethods.FsReadTextFile(rpc, ReadTextFileRequest(sessionId, path, line, limit, _meta))
                }

                override suspend fun fsWriteTextFile(
                    path: String,
                    content: String,
                    _meta: JsonElement?,
                ): WriteTextFileResponse {
                    return AcpMethod.ClientMethods.FsWriteTextFile(rpc, WriteTextFileRequest(sessionId, path, content, _meta))
                }
            }
        }

        override val name: String
            get() = FileSystemOperations::class.simpleName!!
    }

    public suspend fun fsReadTextFile(path: String,
                                      line: UInt? = null,
                                      limit: UInt? = null,
                                      _meta: JsonElement? = null): ReadTextFileResponse
    public suspend fun fsWriteTextFile(path: String,
                                       content: String,
                                       _meta: JsonElement? = null): WriteTextFileResponse
}