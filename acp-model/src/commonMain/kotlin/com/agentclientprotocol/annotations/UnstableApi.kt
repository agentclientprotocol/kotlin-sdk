package com.agentclientprotocol.annotations

@RequiresOptIn(
    message = "This API is unstable and may change in the future",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
public annotation class UnstableApi