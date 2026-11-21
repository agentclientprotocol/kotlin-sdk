# Review: M4, v1 client-side subagents, plus fix slices `5bca6d3`, `2f35a77`, `38a25dc`, `9ce0d52`

Range: `c88eba8..ced95e7`, product commits `57b948c`, `c03d9df`, `d18dc66`, `ced95e7`, `5bca6d3`, `2f35a77`,
`38a25dc` and `9ce0d52`. I read everything through git at `ced95e7`. I did not run Gradle, because another programmer
is working in this checkout. Every finding is confirmed by reading, unless it says "plausible".

Counts: 0 blocker, 3 should-fix, 10 nit. Three findings need a user decision: 1, 2 (partly) and 4.

## Findings

### 1. should-fix (needs a user decision): a subagent's permission request that waits for its holder misses `idle(cancelled)` and `cancel()`, and gets an error instead of `cancelled` on root close

`Client.kt:109-114` (the handler calls `sessions.getSessionOrThrow(params.sessionId)`, which awaits the holder, and
only then `answerPermissionRequest`). `PendingPermissionCancellation.answerUnlessCancelled` reads the signal when it is
called. `SubagentPendingPermissions.onState` (`SubagentPendingPermissions.kt:60-67`) and `cancelByClient` (`:87-90`)
complete the signal and then replace it at once, unless the child has confirmed foreground work.

A child's request therefore enters the race only after the child's holder has completed. Under choice (a), that means
after the root's opening call has returned and after the child's consumer has delivered its queue. During that window
the child's state is already live, because `isAnswered` is set by the response hook, and the child object may be
visible in the parent's `notify`, with `canCancel` true.

- **Scenario A (late path, `idle(cancelled)`).**
  1. The root is open. The agent announces `c` with `state: running`, and the app's `createSubagentOperations` takes a
     while (it creates a UI panel).
  2. The agent sends `session/request_permission` for `c`. The handler waits in `getSessionOrThrow`.
  3. The agent cancels the child's work and sends `subagent_update {c, state: idle(cancelled)}` on the root's stream.
     It is sorted at arrival as live: `cancelPending()` completes a signal that nothing waits on, and
     `clearCancellation()` replaces it.
  4. The factory returns and the holder completes. The request reads the fresh signal and reaches the app, so the user
     is asked to approve a tool call of cancelled work.

  The RFD says the client cleans up a descendant's pending requests once it reports `idle` with `cancelled`
  (`:1170-1174`). The design says a child's permissions follow wire order. The request arrived first, so it is out of
  order here.
- **Scenario B (resume, `cancel()`).**
  1. `resumeSession` with operations. `c` is announced before the answer, with a grant and `state: idle`. It sends a
     permission request, which waits.
  2. The answer promotes the grant. Inside the root's `notify(Opened)`, the app calls `c.cancel()`.
  3. `cancelByClient` completes the signal and then clears it, because `activeWork` is false. The waiting request
     later reaches the app.

  The design says "`cancel()` always answers the requests pending at that moment".
- **Scenario C (root close).**
  1. A child's holder isn't complete: its factory or its pre-`Opened` `notify` is slow. A permission request for it
     waits.
  2. `close()` closes the child's signal (`closeDescendantPermissions`). After the response, `removeSessionTree` fails
     the holder with "Session c closed".
  3. The request is answered with a JSON-RPC error, not with the `cancelled` outcome that the `ClientSession.close`
     KDoc promises for subagents (`ClientSession.kt:63-65`).
- **Why it needs a decision.** The design's text says "requests on a child id wait until its holder completes", and
  `onOpened`'s premise is that no request is pending then. Fixing this means taking the signal at arrival, in the
  request handler's synchronous prefix, before the await. Request handlers start in `handlerDispatcher` FIFO order, so
  that prefix runs before later notifications are sorted. Then answering `cancelled` without waiting for the holder.
  This changes the stated rule for the `cancelled` outcome only.
- **Tests.** No test covers a request that waits for the holder across a live `idle(cancelled)`, a `cancel()`, or a
  close. `ClientSubagentSessionsTest.kt:410` covers only requests sent after the holder completed.

### 2. should-fix: a subagent announced after `onClosing` never has its permissions closed, so its requests stay with the app after the root is closed

`ClientSessionRegistry.kt:106-109` closes the descendants' permissions only before the close request.
`removeSessionTree` (`:145-166`) calls `holder.subagent?.markClosed()`, which only flips `closed`
(`ClientSubagentSession.kt:188-190`). It never calls `subagentPermissions.close()`.

- **Scenario.**
  1. `close()` sends `session/close`. Before the response, the agent announces `d` under the root, which `d18dc66`
     allows, and the holder completes.
  2. The agent sends a permission request for `d`, and it reaches the app's `requestPermissions`.
  3. The close response arrives, and `removeSessionTree` removes `d`.
  4. The request is still pending in the app. Nothing answers it `cancelled`, and the app may show a dialog for a
     session that no longer exists. The same applies to requests that reach `d`'s operations between the close
     request and the response.

  This contradicts the `ClientSession.close` KDoc ("Requests that arrive later are answered `cancelled` without
  reaching [operations]. The same holds for every subagent session below this one", `ClientSession.kt:63-65`). It is
  also what the RFD's "cleans up their requests together with the parent's" covers (residual risk e2).
- **Fix without a decision.** In the `NonCancellable` block of `removeSessionTree`, call
  `holder.subagentPermissions?.close()` for every holder in the subtree. Closing a signal only completes it, and it is
  idempotent. This cleans up at removal.
- **Needs a decision.** Answering `d`'s requests between the close request and the response, as the KDoc promises,
  needs a "root closing" flag that `registerChild` reads. Otherwise, narrow the KDoc to the subagents that existed
  when the close started.
- **Tests.** `ClientSubagentCloseTest.kt:261` registers `duringRequest` in exactly this window but never sends it a
  permission request.

### 3. should-fix: `AGENTS.md` doesn't record the client-side subagent rules

`AGENTS.md:146`. The holder bullet still describes only roots. `d18dc66` and `ced95e7` add shared lifecycle rules that
later changes can easily break, and `AGENTS.md` says to update it in the same change:

- sorting and registration at arrival must stay non-suspending;
- only the handler adds children (one tree writer);
- the hook puts `Opened(null)` into every open descendant queue, and the invariant that each one is still queuing
  then;
- a child's consumer waits for its parent's holder before switching to synchronous delivery;
- announcements on an open holder always take the `lateDeliveries` chain;
- the blocking rule and the reaping exemption;
- the close order: close permissions, request, close the children maps, stop and join the consumers, CAS removal,
  then fail bottom-up;
- the `SubagentConsumer` self-join exemption.

The `9ce0d52` bullets are agent-only, and they match the code at `ced95e7`: the latch, the wrapped factory
`CancellationException`, the replay window from `invokeOnResponseQueued`, the re-open cleanup, and the lock rule. One
small inaccuracy: the latched cancel's grant is checked when the launched coroutine runs (`SubagentWrapper`
`deliverLatchedCancel`), slightly after "when the factory returns". The KDoc wording "at that point" fits better.

### 4. nit (needs a user decision): a subagent whose `notify` fails before its `Opened` stays in `subagents` and keeps looking live

`SubagentSetup.kt:90-94` adds the child to the parent's list before its consumer runs. `consume`
(`SubagentSetup.kt:102-114`) fails the holder when `notify` throws before or at the marker, but leaves the object in
the list, not marked closed.

The design lists "its `notify` throws" among "its own setup fails". `BaseClientSession.subagents`' KDoc says "a
subagent whose setup failed is never in it" (`BaseClientSession.kt:39-42`).

- **Scenario.**
  1. A load's replayed child's `notify` throws on history.
  2. The child stays in `root.subagents`. Later `Patch`es on the root's stream still `applyPatch` it
     (`ClientSessionHolder.kt:545`), so `state` updates and `canCancel` can become true.
  3. `cancel()` sends `session/cancel` for a child whose updates now go to the global handler and whose requests get
     errors.
- **Options.** Mark the object closed when its holder fails after setup, and document that it may remain in the
  list. Or remove it from the list, which breaks "only grows".
- **Tests.** `ClientSubagentSessionsTest.kt:844` doesn't assert membership.

### 5. nit: `ClientSession.close` KDoc says closed subagents' operations "get no more calls", but an accepted late delivery can still call them

`ClientSession.kt:70-71`, together with `ClientSessionHolder.handleOrQueue`/`deliverToSession` (`:558-597`).

- **Scenario.**
  1. A completed child `c` is in a slow synchronous `notify(u1)` in the handler coroutine, and `u2` waits in
     `lateDeliveries`. Both arrived before the close response.
  2. `close()` returns after `removeSessionTree`.
  3. Then `u1` finishes, and `u2` is delivered to `c.notify` after `close()` returned. `deliverToSession` only checks
     whether the session completed exceptionally, and a removed child's holder completed successfully.

  This is residual risk e1. It is the same as for roots today. These are updates, not requests, so the RFD sentence
  isn't violated, but the KDoc is.
- **Fix.** Either say "updates already being delivered when the response arrives may still reach them", or skip
  delivery for a removed holder.

### 6. nit (fix now if cheap): a re-open can fail once on the old root's concurrent `announce` (choice g)

`Agent.kt:710-711` and `registerSubagent` (`Agent.kt:773-788`). `closeSubtree` sets `isSubtreeClosed`, then scans
`_subagents`. `registerSubagent` puts the child in and only afterwards re-checks the flag.

- **Scenario.**
  1. An old root's turn `announce`s `c` and puts it into the table just after the cleanup's scan.
  2. Before its re-check removes it, the new root's `replayHistory` announces `c` and fails the `require` with
     "already registered".
  3. If `replayHistory` doesn't catch that, the load fails and is discarded. Under decision 4 the old root isn't
     restored, so the id is absent until a retry.
- **Assessment.** This needs true parallelism and an app that announces the same id from the old turn while it reloads
  the session. A retry works.
- **Fix.** Read `child.root.isSubtreeClosed` inside the `_subagents.update` lambda, and throw there. Cleanup sets the
  flag before its own CAS, so any registration CAS that succeeds after the cleanup's CAS has read the flag as closed.
  That is three lines with no lock, so it is reasonable to fix now. Otherwise record it as a follow-up.

### 7. nit: on the factory paths, a `CancellationException` from `notify` during history passes to an active caller as is (choice h)

`Client.kt:606-617` (`createSession`'s `runCatching` rethrows the original `throwable`) and
`ClientSessionHolder.drainHistory` (`:333-342`), which doesn't wrap. `completeOpening` (`deliverOpening`) and
`deliverEarly` (`Client.kt:713-723`) do wrap.

- **Scenario.** A `withTimeout` inside `notify` during the factory path's history throws
  `TimeoutCancellationException`. The holder fails correctly, but `loadSession` throws a `CancellationException` while
  its caller is active. A `launch { client.loadSession(...) }` then ends silently as cancelled, while the same failure
  on the operations overload surfaces as `IllegalStateException`. The factory's own
  `createClientOperations` behaves the same way.
- The opening still fails as decision 5 requires. Only the exception type is inconsistent. The six opening paths
  otherwise all fail the holder after the answer: load and resume with operations through
  `withEarlyInitializingSession`'s catch, and load, resume, new and fork with a factory through `createSession`'s
  `runCatching`.

### 8. nit: failed subagent holders of a failed root are never removed from the session map

`ClientSessionRegistry.kt:381-384`. The reaping exempts `parent != null`, and only `removeSessionTree` removes
children.

- **Scenario.** Every failed `session/load` whose replay announced subagents leaves their failed holders in the map
  for the life of the connection. They no longer block their ids, so this is functionally harmless, but it is an
  unbounded leak over repeated failed loads with fresh child ids.
- **Fix.** Remove them in the reaping when their root is reaped. Or let `failSessionTree` remove a child whose parent
  isn't live.

### 9. nit, plausible: the failure cascade cancels consumers without joining them, so one more queued item can reach a failed child's `notify` (choice c)

`ClientSessionRegistry.kt:348-358` and `ClientSessionHolder.consumeSubagentQueue` (`:464-470`, no `ensureActive`
before `receiveCatching` in the pre-`Opened` loop).

- **Scenario.** The root's opening fails after its answer while `c`'s consumer is in a `notify` that ignores
  cancellation. `failAndDrainQueue(c)` drains the queue to the global handler. When `notify` returns, the
  `receiveCatching` fast path can race `tryReceive` for the next item, and deliver it to `c.notify` instead.
- The impact is one update split between two receivers. Joining isn't needed. An `ensureActive()` before each receive,
  as the post-`Opened` loop has, narrows it.

### 10. nit: `createSubagentOperations` runs with no session in the context on the root's early path, and with the parent's session elsewhere (choice c)

`deliverEarly` (`ClientSessionHolder.kt:379-396`) calls `deliverItem` without `executeWithSession`. `drainHistory`,
`deliverToSession` and `consumeSubagentQueue` wrap it.

- **Scenario.** An app's `createSubagentOperations` reads `coroutineContext.baseClientSession` to find the parent. That
  works with a factory or live, and throws "No client session data found" during a load with operations.
- This is consistent with the root's own early `notify`, which also has no context. But `SubagentOperations`' KDoc says
  nothing either way, so state it there.

### 11. nit, plausible: the `SubagentConsumer` element is inherited by app coroutines (choice d)

`ClientSessionHolder.kt:316-318` and `ClientSessionRegistry.kt:138-144`.

- **Scenario.** Inside a child's `notify`, the app runs `coroutineScope { launch { root.close() } }`. The launched
  coroutine carries `SubagentConsumer(c)`, so the close skips joining `c`'s consumer and then cancels it.
- That is the intended outcome when the close came from that consumer. It does no harm when the coroutine is a
  structured child of the consumer. But an app coroutine that copies the consumer's context into a long-lived scope
  would make every close skip that join. It's worth one KDoc line on the element.

### 12. nit: N3 (`remove()` no longer fails a sent `announce`) has no regression test (choice f)

`SubagentWrapper.remove()` (`5bca6d3`). The fix is correct by reading: `wasPending` is computed under the lock that
also guards `ANNOUNCED`. A deterministic test is possible: close the root from `onSend` of the announcement on another
coroutine, gated on a deferred, after the phase change.

### 13. nit, plausible: a 500 ms wall-clock timeout must fire during marker delivery

`ClientSessionOpenedTest.kt:482` (`OPENING_TIMEOUT = 500.milliseconds`, `:708`), in "a caller that times out while
the marker is delivered fails the opening".

The test guards against a vacuous pass with `assertTrue(entered.isCompleted)`. But on a slow CI, the request, answer
and marker delivery of one of six openings can exceed 500 ms, and the test then fails spuriously. A virtual-time
scheduler or a caller-side cancel at `entered` would be deterministic.

## Assessment of the programmers' choices

- **a. A child's consumer waits for its parent's holder.** Correct and needed under decision 5. Without it, a root that
  fails after its answer couldn't fail a child that already completed. That child would stay live under a failed root.
  - **No new deadlock.** The root's `completeOpening` never waits on a child: it delivers the root's own queue and
    sets up children through their factories. A child's consumer waits only on the parent's holder.
  - **The agent case.** An agent that sends a child permission request during load and waits for it before answering
    deadlocks. That is the design's own rule, the same as for the root's own requests (`getSessionOrThrow`), so it
    isn't introduced by this choice.
  - **The app case.** One new app-level pattern does deadlock: a root `notify` (for `Opened` or a queued update) that
    waits for a live event on a child's stream. That is the same class as "a `notify` waiting for a later event of a
    stream", and it is acceptable. Mention it in the `ClientSubagentSession` KDoc, which already says live updates
    wait for the root's opening call.
  - **Against the design.** "Requests on a child id wait until its holder completes" still holds. The holder simply
    completes later. The cost shows up in finding 1, which widens the window.
- **b. A `notify` failure only logged after the child's `Opened`.** Consistent with roots after the call returns, and
  with the design ("its notify throws" before `Opened` is a setup failure). See finding 4 for the list membership.
- **c. Choice c, four parts.**
  - The cascade without join: acceptable, see finding 9.
  - Late-path announcements always taking `lateDeliveries`: correct, and needed so that later parent updates wait for
    the setup (`ClientSessionHolder.kt:566`).
  - The context: see finding 10.
  - `opened` as a parameter rather than a field: fine. Each consumer phase passes a constant, so there is one writer
    by construction.
- **d. The close mechanics.** `SubagentConsumer` works for the direct case, and finding 11 covers the edge.
  - `SubagentList` closing in `closeChildren` correctly makes a setup that finishes after close drop the child
    (`SubagentSetup.kt:90-93`).
  - Removal and failing in `finally` under `NonCancellable`: correct.
  - Failing bottom-up: correct. A child's re-cascade over already-failed grandchildren is a no-op.
  - Roots-only lookup: correct.
- **e. The residual risks.** e1: see finding 5. It doesn't breach the RFD, which is about requests, but it breaches
  the KDoc. e2: it does breach "cleans up their requests together with the parent's" for permission requests, see
  finding 2. The removal-time part is a one-line fix.
- **f. `invokeOnResponseQueued` and the other agent fixes.**
  - Where the hook runs: in the frame-collector coroutine, after `sendFrame` returns and before
    `responseFrameQueued` completes, only for success slots (`Protocol.kt:496-503`, `ResponseSlot`).
    `endReplay` takes a short lock and launches collections, which is allowed.
  - The window: a send from another thread between the transport's acceptance and the hook is classified as replay
    by the agent, but the client sees it as live. That window is documented, and much smaller than the old
    `afterResponse` one. Acceptable.
  - The latch: correct under the lock. `_session` is published together with clearing the latch, so a cancel either
    latches or sees the session. It is delivered via `subtreeScope`, so a closed root drops it, and the grant is
    checked at delivery (decision 2).
  - `discardFailedLoad` removing the root matches "a failed load drops the root with every registration made during
    it". `onCompletion` catches the "response never reached the transport" case.
  - N3: see finding 12.
- **g. The re-open cleanup.** The ordering (factory, replace, clean up the old subtree, new replay, response) matches
  decision 4, and a failed reload leaving the id absent is documented. For the gap, see finding 6: it is cheap to fix
  now, and not a blocker.
- **h. The failure paths after the answer.** All six paths fail the holder. `drainHistory`'s `CancellationException`
  is finding 7.

## Other checks (no finding)

- **Sorting and registration are synchronous.** `getOrCreateSessionHolderOrNull`, `queuedItemFor`, `registerChild` and
  `followSubagentState` are not `suspend`. `handleOrQueue`'s `trySend`, and its non-suspending `join` of completed
  jobs, run before any suspension. The comment on `deliverSessionUpdate` states the constraint, and a regression test
  exists (`ClientSubagentSessionsTest.kt:497`).
- **One writer for the tree.** Only the handler's prefix calls `addChild`/`removeChild`. Cascade and close only close
  the map.
- **The blocking rule.** Self, ancestor, another parent's child, an open root, a root being opened, and a speculative
  holder are all rejected by `blocksId`. A failed parent and an unknown parent register nothing. There are tests at
  `:679`, `:729` and `:766`, and in `ClientSessionRegistryTest.kt:209`.
- **The reaping exemption.** Present (`ClientSessionRegistry.kt:381-384`), and tested at `:788`. The leak it causes is
  finding 8.
- **The hook.** `markOpened`/`markSubagentOpened`/`onOpened` touch only channels and atomics. Descendant queues are
  open whenever the hook runs: a child's queue closes only after its `Opened` and its parent's completion, and failed
  ones are skipped.
- **Exactly one `Opened` per child.** It comes from the hook for children registered before the answer, or at
  registration for later ones, serialized in read-loop order. A root that fails before its answer gives no child an
  `Opened`.
- **Held-back and promoted values.** `applyPatch` holds back `capabilities`/`state` until the parent's `Opened`, and
  `onParentOpened` uses the root's `replayRequested`, at every depth. The getter contract holds: each patch or
  announcement is applied before it is delivered, and held-back values are resolved before the parent's `Opened`.
- **Update order per session.** It holds across the switch and through `lateDeliveries` (test `:878`).
- **Root state.** Root `trackState` is untouched by child updates (test `:816`).
- **v2 runtimes.** No v2 file changed in the range.
- **API dump.** The `acp/api/acp.api` diff for the range matches the design. `BaseClientSession` and its
  `baseClientSession` accessor, `ClientSession : BaseClientSession`, the final `ClientSubagentSession` with no public
  constructor, and `SubagentOperations` are all `@UnstableApi`. The source break for external `ClientSession`
  implementers (`subagents`, `cwd`, `additionalDirectories`) is accepted in the design.
- **KDoc and comment rules.** No single-line KDoc, no runs of `//` comments, and no `.agents/` references in the
  diff. `@property` and `@param` are placed correctly. KDoc inaccuracies are findings 2, 4 and 5.
- **Tests.**
  - The waits use deferreds and flows, and `withTimeout` is only a guard. Apart from finding 13, I found no sleeps.
  - The race tests in `ClientSubagentCloseTest` pin each interleaving with `NonCancellable` gates. They are
    meaningful.
  - The loops over `allOpenings` cover all six paths.
  - Missing cases are listed under findings 1, 2 and 4.
