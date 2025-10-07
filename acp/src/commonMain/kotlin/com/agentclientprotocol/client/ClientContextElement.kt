package com.agentclientprotocol.client

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class ClientContextElement(val client: Client) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ClientContextElement>
}

/**
 * Returns a remote client connected to the counterpart via the current protocol
 */
public val CoroutineContext.remoteClient: Client
    get() = this[ClientContextElement.Key]?.client ?: error("No remote client found in context")

internal fun Client.asContextElement() = ClientContextElement(this)
