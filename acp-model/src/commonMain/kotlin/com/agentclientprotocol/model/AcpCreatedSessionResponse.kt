package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi

public interface AcpCreatedSessionResponse : AcpWithMeta {
    public val modes: SessionModeState?
    @UnstableApi
    public val models: SessionModelState?
    @UnstableApi
    public val configOptions: List<SessionConfigOption>?
}