@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.MethodName

/**
 * Base interface for ACP method enums.
 *
 * Method calling DSL is defined in `Protocol.extensions.kt`
 */
public open class AcpMethod(public val methodName: MethodName) {

    public open class AcpRequestResponseMethod<TRequest: AcpRequest, TResponse: AcpResponse>(method: String) : AcpMethod(MethodName(method))

    public open class AcpSessionRequestResponseMethod<TRequest, TResponse: AcpResponse>(method: String) : AcpRequestResponseMethod<TRequest, TResponse>(method)
            where TRequest : AcpRequest, TRequest : AcpWithSessionId

    public open class AcpNotificationMethod<TNotification: AcpNotification>(method: String) : AcpMethod(MethodName(method))

    public open class AcpSessionNotificationMethod<TNotification>(method: String) : AcpNotificationMethod<TNotification>(method)
            where TNotification : AcpNotification, TNotification : AcpWithSessionId

    public object MetaMethods {
        public object CancelRequest : AcpNotificationMethod<CancelRequestNotification>("\$/cancelRequest")
    }

    public object AgentMethods {
        // Agent-side operations (methods that agents can call on clients)
        public object Initialize : AcpRequestResponseMethod<InitializeRequest, InitializeResponse>("initialize")
        public object Authenticate : AcpRequestResponseMethod<AuthenticateRequest, AuthenticateResponse>("authenticate")
        public object SessionNew : AcpRequestResponseMethod<NewSessionRequest, NewSessionResponse>("session/new")
        public object SessionLoad : AcpRequestResponseMethod<LoadSessionRequest, LoadSessionResponse>("session/load")

        // session specific
        public object SessionPrompt : AcpSessionRequestResponseMethod<PromptRequest, PromptResponse>("session/prompt")
        public object SessionCancel : AcpSessionNotificationMethod<CancelNotification>("session/cancel")
        public object SessionSetMode : AcpSessionRequestResponseMethod<SetSessionModeRequest, SetSessionModeResponse>("session/set_mode")
        public object SessionSetModel : AcpSessionRequestResponseMethod<SetSessionModelRequest, SetSessionModelResponse>("session/set_model")
    }

    public object ClientMethods {
        // Client-side operations (methods that clients can call on agents)
        public object SessionRequestPermission : AcpSessionRequestResponseMethod<RequestPermissionRequest, RequestPermissionResponse>("session/request_permission")
        public object SessionUpdate : AcpSessionNotificationMethod<SessionNotification>("session/update")

        // extensions
        public object FsReadTextFile : AcpSessionRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse>("fs/read_text_file")
        public object FsWriteTextFile : AcpSessionRequestResponseMethod<WriteTextFileRequest, WriteTextFileResponse>("fs/write_text_file")
        public object TerminalCreate : AcpSessionRequestResponseMethod<CreateTerminalRequest, CreateTerminalResponse>("terminal/create")
        public object TerminalOutput : AcpSessionRequestResponseMethod<TerminalOutputRequest, TerminalOutputResponse>("terminal/output")
        public object TerminalRelease : AcpSessionRequestResponseMethod<ReleaseTerminalRequest, ReleaseTerminalResponse>("terminal/release")
        public object TerminalWaitForExit : AcpSessionRequestResponseMethod<WaitForTerminalExitRequest, WaitForTerminalExitResponse>("terminal/wait_for_exit")
        public object TerminalKill : AcpSessionRequestResponseMethod<KillTerminalCommandRequest, KillTerminalCommandResponse>("terminal/kill")
    }


    public class UnknownMethod(methodName: String) : AcpMethod(MethodName(methodName))

    override fun toString(): String = methodName.name
}
