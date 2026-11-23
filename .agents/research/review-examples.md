# Review: subagent examples milestone (E0 `7eff9a3`, E1 `e03539a`, E2 uncommitted)

Scope: `git diff adf85e6..e03539a` without `.agents/`, plus the uncommitted working tree (E2, `git diff` and the
untracked `agent/SessionHistory.kt`). Spec checked at `agent-client-protocol` `840484ff` (pulled today):
`docs/protocol/v1/subagents.mdx`, `docs/protocol/v1/prompt-turn.mdx`, `docs/protocol/v1/session-setup.mdx`.

## What I ran

- `./gradlew :samples:kotlin-acp-client-sample:test --rerun`: 12 tests, 0 failures.
- The same command 20 more times in a loop: 20 of 20 green (`scratch/review-examples-stress.txt`).
- `./gradlew :samples:kotlin-acp-client-sample:runSubagents` (multi-threaded `suspend fun main`): exit 0. stdout was
  only the transcript. stderr had the SLF4J provider line and the one documented WARN
  (`Received CancelRequest for unknown request: 2`). I quote the output below where a finding uses it.

`./gradlew check` was not run. No SDK, API dump or build-logic file changed.

## Findings (most severe first)

No blockers.

### 1. should-fix: the replay reorders the parent's stream

`samples/.../subagents/agent/SessionHistory.kt:55-87`, with the recording in `DelegatedSubagentSession.kt:78`,
`DelegatedTask.kt:62-85` and `RemoteProbeSubagentSession.kt:82`.

**Defect.** A child's patches (its own `subagent_update`s) go on the parent's stream on the wire. The sample records
them in the child's history, and the child's factory replays that whole history before it returns. So on replay every
patch of a child moves up to sit right after the child's announcement, ahead of the parent's own later updates.

**Failure scenario, confirmed by running.** Live, the root stream carried:
1. `subagent reviewer` (the announcement);
2. `message root -> reviewer`;
3. `tool call "Delegate: Reviewer"`;
4. later, interleaved with the other children: `title "Reviewer: 2 findings"`, `requires action`, `running`,
   `idle (end_turn)`.

The replay sent this instead:

```
client root  history: subagent reviewer: title "Reviewer", cancel granted, state running
client root  history: subagent reviewer: title "Reviewer: 2 findings"
client root  history: subagent reviewer: state requires action
client root  history: subagent reviewer: state running
client root  history: subagent reviewer: state idle (end_turn)
client root  history: message root -> reviewer: Review the change in Foo.kt
client root  history: tool call "Delegate: Reviewer": in_progress, links to reviewer
```

A client that renders the history shows the reviewer finished and renamed before the root delegated anything to it.
The cleaner and the tester are reordered the same way.

**Spec.** `subagents.mdx:526`: "Replayed child updates **MUST** keep their original `sessionId`, their known parent,
and their order within each session." `subagents.mdx:210`: "Updates are ordered within each session". A
`subagent_update` is a `session/update` whose outer `sessionId` is the parent (`subagents.mdx:65`), so the natural
reading counts the parent's stream as one session whose order must hold. `session-setup.mdx:136-139` also asks for
replay "using the same update, chunk, and patch semantics as live delivery".

The final snapshot is the same either way, and all of it is history, so the client acts on none of it. That is why
this is not a blocker. Still, the sample conforms only under the narrower reading, where a child's patch is ordered
only against the child's other updates. The `SessionHistory` comment admits the reordering ("a child's patches … are
replayed together right after its announcement") but never says why it is acceptable.

**Recommendation (open question 2): keep each stream's live order. Don't teach the current pattern.** Reasons:
- **The spec.** It is the safe reading of `:526`, and the sample is the reference people copy.
- **The SDK already allows it.**
  - `AgentSubagentContext` "stays valid after the factory returns" (`AgentSubagents.kt:23-24`).
  - Everything sent in the replay window is history (`AgentSubagents.kt:143-149`).
  - So a factory can send only the announcement and the child's own-stream history, return, and leave its context to
    the parent's replay loop.
  - The parent's loop then sends each of the child's patches through that stored context, at the patch's recorded
    place in the parent's log.
  - Cross-stream interleaving can still differ from live, which the spec allows (`:210`, "can interleave").
- **The cost is small.** Record each update in the log of the stream it goes on: the parent's log for a child's
  `subagent_update`, tagged with the child id, and the child's log for everything else.
  - The patch then has to be sent through the child's context, because the root's `notify` rejects every
    `SubagentUpdate`.
  - Grandchildren work the same way one level down.
- **Add a test.** For example: on the replayed root stream, the reviewer's `title "Reviewer: 2 findings"` follows
  `tool call "Delegate: Reviewer"`, as it did live.

**SDK follow-up (not for this milestone).** The `AgentSubagentFactory.createSubagent` KDoc (`AgentSubagents.kt:111-113`)
says to send "the child's announcement and recorded history through [context] before returning". Read literally, that
produces exactly this reordering. It should say that a child's patches keep their place on the parent's stream, and
can be sent through the context after the factory returns while the parent replays.

Confidence: confirmed by running and reading.

### 2. should-fix: the "at open" tree after the load can miss grandchildren

`samples/.../subagents/client/RootClientSessionOperations.kt:15-16` (the KDoc claim) and
`samples/.../subagents/SubagentsSampleTest.kt:222-224` (the assertion).

**Defect.** `RootClientSessionOperations` says that at the root's `Opened` the tree is "the replayed tree as of the
load's answer". The test asserts that the `at open` lines list all six subagents, including `tester-probe` and
`relay-notes`. But a grandchild joins its parent's `subagents` only when the parent's own opening reaches the
grandchild's announcement. Nothing orders that against the root's `Opened`:
- `RootSessionHolder.completeOpening` delivers `Opened` without waiting for any subagent opening
  (`acp/.../client/internal/RootSessionHolder.kt:261-276`);
- each subagent opening runs on its own in the connection scope (`SubagentSessionHolder.kt:128-140`, `:170-186`).

**Failure scenario.** In `runSubagents` (Dispatchers.Default), `relay-planner`'s opening is still delivering its
history when the root's `Opened` runs. `relay-notes` is announced only about five messages before the load response.
`showTree` then lists five subagents, and the test fails the same way. All 20 runs passed, because under
`runBlocking` everything runs on one thread and the FIFO dispatch happens to drain the children first. So the test
relies on a scheduling accident, not on the SDK contract. The claim is also wrong as written for depth 2 and below.

**Fix direction.** Wait for the tree before showing it, as the live part does with `awaitUntil`, or check only the
root's direct `subagents` at `Opened`. Then fix the KDoc to match.

Confidence: plausible (from reading the SDK; not reproduced).

### 3. should-fix: the follow-up task's `session_message` links to an outgoing view that never exists

`samples/.../subagents/agent/DelegatedSubagentSession.kt:58-66` with `SampleTasks.kt:70-77` and
`DelegatingAgentSession.kt:157-173`.

**Defect.** After the load, the reviewer's follow-up sends an incoming `session_message` with
`senderSessionId = root` and `senderMessageId = reviewer-watch-sent`. The root never sends the outgoing view with that
id, and no `Delegate` tool call either. The run shows `client reviewer message root -> reviewer: Watch src/Foo.kt …`
with no matching root line.

**Spec.** `prompt-turn.mdx:96-101`: `senderMessageId` names "the other view's `messageId`", and "the Agent reports each
side only when observed". Section 4 of `DelegatingAgentSession` teaches the two views as a linked pair, so a client
that follows the link from the child finds nothing.

**Fix direction.** Either have the root send the outgoing view (and the tool call) for the follow-up after the load,
for example from `postInitialize`, or drop `senderMessageId` for it.

Confidence: confirmed by reading and running.

### 4. should-fix: `CancellationException` is used as a domain signal and then swallowed

`samples/.../subagents/agent/DelegatedTask.kt:99` and `DelegatedSubagentSession.kt:90-93`.

**Defect.** `askPermission` throws `CancellationException("The permission was cancelled")` for a `cancelled` outcome.
`runTask` catches every `CancellationException` and, if the collector is still active, turns it into
`IdleStopReason.Cancelled` without rethrowing. `AGENTS.md` ("Catch `CancellationException` only to add required
cleanup or protocol behavior, then rethrow it") and kotlinx.coroutines guidance both advise against this. Samples get
copied.

**Failure scenarios.**
- A task's work uses `withTimeout`, and the timeout fires. `TimeoutCancellationException` is a
  `CancellationException`, so the child reports `idle(cancelled)`. It should report `idle(error)`. The spec reserves
  `cancelled` for work that was cancelled (`subagents.mdx:377`, `:454`).
- Someone copies `askPermission` into a `launch {}`. The thrown exception then ends that coroutine silently, as a
  normal cancellation, instead of failing the work.

**Fix direction.** Use a dedicated exception for "permission cancelled". Decide `Cancelled` from the sample's own
signal (`cancelRequested.isCompleted`, or a flag set by the cancel watch), and rethrow any other
`CancellationException`.

Confidence: confirmed by reading.

### 5. nit: the scripted user allows a delete when it can't cancel

`samples/.../subagents/client/ScriptedUser.kt:75-82`. The comment says "The user allows everything except deleting
files". But when `canCancel` is false (no grant yet, or a root-level request), a `DELETE` request falls through to the
`allow` branch, so the user allows the delete. A reader who copies the policy gets the opposite of what the comment
says. Reject the request in that case instead. Confidence: confirmed by reading.

### 6. nit: the README's "One is expected" warning is not deterministic

`samples/README.md:145-147`. `ClientSubagentSession.cancel()` sends `session/cancel` first and then answers the
pending request `cancelled` (`ClientSubagentSession.kt:147-151`). The agent sends `$/cancel_request` only if its scope
cancellation wins against that response. If the response arrives first, no `$/cancel_request` goes out and nothing is
logged. Say "one may appear". Confidence: plausible.

### 7. nit: the tests rely on single-thread scheduling for cross-stream index comparisons

`SubagentsSampleTest.kt:113` and `:248-249` compare positions of lines from different streams, through `awaitUntil`.

- **Where it can break.** `ClientTranscript.awaitUntil` re-checks the getters after any line from any stream. In the
  multi-threaded app, another stream's line can land between the moment the parent's consumer applies the grant and
  the moment it shows the grant line. The main coroutine then writes `user cancels …` before the grant line. Under
  `runBlocking` there is no such window.
- **What happened in the run.** `runSubagents` still printed the grant line first. The reviewer's own-stream lines came
  after `user cancels`, which shows the streams do lag one another.
- **Fix.** If the test is meant to stay valid under a multi-threaded dispatcher, compare against the causal event (the
  grant line), or say in the test KDoc that it relies on the single-threaded run.

Confidence: plausible.

### 8. nit: other small points that are misleading to copy

- `SubagentsClient.kt:156`: `isActive()` counts only `running` and `requires_action`. The SDK
  (`AgentSubagents.kt:70-73`) and the spec (`subagents.mdx:378`) treat an unrecognized state (`StateUpdate.Other`) as
  active. A copied `awaitUntil` would then close while unrecognized work is still running.
- `SubagentsClient.kt:115`: `loaded.subagents.firstOrNull()` assumes the reviewer is the first child. Look it up by id.
- `ScriptedUser.kt:59-61`: `PERMISSION_ANSWERED_CANCELLED` is logged for any cancellation of the handler, including
  connection shutdown.
- `DelegatedTask.kt:98`: "The client answers `cancelled` only when it cancels this child or closes its root". That is
  true of this client, not of clients in general.
- `SessionHistory.kt:76`: for a client without `subagents`, the replay skips the `Delegate` tool calls (they carry
  session content) but still replays their `ToolCallUpdate`s, which then point at tool calls that were never sent. The
  scripted run never takes this path: the same client loads, so its history has no delegation.
- After the load, the agent reports no current state for the history-only children, although it knows their work
  ended (`subagents.mdx:513`, a SHOULD). This is acceptable if the intent is to show "no confirmed state". If so, say
  it in section 12 or 13.

All confirmed by reading.

### 9. nit: naming and layout against the milestone decisions

- `agent/FakeNativeAgent.kt`: its main declarations are `NativeEvent` and `fakeNativeAgentEvents`, so the file is not
  named after its main declaration (AGENTS.md "File layout").
- E0 kept two names that look like SDK types:
  - `support/TerminalClientSupport.kt`: there is no SDK `ClientSupport`, and its main declaration is
    `TerminalClientSessionOperations`. The decision names `…ClientSupport` explicitly.
  - `v2/V2SampleSupport.kt`: its main declaration is `SimpleV2AgentSupport`.
- Pre-existing single-line KDocs moved by E0: `V2SimpleAgentApp.kt:11`, `V2NegotiationApp.kt:11`,
  `InMemoryTransportPair.kt:12`, `V2ClientExamples.kt:43,58`, `V2SampleSupport.kt:41`.
- `InMemoryTransportPair` names both transports `v2-sample-*`, although the v1 samples use it too.

These may be out of E0's "no behaviour change" scope. Confidence: confirmed by reading.

### 10. nit: build and docs

- `build.gradle.kts:25`: `kotlin-test-junit5` is a hard-coded coordinate. It has no version (the Kotlin plugin aligns
  it) and matches `acp.rust-agent-e2e.gradle.kts:20`, so it is fine.
- `build.gradle.kts:12`: the pre-existing hard-coded `logback-classic:1.5.13` could use the catalog's
  `libs.logback.classic`.
- `check` wiring works: the JVM `test` task is part of `check`. The logback override applies to `runSubagents` only, as
  intended. `logback.configurationFile` resolves the classpath resource, and stderr showed only WARN.
- `SubagentsClient.kt:49`: `suspend fun CoroutineScope.runSubagentsClient` is the "suspend extension on
  CoroutineScope" anti-pattern. `coroutineScope {}` inside would be idiomatic.
- `AGENTS.md:62` still lists the samples as "stable ACP, direct v2 use, negotiation, and external-agent integration",
  without subagents.
- The root `README.md` doesn't mention the subagents sample beyond linking `samples/README.md`. This may be owned by the
  recorded docs TODO.

Confidence: confirmed by reading.

## Checked with no findings

- **E0 restructure.** No stale `com.agentclientprotocol.samples.<Class>` or `-PmainClass` references remain in the
  docs. The root README, `samples/README.md` and the Gemini KDoc point at the new tasks. Moved files changed only their
  package and import lines.
- **README scenario table.** It matches the section numbers in the code on both sides. The file list matches the
  package contents.
- **Announcement-first ordering, grant handling and the load boundary.**
  - The factory sends the announcement itself. The probe and the relay workers send it as the first item of `events`.
  - The parent links a child only after `announce` returns.
  - The fresh grant goes through `events`, which the SDK collects after the load response.
  - The client cancels only once `canCancel` is true after the load.
  - All of this matches `AgentSubagents` and `ClientSubagentSession`, and `subagents.mdx:502`, `:508` and `:512`.
- **Getter use outside `notify`.** It follows the `ClientSubagentSession` contract ("Elsewhere they return the latest
  applied value"). The comments say to show the update itself where the getters can be ahead.
- **The probe's `unknown` race from the earlier report.** It is negligible:
  - the client waits, before the close, until no listed subagent is active, and the probe stays `running` until the
    `unknown` applies;
  - the probe is listed under the tester long before that, because the whole second turn runs in between.

## Follow-ups (outside this milestone)

- SDK KDoc: `AgentSubagentFactory.createSubagent`, replay paragraph (see finding 1).
