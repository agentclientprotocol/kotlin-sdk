@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.MethodName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Base interface for ACP method enums.
 *
 * Method calling DSL is defined in `Protocol.extensions.kt`
 */
public open class AcpMethod(public val methodName: MethodName) {

    public open class AcpRequestResponseMethod<TRequest: AcpRequest, TResponse: AcpResponse>(
        method: String,
        public val requestSerializer: KSerializer<TRequest>,
        public val responseSerializer: KSerializer<TResponse>
    ) : AcpMethod(MethodName(method))

    public open class AcpRequestResponseNullableMethod<TRequest: AcpRequest, TResponse: AcpResponse>(
        method: String,
        public val requestSerializer: KSerializer<TRequest>,
        public val responseSerializer: KSerializer<TResponse>
    ) : AcpMethod(MethodName(method))

    public open class AcpSessionRequestResponseMethod<TRequest, TResponse: AcpResponse>(method: String,
                                                                                        requestSerializer: KSerializer<TRequest>,
                                                                                        responseSerializer: KSerializer<TResponse>
    ) : AcpRequestResponseMethod<TRequest, TResponse>(method, requestSerializer, responseSerializer)
            where TRequest : AcpRequest, TRequest : AcpWithSessionId

    public open class AcpSessionRequestResponseNullableMethod<TRequest, TResponse: AcpResponse>(method: String,
                                                                                        requestSerializer: KSerializer<TRequest>,
                                                                                        responseSerializer: KSerializer<TResponse>
    ) : AcpRequestResponseNullableMethod<TRequest, TResponse>(method, requestSerializer, responseSerializer)
            where TRequest : AcpRequest, TRequest : AcpWithSessionId

    public open class AcpNotificationMethod<TNotification: AcpNotification>(
        method: String,
        public val serializer: KSerializer<TNotification>,
    ) : AcpMethod(MethodName(method))

    public open class AcpSessionNotificationMethod<TNotification>(method: String,
                                                                  serializer: KSerializer<TNotification>
    ) : AcpNotificationMethod<TNotification>(method, serializer)
            where TNotification : AcpNotification, TNotification : AcpWithSessionId

    public object MetaMethods {
        public object CancelRequest : AcpNotificationMethod<CancelRequestNotification>("\$/cancelRequest", CancelRequestNotification.serializer())
    }

    public object AgentMethods {
        // Agent-side operations (methods that agents can call on clients)
        public object Initialize : AcpRequestResponseMethod<InitializeRequest, InitializeResponse>("initialize", InitializeRequest.serializer(), InitializeResponse.serializer())
        public object Authenticate : AcpRequestResponseNullableMethod<AuthenticateRequest, AuthenticateResponse>("authenticate", AuthenticateRequest.serializer(), AuthenticateResponse.serializer())
        public object SessionNew : AcpRequestResponseMethod<NewSessionRequest, NewSessionResponse>("session/new", NewSessionRequest.serializer(), NewSessionResponse.serializer())
        public object SessionLoad : AcpRequestResponseMethod<LoadSessionRequest, LoadSessionResponse>("session/load", LoadSessionRequest.serializer(), LoadSessionResponse.serializer())

        // session specific
        public object SessionPrompt : AcpSessionRequestResponseMethod<PromptRequest, PromptResponse>("session/prompt", PromptRequest.serializer(), PromptResponse.serializer())
        public object SessionCancel : AcpSessionNotificationMethod<CancelNotification>("session/cancel", CancelNotification.serializer())
        public object SessionSetMode : AcpSessionRequestResponseNullableMethod<SetSessionModeRequest, SetSessionModeResponse>("session/set_mode", SetSessionModeRequest.serializer(), SetSessionModeResponse.serializer())
        @UnstableApi
        public object SessionSetModel : AcpSessionRequestResponseNullableMethod<SetSessionModelRequest, SetSessionModelResponse>("session/set_model", SetSessionModelRequest.serializer(), SetSessionModelResponse.serializer())
    }

    public object ClientMethods {
        // Client-side operations (methods that clients can call on agents)
        public object SessionRequestPermission : AcpSessionRequestResponseMethod<RequestPermissionRequest, RequestPermissionResponse>("session/request_permission", RequestPermissionRequest.serializer(), RequestPermissionResponse.serializer())
        public object SessionUpdate : AcpSessionNotificationMethod<SessionNotification>("session/update", SessionNotification.serializer())

        // extensions
        public object FsReadTextFile : AcpSessionRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse>("fs/read_text_file", ReadTextFileRequest.serializer(), ReadTextFileResponse.serializer())
        public object FsWriteTextFile : AcpSessionRequestResponseNullableMethod<WriteTextFileRequest, WriteTextFileResponse>("fs/write_text_file", WriteTextFileRequest.serializer(), WriteTextFileResponse.serializer())
        public object TerminalCreate : AcpSessionRequestResponseMethod<CreateTerminalRequest, CreateTerminalResponse>("terminal/create", CreateTerminalRequest.serializer(), CreateTerminalResponse.serializer())
        public object TerminalOutput : AcpSessionRequestResponseMethod<TerminalOutputRequest, TerminalOutputResponse>("terminal/output", TerminalOutputRequest.serializer(), TerminalOutputResponse.serializer())
        public object TerminalRelease : AcpSessionRequestResponseNullableMethod<ReleaseTerminalRequest, ReleaseTerminalResponse>("terminal/release", ReleaseTerminalRequest.serializer(), ReleaseTerminalResponse.serializer())
        public object TerminalWaitForExit : AcpSessionRequestResponseNullableMethod<WaitForTerminalExitRequest, WaitForTerminalExitResponse>("terminal/wait_for_exit", WaitForTerminalExitRequest.serializer(), WaitForTerminalExitResponse.serializer())
        public object TerminalKill : AcpSessionRequestResponseNullableMethod<KillTerminalCommandRequest, KillTerminalCommandResponse>("terminal/kill", KillTerminalCommandRequest.serializer(), KillTerminalCommandResponse.serializer())
    }


    public class UnknownMethod(methodName: String) : AcpMethod(MethodName(methodName))

    override fun toString(): String = methodName.name
}
