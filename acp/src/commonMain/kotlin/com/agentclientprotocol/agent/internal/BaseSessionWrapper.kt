package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal open class BaseSessionWrapper(
    val agent: Agent,
    val protocol: Protocol
) {
    internal suspend fun <T> executeWithSession(block: suspend () -> T): T {
        return withContext(this.asContextElement()) {
            return@withContext block()
        }
    }
}

internal class SessionWrapperContextElement(val sessionWrapper: BaseSessionWrapper) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SessionWrapperContextElement>
}

internal fun BaseSessionWrapper.asContextElement() = SessionWrapperContextElement(this)

/**
 * The root session or subagent of this context.
 *
 * @param feature what the caller needs from the session, for the error message
 * @throws IllegalStateException outside a session context or in a NES session context
 */
internal fun CoroutineContext.sessionWrapper(feature: String): SessionWrapper {
    val wrapper = this[SessionWrapperContextElement.Key]?.sessionWrapper
        ?: error("No session found in context")
    return wrapper as? SessionWrapper
        ?: error("$feature are not available for NES sessions. Only chat sessions have access to ${feature.lowercase()}.")
}
