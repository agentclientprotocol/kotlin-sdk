package com.agentclientprotocol.client

import com.agentclientprotocol.model.*

public interface TerminalOperations {
    public companion object : HandlerExtension<TerminalOperations> {
        override fun RegistrarContext<TerminalOperations>.register() {
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { operations, params ->
                operations.terminalCreate(params)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { operations, params ->
                operations.terminalOutput(params)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { operations, params ->
                operations.terminalRelease(params)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { operations, params ->
                operations.terminalWaitForExit(params)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalKill) { operations, params ->
                operations.terminalKill(params)
            }
        }
    }
    public suspend fun terminalCreate(params: CreateTerminalRequest): CreateTerminalResponse
    public suspend fun terminalOutput(params: TerminalOutputRequest): TerminalOutputResponse
    public suspend fun terminalRelease(params: ReleaseTerminalRequest): ReleaseTerminalResponse
    public suspend fun terminalWaitForExit(params: WaitForTerminalExitRequest): WaitForTerminalExitResponse
    public suspend fun terminalKill(params: KillTerminalCommandRequest): KillTerminalCommandResponse
}