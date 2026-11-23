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
import com.agentclientprotocol.samples.subagents.client.PERMISSION_ANSWERED_CANCELLED
import com.agentclientprotocol.samples.subagents.client.USER_CANCELS
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the scripted subagents sample, as `runSubagents` does, and checks the key events of its transcript. Lines of
 * different sessions may interleave in any order, so the order checks stay within one session's stream, or follow a
 * causal chain.
 */
class SubagentsSampleTest {
    private val root = SessionId("root")
    private val reviewer = SessionId("reviewer")
    private val cleaner = SessionId("cleaner")
    private val tester = SessionId("tester")
    private val probe = SessionId("tester-probe")
    private val planner = SessionId("relay-planner")
    private val notes = SessionId("relay-notes")

    private class Run(val lines: List<TranscriptLine>, val session: ClientSession) {
        fun client(sessionId: SessionId) = lines.filter { it.side == Side.CLIENT && it.sessionId == sessionId }

        fun clientUpdates(sessionId: SessionId) = client(sessionId).mapNotNull { it.update }
    }

    private fun runSample(declareSubagents: Boolean = true): Run = runBlocking {
        val lines = ConcurrentLinkedQueue<TranscriptLine>()
        val session = withTimeout(30.seconds) { runSubagentsSample(Transcript { lines += it }, declareSubagents) }
        Run(lines.toList(), session)
    }

    @Test
    fun `the parent names a child only after the child's announcement`() {
        val run = runSample()
        val rootStream = run.clientUpdates(root)

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
    fun `a live child's stream starts with Opened`() {
        val run = runSample()

        for (child in listOf(reviewer, cleaner, tester, probe, planner, notes)) {
            val first = run.client(child).first()
            assertTrue(first.text.startsWith("opened under"), "the first line of $child is its Opened: ${first.text}")
        }
    }

    @Test
    fun `the cancelled child's pending permission is answered cancelled and the child reports idle cancelled`() {
        val run = runSample()
        val cleanerLines = run.client(cleaner).map { it.text }

        val asked = cleanerLines.indexOfFirst { it.startsWith("asks for permission") }
        val cancelled = cleanerLines.indexOf(USER_CANCELS)
        val answered = cleanerLines.indexOf(PERMISSION_ANSWERED_CANCELLED)
        assertTrue(asked in 0 until cancelled, "the user cancels the cleaner while its request is pending")
        assertTrue(cancelled < answered, "the SDK answers the pending request cancelled after the user cancels")

        val idle = run.client(root).single { it.update == stateUpdate(cleaner, StateUpdate.Idle(IdleStopReason.Cancelled)) }
        val userCancels = run.lines.first { it.sessionId == cleaner && it.text == USER_CANCELS }
        assertTrue(run.lines.indexOf(userCancels) < run.lines.indexOf(idle), "idle(cancelled) follows the user's cancel")

        val agentLines = run.lines.filter { it.side == Side.AGENT && it.sessionId == cleaner }.map { it.text }
        assertTrue(agentLines.none { it.startsWith("permission for") }, "the cleaner's work never got a permission")
    }

    @Test
    fun `the failed child reports idle error and keeps its grandchild, whose lost feed is reported unknown`() {
        val run = runSample()

        val failure = run.clientUpdates(root).filterIsInstance<SessionUpdate.SubagentUpdate>()
            .filter { it.sessionId == tester }
            .mapNotNull { it.state.valueOrNull() as? StateUpdate.Idle }
            .single()
        val stopReason = assertIs<IdleStopReason.Error>(failure.stopReason)
        assertEquals("Lost the connection to the Linux runner", stopReason.error?.message)

        val testerSession = run.session.subagent(tester)
        val probeSession = testerSession.subagents.single()
        assertEquals(probe, probeSession.sessionId)
        assertEquals(tester, probeSession.parentSessionId)
        assertIs<StateUpdate.Unknown>(probeSession.state, "the SDK reports unknown for the probe's lost feed")
        assertTrue(stateUpdate(probe, StateUpdate.Unknown()) in run.clientUpdates(tester), "on the tester's stream")
    }

    @Test
    fun `closing the root closes every subagent below it`() {
        val run = runSample()

        val beforeClose = run.client(reviewer).single { it.text.startsWith("before close") }
        assertTrue("can cancel" in beforeClose.text, "the reviewer could be cancelled before the close")
        assertTrue(run.lines.any { it.side == Side.AGENT && it.text == "close: stopped the workers of 3 subagents" })

        val all = run.session.subagents.descendants()
        assertEquals(listOf(reviewer, cleaner, tester, probe, planner, notes), all.map { it.sessionId })
        for (subagent in all) {
            assertTrue(!subagent.canCancel, "${subagent.sessionId} can't be cancelled after the close")
        }
        assertFailsWith<IllegalStateException> { runBlocking { run.session.subagent(reviewer).cancel() } }
    }

    @Test
    fun `the relay nests the native agent's workers under their parents`() {
        val run = runSample()

        val plannerSession = run.session.subagent(planner)
        assertEquals(root, plannerSession.parentSessionId)
        assertEquals(listOf(notes), plannerSession.subagents.map { it.sessionId })
        assertEquals(planner, plannerSession.subagents.single().parentSessionId)

        val plannerStream = run.clientUpdates(planner)
        val announcement = plannerStream.indexOfFirst { it is SessionUpdate.SubagentUpdate && it.sessionId == notes }
        val idle = plannerStream.indexOf(stateUpdate(notes, StateUpdate.Idle(IdleStopReason.EndTurn)))
        assertTrue(announcement in 0 until idle, "the notes writer is announced and finishes on the planner's stream")
    }

    @Test
    fun `without the subagents capability the agent works inline and exposes no subagent`() {
        val run = runSample(declareSubagents = false)

        assertTrue(run.session.subagents.isEmpty())
        val rootStream = run.clientUpdates(root)
        assertTrue(rootStream.none { it is SessionUpdate.SubagentUpdate || it is SessionUpdate.SessionMessage })
        assertEquals(
            listOf("Reviewer", "Cleaner", "Tester"),
            rootStream.filterIsInstance<SessionUpdate.ToolCall>()
                .filter { it.status == ToolCallStatus.COMPLETED }
                .map { it.title },
        )
        assertTrue(run.lines.all { it.sessionId == null || it.sessionId == root })
    }

    private fun ClientSession.subagent(id: SessionId): ClientSubagentSession = subagents.single { it.sessionId == id }

    private fun List<ClientSubagentSession>.descendants(): List<ClientSubagentSession> =
        flatMap { listOf(it) + it.subagents.descendants() }

    private fun stateUpdate(id: SessionId, state: StateUpdate) =
        SessionUpdate.SubagentUpdate(id, state = MaybeUndefined.Value(state))
}
