package com.agentclientprotocol.client

import com.agentclientprotocol.common.HandlerSideExtension
import com.agentclientprotocol.common.RegistrarContext
import com.agentclientprotocol.common.RemoteSideExtension
import com.agentclientprotocol.common.setSessionExtensionRequestHandler
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.RpcMethodsOperations
import com.agentclientprotocol.protocol.invoke
import kotlinx.serialization.json.JsonElement

public interface TerminalOperations {
    public companion object : HandlerSideExtension<TerminalOperations>, RemoteSideExtension<TerminalOperations> {
        override fun RegistrarContext<TerminalOperations>.register() {
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { operations, params ->
                operations.terminalCreate(params.command, params.args, params.cwd, params.env, params.outputByteLimit, params._meta)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { operations, params ->
                operations.terminalOutput(params.terminalId, params._meta)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { operations, params ->
                operations.terminalRelease(params.terminalId, params._meta)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { operations, params ->
                operations.terminalWaitForExit(params.terminalId, params._meta)
            }
            setSessionExtensionRequestHandler(AcpMethod.ClientMethods.TerminalKill) { operations, params ->
                operations.terminalKill(params.terminalId, params._meta)
            }
        }

        override fun isSupported(remoteSideCapabilities: AcpCapabilities): Boolean {
            return remoteSideCapabilities is ClientCapabilities && remoteSideCapabilities.terminal
        }

        override fun createSessionRemote(
            rpc: RpcMethodsOperations,
            capabilities: AcpCapabilities,
            sessionId: SessionId,
        ): TerminalOperations {
            return object : TerminalOperations {
                override suspend fun terminalCreate(
                    command: String,
                    args: List<String>,
                    cwd: String?,
                    env: List<EnvVariable>,
                    outputByteLimit: ULong?,
                    _meta: JsonElement?,
                ): CreateTerminalResponse {
                    return AcpMethod.ClientMethods.TerminalCreate(rpc, CreateTerminalRequest(sessionId, command, args, cwd, env, outputByteLimit, _meta))
                }

                override suspend fun terminalOutput(
                    terminalId: String,
                    _meta: JsonElement?,
                ): TerminalOutputResponse {
                    return AcpMethod.ClientMethods.TerminalOutput(rpc, TerminalOutputRequest(sessionId, terminalId, _meta))
                }

                override suspend fun terminalRelease(
                    terminalId: String,
                    _meta: JsonElement?,
                ): ReleaseTerminalResponse {
                    return AcpMethod.ClientMethods.TerminalRelease(rpc, ReleaseTerminalRequest(sessionId, terminalId, _meta))
                }

                override suspend fun terminalWaitForExit(
                    terminalId: String,
                    _meta: JsonElement?,
                ): WaitForTerminalExitResponse {
                    return AcpMethod.ClientMethods.TerminalWaitForExit(rpc, WaitForTerminalExitRequest(sessionId, terminalId, _meta))
                }

                override suspend fun terminalKill(
                    terminalId: String,
                    _meta: JsonElement?,
                ): KillTerminalCommandResponse {
                    return AcpMethod.ClientMethods.TerminalKill(rpc, KillTerminalCommandRequest(sessionId, terminalId, _meta))
                }
            }
        }

        override val name: String
            get() = TerminalOperations::class.simpleName!!
    }
    public suspend fun terminalCreate(command: String,
                                      args: List<String> = emptyList(),
                                      cwd: String? = null,
                                      env: List<EnvVariable> = emptyList(),
                                      outputByteLimit: ULong? = null,
                                      _meta: JsonElement? = null): CreateTerminalResponse

    public suspend fun terminalOutput(terminalId: String,
                                      _meta: JsonElement? = null): TerminalOutputResponse

    public suspend fun terminalRelease(terminalId: String,
                                      _meta: JsonElement? = null): ReleaseTerminalResponse

    public suspend fun terminalWaitForExit(terminalId: String,
                                      _meta: JsonElement? = null): WaitForTerminalExitResponse

    public suspend fun terminalKill(terminalId: String,
                                      _meta: JsonElement? = null): KillTerminalCommandResponse
}