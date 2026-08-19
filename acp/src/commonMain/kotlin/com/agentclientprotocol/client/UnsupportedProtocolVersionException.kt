package com.agentclientprotocol.client

import com.agentclientprotocol.model.ProtocolVersion

/**
 * Thrown when an agent answers `initialize` with a protocol version the client cannot speak.
 *
 * The spec says a client in this situation SHOULD close the connection and inform the user about it
 * ([version negotiation](https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation)).
 * Whether it is closed before this exception is thrown depends on the caller: a negotiator may keep it
 * open to retry with a version the agent offered.
 *
 * @property requestedVersion the version the client asked for
 * @property offeredVersion the version the agent answered with
 * @property supportedVersions the versions the client can speak
 */
public class UnsupportedProtocolVersionException(
    public val requestedVersion: ProtocolVersion,
    public val offeredVersion: ProtocolVersion,
    public val supportedVersions: Set<ProtocolVersion>,
) : Exception(
    "The agent answered protocol version $offeredVersion, which this client does not support " +
        "(requested $requestedVersion, supports $supportedVersions)."
)
