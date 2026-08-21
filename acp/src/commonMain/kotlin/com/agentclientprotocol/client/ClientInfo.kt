package com.agentclientprotocol.client

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.ProtocolVersion
import com.agentclientprotocol.model.SUPPORTED_PROTOCOL_VERSIONS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What a client reports to an agent during initialization.
 *
 * @property protocolVersion the version sent in the `initialize` request, which per spec MUST be the
 *   latest version the client supports — i.e. the maximum of [supportedProtocolVersions].
 * @property supportedProtocolVersions every version this client can speak, used to decide whether
 *   the version the agent answers with is acceptable. Defaults to the SDK's stable versions plus
 *   whatever [protocolVersion] asks for, so an explicit request is always accepted and a downgrade
 *   to a stable version still works. A client wired only for one version passes just that version
 *   and then refuses anything else.
 */
@Serializable
public class ClientInfo(
    public val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION,
    public val capabilities: ClientCapabilities = ClientCapabilities(),
    public val implementation: Implementation? = null,
    public val _meta: JsonElement? = null,
    public val supportedProtocolVersions: Set<ProtocolVersion> = SUPPORTED_PROTOCOL_VERSIONS.toSet() + protocolVersion,
) {
    init {
        require(supportedProtocolVersions.isNotEmpty()) { "A client must support at least one protocol version" }
        require(protocolVersion == supportedProtocolVersions.max()) {
            "protocolVersion must be the latest supported version, but got protocolVersion=$protocolVersion " +
                "with supportedProtocolVersions=$supportedProtocolVersions"
        }
    }
}
