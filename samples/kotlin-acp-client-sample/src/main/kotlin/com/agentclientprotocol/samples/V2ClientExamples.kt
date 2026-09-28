@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientNegotiator
import com.agentclientprotocol.client.NegotiatedClient
import com.agentclientprotocol.client.V1ClientConfig
import com.agentclientprotocol.client.V2ClientConfig
import com.agentclientprotocol.client.v2.Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.client.v2.ClientSessionOperations
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.SessionConfigOptionValue
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.UpdateSessionNotification
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

private class AllowFirstPermission : ClientSessionOperations {
    override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        val outcome = request.options.firstOrNull()?.let { RequestPermissionOutcome.Selected(it.optionId) }
            ?: RequestPermissionOutcome.Cancelled
        return RequestPermissionResponse(outcome)
    }
}

/**
 * Connects directly with a v2 client.
 */
suspend fun CoroutineScope.runDirectV2Client(transport: Transport) {
    val protocol = Protocol(this, transport)
    val conversation = V2Conversation()
    val client = Client(protocol, operations = AllowFirstPermission(), onSessionUpdate = conversation::onUpdate)
    protocol.start()

    try {
        val agentInfo = client.initialize(v2ClientInfo())
        println("Connected directly to ${agentInfo.implementation.name} over ACP v2")
        conversation.run(client)
    } finally {
        protocol.close()
    }
}

/**
 * Negotiates once and then uses the concrete v1 or v2 client returned by the SDK.
 */
suspend fun CoroutineScope.runNegotiatedClient(transport: Transport) {
    val conversation = V2Conversation()
    val protocol = Protocol(this, transport)
    protocol.start()

    try {
        val negotiated = ClientNegotiator(
            protocol = protocol,
            v1 = V1ClientConfig(ClientInfo(implementation = Implementation("sample-client", "1.0.0"))),
            v2 = V2ClientConfig(
                v2ClientInfo(),
                operations = AllowFirstPermission(),
                onSessionUpdate = conversation::onUpdate,
            ),
        ).negotiate()

        when (negotiated) {
            is NegotiatedClient.V1 -> println("Agent selected ACP v1: ${negotiated.agentInfo.implementation?.name}")
            is NegotiatedClient.V2 -> {
                println("Agent selected ACP v2: ${negotiated.agentInfo.implementation.name}")
                conversation.run(negotiated.client)
            }
        }
    } finally {
        protocol.close()
    }
}

private fun v2ClientInfo() = V2ClientInfo(
    protocolVersion = PROTOCOL_VERSION_V2,
    implementation = Implementation("sample-client", "2.0.0"),
)

/**
 * Application state for this sample's single session and single turn.
 */
private class V2Conversation {
    private val turnFinished = CompletableDeferred<Unit>()

    fun onUpdate(received: UpdateSessionNotification) {
        received.update.render()
        val state = (received.update as? SessionUpdate.StateUpdate)?.state
        if (state is StateUpdate.Idle) turnFinished.complete(Unit)
    }

    suspend fun run(client: Client) {
        val response = client.newSession(cwd = Paths.get("").absolutePathString())
        val session = client.session(response.sessionId)

        if (response.configOptions.any { it.configId == SessionConfigId("response_mode") }) {
            val configured = session.setConfigOption(
                SessionConfigId("response_mode"),
                SessionConfigOptionValue.Id(SessionConfigValueId("uppercase")),
            )
            println("Configured next reply: $configured")
        }
        session.prompt(listOf(ContentBlock.Text("Hello from the v2 client")))
        turnFinished.await()
        session.close()
    }
}
