package com.agentclientprotocol.client

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.Client as V2Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.client.v2.ElicitationHandler as V2ElicitationHandler
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.InitializeRequest as V1InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.ProtocolVersion
import com.agentclientprotocol.model.v2.InitializeRequest as V2InitializeRequest
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.readProtocolVersionOrNull
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** Configuration used when negotiation selects protocol version 1. */
@UnstableApi
public class V1ClientConfig(
    public val clientInfo: ClientInfo = ClientInfo(),
    public val globalElicitationHandler: GlobalElicitationHandler? = null,
) {
    init {
        require(clientInfo.protocolVersion == LATEST_PROTOCOL_VERSION) {
            "V1ClientConfig requires protocolVersion=$LATEST_PROTOCOL_VERSION, got ${clientInfo.protocolVersion}"
        }
        require(clientInfo.supportedProtocolVersions == setOf(LATEST_PROTOCOL_VERSION)) {
            "V1ClientConfig can only support protocol version $LATEST_PROTOCOL_VERSION, got " +
                clientInfo.supportedProtocolVersions
        }
    }
}

/** Configuration used when negotiation selects protocol version 2. */
@UnstableApi
public class V2ClientConfig(
    public val clientInfo: V2ClientInfo,
    public val elicitationHandler: V2ElicitationHandler? = null,
) {
    init {
        require(clientInfo.protocolVersion == PROTOCOL_VERSION_V2) {
            "V2ClientConfig requires protocolVersion=$PROTOCOL_VERSION_V2, got ${clientInfo.protocolVersion}"
        }
    }
}

/** An initialized client selected by [ClientNegotiator]. */
@UnstableApi
public sealed interface NegotiatedClient {
    public val protocolVersion: ProtocolVersion

    /** A connection negotiated to protocol version 1. */
    public class V1(
        public val client: Client,
        public val agentInfo: AgentInfo,
    ) : NegotiatedClient {
        override val protocolVersion: ProtocolVersion = LATEST_PROTOCOL_VERSION
    }

    /** A connection negotiated to protocol version 2. */
    public class V2(
        public val client: V2Client,
        public val agentInfo: V2AgentInfo,
    ) : NegotiatedClient {
        override val protocolVersion: ProtocolVersion = PROTOCOL_VERSION_V2
    }
}

/**
 * Selects and initializes the newest configured client that the agent supports.
 *
 * Version 1 is always available. Passing [v2] opts into version 2 and makes it the requested version. The
 * raw `initialize` response decides which client is constructed, and that client completes initialization
 * from the response without sending a second request. The result is cached, so repeated and concurrent
 * calls return the same client.
 *
 * The caller owns [protocol] and must start it before calling [negotiate]. A negotiation that cannot produce
 * a usable client closes the protocol because its initialization state is no longer safe to reuse.
 *
 * ```kotlin
 * when (val negotiated = ClientNegotiator(protocol, v1, v2).negotiate()) {
 *     is NegotiatedClient.V1 -> useV1(negotiated.client)
 *     is NegotiatedClient.V2 -> useV2(negotiated.client)
 * }
 * ```
 */
@UnstableApi
public class ClientNegotiator(
    private val protocol: Protocol,
    private val v1: V1ClientConfig = V1ClientConfig(),
    private val v2: V2ClientConfig? = null,
) {
    private sealed interface State {
        data object New : State
        class Resolved(public val client: NegotiatedClient) : State
        class Failed(public val cause: Throwable) : State
    }

    private val mutex = Mutex()
    private var state: State = State.New

    /** Negotiates once and returns the initialized v1 or v2 client selected for this connection. */
    public suspend fun negotiate(): NegotiatedClient = mutex.withLock {
        when (val current = state) {
            is State.Resolved -> return@withLock current.client
            is State.Failed -> throw current.cause
            State.New -> Unit
        }

        try {
            negotiateOnce().also { state = State.Resolved(it) }
        } catch (cause: Throwable) {
            state = State.Failed(cause)
            throw cause
        }
    }

    private suspend fun negotiateOnce(): NegotiatedClient {
        try {
            val rawResponse = requestInitialize()
            val offeredVersion = readProtocolVersionOrNull(rawResponse)
                ?: acpFail("The agent's initialize response is missing the required `protocolVersion` field")
            return when (offeredVersion) {
                PROTOCOL_VERSION_V2 if v2 != null -> initializeV2(rawResponse)
                LATEST_PROTOCOL_VERSION -> initializeV1(rawResponse)
                else -> throw UnsupportedProtocolVersionException(
                    requestedVersion = v2?.clientInfo?.protocolVersion ?: v1.clientInfo.protocolVersion,
                    offeredVersion = offeredVersion,
                    supportedVersions = if (v2 == null) {
                        setOf(LATEST_PROTOCOL_VERSION)
                    } else {
                        setOf(LATEST_PROTOCOL_VERSION, PROTOCOL_VERSION_V2)
                    },
                )
            }
        } catch (cause: Throwable) {
            runCatching { protocol.close() }.onFailure(cause::addSuppressed)
            throw cause
        }
    }

    private suspend fun requestInitialize(): JsonElement {
        val v2Config = v2
        if (v2Config != null) {
            val method = AcpMethod.AgentMethods.V2.Initialize
            val info = v2Config.clientInfo
            return protocol.sendRequestRaw(
                method.methodName,
                ACPJson.encodeToJsonElement(
                    method.requestSerializer,
                    V2InitializeRequest(info.protocolVersion, info.implementation, info.capabilities),
                ),
            )
        }

        val method = AcpMethod.AgentMethods.V1.Initialize
        val info = v1.clientInfo
        return protocol.sendRequestRaw(
            method.methodName,
            ACPJson.encodeToJsonElement(
                method.requestSerializer,
                V1InitializeRequest(info.protocolVersion, info.capabilities, info.implementation),
            ),
        )
    }

    private fun initializeV1(rawResponse: JsonElement): NegotiatedClient.V1 {
        val client = Client(protocol, globalElicitationHandler = v1.globalElicitationHandler)
        val agentInfo = client.completeInitialize(v1.clientInfo, rawResponse)
        return NegotiatedClient.V1(client, agentInfo)
    }

    private fun initializeV2(rawResponse: JsonElement): NegotiatedClient.V2 {
        val config = checkNotNull(v2)
        val client = V2Client(protocol, elicitation = config.elicitationHandler)
        val agentInfo = client.completeInitialize(config.clientInfo, rawResponse)
        return NegotiatedClient.V2(client, agentInfo)
    }
}
