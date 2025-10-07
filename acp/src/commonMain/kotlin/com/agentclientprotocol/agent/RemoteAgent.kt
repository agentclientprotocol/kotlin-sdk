package com.agentclientprotocol.agent

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AuthenticateRequest
import com.agentclientprotocol.model.AuthenticateResponse
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.LoadSessionResponse
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.NewSessionResponse
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SetSessionModeRequest
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.protocol.sendRequest

public class RemoteAgent(private val protocol: Protocol) : Agent {
    override suspend fun initialize(request: InitializeRequest): InitializeResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.Initialize, request)
    }

    override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.Authenticate, request)
    }

    override suspend fun sessionNew(request: NewSessionRequest): NewSessionResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionNew, request)
    }

    override suspend fun sessionLoad(request: LoadSessionRequest): LoadSessionResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionLoad, request)
    }

    override suspend fun sessionSetMode(request: SetSessionModeRequest): SetSessionModeResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionSetMode, request)
    }

    override suspend fun sessionPrompt(request: PromptRequest): PromptResponse {
        return protocol.sendRequest(AcpMethod.AgentMethods.SessionPrompt, request)
    }

    override suspend fun sessionCancel(notification: CancelNotification) {
        protocol.sendNotification(AcpMethod.AgentMethods.SessionCancel, notification)
    }
}