@file:Suppress("unused")

package com.agentclientprotocol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Terminal-related request and response types.
 * 
 * **UNSTABLE**: These types are not part of the spec yet,
 * and may be removed or changed at any point.
 */

/**
 * Request to create a new terminal session.
 */
@Serializable
public data class CreateTerminalRequest(
    val sessionId: SessionId,
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val env: List<EnvVariable> = emptyList(),
    val outputByteLimit: ULong? = null,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response from creating a terminal session.
 */
@Serializable
public data class CreateTerminalResponse(
    val terminalId: String,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request to get output from a terminal.
 */
@Serializable
public data class TerminalOutputRequest(
    val sessionId: SessionId,
    val terminalId: String,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response containing terminal output.
 */
@Serializable
public data class TerminalOutputResponse(
    val output: String,
    val truncated: Boolean,
    val exitStatus: TerminalExitStatus? = null,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Request to release a terminal session.
 */
@Serializable
public data class ReleaseTerminalRequest(
    val sessionId: SessionId,
    val terminalId: String,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Request to wait for a terminal to exit.
 */
@Serializable
public data class WaitForTerminalExitRequest(
    val sessionId: SessionId,
    val terminalId: String,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response from waiting for terminal exit.
 */
@Serializable
public data class WaitForTerminalExitResponse(
    val exitCode: UInt? = null,
    val signal: String? = null,
    override val _meta: JsonElement? = null
) : AcpResponse

/**
 * Terminal exit status information.
 */
@Serializable
public data class TerminalExitStatus(
    val exitCode: UInt? = null,
    val signal: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * Request to kill a terminal command without releasing the terminal.
 */
@Serializable
public data class KillTerminalCommandRequest(
    val sessionId: SessionId,
    val terminalId: String,
    override val _meta: JsonElement? = null
) : AcpRequest

/**
 * Response to terminal/kill command method
 */
@Serializable
public data class KillTerminalCommandResponse(
    override val _meta: JsonElement? = null
) : AcpResponse