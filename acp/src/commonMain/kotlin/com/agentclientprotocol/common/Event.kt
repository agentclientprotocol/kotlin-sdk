package com.agentclientprotocol.common

import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

public sealed class Event {
    /** A session update and the optional [_meta] from its notification envelope. */
    public class SessionUpdateEvent(public val update: SessionUpdate, public val _meta: JsonElement?) : Event() {
        public constructor(update: SessionUpdate) : this(update, null)
    }

    public class PromptResponseEvent(public val response: PromptResponse) : Event()
}