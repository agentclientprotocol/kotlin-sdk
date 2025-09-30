package com.agentclientprotocol.client

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

/**
 * Interface that clients must implement to handle agent requests.
 *
 * This interface defines the contract for client implementations,
 * covering file system operations, permission handling, and session updates.
 *
 * See protocol docs: [Client](https://agentclientprotocol.com/protocol/overview#client)
 */
public interface Client {
    /**
     * Read content from a text file in the client's file system.
     *
     * Only called if the client advertises the `fs.readTextFile` capability.
     * The client should validate the path and ensure it's within allowed boundaries.
     *
     * @param request The file read request containing path and optional line/limit
     * @return The file contents
     */
    public suspend fun fsReadTextFile(request: ReadTextFileRequest): ReadTextFileResponse

    /**
     * Write content to a text file in the client's file system.
     *
     * Only called if the client advertises the `fs.writeTextFile` capability.
     * The client should validate the path and ensure it's within allowed boundaries.
     *
     * @param request The file write request containing path and content
     * @return Write text file response
     */
    public suspend fun fsWriteTextFile(request: WriteTextFileRequest): WriteTextFileResponse

    /**
     * Request permission from the user for a tool call operation.
     *
     * The client should present the permission options to the user and return their choice.
     * This is called when the agent needs authorization for potentially sensitive operations.
     *
     * @param request The permission request with tool call details and options
     * @return The user's permission decision
     */
    public suspend fun sessionRequestPermission(request: RequestPermissionRequest): RequestPermissionResponse

    /**
     * Handle session update notifications from the agent.
     *
     * This is a notification method (no response expected) that receives
     * real-time updates about session progress, including message chunks,
     * tool calls, and execution plans.
     *
     * @param notification The session update notification
     */
    public suspend fun sessionUpdate(notification: SessionNotification)

    /**
     * Creates a new terminal to execute a command.
     *
     * Only called if the client advertises the `terminal` capability.
     * The agent must call [terminalRelease] when done with the terminal to free resources.
     *
     * See protocol docs: [Terminal Documentation](https://agentclientprotocol.com/protocol/terminals)
     *
     * @param request The terminal creation request containing command, args, and environment
     * @return The created terminal's ID
     */
    public suspend fun terminalCreate(request: CreateTerminalRequest): CreateTerminalResponse

    /**
     * Gets the current output and exit status of a terminal.
     *
     * Returns immediately without waiting for the command to complete.
     * If the command has already exited, the exit status is included in the response.
     *
     * See protocol docs: [Getting Terminal Output](https://agentclientprotocol.com/protocol/terminals#getting-output)
     *
     * @param request The terminal output request with terminal ID
     * @return The terminal output, truncation status, and optional exit status
     */
    public suspend fun terminalOutput(request: TerminalOutputRequest): TerminalOutputResponse

    /**
     * Releases a terminal and frees all associated resources.
     *
     * The command is killed if it hasn't exited yet. After release, the terminal ID
     * becomes invalid for all other terminal methods. Tool calls that already contain
     * the terminal ID continue to display its output.
     *
     * See protocol docs: [Releasing Terminals](https://agentclientprotocol.com/protocol/terminals#releasing-terminals)
     *
     * @param request The release terminal request with terminal ID
     * @return Release terminal response
     */
    public suspend fun terminalRelease(request: ReleaseTerminalRequest): ReleaseTerminalResponse

    /**
     * Waits for a terminal command to exit and returns its exit status.
     *
     * This method blocks until the command completes, providing the exit code
     * and/or signal that terminated the process.
     *
     * See protocol docs: [Waiting for Exit](https://agentclientprotocol.com/protocol/terminals#waiting-for-exit)
     *
     * @param request The wait for exit request with terminal ID
     * @return The exit code and/or signal
     */
    public suspend fun terminalWaitForExit(request: WaitForTerminalExitRequest): WaitForTerminalExitResponse

    /**
     * Kills a terminal command without releasing the terminal.
     *
     * While [terminalRelease] also kills the command, this method keeps the terminal ID
     * valid so it can be used with other methods. Useful for implementing command timeouts
     * that terminate the command and then retrieve the final output.
     *
     * Note: Call [terminalRelease] when the terminal is no longer needed.
     *
     * See protocol docs: [Killing Commands](https://agentclientprotocol.com/protocol/terminals#killing-commands)
     *
     * @param request The kill terminal request with terminal ID
     * @return Kill terminal response
     */
    public suspend fun terminalKill(request: KillTerminalCommandRequest): KillTerminalCommandResponse
}