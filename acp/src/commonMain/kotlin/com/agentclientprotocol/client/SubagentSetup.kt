@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val logger = KotlinLogging.logger {}

/**
 * Sets up the subagent sessions that the agent announces, when the consumer of the parent's stream reaches the
 * announcement, and runs the consumer of each subagent's queue.
 *
 * @param protocol the connection, for the subagents' `session/cancel`
 * @param scope the scope of the subagents' consumers. It belongs to the connection, so they end with it, and they
 * don't run on the protocol's handler thread.
 * @param failHolder fails a holder and the holders below it, see [ClientSessionRegistry.failSessionTree]
 */
internal class SubagentSetup(
    private val protocol: Protocol,
    private val scope: CoroutineScope,
    private val failHolder: suspend (ClientSessionHolder, Throwable) -> Unit,
) {
    /**
     * Sets up the subagent of [announcement], announced in the stream of [parent], whose session has
     * [parentOperations]: creates its operations with [SubagentOperations.createSubagentOperations], builds its
     * [ClientSubagentSession] with the announcement's patch applied, adds it to the parent's subagents, and starts the
     * consumer of its queue. The caller delivers the announcement afterwards, so inside the parent's `notify` for it
     * the subagent exists.
     *
     * If [parentOperations] don't implement [SubagentOperations], or the factory throws, only the subagent's holder
     * fails, and the failure is logged. A [CancellationException] from the factory while the caller is active counts
     * as such a failure. A cancellation of the caller is rethrown. If the parent failed or was closed while the
     * factory ran, the subagent is neither added nor started. If only the subagent's holder failed or was removed
     * meanwhile, the subagent is added closed and not started.
     *
     * @param parentOpened whether the marker of the parent's stream has been delivered
     */
    suspend fun setUp(
        parent: ClientSessionHolder,
        announcement: QueuedItem.Announcement,
        parentOperations: ClientSessionOperations,
        parentOpened: Boolean,
    ) {
        val child = announcement.child
        val childId = child.sessionId
        val factory = parentOperations as? SubagentOperations
        if (factory == null) {
            val cause = IllegalStateException(
                "The operations of session ${parent.sessionId} don't implement ${SubagentOperations::class.simpleName}, " +
                    "so its subagent session $childId can't be set up"
            )
            logger.error { cause.message }
            failHolder(child, cause)
            return
        }

        val operations = try {
            factory.createSubagentOperations(childId)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            failSetup(child, IllegalStateException("createSubagentOperations was cancelled", ce))
            return
        } catch (t: Throwable) {
            failSetup(child, t)
            return
        }

        val session = ClientSubagentSession(
            sessionId = childId,
            parentSessionId = parent.sessionId,
            operations = operations,
            protocol = protocol,
            parentCwd = parent::sessionCwd,
            parentAdditionalDirectories = parent::sessionAdditionalDirectories,
            children = child::subagents,
            pendingPermissions = child.permissions,
        )
        session.applyPatch(announcement.update, parentOpened)
        child.attachSubagent(session)
        /*
         * The parent failed or was closed while the factory ran. That failure or close also ends the subagent's
         * holder, so the subagent is dropped: the parent's list stays as it was, and the subagent gets no consumer.
         */
        if (!parent.listSubagent(session)) {
            session.markClosed()
            return
        }
        /*
         * The subagent's holder has failed or was removed, perhaps before the attach, so the failure may have missed
         * the session object. It stays in the list, closed, as after any failure of its holder.
         */
        if (!child.startConsumer(scope) { consume(child, session) }) session.markClosed()
    }

    private suspend fun failSetup(child: SubagentSessionHolder, cause: Throwable) {
        logger.error(cause) { "Failed to create the operations of subagent session ${child.sessionId}" }
        failHolder(child, IllegalStateException("Failed to set up subagent session ${child.sessionId}", cause))
    }

    private suspend fun consume(child: SubagentSessionHolder, session: ClientSubagentSession) {
        try {
            child.consumeSubagentQueue(session)
        } catch (ce: CancellationException) {
            // A stopped consumer's holder is already failing, and the end of the connection fails nothing.
            currentCoroutineContext().ensureActive()
            logger.error(ce) { "ClientSessionOperations.notify of subagent session ${child.sessionId} was cancelled" }
            failHolder(child, IllegalStateException("ClientSessionOperations.notify was cancelled", ce))
        } catch (t: Throwable) {
            logger.error(t) { "ClientSessionOperations.notify of subagent session ${child.sessionId} failed" }
            failHolder(child, IllegalStateException("Failed to deliver the history of subagent session ${child.sessionId}", t))
        }
    }
}
