package com.agentclientprotocol.common

import com.agentclientprotocol.model.*
import kotlinx.serialization.json.JsonElement

public interface TerminalOperations {
    public suspend fun terminalCreate(
        command: String,
        args: List<String> = emptyList(),
        cwd: String? = null,
        env: List<EnvVariable> = emptyList(),
        outputByteLimit: ULong? = null,
        _meta: JsonElement? = null
    ): CreateTerminalResponse {
        throw NotImplementedError("Must be implemented by client when advertising terminal capability")
    }

    public suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement? = null
    ): TerminalOutputResponse {
        throw NotImplementedError("Must be implemented by client when advertising terminal capability")
    }

    public suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement? = null
    ): ReleaseTerminalResponse {
        throw NotImplementedError("Must be implemented by client when advertising terminal capability")
    }

    public suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement? = null
    ): WaitForTerminalExitResponse {
        throw NotImplementedError("Must be implemented by client when advertising terminal capability")
    }

    public suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement? = null
    ): KillTerminalCommandResponse {
        throw NotImplementedError("Must be implemented by client when advertising terminal capability")
    }
}