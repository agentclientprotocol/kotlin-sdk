package com.agentclientprotocol.common

import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.serialization.json.JsonElement

public interface FileSystemOperations {
    public suspend fun fsReadTextFile(
        path: String,
        line: UInt? = null,
        limit: UInt? = null,
        _meta: JsonElement? = null
    ): ReadTextFileResponse {
        throw NotImplementedError("Must be implemented by client when advertising fs.readTextFile capability")
    }

    public suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement? = null
    ): WriteTextFileResponse {
        throw NotImplementedError("Must be implemented by client when advertising fs.writeTextFile capability")
    }
}