package com.agentclientprotocol.common

import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate

public sealed class Event {
    public class SessionUpdateEvent(public val update: SessionUpdate) : Event()
    public class PromptResponseEvent(public val response: PromptResponse) : Event()
}