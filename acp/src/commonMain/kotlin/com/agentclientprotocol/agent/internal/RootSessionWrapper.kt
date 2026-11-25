package com.agentclientprotocol.agent.internal

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSubagents
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.notify
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * A root session: runs its turns one at a time (see [AgentSession.prompt]) and owns the replay window and the subtree
 * scope of its subagents.
 *
 * @param replaying whether the session is being opened by `session/load`: its replay window is then open until
 * [endReplay]
 */
internal class RootSessionWrapper(
    agent: Agent,
    val agentSession: AgentSession,
    override val clientOperations: ClientSessionOperations,
    protocol: Protocol,
    subagentsSupported: Boolean,
    replaying: Boolean = false,
) : SessionWrapper(agent, protocol) {
    override val sessionId: SessionId get() = agentSession.sessionId
    override val root: RootSessionWrapper get() = this

    @OptIn(UnstableApi::class)
    override val subagents: AgentSubagents? = if (subagentsSupported) AgentSubagents(this) else null

    /**
     * The supervisor scope of this root's subtree: the collections of its subagents' events run here.
     *
     * Created on first use, so a session that never has subagents keeps no job attached to the connection. It is a
     * connection scope, so the connection's end cancels it.
     */
    val subtreeScope: CoroutineScope by lazy { protocol.newConnectionScope("Subagents of session $sessionId") }

    /**
     * Guards [_isReplaying] and [replayedCollections] together, so a collection is either deferred before the
     * window closes or started by its caller.
     */
    private val replayLock = SynchronizedObject()

    private val _isReplaying = atomic(replaying)

    /**
     * Whether the replay window of `session/load` is open: everything sent for this subtree meanwhile goes out
     * before the load response, so it is history. A subagent announced meanwhile is a replayed one. The window
     * closes once the transport has accepted the load response, which is where the client's view of history
     * ends too.
     */
    val isReplaying: Boolean get() = _isReplaying.value

    private var replayedCollections = mutableListOf<() -> Unit>()

    /**
     * Runs [start] now, or when the replay window closes if it is still open.
     */
    fun startAfterReplay(start: () -> Unit) {
        val deferred = synchronized(replayLock) {
            if (_isReplaying.value) replayedCollections += start
            _isReplaying.value
        }
        if (!deferred) start()
    }

    /**
     * Closes the replay window and starts the collections deferred until then. Called once the transport has
     * accepted the load response.
     */
    fun endReplay() {
        val starts = synchronized(replayLock) {
            _isReplaying.value = false
            replayedCollections.also { replayedCollections = mutableListOf() }
        }
        starts.forEach { it() }
    }

    /**
     * Cancels the collections of this root's subtree, once the session was closed, its `session/load` failed, or a
     * re-opening of its id replaced it. The caller removes the subagents themselves.
     */
    fun cancelSubtreeScope() {
        subtreeScope.cancel()
    }

    private val _activePrompt = atomic<PromptSession?>(null)

    @OptIn(UnstableApi::class)
    suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement? = null): PromptResponse {
        var response: PromptResponse? = null
        var failure: Throwable? = null
        return coroutineScope {
            val promptJob = launch(start = CoroutineStart.LAZY) {
                /*
                 * Keep a failure inside the turn's job, so a failed turn still waits for running cancels and a
                 * cancel can still decide its answer.
                 */
                try {
                    agentSession.prompt(content, _meta).collect { event ->
                        when (event) {
                            is Event.PromptResponseEvent -> {
                                if (response != null) {
                                    logger.error { "Received repeated prompt response: ${event.response} (previous: $response). The last is used" }
                                }
                                response = event.response
                            }

                            is Event.SessionUpdateEvent -> {
                                clientOperations.notify(event.update, _meta)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    /*
                     * FIXME: a CancellationException that the app's own flow throws, such as an uncaught
                     *  `withTimeout`, is treated as a client cancel: rethrowing it cancels `promptJob`, so the turn
                     *  answers `cancelled` and the real failure is lost.
                     *
                     *  See https://github.com/agentclientprotocol/kotlin-sdk/issues/166.
                     */
                    throw e
                } catch (e: Throwable) {
                    failure = e
                }
            }

            val promptSession = PromptSession(promptJob)
            if (!_activePrompt.compareAndSet(null, promptSession)) {
                error("There is already active prompt execution")
            }
            try {
                promptJob.join()
                /*
                 * A cancelled turn answers only after the session's cancel() returns: the response must follow
                 * every aborted operation and every update, including those sent from cancel().
                 */
                promptSession.awaitCancels()
                /*
                 * The session's cancel() may stop the turn cooperatively, so its flow ends normally before the
                 * SDK cancels the job. The client still cancelled the turn and must get the `cancelled` reason.
                 */
                val isCancelled = promptJob.isCancelled || promptSession.isCancelRequested
                failure?.let { e ->
                    if (!isCancelled) throw e
                    logger.debug(e) { "Prompt turn failed after a cancel; answering as cancelled" }
                }
                response ?: PromptResponse(
                    stopReason = if (isCancelled) StopReason.CANCELLED else StopReason.END_TURN
                )
            } finally {
                // Only the turn frees its slot, so the next prompt can start only after this one has answered.
                _activePrompt.value = null
            }
        }
    }

    suspend fun cancel() {
        /*
         * Take the turn to cancel before notifying the session: the session's cancel() may suspend, and a turn
         * that has already answered may be followed by the next one meanwhile, which this cancel must not touch.
         */
        val promptToCancel = _activePrompt.value?.takeIf { it.startCancel() }
        try {
            agentSession.cancel()

            if (promptToCancel != null) {
                logger.trace { "Cancelling prompt" }
                /*
                 * Cancelling the turn's job cancels every outgoing request it has in flight, such as a pending
                 * `fs/read_text_file` or `session/request_permission`, and each of them sends `$/cancel_request`.
                 */
                promptToCancel.promptJob.cancel()
            }
        } finally {
            promptToCancel?.finishCancel()
        }
    }
}

private class PromptSession(val promptJob: Job) {
    /**
     * The number of cancels still running for this turn, or [ANSWERING] once the turn has passed the wait for
     * them and is about to answer.
     */
    private val runningCancels = MutableStateFlow(0)

    private val _isCancelRequested = atomic(false)

    /**
     * Whether a `session/cancel` targeted this turn before it started answering.
     */
    val isCancelRequested: Boolean get() = _isCancelRequested.value

    /**
     * Registers a cancel for this turn. Returns `false` if the turn is already answering.
     */
    fun startCancel(): Boolean {
        val started = runningCancels.getAndUpdate { if (it == ANSWERING) it else it + 1 } != ANSWERING
        if (started) _isCancelRequested.value = true
        return started
    }

    fun finishCancel() {
        runningCancels.update { it - 1 }
    }

    /**
     * Suspends until no cancel is running for this turn, then marks the turn as answering.
     */
    suspend fun awaitCancels() {
        while (!runningCancels.compareAndSet(0, ANSWERING)) {
            runningCancels.first { it == 0 }
        }
    }

    private companion object {
        const val ANSWERING = -1
    }
}
