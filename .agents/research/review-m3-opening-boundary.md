# Review: M3, the v1 client's opening boundary (`fa1f66d..d505b25`)

Commits reviewed: `955965c`, `a095659`, `751a644`, `21966d5`, `d505b25`. The `chore:` commits that touch only
`.agents/` were skipped. Everything was read through git at `d505b25`. Gradle was not run, as the brief required.

Counts: blocker 0, should-fix 3, nit 4. Follow-ups: 2.

## Findings

### 1. should-fix: a caller cancelled or timed out after the commit point loses the session, the session stays registered, and a retry fails

- **Where:** `acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientSessionHolder.kt:214-225` (`completeOpening`),
  `client/Client.kt:715-716` and `:596-597`. The claim appears in the `loadSession` KDoc (`Client.kt` ~`:388-392`),
  in `AGENTS.md`, and in the `d505b25` release note ("a caller cancelled at that point still gets the open session").
- **Defect:** after `Opened` is taken, the opening returns its value normally even when the caller is cancelled. The
  usual wrappers throw that value away: `withTimeout`, and `async` that is cancelled. The holder is complete and
  registered all the same.
- **Scenario:** the app calls `withTimeout(5.seconds) { client.loadSession(id, params, ops) }`. Its `notify` is slow
  on `Opened` or on the updates queued after it, which run inside `NonCancellable`, so the timeout fires during
  `completeOpening`. The block finishes with the session after its `TimeoutCoroutine` was cancelled. A suspended block
  that completes in a cancelling job ends with the cancellation cause, so `withTimeout` throws
  `TimeoutCancellationException` and drops the session. The session is open on the agent and registered in the client,
  and `ops` gets live updates. When the app retries `loadSession(id, ...)`, it fails with "Session … is already open or
  being opened". Before `d505b25`, the post-answer drain could be cancelled: it failed the holder, and the retry
  worked. For `newSession`/`forkSession`, the app doesn't even get the id from the call.
  `ClientSessionOpenedTest."a call cancelled while the marker is delivered still opens the session"` only passes
  because the test captures the value inside the cancelled `async` (`returned.complete(...)`). The `async` itself ends
  cancelled.
- **Fix direction (not designed here):** at minimum, document the recovery (`client.getSession(id)` / `close()`)
  and correct the "still gets the open session" wording in the KDoc, `AGENTS.md`, and the release note. Otherwise,
  reconsider making the whole post-marker drain non-cancellable.
- **Confidence:** confirmed by reading the code and kotlinx `withTimeout`/`JobSupport` completion semantics. Not run.

### 2. should-fix: app `notify` code that runs in `NonCancellable` can hang a cancelled opening, and holds every unknown session's updates in the buffer meanwhile

- **Where:** `client/ClientSessionHolder.kt:214-224` (`completeOpening` → `deliverLive`).
- **Defect:** `notify(Opened)` and `notify` for every update queued after the marker run under `NonCancellable`, so
  cancelling the caller no longer interrupts them. Before, the factory drain and the early delivery could be
  cancelled.
- **Scenario:** a typical UI client's `notify` does `uiEvents.send(event)` on a rendezvous channel. Its consumer runs
  in the same scope that called `loadSession`. When the user closes the window, the scope is cancelled: the consumer
  dies, and `send` suspends forever because the channel is never closed. The opening caller is stuck in
  `NonCancellable`, so `scope.cancel(); scope.join()` (or `runBlocking`, or a Ktor shutdown that joins) never
  returns. The opening is still inside `withInitializingSession` (count > 0). Until that call ends, any
  `session/update` for an unknown id therefore gets a speculative holder and is buffered without limit, and it never
  reaches `GlobalSessionUpdateHandler`. `Protocol.close()` itself does not hang, because it doesn't join the caller.
  Protocol requests made from inside `notify` fail with `CancellationException` on close and are logged, so they
  don't hang either. The hang comes only from app-level suspension.
- **Design conformance:** the design doesn't prescribe `NonCancellable`. It's the programmers' way to keep "if the
  opening fails, no stream gets `Opened`" (spec 771-891). It adds to the design rather than contradicting it, but it
  runs unbounded app code in `NonCancellable`. That is the opposite of `CLAUDE.md`'s "Preserve coroutine
  cancellation". One option is to leave only the SDK's own bookkeeping non-cancellable: after `Opened`, on
  cancellation, complete the session and hand the rest of the queue to the late path.
- **Confidence:** plausible. The hang depends on the app's `notify`. The `NonCancellable` scope was confirmed by
  reading.

### 3. should-fix: the behaviour change in choice 4 is covered only for the marker, not for the updates queued after it

- **Where:** `acp/src/jvmTest/kotlin/com/agentclientprotocol/client/ClientSessionOpenedTest.kt:462-476`. The other
  two cases have no test.
- **Defect:** the release note's behaviour change is "a later failure of `notify` is logged". Its main case, an update
  queued after the answer whose `notify` throws during `completeOpening` while the next updates still arrive, has no
  test. Neither does a `notify` that throws `CancellationException` from its own `withTimeout` there. The only test
  makes `notify(Opened)` throw.
- **Scenario:** a regression makes `deliverLive` rethrow, or stops the drain at the first failure. The open would then
  fail, or the later queued updates would be lost, and every current test would still pass. Most existing
  failure-path tests (`ClientEarlySessionUpdatesTest."a failing notify …"`, `"… times out …"`) fail before the
  answer, so they don't reach this path.
- **Confidence:** confirmed by reading the tests.

### 4. nit: the release note and KDoc say "once `Opened` has been delivered", but a `notify(Opened)` that throws also commits the opening

- **Where:** the `d505b25` commit body ("Once Opened has been delivered the opening succeeds"), the `loadSession` KDoc
  in `Client.kt` (~`:388-392`, "Once the marker is delivered, the load succeeds"), and `AGENTS.md`.
- **Scenario:** an app author reads this as "after `notify(Opened)` returns" and expects a throwing `notify(Opened)` to
  fail the load. In fact it is logged and the session opens (`ClientSessionOpenedTest:462`). The wording should say
  that the opening commits once the marker is handed to `notify`, whatever `notify` does.
- **Completeness:** the note doesn't mention the fix for permission requests that wait across turns (`955965c`). That
  can be fine if the changelog is generated from `fix:` subjects. Two smaller observable changes are also missing,
  and both are harmless: with the operations overloads, updates queued after the answer now get `clientSession` in
  their context, and they now pass through `trackState` and prompt routing. The M1/M2 model entries in the note are
  outside this range, so I did not check them.
- **Confidence:** confirmed by reading.

### 5. nit: `elicitation/complete` keeps the quirk where a failed holder passes the completed check

- **Where:** `client/Client.kt:165-167` and `client/ClientSessionRegistry.kt` (`isSessionCompleted`).
- **Scenario:** an elicitation's session failed to open, and its failed holder is still in the map because another
  opening is in flight. `isSessionCompleted` is true. `getSessionOrThrow` skips the failed holder, creates a
  speculative one (count > 0), and suspends in `await()` until the reaping. The `runCatching` around that suspending
  call also catches `CancellationException`. This matches the old `Client.kt` exactly, so `a095659` is
  behaviour-neutral as claimed. The design allowed tidying this up (spec 1131-1133), and the commit didn't.
- **Confidence:** confirmed by reading.

### 6. nit: code moved into the new file breaks the `AGENTS.md` comment rule

- **Where:** `client/ClientSessionRegistry.kt:46-49` and `:117-121`.
- **Defect:** these are multi-line `//` comments, where a block comment is required. They were copied verbatim from
  `Client.kt` into a new file.
- **Confidence:** confirmed by reading.

### 7. nit: `replayRequested` and `isAnswered` are stored but read only by tests

- **Where:** `client/ClientSessionHolder.kt:87-97`.
- **Assessment:** this is state for the next milestone (children's `Opened(null)`, `onParentOpened`) and is harmless
  now. Listed only so it isn't forgotten if that milestone slips.

## Assessment of the programmers' choices

1. **`21966d5` cancel swap.** Correct. `handleResponse`'s `getAndUpdate` gets either the original entry (hook runs) or
   the copy (no hook). The `finally` matches on the shared `response` deferred, so it removes the copy too. The
   remaining race (the read loop took the entry before the swap) is documented. In the normal path the graceful
   `response.await()` returns only after the hook, because the hook runs before `complete`. So the caller's failure
   path always sees the hook's effect. It is skipped only when the protocol is closing, which is choice 5. The
   read-loop write to `decoded` happens before `complete`, so the caller's read after `await` is ordered.
   `ReadLoopOrderingTest.hookDoesNotRunForACancelledCall…` fails without the swap. Good.
2. **Two helpers, and `getAndUpdate` in `handleResponse`.** Correct and behaviour-preserving. `JsonNull`→`{}` is
   checked for both overloads (`bothTypedOverloadsReadANullResultAsAnEmptyObject`).
3. **The consumer stops at the marker.** Correct on every path. In the early path, coroutineScope's exit is the commit
   point: a cancellation before it throws, and `failSessionHolder` drops the marker, even if the consumer already
   returned it. In the factory path, `drainHistory` can be cancelled inside `runCatching`, which fails the holder and
   rethrows. `failAndDrainQueue` closes the queue before draining, so a hook that races with it either fails
   `trySend` or leaves the marker to be dropped.
4. **The commit point.** See findings 1-3. Cancellation is still preserved where it must be: `createSession`'s
   `runCatching`, the `withAnsweredSessionId` catch, and the early catch all clean up and rethrow. `deliverLive`
   swallows a `CancellationException` that the app throws inside `NonCancellable`. That matches how
   `Protocol.handleNotification` treats live updates after the switch. App code inside `NonCancellable` cannot hang
   `Protocol.close()`, but it can hang the caller's scope (finding 2).
5. **New/fork with a late hook.** Acceptable. The hook can run after the catch's `claimed.isCompleted` check only when
   the graceful wait is skipped, which happens only while the protocol closes. The orphaned, claimed, answered holder
   is reaped when the count drops to 0. If the reaping's CAS has already removed the holder, the hook's
   `getOrCreateSessionHolderOrNull` returns `null` at count 0, so nothing leaks. The test at
   `ClientSessionOpenedTest:247` covers the main path, where the hook runs before the check.
6. **`Opened(response)` non-null; the late path throws; stored flags; `applyEarlyState` before the commit point.**
   All fine. `handleOrQueue` gets only `Update` items, and `markOpened` uses `trySend` directly, so the throwing
   branch is unreachable. `applyEarlyState` writes only to the not-yet-published session's own flows, which are
   discarded on failure. The deprecated `currentMode`/`configOptions` end up with the last mode overall, as before:
   pre-marker updates are applied by `applyEarlyState`, and post-marker ones by `handleNotification` → `trackState`.
   The control in `ClientEarlySessionUpdatesTest."updates racing the answer…"` asserts `REVIEW`.
7. **`getSession` and `isSessionCompleted` in the registry, and the kept quirk.** Fine, see finding 5.

## Other checks

- **`a095659` is behaviour-neutral.** The moved bodies are verbatim. `handleSessionRequest` and
  `createSessionElicitation` reproduce the old per-method lookup, cast, `executeWithSession` and URL tracking.
  `onClosed` → `removeClosedSession` does what the old `removeSessionHolder` did (map and elicitation store).
  `onClosing` is a no-op. No test changed in that commit.
- **Arrival order.** Before the switch, updates and the marker share one queue with one consumer at a time. The
  handoff from `deliverEarly` to the caller goes through `await`. Notification handlers run their non-suspending
  `trySend` part before the read loop reads on (`handleIncomingFrame`'s `withContext(handlerDispatcher) {}` on a
  single-thread dispatcher), and the hook runs inline. `completeOpening` closes the queue after `Opened`. An update
  that misses the queue waits on `lateDeliveries` and then `sessionDeferred.join()`, and the session completes only
  after the drain. Order holds.
- **Exactly one `Opened` per operations object.** It is delivered only by `completeOpening`, which is reached once
  per opening on all six paths (load/resume × operations/factory, new, fork). It is never delivered on error, decode
  failure, or cancellation before the commit point. `ClientSessionOpenedTest` covers all six for each case.
- **The hook touches only atomics, `Channel.trySend`, holder allocation, and `CompletableDeferred.complete`, plus the
  response decode.**
- **The one-opening rule.** All 4×4 known-id pairs fail before the request, whether the session is open or being
  opened. New/fork fail after the answer. Close then load works for all pairs.
- **Agent-side `notify`.** `RemoteClientSessionOperations` and `SubagentWrapper`'s child operations call
  `requireUpdate()` first and are otherwise unchanged. There is a test for both
  (`AgentSubagentsTest."the opening marker can't be sent…"`).
- **The v2 client** changes only through `clearCancellation()`.
- **`acp.api`.** The diff matches the intended change: the member `notify(SessionEvent)`, the `ClientSessionOperationsKt.notify`
  extension, and `SessionEvent`/`Update`/`Opened`, with no public `Opened` constructor. The deprecation doesn't show in
  the dump, as expected.
- **KDoc, `AGENTS.md`, `README.md`, and samples** follow the `CLAUDE.md` rules: no single-line KDoc, `@property` in
  the class KDoc, and no `.agents/` references. Samples and the README use only public API. All 37 `notify`
  overrides are migrated.
- **`955965c`.** `clearCancellation()` matches spec 1352-1371. The v1 regression test would fail (by timeout) with the
  old `startTurn()`, and a control case is included. The v2 counterpart is tested.
- **Test quality otherwise.** `ReadLoopOrderingTest` is deterministic (`HeldDispatcher`, transport callbacks). The
  "cancelled after hook" tests use an occupied executor, not sleeps. Existing `notify` test doubles that now return
  early on `Opened` were not weakened in what they assert. One exception: `ClientHangingSessionUpdateDeliveryTest`'s
  guard `error(...)` would now only be logged if it fired during the drain. It guards an update for another session,
  so the effect is negligible.

## Follow-ups (outside this milestone)

- `ClientSessionImpl.close()` → `removeSessionHolder(sessionId)` removes the holder by id, not by identity. Now that
  close-then-load is the documented way to reopen, calling `close()` again on the first session's stale handle would
  remove the reopened session's holder. It would also close that session on the agent. This predates M3. Check it
  when `removeSessionTree` lands.
- A failed `session/close` (error answer) leaves the holder complete and registered, so a reload fails with
  "already open or being opened", and the app can't release the id. This predates M3, but the one-opening rule makes
  it more visible.
