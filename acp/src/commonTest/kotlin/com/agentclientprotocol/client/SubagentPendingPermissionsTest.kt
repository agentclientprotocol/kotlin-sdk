package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.IdleStopReason
import com.agentclientprotocol.model.StateUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules by which a subagent's permission requests are answered `cancelled`: a live `idle` with `cancelled`, a
 * cancel by the client while the subagent confirms foreground work, and a close.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class SubagentPendingPermissionsTest {

    @Test
    fun `without a cancel or a close the app's answer is the response`() = runTest {
        val permissions = SubagentPendingPermissions()
        val request = request(permissions)

        request.assertWaitingInApp()
        request.assertAnsweredByUser()
    }

    @Test
    fun `a live idle with cancelled answers the pending requests and later requests reach the app`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val first = request(permissions)
        val second = request(permissions)

        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = true)

        first.assertCancelledInApp()
        second.assertCancelledInApp()
        val later = request(permissions)
        later.assertWaitingInApp()
        later.assertAnsweredByUser()
    }

    @Test
    fun `a live idle with cancelled answers the pending requests without any cancel by the client`() = runTest {
        for (previous in listOf(null, StateUpdate.Running(), StateUpdate.RequiresAction(), StateUpdate.Unknown())) {
            val permissions = SubagentPendingPermissions()
            if (previous != null) permissions.onState(previous, live = true)
            val request = request(permissions)

            permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = true)

            request.assertCancelledInApp("after $previous")
        }
    }

    @Test
    fun `any other live state leaves the pending requests alone`() = runTest {
        val states = listOf(
            StateUpdate.Idle(),
            StateUpdate.Idle(stopReason = IdleStopReason.EndTurn),
            StateUpdate.Idle(stopReason = IdleStopReason.Error(null)),
            StateUpdate.Idle(stopReason = IdleStopReason.Refusal),
            StateUpdate.Running(),
            StateUpdate.RequiresAction(),
            StateUpdate.Unknown(),
            StateUpdate.Other("paused"),
            null,
        )
        for (state in states) {
            val permissions = SubagentPendingPermissions()
            permissions.onState(StateUpdate.Running(), live = true)
            val request = request(permissions)

            permissions.onState(state, live = true)

            request.assertWaitingInApp("$state")
            request.assertAnsweredByUser("$state")
        }
    }

    @Test
    fun `cancel by the client answers the pending requests whatever the state`() = runTest {
        val states = listOf(
            "never reported" to emptyList(),
            "running" to listOf(StateUpdate.Running()),
            "requires_action" to listOf(StateUpdate.RequiresAction()),
            "idle" to listOf(StateUpdate.Idle(stopReason = IdleStopReason.EndTurn)),
            "unknown" to listOf(StateUpdate.Unknown()),
            "unconfirmed" to listOf(null),
        )
        for ((name, reported) in states) {
            val permissions = SubagentPendingPermissions()
            reported.forEach { permissions.onState(it, live = true) }
            val first = request(permissions)
            val second = request(permissions)

            permissions.cancelByClient()

            first.assertCancelledInApp(name)
            second.assertCancelledInApp(name)
        }
    }

    @Test
    fun `after cancel by the client with confirmed foreground work later requests are answered without the app`() =
        runTest {
            for (active in listOf(StateUpdate.Running(), StateUpdate.RequiresAction())) {
                val permissions = SubagentPendingPermissions()
                permissions.onState(active, live = true)

                permissions.cancelByClient()

                request(permissions).assertCancelledWithoutApp("$active")
                request(permissions).assertCancelledWithoutApp("$active, a second request")
            }
        }

    @Test
    fun `any next live state ends the window that cancel by the client opened`() = runTest {
        val states = listOf(
            StateUpdate.Running(),
            StateUpdate.RequiresAction(),
            StateUpdate.Idle(stopReason = IdleStopReason.EndTurn),
            StateUpdate.Idle(stopReason = IdleStopReason.Cancelled),
            StateUpdate.Unknown(),
            StateUpdate.Other("paused"),
            null,
        )
        for (next in states) {
            val permissions = SubagentPendingPermissions()
            permissions.onState(StateUpdate.Running(), live = true)
            permissions.cancelByClient()

            permissions.onState(next, live = true)

            val later = request(permissions)
            later.assertWaitingInApp("after $next")
            later.assertAnsweredByUser("after $next")
        }
    }

    @Test
    fun `after cancel by the client without confirmed foreground work later requests reach the app`() = runTest {
        val states = listOf(
            "never reported" to emptyList(),
            "idle" to listOf(StateUpdate.Idle(stopReason = IdleStopReason.EndTurn)),
            "idle with cancelled" to listOf(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled)),
            "unknown" to listOf(StateUpdate.Unknown()),
            "unconfirmed" to listOf(null),
            "unconfirmed after running" to listOf(StateUpdate.Running(), null),
            "unrecognized" to listOf(StateUpdate.Other("paused")),
        )
        for ((name, reported) in states) {
            val permissions = SubagentPendingPermissions()
            reported.forEach { permissions.onState(it, live = true) }

            permissions.cancelByClient()

            val later = request(permissions)
            later.assertWaitingInApp(name)
            later.assertAnsweredByUser(name)
        }
    }

    @Test
    fun `a request pending when requires_action arrives is answered by a later cancel by the client`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val request = request(permissions)

        permissions.onState(StateUpdate.RequiresAction(), live = true)
        request.assertWaitingInApp()
        permissions.cancelByClient()

        request.assertCancelledInApp()
    }

    @Test
    fun `a request pending across an earlier cancel window is answered by a later cancel by the client`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.EndTurn), live = true)
        permissions.cancelByClient()
        val request = request(permissions)
        request.assertWaitingInApp()

        permissions.onState(StateUpdate.Running(), live = true)
        permissions.cancelByClient()

        request.assertCancelledInApp()
    }

    @Test
    fun `a state that arrives just before the cancel leaves later requests blocked until the next state`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val pending = request(permissions)

        permissions.onState(StateUpdate.RequiresAction(), live = true)
        permissions.cancelByClient()

        pending.assertCancelledInApp()
        request(permissions).assertCancelledWithoutApp()
        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = true)
        request(permissions).assertWaitingInApp()
    }

    @Test
    fun `a state that arrives just after the cancel ends the window the cancel opened`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val pending = request(permissions)

        permissions.cancelByClient()
        pending.assertCancelledInApp()
        request(permissions).assertCancelledWithoutApp("before the next state")
        permissions.onState(StateUpdate.RequiresAction(), live = true)

        request(permissions).assertWaitingInApp("the next state reported")
    }

    @Test
    fun `a state that is not live only holds its value back`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = false)

        permissions.cancelByClient()

        request(permissions).assertWaitingInApp("a held back running confirms no work yet")
    }

    @Test
    fun `a state that is not live neither answers the pending requests nor ends a cancel window`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val pending = request(permissions)

        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)
        pending.assertWaitingInApp("a held back idle with cancelled is not applied")

        permissions.cancelByClient()
        pending.assertCancelledInApp()
        permissions.onState(StateUpdate.Idle(), live = false)
        request(permissions).assertCancelledWithoutApp("a held back state does not end the window")
    }

    @Test
    fun `a held back foreground state takes effect when the parent's answer follows a resume`() = runTest {
        for (held in listOf(StateUpdate.Running(), StateUpdate.RequiresAction())) {
            val permissions = SubagentPendingPermissions()
            permissions.onState(held, live = false)

            permissions.onOpened(promote = true)
            permissions.cancelByClient()

            request(permissions).assertCancelledWithoutApp("$held")
        }
    }

    @Test
    fun `a held back foreground state is dropped when the parent's answer follows a load`() = runTest {
        for (held in listOf(StateUpdate.Running(), StateUpdate.RequiresAction())) {
            val permissions = SubagentPendingPermissions()
            permissions.onState(held, live = false)

            permissions.onOpened(promote = false)
            permissions.cancelByClient()

            request(permissions).assertWaitingInApp("$held")
        }
    }

    @Test
    fun `the latest held back state decides the value promoted at the parent's answer`() = runTest {
        val idleLast = SubagentPendingPermissions()
        idleLast.onState(StateUpdate.Running(), live = false)
        idleLast.onState(StateUpdate.Idle(), live = false)
        idleLast.onOpened(promote = true)
        idleLast.cancelByClient()
        request(idleLast).assertWaitingInApp("idle came last")

        val unconfirmedLast = SubagentPendingPermissions()
        unconfirmedLast.onState(StateUpdate.Running(), live = false)
        unconfirmedLast.onState(null, live = false)
        unconfirmedLast.onOpened(promote = true)
        unconfirmedLast.cancelByClient()
        request(unconfirmedLast).assertWaitingInApp("null came last")

        val runningLast = SubagentPendingPermissions()
        runningLast.onState(StateUpdate.Idle(), live = false)
        runningLast.onState(StateUpdate.Running(), live = false)
        runningLast.onOpened(promote = true)
        runningLast.cancelByClient()
        request(runningLast).assertCancelledWithoutApp("running came last")

        val nothingHeld = SubagentPendingPermissions()
        nothingHeld.onOpened(promote = true)
        nothingHeld.cancelByClient()
        request(nothingHeld).assertWaitingInApp("nothing was held back")
    }

    @Test
    fun `a held back idle with cancelled answers the requests pending at its arrival when the parent's answer follows a resume`() =
        runTest {
            val permissions = SubagentPendingPermissions()
            permissions.onState(StateUpdate.Running(), live = false)
            val first = request(permissions)
            permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)
            val second = request(permissions)
            permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)
            val later = request(permissions)
            first.assertWaitingInApp("before the answer")
            second.assertWaitingInApp("before the answer")

            permissions.onOpened(promote = true)

            first.assertCancelledInApp("set aside by the first idle")
            second.assertCancelledInApp("set aside by the second idle")
            later.assertWaitingInApp("arrived after the last idle")
            later.assertAnsweredByUser()
        }

    @Test
    fun `a held back idle with cancelled still answers its requests after a later held back state`() = runTest {
        val permissions = SubagentPendingPermissions()
        val pending = request(permissions)
        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)
        permissions.onState(StateUpdate.Running(), live = false)
        val later = request(permissions)

        permissions.onOpened(promote = true)

        pending.assertCancelledInApp()
        later.assertWaitingInApp("the work that runs again")
        // The latest held state, running, is promoted as before.
        permissions.cancelByClient()
        later.assertCancelledInApp()
        request(permissions).assertCancelledWithoutApp("the promoted running confirms work")
    }

    @Test
    fun `a held back idle with cancelled is dropped after a load and its requests stay answerable`() = runTest {
        for (end in listOf("cancel by the client", "close", "live idle with cancelled")) {
            val permissions = SubagentPendingPermissions()
            val pending = request(permissions)
            permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)
            val later = request(permissions)

            permissions.onOpened(promote = false)
            pending.assertWaitingInApp("$end: the history's idle answers nothing")
            later.assertWaitingInApp(end)

            when (end) {
                "cancel by the client" -> permissions.cancelByClient()
                "close" -> permissions.close()
                else -> permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = true)
            }
            pending.assertCancelledInApp(end)
            later.assertCancelledInApp(end)
        }
    }

    @Test
    fun `requests set aside by a held back idle are still answered by an earlier cancel or close`() = runTest {
        for (end in listOf("cancel by the client", "close")) {
            val permissions = SubagentPendingPermissions()
            val pending = request(permissions)
            permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = false)

            if (end == "close") permissions.close() else permissions.cancelByClient()

            pending.assertCancelledInApp(end)
            permissions.onOpened(promote = true)
            request(permissions).let { if (end == "close") it.assertCancelledWithoutApp(end) else it.assertWaitingInApp(end) }
        }
    }

    @Test
    fun `a live state after the parent's answer replaces the promoted value`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = false)
        permissions.onOpened(promote = true)

        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.EndTurn), live = true)
        permissions.cancelByClient()

        request(permissions).assertWaitingInApp()
    }

    @Test
    fun `close answers the pending and every later request even after a live state`() = runTest {
        val permissions = SubagentPendingPermissions()
        permissions.onState(StateUpdate.Running(), live = true)
        val first = request(permissions)
        val second = request(permissions)

        permissions.close()

        first.assertCancelledInApp()
        second.assertCancelledInApp()
        request(permissions).assertCancelledWithoutApp("after close")

        permissions.onState(StateUpdate.Running(), live = true)
        request(permissions).assertCancelledWithoutApp("after a live running")
        permissions.onState(StateUpdate.Idle(stopReason = IdleStopReason.Cancelled), live = true)
        request(permissions).assertCancelledWithoutApp("after a live idle with cancelled")
        permissions.onOpened(promote = false)
        permissions.cancelByClient()
        request(permissions).assertCancelledWithoutApp("after a cancel by the client")
    }

    private fun TestScope.request(permissions: SubagentPendingPermissions): Request {
        val request = Request(this)
        request.result = backgroundScope.async {
            permissions.answerUnlessCancelled(cancelledAnswer = { CANCELLED }) { request.answerInApp() }
        }
        runCurrent()
        return request
    }

    /**
     * One permission request: the app's handler waits for the user until [userAnswer] completes.
     */
    private class Request(private val scope: TestScope) {
        lateinit var result: Deferred<String>
        val userAnswer = CompletableDeferred<String>()
        var appCalls = 0
        var appHandlerEnded = false

        suspend fun answerInApp(): String {
            appCalls++
            try {
                return userAnswer.await()
            } finally {
                appHandlerEnded = true
            }
        }

        fun assertWaitingInApp(case: String = "") {
            scope.runCurrent()
            assertEquals(1, appCalls, "the request reached the app once $case")
            assertFalse(result.isCompleted, "the request is still waiting for the user $case")
        }

        suspend fun assertAnsweredByUser(case: String = "") {
            userAnswer.complete(ALLOWED)
            scope.runCurrent()
            assertEquals(ALLOWED, result.await(), "the user's answer is the response $case")
            assertEquals(1, appCalls, case)
        }

        suspend fun assertCancelledInApp(case: String = "") {
            scope.runCurrent()
            assertTrue(result.isCompleted, "the request was answered $case")
            assertEquals(CANCELLED, result.await(), case)
            assertTrue(appHandlerEnded, "the app's handler was cancelled $case")
            assertUserCannotAnswer(case)
        }

        suspend fun assertCancelledWithoutApp(case: String = "") {
            scope.runCurrent()
            assertTrue(result.isCompleted, "the request was answered $case")
            assertEquals(CANCELLED, result.await(), case)
            assertEquals(0, appCalls, "the request never reached the app $case")
            assertUserCannotAnswer(case)
        }

        private suspend fun assertUserCannotAnswer(case: String) {
            userAnswer.complete(ALLOWED)
            scope.runCurrent()
            assertEquals(CANCELLED, result.await(), "a late user answer is not a second response $case")
        }
    }

    private companion object {
        const val CANCELLED = "cancelled"
        const val ALLOWED = "allowed"
    }
}
