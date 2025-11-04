package com.agentclientprotocol.model

public interface AcpCreatedSessionResponse : AcpWithMeta {
    public val modes: SessionModeState?
    public val models: SessionModelState?
}