@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.samples.subagents

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSubagentSession
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.MaybeUndefined
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StateUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.samples.subagents.client.LOADING
import com.agentclientprotocol.samples.subagents.client.PERMISSION_ANSWERED_CANCELLED
import com.agentclientprotocol.samples.subagents.client.SubagentsSessions
import com.agentclientprotocol.samples.subagents.client.USER_CANCELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the scripted subagents sample, as `runSubagents` does, and checks the key events of its transcript.
 *
 * Each test checks two runs: one on `runBlocking`'s single thread, and one on `Dispatchers.Default`, where the streams
 * of different sessions really run at once. Lines of different sessions may interleave in any order, so order checks
 * stay within one session's stream, or follow a causal chain: a line the client writes before it acts, then a line
 * that only that action can cause.
 *
 * The transcript has two parts: the live run of the opened session, and the load of that session after its close,
 * which starts with the client's [LOADING] line.
 */
class SubagentsSampleTest {
    private val root = SessionId("root")
    private val reviewer = SessionId("reviewer")
    private val cleaner = SessionId("cleaner")
    private val tester = SessionId("tester")
    private val probe = SessionId("tester-probe")
    private val planner = SessionId("relay-planner")
    private val notes = SessionId("relay-notes")
    private val allSubagents = listOf(reviewer, cleaner, tester, probe, planner, notes)

    private class Part(val lines: List<TranscriptLine>) {
        fun client(sessionId: SessionId) = lines.filter { it.side == Side.CLIENT && it.sessionId == sessionId }

        fun clientUpdates(sessionId: SessionId) = client(sessionId).mapNotNull { it.update }

        fun agent(sessionId: SessionId) = lines.filter { it.side == Side.AGENT && it.sessionId == sessionId }.map { it.text }

        /**
         * The updates the client showed live on the stream of [sessionId], without the replayed history.
         */
        fun liveUpdates(sessionId: SessionId) = client(sessionId).filter { !it.isHistory }.mapNotNull { it.update }

        /**
         * The updates the client showed as history on the stream of [sessionId].
         */
        fun historyUpdates(sessionId: SessionId) = client(sessionId).filter { it.isHistory }.mapNotNull { it.update }
    }

    private class Run(lines: List<TranscriptLine>, val sessions: SubagentsSessions) {
        private val loadStart = lines.indexOfFirst { it.side == Side.CLIENT && it.text == LOADING }

        val live = Part(lines.subList(0, loadStart))
        val load = Part(lines.subList(loadStart, lines.size))
    }

    private fun runSample(context: CoroutineContext, declareSubagents: Boolean): Run = runBlocking(context) {
        val lines = ConcurrentLinkedQueue<TranscriptLine>()
        val sessions = withTimeout(30.seconds) { runSubagentsSample(Transcript { lines += it }, declareSubagents) }
        Run(lines.toList(), sessions)
    }

    private fun eachRun(declareSubagents: Boolean = true, check: (Run) -> Unit) {
        for (context in listOf(EmptyCoroutineContext, Dispatchers.Default)) {
            check(runSample(context, declareSubagents))
        }
    }

    // The live run

    @Test
    fun `the parent names a child only after the child's announcement`() = eachRun { run ->
        val rootStream = run.live.clientUpdates(root)

        for (child in listOf(reviewer, cleaner, tester)) {
            val announcement = rootStream.indexOfFirst { it is SessionUpdate.SubagentUpdate && it.sessionId == child }
            val message = rootStream.indexOfFirst {
                it is SessionUpdate.SessionMessage && it.recipientSessionId.valueOrNull() == child
            }
            val link = rootStream.indexOfFirst {
                it is SessionUpdate.ToolCall && ToolCallContent.Session(child) in it.content
            }
            assertTrue(announcement >= 0, "$child is announced on the root's stream")
            assertTrue(announcement < message, "the announcement of $child precedes the session_message naming it")
            assertTrue(announcement < link, "the announcement of $child precedes the tool call that links it")
        }
    }

    @Test
    fun `a live child's stream starts with Opened`() = eachRun { run ->
        for (child in allSubagents) {
            val first = run.live.client(child).first()
            assertTrue(first.text.startsWith("opened under"), "the first line of $child is its Opened: ${first.text}")
        }
    }

    @Test
    fun `the cancelled child's pending permission is answered cancelled and the child reports idle cancelled`() =
        eachRun { run ->
            val live = run.live
            val cleanerLines = live.client(cleaner).map { it.text }

            val asked = cleanerLines.indexOfFirst { it.startsWith("asks for permission") }
            val cancelled = cleanerLines.indexOf(USER_CANCELS)
            val answered = cleanerLines.indexOf(PERMISSION_ANSWERED_CANCELLED)
            assertTrue(asked in 0 until cancelled, "the user cancels the cleaner while its request is pending")
            assertTrue(cancelled < answered, "the SDK answers the pending request cancelled after the user cancels")

            // Causal: the user writes its line before it cancels, and only that cancel makes the cleaner report this.
            val idle = live.client(root).single { it.update == stateUpdate(cleaner, idle(IdleStopReason.Cancelled)) }
            val userCancels = live.lines.first { it.sessionId == cleaner && it.text == USER_CANCELS }
            assertTrue(live.lines.indexOf(userCancels) < live.lines.indexOf(idle), "idle(cancelled) follows the cancel")

            assertTrue(live.agent(cleaner).none { it.startsWith("permission for") }, "the cleaner's work got no permission")
        }

    @Test
    fun `the failed child reports idle error and keeps its grandchild, whose lost feed is reported unknown`() =
        eachRun { run ->
            val failure = run.live.clientUpdates(root).filterIsInstance<SessionUpdate.SubagentUpdate>()
                .filter { it.sessionId == tester }
                .mapNotNull { it.state.valueOrNull() as? StateUpdate.Idle }
                .single()
            val stopReason = assertIs<IdleStopReason.Error>(failure.stopReason)
            assertEquals("Lost the connection to the Linux runner", stopReason.error?.message)

            val probeSession = run.sessions.opened.subagent(tester).subagents.single()
            assertEquals(probe, probeSession.sessionId)
            assertEquals(tester, probeSession.parentSessionId)
            assertIs<StateUpdate.Unknown>(probeSession.state, "the SDK reports unknown for the probe's lost feed")
            assertTrue(stateUpdate(probe, StateUpdate.Unknown()) in run.live.clientUpdates(tester), "on the tester's stream")
        }

    @Test
    fun `closing the root closes every subagent below it`() = eachRun { run ->
        val beforeClose = run.live.client(reviewer).single { it.text.startsWith("before close") }
        assertTrue("can cancel" in beforeClose.text, "the reviewer could be cancelled before the close")
        assertTrue("close: stopped the workers of reviewer, cleaner, tester" in run.live.agent(root))

        val all = run.sessions.opened.subagents.descendants()
        assertEquals(allSubagents, all.map { it.sessionId })
        for (subagent in all) {
            assertTrue(!subagent.canCancel, "${subagent.sessionId} can't be cancelled after the close")
        }
        assertFailsWith<IllegalStateException> { runBlocking { run.sessions.opened.subagent(reviewer).cancel() } }
    }

    @Test
    fun `the relay nests the native agent's workers under their parents`() = eachRun { run ->
        val plannerSession = run.sessions.opened.subagent(planner)
        assertEquals(root, plannerSession.parentSessionId)
        assertEquals(listOf(notes), plannerSession.subagents.map { it.sessionId })
        assertEquals(planner, plannerSession.subagents.single().parentSessionId)

        val plannerStream = run.live.clientUpdates(planner)
        val announcement = plannerStream.indexOfFirst { it is SessionUpdate.SubagentUpdate && it.sessionId == notes }
        val idle = plannerStream.indexOf(stateUpdate(notes, idle(IdleStopReason.EndTurn)))
        assertTrue(announcement in 0 until idle, "the notes writer is announced and finishes on the planner's stream")
    }

    @Test
    fun `without the subagents capability the agent works inline and exposes no subagent`() =
        eachRun(declareSubagents = false) { run ->
            assertTrue(run.sessions.opened.subagents.isEmpty())
            assertTrue(run.sessions.loaded.subagents.isEmpty())
            val inline = listOf("Reviewer", "Cleaner", "Tester")
            for (part in listOf(run.live, run.load)) {
                val rootStream = part.clientUpdates(root)
                assertTrue(rootStream.none { it is SessionUpdate.SubagentUpdate || it is SessionUpdate.SessionMessage })
                val completed = rootStream.filterIsInstance<SessionUpdate.ToolCall>()
                    .filter { it.status == ToolCallStatus.COMPLETED }
                assertEquals(inline, completed.map { it.title }, "the work is reported inline, and replayed as such")
                assertTrue(part.lines.all { it.sessionId == null || it.sessionId == root })
            }
        }

    // The load

    @Test
    fun `each replayed stream gets its history, then Opened, then live updates`() = eachRun { run ->
        fun isOpened(text: String) = text == "session opened" || text.startsWith("opened under")

        for (id in listOf(root) + allSubagents) {
            // The lines of the stream itself: the updates and the Opened, without the client's other lines about it.
            val texts = run.load.client(id).filter { it.update != null || isOpened(it.text) }.map { it.text }
            val opened = texts.indexOfFirst(::isOpened)
            assertTrue(opened > 0, "$id gets history before its Opened: $texts")
            assertTrue(texts.take(opened).all { it.startsWith("history: ") }, "only history precedes the Opened of $id")
            assertTrue(texts.drop(opened + 1).none { it.startsWith("history: ") }, "no history follows the Opened of $id")
        }
    }

    @Test
    fun `each replayed stream keeps its live order`() = eachRun { run ->
        for (id in listOf(root) + allSubagents) {
            // The SDK sends the probe's `unknown` on its own, and the client sent the user's prompts: neither is recorded.
            val live = run.live.liveUpdates(id).filterNot { it == stateUpdate(probe, StateUpdate.Unknown()) }
            val replayed = run.load.historyUpdates(id).filterNot { it is SessionUpdate.UserMessageChunk }
            assertEquals(live, replayed, "the replay of the stream of $id")
        }

        val rootHistory = run.load.historyUpdates(root)
        val delegation = rootHistory.indexOfFirst { it is SessionUpdate.ToolCall && it.title == "Delegate: Reviewer" }
        val titlePatch = rootHistory.indexOfFirst {
            it is SessionUpdate.SubagentUpdate && it.title.valueOrNull() == "Reviewer: 2 findings"
        }
        assertTrue(delegation in 0 until titlePatch, "the reviewer's title patch still follows its delegation")
    }

    @Test
    fun `the load rebuilds the recorded tree of subagents`() = eachRun { run ->
        val sessions = run.sessions

        fun ClientSession.tree() = subagents.descendants().map { Triple(it.sessionId, it.parentSessionId, it.title) }
        assertEquals(sessions.opened.tree(), sessions.loaded.tree())
        assertEquals(allSubagents, sessions.loaded.subagents.descendants().map { it.sessionId })
        assertNotSame(sessions.opened.subagent(tester), sessions.loaded.subagent(tester), "the load builds new objects")
        assertEquals(listOf(probe), sessions.loaded.subagent(tester).subagents.map { it.sessionId })
    }

    @Test
    fun `nothing replayed counts as active work or grants cancel`() = eachRun { run ->
        val replayedGrant = run.load.client(root).first { line ->
            val update = line.update as? SessionUpdate.SubagentUpdate
            update?.sessionId == reviewer && update.capabilities.valueOrNull()?.cancel != null
        }
        assertTrue(replayedGrant.isHistory, "the history carries the reviewer's first grant")

        // Each one is shown at its own parent's Opened, where the getters are exact.
        val atOpen = run.load.lines.filter { it.side == Side.CLIENT && it.text.startsWith("at open: ") }
        assertEquals(allSubagents.toSet(), atOpen.map { it.sessionId }.toSet())
        assertEquals(allSubagents.size, atOpen.size)
        assertTrue(atOpen.all { "no confirmed state, can't cancel" in it.text }, "replayed states and grants are dropped")

        // After the load, the agent reports each finished child's current state, and nothing else but the reviewer.
        val livePatches = run.load.lines.filter { !it.isHistory }.mapNotNull { it.update as? SessionUpdate.SubagentUpdate }
        val others = livePatches.filter { it.sessionId != reviewer }
        assertEquals(setOf(cleaner, tester, planner, notes), others.map { it.sessionId }.toSet())
        assertTrue(others.all { it.state.valueOrNull() is StateUpdate.Idle && it.capabilities == MaybeUndefined.Undefined })
        assertNull(run.sessions.loaded.subagent(tester).subagents.single().state, "the probe's state stays unconfirmed")
    }

    @Test
    fun `a replayed subagent can be cancelled once the agent grants cancel again`() = eachRun { run ->
        val load = run.load
        val rootLive = load.client(root).filter { !it.isHistory }

        val grant = rootLive.single { line ->
            val update = line.update as? SessionUpdate.SubagentUpdate
            update?.capabilities?.valueOrNull()?.cancel != null
        }
        assertTrue(grant.text.endsWith("running, can cancel"), "the fresh grant makes the reviewer cancellable: ${grant.text}")
        val idle = rootLive.single { it.update == stateUpdate(reviewer, idle(IdleStopReason.Cancelled)) }
        assertTrue(rootLive.indexOf(grant) < rootLive.indexOf(idle), "on the root's stream, the grant precedes idle")

        // Causal: the user writes its line before it cancels, and only that cancel makes the reviewer report idle.
        val userCancels = load.lines.single { it.sessionId == reviewer && it.text.startsWith("user cancels") }
        assertTrue(load.lines.indexOf(userCancels) < load.lines.indexOf(idle), "idle(cancelled) follows the cancel")
        assertTrue("task ended: cancelled" in load.agent(reviewer))
    }

    @Test
    fun `closing the loaded root closes every replayed subagent`() = eachRun { run ->
        val beforeClose = run.load.client(reviewer).single { it.text.startsWith("before the second close") }
        assertTrue("can cancel" in beforeClose.text, "the fresh grant held until the second close")
        assertTrue("close: stopped the workers of reviewer" in run.load.agent(root))

        for (subagent in run.sessions.loaded.subagents.descendants()) {
            assertTrue(!subagent.canCancel, "${subagent.sessionId} can't be cancelled after the second close")
        }
        assertFailsWith<IllegalStateException> { runBlocking { run.sessions.loaded.subagent(reviewer).cancel() } }
    }

    private fun ClientSession.subagent(id: SessionId): ClientSubagentSession = subagents.single { it.sessionId == id }

    private fun List<ClientSubagentSession>.descendants(): List<ClientSubagentSession> =
        flatMap { listOf(it) + it.subagents.descendants() }

    private fun idle(stopReason: IdleStopReason) = StateUpdate.Idle(stopReason)

    private fun stateUpdate(id: SessionId, state: StateUpdate) =
        SessionUpdate.SubagentUpdate(id, state = MaybeUndefined.Value(state))
}

private val TranscriptLine.isHistory: Boolean get() = text.startsWith("history: ")
