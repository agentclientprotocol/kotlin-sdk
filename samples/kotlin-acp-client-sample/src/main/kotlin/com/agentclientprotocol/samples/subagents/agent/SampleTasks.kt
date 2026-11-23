package com.agentclientprotocol.samples.subagents.agent

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind

/**
 * The three tasks the root delegates for the first prompt:
 * - the reviewer patches its title (scenario 5), asks a permission that the user grants (3), and completes;
 * - the cleaner asks to delete files, and the user cancels it while the request is pending (6);
 * - the tester starts a grandchild (8) and fails when the grandchild's runner goes away (7).
 */
internal val sampleTasks = listOf(
    DelegatedTask(SessionId("reviewer"), "Reviewer", "Reviews the change in Foo.kt", "Review the change in Foo.kt") {
        say(SessionUpdate.ToolCall(ToolCallId("read-foo"), "Read src/Foo.kt", ToolKind.READ, ToolCallStatus.COMPLETED))
        patchTitle("Reviewer: 2 findings")

        val writeNotes = ToolCallId("write-notes")
        say(SessionUpdate.ToolCall(writeNotes, "Write REVIEW.md", ToolKind.EDIT, ToolCallStatus.PENDING))
        if (askPermission(SessionUpdate.ToolCallUpdate(writeNotes, title = "Write REVIEW.md", kind = ToolKind.EDIT))) {
            say(SessionUpdate.ToolCallUpdate(writeNotes, status = ToolCallStatus.COMPLETED))
            say(message("Wrote two findings to REVIEW.md"))
        }
    },

    DelegatedTask(SessionId("cleaner"), "Cleaner", "Frees disk space", "Free disk space in the build directory") {
        val deleteCache = ToolCallId("delete-cache")
        say(SessionUpdate.ToolCall(deleteCache, "Delete build/cache", ToolKind.DELETE, ToolCallStatus.PENDING))

        /*
         * The user cancels this child while the request below is pending. The client then sends `session/cancel` for
         * the child and answers the request `cancelled`. Either one stops this work, see `DelegatedSubagentSession`.
         */
        askPermission(SessionUpdate.ToolCallUpdate(deleteCache, title = "Delete build/cache", kind = ToolKind.DELETE))
        say(SessionUpdate.ToolCallUpdate(deleteCache, status = ToolCallStatus.COMPLETED))
    },

    DelegatedTask(SessionId("tester"), "Tester", "Runs the test suite", "Run the test suite") {
        val runTests = ToolCallId("run-tests")
        say(SessionUpdate.ToolCall(runTests, "Run the test suite", ToolKind.EXECUTE, ToolCallStatus.IN_PROGRESS))
        say(
            SessionUpdate.ToolCallUpdate(
                runTests, status = ToolCallStatus.COMPLETED,
                content = listOf(ToolCallContent.Content(ContentBlock.Text("FooTest fails on Linux only"))),
            )
        )

        val probe = startRemoteProbe(SessionId("tester-probe"))
        say(
            SessionUpdate.ToolCall(
                ToolCallId("start-probe"), "Rerun FooTest on a Linux runner", ToolKind.OTHER, ToolCallStatus.COMPLETED,
                content = listOf(ToolCallContent.Session(probe.id)),
            )
        )

        // Throws once the probe's runner goes away, so this task fails with `idle(error)`.
        probe.rerun("Rerun FooTest")
    },
)
