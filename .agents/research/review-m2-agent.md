# Review: M2, v1 agent-side subagents (`:acp`)

Range `63f54c5..7ab77d2`, product commits `9bd0d26`, `6105d21`, `7ab77d2` (skipped `6b4dc10` and the `chore:` commits).
I read everything through git at `7ab77d2`. Gradle was not run, at the brief's request. Line numbers are at
`7ab77d2`. `SubagentWrapper.kt`, `AgentSubagents.kt`, `Agent.kt` and `RemoteClientSessionOperations.kt` are under
`acp/src/commonMain/kotlin/com/agentclientprotocol/agent/`. The test is
`acp/src/jvmTest/kotlin/com/agentclientprotocol/agent/AgentSubagentsTest.kt`.

Counts: 0 blocker, 4 should-fix, 7 nit.

Scope checks that pass: `git diff --stat 63f54c5..7ab77d2` shows no change under `agent/v2` or `client`. No added line
cites `.agents/`. `acp/api/acp.api` adds only the five intended declarations (`AgentSubagentContext`,
`AgentSubagentFactory`, `AgentSubagentSession`, `AgentSubagents`, `AgentSubagentsKt.getSubagents`), and
`Protocol.newConnectionScope` is `internal`. Root behaviour is unchanged:
- `getSessionOrThrow` checks for a subagent only on a miss;
- `session/cancel` looks up a child first and otherwise behaves as before;
- `createSession` now skips the old no-op `beforeResponse` for new/resume/fork and adds `endReplay()` to
  `afterResponse`, which does nothing when no children were replayed.

## Findings

### S1. A `CancellationException` from the factory after the announcement escapes into an active caller (choice 2)

`SubagentWrapper.kt:195-196`: `reportFactoryFailure(); throw e` rethrows the exception unchanged, so a CE the app's
factory throws is passed on as a CE while the caller is still active.

- **Scenario, current behaviour.** A root prompt flow calls `subagents.announce(id) { ctx -> ctx.client.notify(grant
  + running); withTimeout(1.seconds) { native.handshake() }; ... }`. The handshake times out, so the factory throws
  `TimeoutCancellationException` after the announcement.
  - The SDK sends the inline correction (revoke + `unknown`), which is right.
  - `announce` then rethrows the TCE into the prompt flow. `SessionWrapper.prompt` rethrows CEs (the FIXME at
    `Agent.kt` around line 240), which cancels `promptJob`. `session/prompt` answers `stopReason: cancelled`, but the
    client sent no `session/cancel`, and the failure is never reported.
  - If the caller is an app `launch`, the coroutine ends as "cancelled" without a word: nothing is logged and no
    exception handler sees it.
  - Through `async`/`await`, the awaiting coroutine sees what looks like its own cancellation.
  - This is the #131 bug class that the nothing-sent branch (line 192-193) and the design's step 5 explicitly avoid.
- **Option "convert to `IllegalStateException(cause = e)` when `currentCoroutineContext().isActive`".**
  - The same prompt turn records the failure and answers `-32603`, or `cancelled` if a real `session/cancel`
    targeted the turn.
  - A `launch` caller fails visibly and its parent scope sees an ordinary failure. Siblings in a non-supervisor scope
    are cancelled, as for any other factory failure.
  - The correction is still sent.
  - Cost: it departs from the design's "rethrows the factory's exception unchanged". An app that catches
    `TimeoutCancellationException` around `announce` must catch ISE and inspect the cause. That is already the case in
    the nothing-sent branch, so both branches would behave the same.
  - When the caller itself was cancelled, `isActive` is false and both options rethrow its CE unchanged.
- **Recommendation.** Convert, guarded exactly like line 192, and update the `announce` KDoc
  (`AgentSubagents.kt:156-158`). Whichever option the user picks, there is no test for this branch: the only factory-CE
  test (`AgentSubagentsTest.kt:501`) is the nothing-sent case. Add one.
- Severity: should-fix (open user decision). Confidence: confirmed by reading.

### S2. `session/cancel` during a granted, still-running factory is dropped, and the `cancel()` KDoc says otherwise (choice 7)

`SubagentWrapper.kt:346-354`: `cancel()` ignores the notification when `session == null`, including while the factory
runs with a cancel grant already on the wire. `AgentSubagents.kt:82` says `cancel()` is "Called on `session/cancel`
for this child while the current cancel grant is in effect".

- **Scenario.** A relay factory sends the announcement with `capabilities.cancel` (the natural single-update
  announcement), then spends seconds waiting for the native child to spawn before it returns.
  1. The user presses cancel.
  2. The client, correctly, sends `session/cancel`, because the grant is in effect on its side.
  3. The SDK logs at `info` and drops it.
  4. The work runs on, and the client can't learn that the cancel was lost, because it was a notification.
- The design settles only the factory-threw case (step 5, "logged and ignored"). For a factory that is still running,
  the grant is already "in effect" in the design's own sense, since it is recorded before the send so that such a
  cancel is routed. The test `AgentSubagentsTest.kt:948` pins "ignored, not deferred", so the choice was deliberate,
  but it is not documented anywhere public.
- **Fix.** Either latch a cancel received while granted and call `cancel()` once the factory returns a session (and
  drop the latch if the factory throws), or keep dropping and say in the KDoc of `AgentSubagentSession.cancel` and
  `AgentSubagentFactory.createSubagent` that a grant sent from the factory takes effect only once the factory returns.
- Severity: should-fix. Confidence: confirmed by reading (behaviour), plausible (user impact).

### S3. Re-opening an already-open root orphans its subtree, and a reload fails on its own child ids

`Agent.kt:697` (`_sessions.update { it.put(...) }` in `createSession`) and `Agent.kt:777` (`closeSubtree` filters
`it.root === root`). `session/load` and `session/resume` for an id that is already open on the connection replace
the old `SessionWrapper` in `_sessions`, but the old wrapper's children stay in `_subagents` with the old root.

- **Scenario A (load).** The client loads `s1`, and replay announces child `c1`. Later the client sends `session/load
  s1` again on the same connection, for example when an IDE reopens a tab without reconnecting.
  1. Replay calls `announce(c1)`, and `registerSubagent` throws `IllegalArgumentException` ("already registered").
  2. The replay fails, so the load answers with an error. The failure path at `Agent.kt:705` removes the new wrapper,
     and the old one was already replaced.
  3. `s1` is now gone from `_sessions`. The old `c1` subtree stays registered with its collection running, and no
     `session/close` can reach it.
- **Scenario B (resume).** Resuming an open `s1` gives the same orphaned subtree. A later `session/close s1` cleans
  only the new wrapper's children, so the old children's collections keep sending on the wire until the connection
  ends, and their cancels are still routed.
- Replacing a root this way is pre-existing behaviour, but before subagents it leaked only the root. The design
  assumes child registrations die with the connection ("child registrations don't survive a reconnect"), and does not
  settle re-opening on the same connection.
- **Fix.** Reject `load`/`resume` of an id that is already open, or run `closeSubtree(old)` when a root is replaced.
  Either way it needs a decision on whether ACP allows re-loading an open session.
- Severity: should-fix. Confidence: confirmed by reading (mechanism), plausible (whether clients do this).

### S4. Test gaps on deliberate, divergent paths

`AgentSubagentsTest.kt` has no test for:
1. a factory CE after the announcement with an active caller (see S1);
2. the positive request path: no test asserts that an announced child's `requestPermissions`/`fs`/`terminal`/
   elicitation request carries `sessionId = child`. `operations fail before the announcement` (line 350) only tests
   the failures, so a wrong id in `SubagentClientOperations` (`SubagentWrapper.kt:370`) would go unnoticed;
3. `CoroutineContext.subagents` in a NES session context (choice 5). Only "outside a session" is covered, at line 276.

- Scenario: a regression that passes the root id to the child's operations' constructor, or that wraps the late CE
  differently, keeps all 69 tests green.
- Severity: should-fix for items 1 and 2, nit for item 3. Confidence: confirmed by reading.

### N1. Child requests and `$/cancel_request` can reach the wire after the close response (choice 11)

`RemoteClientSessionOperations.kt:33` and the other request methods: `checkCanSend()` runs outside the child's lock.
Separately, cleanup cancels the collection without joining it (`Agent.kt:777`, `SessionWrapper.closeSubtree`).

- **Race.** A request whose `checkCanSend()` passes on one thread, while `session/close` finishes on another, is sent
  after the response. Between the check and `transport.send` there is no suspension point (`sendRequest` →
  `sendRequestRaw` → `sendFrame`), so this needs true parallelism and a window of a few instructions. On a single
  thread it can't happen.
- **More likely.** An in-process child whose `events` flow is suspended in `ctx.client.requestPermissions(...)` when
  its root is closed: the cancelled request makes `Protocol.sendRequestRaw` send `$/cancel_request` from
  `NonCancellable`. That coroutine resumes on the app's dispatcher, independently of the close response being queued,
  so the `$/cancel_request` often follows the response.
- **Impact against the design's "nothing about the children reaches the wire after the close is answered".** It is
  broken in letter, not in effect.
  - A late request names a child the client has already discarded. The client answers with an error, and the app's
    call fails.
  - A late `$/cancel_request` targets a request the client has already settled, or will ignore.
  - No correlation or framing breaks, and no update for the child goes out after the response: notifications are
    covered by the lock.
- If the user wants the guarantee to be strict:
  - take the lock around `checkCanSend()` plus the send in each request method, using the non-suspending part of
    `sendRequestRaw`;
  - and either join the subtree's collections before answering, or accept the `$/cancel_request` and document it.
- Severity: nit. Confidence: confirmed by reading (mechanism), plausible (ordering on the wire).

### N2. A grant sent after the load response but before `afterResponse` is ignored, though the client treats it as live (choice 9)

`Agent.kt:721` and `SubagentWrapper.kt:423`: the replay window closes when `afterResponse` starts, not when the
response frame is queued.

- **Scenario.** A replay factory launches a forwarder that sends a fresh cancel grant through `context.client` as soon
  as the native child confirms. The confirmation lands after the response frame is queued but before the handler job
  resumes into `afterResponse` (one dispatch hop, or longer on a busy handler dispatcher).
  - On the wire the grant comes after the load response, so the client applies it as live (`:1027-1035`).
  - The SDK classifies it as history and leaves `_cancelGranted` false.
  - The next `session/cancel` is silently ignored.
- Updates sent before the response frame is queued (for example while a batch sibling is still running) are correctly
  history.
- The KDoc at `AgentSubagents.kt:85` ("before its response") is slightly stronger than what is implemented.
- **Fix.** Accept it and say "until the SDK starts its post-response work". Or close the window from Protocol's
  `responseFrameQueued`, which would need an internal hook.
- Severity: nit. Confidence: plausible.

### N3. `remove()` can fail an `announce` whose announcement already went out

`SubagentWrapper.kt:258-269` and `:389-392`: `sendOwnUpdate` sets `ANNOUNCED` under the lock but completes
`announcement` after releasing it. `remove()` calls `completeExceptionally` whatever the phase.

- **Scenario.** The root is closed between the two statements. `announce` then throws "Subagent X is not announced:
  its root session is closed", although the announcement is on the wire, before the close response.
- Impact is low, because the root is closed anyway, but the message is false.
- **Fix.** Complete exceptionally only when the phase was `PENDING`.
- Severity: nit. Confidence: confirmed by reading.

### N4. A `session/load` that fails after replay leaves the replayed children registered, with the window open for good

`Agent.kt:479` (`created.mapResponse { LoadSessionResponse(session.asModeState(), ...) }`) runs after
`createSession` returns.

- **Scenario.** Replay announces `c1`, then building the response throws (an app getter used by `asModeState`/
  `asModelState`/`asConfigOptionsState`). The same happens if the handler is cancelled between `createSession` and
  `slot.response.complete`.
  - The load answers with an error, and `afterResponse` never runs.
  - `c1` stays registered and is never collected, and `isReplaying` stays true, so every later grant is classified as
    history.
- The design says "If the load fails, drop every registration made during the attempt". The root also leaks here
  today, which the design accepts only for "the response never reaches the transport".
- **Fix.** Build the load response inside the guarded region, or clean up through `RequestOutcome.onCompletion`.
- Severity: nit. Confidence: confirmed by reading (mechanism), plausible (trigger).

### N5. Two tests can't fail for the reason they name

- `AgentSubagentsTest.kt:1276`, `the connection's end cancels the collections without reporting unknown`. Both
  endings cancel `protocolJob` before the collections see cancellation, and `Protocol.sendFrame` calls
  `protocolJob.ensureActive()` before `transport.send`. So `onSend` can't observe an `unknown` even if `onFeedEnd`
  tried to send one, and `assertNull(sent.tryReceive())` can't fail. The SDK-cancel rule is covered meaningfully only
  by line 1096, which cancels `subtreeScope` directly.
- `AgentSubagentsTest.kt:885`, `session cancel sent right after the grant goes out is routed`. The cancel is handled
  on the handler dispatcher after `onSend` returns, so it would usually still be routed if the grant were recorded
  after the send. The deterministic check is line 373, which reads `isCancelGranted` inside `onSend`.
- Several tests reach internals: `subtreeScope`, `subagentOrNull`, `isCancelGranted`, `SessionWrapperContextElement`,
  and `awaitCollectionsEnded` joins a snapshot of `subtreeScope`'s children. All of these are in-module and
  deterministic. Waits are deferreds and channels, and `withTimeout(5.seconds)` is only a guard. I found no sleeps.
- Severity: nit. Confidence: confirmed by reading.

### N6. `@property` on an interface body property

`AgentSubagents.kt:23`: `@property sessionId` sits in the KDoc of `AgentSubagentContext`, but `sessionId` is a body
property of an interface. `AGENTS.md` ("Where property and parameter documentation goes") puts its KDoc directly above
the declaration.

- Severity: nit. Confidence: confirmed by reading.

### N7. `AGENTS.md` doesn't record the new lifecycle rules yet

The range adds a shared lifecycle pattern: per-root subtree scope, cleanup before the close response, the replay
window, and the per-child lock. `AGENTS.md` "Runtime and Lifecycle Rules" has a paragraph for each comparable v1 rule,
but `AGENTS.md` is unchanged in the range. This may be planned for a later milestone. Record it as a follow-up if it
is.

- Severity: nit. Confidence: confirmed by reading.

## Assessment of the programmers' choices

1. **Inline correction.** Conformant in effect.
   - The send doesn't suspend (`sendUpdate` → `sendNotificationRaw` → `sendFrame` → `transport.send`), so a cancelled
     caller can't prevent it, which is what "from the root's supervisor scope" was for.
   - The `subtreeScope.isActive` guard gives "if that scope is already cancelled, nothing is sent", `runCatching`
     covers a dead protocol, and the replay case is skipped.
   - Inline is in fact stronger: the correction precedes anything the caller sends after catching.
   - Side effect: reading `subtreeScope` creates the lazy scope. That is harmless; it is also created and then
     cancelled in every `closeSubtree`.
2. **Factory CE after the announcement.** See S1. Recommend converting it to ISE when the caller is active.
3. **Per-child lock.** Correct.
   - Nothing suspends under it. The work under it is `checkRegistered`, atomic reads (`root.isReplaying` doesn't take
     `replayLock`), the grant/phase/`factoryState` writes, and the encode plus `trySend` in `StdioTransport` and
     `WebSocketTransport`.
   - No second lock is taken while it is held. `closeSubtree` takes each child's lock one at a time, after the table
     update, and `dropUnlessAnnounced` releases it before `unregisterSubagent`. `invokeOnCompletion` handlers and the
     `lazy` scope are not touched under it, so no lock-order cycle exists across parent/child/cleanup.
   - atomicfu 0.25: on JVM it is a monitor (reentrant), on Native a reentrant lock, and on JS/wasm a no-op. This is
     the repo's first use of `kotlinx.atomicfu.locks`, so the iOS targets compiling in CI is the real check.
   - Caveat: a custom `Transport.send` that blocks would block under the lock. `TestTransport.onSend` also runs under
     it.
4. **Lazy subtree scope and register-then-recheck.** Correct.
   - `closeSubtree` sets the flag before it snapshots the table, and `registerSubagent` puts the entry before it reads
     the flag. So either the cleanup removes the entry, or the registration sees the flag. Both can happen; both
     paths are idempotent and use identity checks.
5. **`.subagents` throws outside a session or in NES, `null` only without the capability.** Consistent with `.client`,
   and the design's `null` contract holds. Missing NES test (S4.3).
6. **ISE when the collection is cancelled before announcing.** Correct. A cancelled caller still gets its own CE
   (await is cancellable), and a caller in an app scope gets ISE instead of a foreign CE.
7. **Cancel during the factory dropped.** See S2.
8. **No `unknown` after an SDK cancel even if the flow swallowed it.** Matches "Feed end and failure"
   (`!currentCoroutineContext().isActive` at `SubagentWrapper.kt:336`). Tested at line 1096.
9. **Replay window ends at the start of `afterResponse`.** See N2.
10. **Pending child at cleanup becomes `DROPPED`, and `announce` on a closed or failed root fails.** Matches the design.
    See N3 for the message edge.
11. **Requests not under the lock.** See N1: a few-instruction race plus a likely `$/cancel_request`, not a protocol
    corruption.

Other required checks:
- **Grant before the send.** `sendOwnUpdateLocked` writes it before the send and restores it if the send throws.
- **Announcement first.** Pending-phase gate on every operation, plus the first-item check through the same `notify`.
- **`announce` as the ordering point.** `announcement` completes after the send returns and the phase is set.
- **Failed-load cleanup.** `createSession` catch → `closeSubtree`. Deferred collections are never started.
- **Close ordering.** App `close()`, then cleanup, then the response. If `close()` throws, there is no cleanup.

Public KDoc matches the implementation, except S1 (pending decision), S2 and N2, and the layout rule in N6.

## Open questions for the orchestrator

- S1: the user's decision between rethrowing unchanged and converting to ISE.
- S2: latch the cancel or document the gap.
- S3: does ACP allow `session/load` or `session/resume` of a session already open on the connection? The fix depends
  on it.
