package com.agentclientprotocol.client

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.WriteTextFileRequest
import com.agentclientprotocol.model.WriteTextFileResponse

public interface FileSystemOperations {
    public companion object : HandlerExtension<FileSystemOperations> {

        override fun RegistrarContext<FileSystemOperations>.register() {
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { operations, params ->
                operations.fsReadTextFile(params)
            }

            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { operations, params ->
                operations.fsWriteTextFile(params)
            }
        }
    }
    public suspend fun fsReadTextFile(params: ReadTextFileRequest): ReadTextFileResponse
    public suspend fun fsWriteTextFile(params: WriteTextFileRequest): WriteTextFileResponse
}