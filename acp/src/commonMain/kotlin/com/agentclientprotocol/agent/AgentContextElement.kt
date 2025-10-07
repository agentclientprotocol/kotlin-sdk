package com.agentclientprotocol.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class AgentContextElement(val agent: Agent) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<AgentContextElement>
}

/**
 * Returns an remote agent connected to the counterpart via the current protocol
 */
public val CoroutineContext.remoteAgent: Agent
    get() = this[AgentContextElement.Key]?.agent ?: error("No remote agent found in context")

internal fun Agent.asContextElement() = AgentContextElement(this)