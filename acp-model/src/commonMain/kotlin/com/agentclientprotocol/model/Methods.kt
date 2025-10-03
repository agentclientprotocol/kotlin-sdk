@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.MethodName

/**
 * Base interface for ACP method enums.
 */
public sealed class AcpMethod(public val methodName: MethodName) {

    public sealed class AcpRequestResponseMethod<TRequest: AcpRequest, TResponse: AcpResponse>(public val method: String) : AcpMethod(MethodName(method)) {
    }

    public sealed class AcpNotificationMethod<TNotification: AcpNotification>(public val method: String) : AcpMethod(MethodName(method)) {
    }

    public class AgentMethods {
        // Agent-side operations (methods that agents can call on clients)
        public object Initialize : AcpRequestResponseMethod<InitializeRequest, InitializeResponse>("initialize")
        public object Authenticate : AcpRequestResponseMethod<AuthenticateRequest, AuthenticateResponse>("authenticate")
        public object SessionNew : AcpRequestResponseMethod<NewSessionRequest, NewSessionResponse>("session/new")
        public object SessionLoad : AcpRequestResponseMethod<LoadSessionRequest, LoadSessionResponse>("session/load")
        public object SessionPrompt : AcpRequestResponseMethod<PromptRequest, PromptResponse>("session/prompt")
        public object SessionCancel : AcpNotificationMethod<CancelNotification>("session/cancel")
        public object SessionSetMode : AcpRequestResponseMethod<SetSessionModeRequest, SetSessionModeResponse>("session/set_mode")
    }

    public class ClientMethods {
        // Client-side operations (methods that clients can call on agents)
        public object FsReadTextFile : AcpRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse>("fs/read_text_file")
        public object FsWriteTextFile : AcpRequestResponseMethod<WriteTextFileRequest, WriteTextFileResponse>("fs/write_text_file")
        public object SessionRequestPermission : AcpRequestResponseMethod<RequestPermissionRequest, RequestPermissionResponse>("session/request_permission")
        public object SessionUpdate : AcpNotificationMethod<SessionNotification>("session/update")
        public object TerminalCreate : AcpRequestResponseMethod<CreateTerminalRequest, CreateTerminalResponse>("terminal/create")
        public object TerminalOutput : AcpRequestResponseMethod<TerminalOutputRequest, TerminalOutputResponse>("terminal/output")
        public object TerminalRelease : AcpRequestResponseMethod<ReleaseTerminalRequest, ReleaseTerminalResponse>("terminal/release")
        public object TerminalWaitForExit : AcpRequestResponseMethod<WaitForTerminalExitRequest, WaitForTerminalExitResponse>("terminal/wait_for_exit")
        public object TerminalKill : AcpRequestResponseMethod<KillTerminalCommandRequest, KillTerminalCommandResponse>("terminal/kill")
    }


    public class UnknownMethod(methodName: String) : AcpMethod(MethodName(methodName))
}
