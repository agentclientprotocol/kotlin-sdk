@file:Suppress("unused")

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.MethodName
import kotlinx.serialization.KSerializer
import com.agentclientprotocol.model.v2.CancelSessionNotification as V2CancelSessionNotification
import com.agentclientprotocol.model.v2.CloseSessionRequest as V2CloseSessionRequest
import com.agentclientprotocol.model.v2.CompleteElicitationNotification as V2CompleteElicitationNotification
import com.agentclientprotocol.model.v2.CreateElicitationRequest as V2CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse as V2CreateElicitationResponse
import com.agentclientprotocol.model.v2.CloseSessionResponse as V2CloseSessionResponse
import com.agentclientprotocol.model.v2.DeleteSessionRequest as V2DeleteSessionRequest
import com.agentclientprotocol.model.v2.DisableProviderRequest as V2DisableProviderRequest
import com.agentclientprotocol.model.v2.DisableProviderResponse as V2DisableProviderResponse
import com.agentclientprotocol.model.v2.ForkSessionRequest as V2ForkSessionRequest
import com.agentclientprotocol.model.v2.ForkSessionResponse as V2ForkSessionResponse
import com.agentclientprotocol.model.v2.ListProvidersRequest as V2ListProvidersRequest
import com.agentclientprotocol.model.v2.ListProvidersResponse as V2ListProvidersResponse
import com.agentclientprotocol.model.v2.SetProviderRequest as V2SetProviderRequest
import com.agentclientprotocol.model.v2.SetProviderResponse as V2SetProviderResponse
import com.agentclientprotocol.model.v2.DeleteSessionResponse as V2DeleteSessionResponse
import com.agentclientprotocol.model.v2.ListSessionsRequest as V2ListSessionsRequest
import com.agentclientprotocol.model.v2.ListSessionsResponse as V2ListSessionsResponse
import com.agentclientprotocol.model.v2.LoginAuthRequest as V2LoginAuthRequest
import com.agentclientprotocol.model.v2.LoginAuthResponse as V2LoginAuthResponse
import com.agentclientprotocol.model.v2.LogoutAuthRequest as V2LogoutAuthRequest
import com.agentclientprotocol.model.v2.LogoutAuthResponse as V2LogoutAuthResponse
import com.agentclientprotocol.model.v2.InitializeRequest as V2InitializeRequest
import com.agentclientprotocol.model.v2.InitializeResponse as V2InitializeResponse
import com.agentclientprotocol.model.v2.NewSessionRequest as V2NewSessionRequest
import com.agentclientprotocol.model.v2.NewSessionResponse as V2NewSessionResponse
import com.agentclientprotocol.model.v2.PromptRequest as V2PromptRequest
import com.agentclientprotocol.model.v2.RequestPermissionRequest as V2RequestPermissionRequest
import com.agentclientprotocol.model.v2.ResumeSessionRequest as V2ResumeSessionRequest
import com.agentclientprotocol.model.v2.ResumeSessionResponse as V2ResumeSessionResponse
import com.agentclientprotocol.model.v2.SetSessionConfigOptionRequest as V2SetSessionConfigOptionRequest
import com.agentclientprotocol.model.v2.SetSessionConfigOptionResponse as V2SetSessionConfigOptionResponse
import com.agentclientprotocol.model.v2.RequestPermissionResponse as V2RequestPermissionResponse
import com.agentclientprotocol.model.v2.PromptResponse as V2PromptResponse
import com.agentclientprotocol.model.v2.UpdateSessionNotification as V2UpdateSessionNotification

/**
 * Base interface for ACP method enums.
 *
 * Method calling DSL is defined in `Protocol.extensions.kt`
 *
 * Methods are grouped by the protocol version they belong to: [AgentMethods.V1]/[AgentMethods.V2] and
 * [ClientMethods.V1]. A group holds that version's own request and response types, and a connection has
 * exactly one group's handlers installed — the one it negotiated — so nothing is converted and nothing
 * checks a version per message.
 */
public open class AcpMethod(public val methodName: MethodName) {

    public open class AcpRequestResponseMethod<TRequest : AcpRequest, TResponse : AcpResponse>(
        method: String,
        public val requestSerializer: KSerializer<TRequest>,
        public val responseSerializer: KSerializer<TResponse>
    ) : AcpMethod(MethodName(method))

    public open class AcpSessionRequestResponseMethod<TRequest, TResponse : AcpResponse>(
        method: String,
        requestSerializer: KSerializer<TRequest>,
        responseSerializer: KSerializer<TResponse>
    ) : AcpRequestResponseMethod<TRequest, TResponse>(method, requestSerializer, responseSerializer)
            where TRequest : AcpRequest, TRequest : AcpWithSessionId

    public open class AcpNotificationMethod<TNotification : AcpNotification>(
        method: String,
        public val serializer: KSerializer<TNotification>,
    ) : AcpMethod(MethodName(method))

    public open class AcpSessionNotificationMethod<TNotification>(
        method: String,
        serializer: KSerializer<TNotification>
    ) : AcpNotificationMethod<TNotification>(method, serializer)
            where TNotification : AcpNotification, TNotification : AcpWithSessionId

    public object MetaMethods {
        /**
         * Protocol-level cancellation. Its name and payload are identical in every protocol version,
         * so it stays available regardless of what was negotiated.
         */
        public object CancelRequest : AcpNotificationMethod<CancelRequestNotification>(
            "\$/cancel_request",
            CancelRequestNotification.serializer(),
        )
    }

    public object AgentMethods {
        /** Agent-side methods as they exist in protocol v1. */
        public object V1 {
            // Agent-side operations (methods that agents can call on clients)
            public object Initialize : AcpRequestResponseMethod<InitializeRequest, InitializeResponse>(
                "initialize",
                InitializeRequest.serializer(),
                InitializeResponse.serializer()
            )

            public object Authenticate : AcpRequestResponseMethod<AuthenticateRequest, AuthenticateResponse>(
                "authenticate",
                AuthenticateRequest.serializer(),
                AuthenticateResponse.serializer()
            )

            @UnstableApi
            public object Logout : AcpRequestResponseMethod<LogoutRequest, LogoutResponse>(
                "logout",
                LogoutRequest.serializer(),
                LogoutResponse.serializer()
            )

            @UnstableApi
            public object ProvidersList : AcpRequestResponseMethod<ListProvidersRequest, ListProvidersResponse>(
                "providers/list",
                ListProvidersRequest.serializer(),
                ListProvidersResponse.serializer()
            )

            @UnstableApi
            public object ProvidersSet : AcpRequestResponseMethod<SetProvidersRequest, SetProvidersResponse>(
                "providers/set",
                SetProvidersRequest.serializer(),
                SetProvidersResponse.serializer()
            )

            @UnstableApi
            public object ProvidersDisable :
                AcpRequestResponseMethod<DisableProvidersRequest, DisableProvidersResponse>(
                    "providers/disable",
                    DisableProvidersRequest.serializer(),
                    DisableProvidersResponse.serializer()
                )

            public object SessionNew : AcpRequestResponseMethod<NewSessionRequest, NewSessionResponse>(
                "session/new",
                NewSessionRequest.serializer(),
                NewSessionResponse.serializer()
            )

            public object SessionLoad : AcpRequestResponseMethod<LoadSessionRequest, LoadSessionResponse>(
                "session/load",
                LoadSessionRequest.serializer(),
                LoadSessionResponse.serializer()
            )

            public object SessionDelete : AcpRequestResponseMethod<DeleteSessionRequest, DeleteSessionResponse>(
                "session/delete",
                DeleteSessionRequest.serializer(),
                DeleteSessionResponse.serializer()
            )

            // session specific
            public object SessionPrompt : AcpSessionRequestResponseMethod<PromptRequest, PromptResponse>(
                "session/prompt",
                PromptRequest.serializer(),
                PromptResponse.serializer()
            )

            public object SessionCancel :
                AcpSessionNotificationMethod<CancelNotification>("session/cancel", CancelNotification.serializer())

            public object SessionSetMode :
                AcpSessionRequestResponseMethod<SetSessionModeRequest, SetSessionModeResponse>(
                    "session/set_mode",
                    SetSessionModeRequest.serializer(),
                    SetSessionModeResponse.serializer()
                )

            @UnstableApi
            public object SessionSetModel :
                AcpSessionRequestResponseMethod<SetSessionModelRequest, SetSessionModelResponse>(
                    "session/set_model",
                    SetSessionModelRequest.serializer(),
                    SetSessionModelResponse.serializer()
                )

            // unstable session methods
            @UnstableApi
            public object SessionFork : AcpSessionRequestResponseMethod<ForkSessionRequest, ForkSessionResponse>(
                "session/fork",
                ForkSessionRequest.serializer(),
                ForkSessionResponse.serializer()
            )

            @UnstableApi
            public object SessionList : AcpRequestResponseMethod<ListSessionsRequest, ListSessionsResponse>(
                "session/list",
                ListSessionsRequest.serializer(),
                ListSessionsResponse.serializer()
            )

            @UnstableApi
            public object SessionResume : AcpSessionRequestResponseMethod<ResumeSessionRequest, ResumeSessionResponse>(
                "session/resume",
                ResumeSessionRequest.serializer(),
                ResumeSessionResponse.serializer()
            )

            @UnstableApi
            public object SessionSetConfigOption :
                AcpSessionRequestResponseMethod<SetSessionConfigOptionRequest, SetSessionConfigOptionResponse>(
                    "session/set_config_option",
                    SetSessionConfigOptionRequest.serializer(),
                    SetSessionConfigOptionResponse.serializer()
                )

            @UnstableApi
            public object SessionClose : AcpSessionRequestResponseMethod<CloseSessionRequest, CloseSessionResponse>(
                "session/close",
                CloseSessionRequest.serializer(),
                CloseSessionResponse.serializer()
            )

            // NES methods
            @UnstableApi
            public object NesStart : AcpRequestResponseMethod<StartNesRequest, StartNesResponse>(
                "nes/start",
                StartNesRequest.serializer(),
                StartNesResponse.serializer()
            )

            @UnstableApi
            public object NesSuggest : AcpSessionRequestResponseMethod<SuggestNesRequest, SuggestNesResponse>(
                "nes/suggest",
                SuggestNesRequest.serializer(),
                SuggestNesResponse.serializer()
            )

            @UnstableApi
            public object NesClose : AcpSessionRequestResponseMethod<CloseNesRequest, CloseNesResponse>(
                "nes/close",
                CloseNesRequest.serializer(),
                CloseNesResponse.serializer()
            )

            @UnstableApi
            public object NesAccept :
                AcpSessionNotificationMethod<AcceptNesNotification>("nes/accept", AcceptNesNotification.serializer())

            @UnstableApi
            public object NesReject :
                AcpSessionNotificationMethod<RejectNesNotification>("nes/reject", RejectNesNotification.serializer())

            @UnstableApi
            public object DocumentDidOpen : AcpSessionNotificationMethod<DidOpenDocumentNotification>(
                "document/didOpen",
                DidOpenDocumentNotification.serializer()
            )

            @UnstableApi
            public object DocumentDidChange : AcpSessionNotificationMethod<DidChangeDocumentNotification>(
                "document/didChange",
                DidChangeDocumentNotification.serializer()
            )

            @UnstableApi
            public object DocumentDidClose : AcpSessionNotificationMethod<DidCloseDocumentNotification>(
                "document/didClose",
                DidCloseDocumentNotification.serializer()
            )

            @UnstableApi
            public object DocumentDidSave : AcpSessionNotificationMethod<DidSaveDocumentNotification>(
                "document/didSave",
                DidSaveDocumentNotification.serializer()
            )

            @UnstableApi
            public object DocumentDidFocus : AcpSessionNotificationMethod<DidFocusDocumentNotification>(
                "document/didFocus",
                DidFocusDocumentNotification.serializer()
            )
        }

        /**
         * Agent-side methods as they exist in the v2 draft, with v2's own payload types.
         *
         * Only `initialize` so far: the rest of the v2 surface (`auth/login`, `session/resume`,
         * `session/set_config_option`, ...) is not implemented yet, and a method absent from this group
         * is refused on a v2 connection rather than answered with a v1 payload.
         */
        @UnstableApi
        public object V2 {
            public object Initialize : AcpRequestResponseMethod<V2InitializeRequest, V2InitializeResponse>(
                "initialize",
                V2InitializeRequest.serializer(),
                V2InitializeResponse.serializer(),
            )

            public object SessionNew : AcpRequestResponseMethod<V2NewSessionRequest, V2NewSessionResponse>(
                "session/new",
                V2NewSessionRequest.serializer(),
                V2NewSessionResponse.serializer(),
            )

            public object SessionPrompt : AcpSessionRequestResponseMethod<V2PromptRequest, V2PromptResponse>(
                "session/prompt",
                V2PromptRequest.serializer(),
                V2PromptResponse.serializer(),
            )

            public object SessionCancel : AcpSessionNotificationMethod<V2CancelSessionNotification>(
                "session/cancel",
                V2CancelSessionNotification.serializer(),
            )

            public object AuthLogin : AcpRequestResponseMethod<V2LoginAuthRequest, V2LoginAuthResponse>(
                "auth/login",
                V2LoginAuthRequest.serializer(),
                V2LoginAuthResponse.serializer(),
            )

            public object AuthLogout : AcpRequestResponseMethod<V2LogoutAuthRequest, V2LogoutAuthResponse>(
                "auth/logout",
                V2LogoutAuthRequest.serializer(),
                V2LogoutAuthResponse.serializer(),
            )

            public object SessionList : AcpRequestResponseMethod<V2ListSessionsRequest, V2ListSessionsResponse>(
                "session/list",
                V2ListSessionsRequest.serializer(),
                V2ListSessionsResponse.serializer(),
            )

            public object SessionClose : AcpSessionRequestResponseMethod<V2CloseSessionRequest, V2CloseSessionResponse>(
                "session/close",
                V2CloseSessionRequest.serializer(),
                V2CloseSessionResponse.serializer(),
            )

            public object SessionDelete :
                AcpSessionRequestResponseMethod<V2DeleteSessionRequest, V2DeleteSessionResponse>(
                    "session/delete",
                    V2DeleteSessionRequest.serializer(),
                    V2DeleteSessionResponse.serializer(),
                )

            public object SessionResume :
                AcpSessionRequestResponseMethod<V2ResumeSessionRequest, V2ResumeSessionResponse>(
                    "session/resume",
                    V2ResumeSessionRequest.serializer(),
                    V2ResumeSessionResponse.serializer(),
                )

            public object SessionSetConfigOption :
                AcpSessionRequestResponseMethod<V2SetSessionConfigOptionRequest, V2SetSessionConfigOptionResponse>(
                    "session/set_config_option",
                    V2SetSessionConfigOptionRequest.serializer(),
                    V2SetSessionConfigOptionResponse.serializer(),
                )

            public object SessionFork : AcpSessionRequestResponseMethod<V2ForkSessionRequest, V2ForkSessionResponse>(
                "session/fork",
                V2ForkSessionRequest.serializer(),
                V2ForkSessionResponse.serializer(),
            )

            public object ProvidersList : AcpRequestResponseMethod<V2ListProvidersRequest, V2ListProvidersResponse>(
                "providers/list",
                V2ListProvidersRequest.serializer(),
                V2ListProvidersResponse.serializer(),
            )

            /** Singular, unlike v1's `SetProvidersRequest`, and it replaces a provider's whole configuration. */
            public object ProvidersSet : AcpRequestResponseMethod<V2SetProviderRequest, V2SetProviderResponse>(
                "providers/set",
                V2SetProviderRequest.serializer(),
                V2SetProviderResponse.serializer(),
            )

            public object ProvidersDisable :
                AcpRequestResponseMethod<V2DisableProviderRequest, V2DisableProviderResponse>(
                    "providers/disable",
                    V2DisableProviderRequest.serializer(),
                    V2DisableProviderResponse.serializer(),
                )
        }

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.Initialize"),
        )
        public val Initialize: AcpRequestResponseMethod<InitializeRequest, InitializeResponse> get() = V1.Initialize

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.Authenticate"),
        )
        public val Authenticate: AcpRequestResponseMethod<AuthenticateRequest, AuthenticateResponse> get() = V1.Authenticate

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.Logout"),
        )
        public val Logout: AcpRequestResponseMethod<LogoutRequest, LogoutResponse> get() = V1.Logout

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.ProvidersList"),
        )
        public val ProvidersList: AcpRequestResponseMethod<ListProvidersRequest, ListProvidersResponse> get() = V1.ProvidersList

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.ProvidersSet"),
        )
        public val ProvidersSet: AcpRequestResponseMethod<SetProvidersRequest, SetProvidersResponse> get() = V1.ProvidersSet

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.ProvidersDisable"),
        )
        public val ProvidersDisable: AcpRequestResponseMethod<DisableProvidersRequest, DisableProvidersResponse> get() = V1.ProvidersDisable

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionNew"),
        )
        public val SessionNew: AcpRequestResponseMethod<NewSessionRequest, NewSessionResponse> get() = V1.SessionNew

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionLoad"),
        )
        public val SessionLoad: AcpRequestResponseMethod<LoadSessionRequest, LoadSessionResponse> get() = V1.SessionLoad

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionDelete"),
        )
        public val SessionDelete: AcpRequestResponseMethod<DeleteSessionRequest, DeleteSessionResponse> get() = V1.SessionDelete

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionPrompt"),
        )
        public val SessionPrompt: AcpSessionRequestResponseMethod<PromptRequest, PromptResponse> get() = V1.SessionPrompt

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionCancel"),
        )
        public val SessionCancel: AcpSessionNotificationMethod<CancelNotification> get() = V1.SessionCancel

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionSetMode"),
        )
        public val SessionSetMode: AcpSessionRequestResponseMethod<SetSessionModeRequest, SetSessionModeResponse> get() = V1.SessionSetMode

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionSetModel"),
        )
        public val SessionSetModel: AcpSessionRequestResponseMethod<SetSessionModelRequest, SetSessionModelResponse> get() = V1.SessionSetModel

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionFork"),
        )
        public val SessionFork: AcpSessionRequestResponseMethod<ForkSessionRequest, ForkSessionResponse> get() = V1.SessionFork

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionList"),
        )
        public val SessionList: AcpRequestResponseMethod<ListSessionsRequest, ListSessionsResponse> get() = V1.SessionList

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionResume"),
        )
        public val SessionResume: AcpSessionRequestResponseMethod<ResumeSessionRequest, ResumeSessionResponse> get() = V1.SessionResume

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionSetConfigOption"),
        )
        public val SessionSetConfigOption: AcpSessionRequestResponseMethod<SetSessionConfigOptionRequest, SetSessionConfigOptionResponse> get() = V1.SessionSetConfigOption

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.SessionClose"),
        )
        public val SessionClose: AcpSessionRequestResponseMethod<CloseSessionRequest, CloseSessionResponse> get() = V1.SessionClose

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.NesStart"),
        )
        public val NesStart: AcpRequestResponseMethod<StartNesRequest, StartNesResponse> get() = V1.NesStart

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.NesSuggest"),
        )
        public val NesSuggest: AcpSessionRequestResponseMethod<SuggestNesRequest, SuggestNesResponse> get() = V1.NesSuggest

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.NesClose"),
        )
        public val NesClose: AcpSessionRequestResponseMethod<CloseNesRequest, CloseNesResponse> get() = V1.NesClose

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.NesAccept"),
        )
        public val NesAccept: AcpSessionNotificationMethod<AcceptNesNotification> get() = V1.NesAccept

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.NesReject"),
        )
        public val NesReject: AcpSessionNotificationMethod<RejectNesNotification> get() = V1.NesReject

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.DocumentDidOpen"),
        )
        public val DocumentDidOpen: AcpSessionNotificationMethod<DidOpenDocumentNotification> get() = V1.DocumentDidOpen

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.DocumentDidChange"),
        )
        public val DocumentDidChange: AcpSessionNotificationMethod<DidChangeDocumentNotification> get() = V1.DocumentDidChange

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.DocumentDidClose"),
        )
        public val DocumentDidClose: AcpSessionNotificationMethod<DidCloseDocumentNotification> get() = V1.DocumentDidClose

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.DocumentDidSave"),
        )
        public val DocumentDidSave: AcpSessionNotificationMethod<DidSaveDocumentNotification> get() = V1.DocumentDidSave

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.AgentMethods.V1.DocumentDidFocus"),
        )
        public val DocumentDidFocus: AcpSessionNotificationMethod<DidFocusDocumentNotification> get() = V1.DocumentDidFocus
    }

    public object ClientMethods {
        /**
         * Client-side methods as they exist in the v2 draft, with v2's own payload types.
         *
         * Note what is absent: `fs` and `terminal` do not exist in v2, where file and terminal access goes
         * through MCP-over-ACP.
         */
        @UnstableApi
        public object V2 {
            public object SessionUpdate : AcpSessionNotificationMethod<V2UpdateSessionNotification>(
                "session/update",
                V2UpdateSessionNotification.serializer(),
            )

            public object SessionRequestPermission :
                AcpSessionRequestResponseMethod<V2RequestPermissionRequest, V2RequestPermissionResponse>(
                    "session/request_permission",
                    V2RequestPermissionRequest.serializer(),
                    V2RequestPermissionResponse.serializer(),
                )

            /**
             * Note this is not a session method: a v2 elicitation carries its own scope, which may be a
             * request outside any session.
             */
            public object ElicitationCreate :
                AcpRequestResponseMethod<V2CreateElicitationRequest, V2CreateElicitationResponse>(
                    "elicitation/create",
                    V2CreateElicitationRequest.serializer(),
                    V2CreateElicitationResponse.serializer(),
                )

            public object ElicitationComplete : AcpNotificationMethod<V2CompleteElicitationNotification>(
                "elicitation/complete",
                V2CompleteElicitationNotification.serializer(),
            )
        }

        /** Client-side methods as they exist in protocol v1. */
        public object V1 {
            // Client-side operations (methods that clients can call on agents)
            public object SessionRequestPermission :
                AcpSessionRequestResponseMethod<RequestPermissionRequest, RequestPermissionResponse>(
                    "session/request_permission",
                    RequestPermissionRequest.serializer(),
                    RequestPermissionResponse.serializer()
                )

            public object SessionUpdate :
                AcpSessionNotificationMethod<SessionNotification>("session/update", SessionNotification.serializer())

            // extensions
            public object FsReadTextFile : AcpSessionRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse>(
                "fs/read_text_file",
                ReadTextFileRequest.serializer(),
                ReadTextFileResponse.serializer()
            )

            public object FsWriteTextFile :
                AcpSessionRequestResponseMethod<WriteTextFileRequest, WriteTextFileResponse>(
                    "fs/write_text_file",
                    WriteTextFileRequest.serializer(),
                    WriteTextFileResponse.serializer()
                )

            public object TerminalCreate :
                AcpSessionRequestResponseMethod<CreateTerminalRequest, CreateTerminalResponse>(
                    "terminal/create",
                    CreateTerminalRequest.serializer(),
                    CreateTerminalResponse.serializer()
                )

            public object TerminalOutput :
                AcpSessionRequestResponseMethod<TerminalOutputRequest, TerminalOutputResponse>(
                    "terminal/output",
                    TerminalOutputRequest.serializer(),
                    TerminalOutputResponse.serializer()
                )

            public object TerminalRelease :
                AcpSessionRequestResponseMethod<ReleaseTerminalRequest, ReleaseTerminalResponse>(
                    "terminal/release",
                    ReleaseTerminalRequest.serializer(),
                    ReleaseTerminalResponse.serializer()
                )

            public object TerminalWaitForExit :
                AcpSessionRequestResponseMethod<WaitForTerminalExitRequest, WaitForTerminalExitResponse>(
                    "terminal/wait_for_exit",
                    WaitForTerminalExitRequest.serializer(),
                    WaitForTerminalExitResponse.serializer()
                )

            public object TerminalKill :
                AcpSessionRequestResponseMethod<KillTerminalCommandRequest, KillTerminalCommandResponse>(
                    "terminal/kill",
                    KillTerminalCommandRequest.serializer(),
                    KillTerminalCommandResponse.serializer()
                )

            // Elicitation methods
            @UnstableApi
            public object ElicitationCreate :
                AcpRequestResponseMethod<CreateElicitationRequest, CreateElicitationResponse>(
                    "elicitation/create",
                    CreateElicitationRequest.serializer(),
                    CreateElicitationResponse.serializer()
                )

            @UnstableApi
            public object ElicitationComplete : AcpNotificationMethod<CompleteElicitationNotification>(
                "elicitation/complete",
                CompleteElicitationNotification.serializer()
            )
        }

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.SessionRequestPermission"),
        )
        public val SessionRequestPermission: AcpSessionRequestResponseMethod<RequestPermissionRequest, RequestPermissionResponse> get() = V1.SessionRequestPermission

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.SessionUpdate"),
        )
        public val SessionUpdate: AcpSessionNotificationMethod<SessionNotification> get() = V1.SessionUpdate

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.FsReadTextFile"),
        )
        public val FsReadTextFile: AcpSessionRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse> get() = V1.FsReadTextFile

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.FsWriteTextFile"),
        )
        public val FsWriteTextFile: AcpSessionRequestResponseMethod<WriteTextFileRequest, WriteTextFileResponse> get() = V1.FsWriteTextFile

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.TerminalCreate"),
        )
        public val TerminalCreate: AcpSessionRequestResponseMethod<CreateTerminalRequest, CreateTerminalResponse> get() = V1.TerminalCreate

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.TerminalOutput"),
        )
        public val TerminalOutput: AcpSessionRequestResponseMethod<TerminalOutputRequest, TerminalOutputResponse> get() = V1.TerminalOutput

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.TerminalRelease"),
        )
        public val TerminalRelease: AcpSessionRequestResponseMethod<ReleaseTerminalRequest, ReleaseTerminalResponse> get() = V1.TerminalRelease

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.TerminalWaitForExit"),
        )
        public val TerminalWaitForExit: AcpSessionRequestResponseMethod<WaitForTerminalExitRequest, WaitForTerminalExitResponse> get() = V1.TerminalWaitForExit

        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.TerminalKill"),
        )
        public val TerminalKill: AcpSessionRequestResponseMethod<KillTerminalCommandRequest, KillTerminalCommandResponse> get() = V1.TerminalKill

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.ElicitationCreate"),
        )
        public val ElicitationCreate: AcpRequestResponseMethod<CreateElicitationRequest, CreateElicitationResponse> get() = V1.ElicitationCreate

        @UnstableApi
        @Deprecated(
            "Method objects are grouped by protocol version now",
            ReplaceWith("AcpMethod.ClientMethods.V1.ElicitationComplete"),
        )
        public val ElicitationComplete: AcpNotificationMethod<CompleteElicitationNotification> get() = V1.ElicitationComplete
    }


    public class UnknownMethod(methodName: String) : AcpMethod(MethodName(methodName))

    override fun toString(): String = methodName.name
}
