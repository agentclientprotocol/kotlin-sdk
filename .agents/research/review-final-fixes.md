# Review: final fix-and-docs milestone of v1 subagents (`1bb9dcc`, `f684ec3`, `0bd1962`)

Range `ced95e7..c1d1378`. I reviewed the product commits `1bb9dcc` (M4.5a), `f684ec3` (M4.5b) and `0bd1962` (M6b).
I read `c4b62c9` only for context. I read the code at the tip (`c1d1378`) and changed nothing in the checkout.

To confirm findings by running, I copied the tip into `scratch/review-final-copy` and extracted `ced95e7` into
`scratch/review-final-ced` with `git archive`. Both are gitignored. I added one experimental test to each copy and ran
`:acp:jvmTest` there. The logs are `scratch/review-final-run{1..4}.log`.

Counts: 0 blocker, 2 should-fix, 9 nit.

## Findings

### 1. should-fix (confirmed by running): a close that a subagent's `notify` calls under `withContext(NonCancellable)` hangs forever

`ClientSessionRegistry.kt:154-160` and `:200` (`isSelfOrAncestorOf`), introduced by `1bb9dcc`. It is prescribed by
`AGENTS.md:167`, the spec's "Detecting the consumer that the close runs in" (spec `:1305`) and decision 25.

**Defect.** `withContext(NonCancellable)` replaces the coroutine's `Job`. The same goes for
`withContext(appScope.coroutineContext)`, `withContext(Job())`, and `otherScope.async { close() }.await()`. The coroutine
that runs the close is then no longer in the consumer's `Job.children` tree, although the consumer waits for it.
`removeSessionTree` doesn't recognise its own consumer, cancels it, and `join()`s it under `NonCancellable`. The
consumer can't finish while it waits for the close, so the close and the subagent's consumer deadlock.

**Scenario.**
1. A subagent's `notify(Opened)` (or any of its pre-completion `notify` calls) runs cleanup in a `finally` block with
   the usual idiom `withContext(NonCancellable) { root.close() }`.
2. The agent answers `session/close`.
3. `close()` never returns, the subagent's consumer is stuck, and the tree is never removed from the map.

**Evidence.**
- The test below hangs and times out after 5 s at the tip.
- It passes at `ced95e7`, where the `SubagentConsumer` context element survived `withContext(NonCancellable)`.
- The other 13 tests in `ClientSubagentCloseTest` pass at the tip.

```kotlin
world.configure = { operations ->
    if (operations.id == child) operations.onNotify = { event, _ ->
        if (event is SessionEvent.Opened) closed.complete(runCatching { withContext(NonCancellable) { rootSession.await().close() } })
    }
}
rootSession.complete(openLive(c, root, world))
c.agent.sendUpdate(root, announce(child))
c.agent.answer(c.agent.receiveRequest(CLOSE.methodName), CLOSE.responseSerializer, CloseSessionResponse())
assertEquals(CloseSessionResponse(), withTimeout(5.seconds) { closed.await() }.getOrThrow())
```

**Assessment.** M4 nit 11 traded a mild false positive for a hang. With the element, a close from a copied context only
skipped one join. With job ancestry, a close that the consumer waits for through a replaced `Job` deadlocks. This is a
regression caused by the fix. `AGENTS.md` now forbids the approach that handled it.

**Direction (needs a decision, since `AGENTS.md` and decision 25 fix the mechanism).**
- Recommended: skip the join when either signal matches, the job ancestry or a context element that names the
  consumer. The element's only false positive is a close from a copied context returning before that consumer reacts.
  Update `AGENTS.md:167` and the spec accordingly.
- Alternatively: document that `close()` must not be called from a subagent's `notify` under a replaced `Job`. This is
  weak, because the idiom is common.
- Either way: add the test above as a regression test.

### 2. should-fix (confirmed by running): during a resume, an `idle(cancelled)` that arrives before the answer never answers the permission requests that wait for the holder

`SubagentPendingPermissions.kt:54-59`: a non-live state only stores `confirmsWork`, and the `cancelPending()` effect of
`idle(cancelled)` is dropped. `:80-83`: `onOpened` promotes only `activeWork`.

**Defect.** Since `f684ec3`, a request can be pending at `onOpened`. The updated KDoc there says that is "safe". But a
resume's pre-answer states are current, so a held `idle(cancelled)` should still answer the requests that arrived
before it.

**Scenario.**
1. `resumeSession` with operations. The agent announces `c` with `state: running`.
2. `c` sends `session/request_permission`. It joins the race and waits for the holder.
3. The agent cancels `c`'s work and sends `subagent_update {c, state: idle(cancelled)}`, still before the resume
   response.
4. The answer arrives. `onOpened(promote = true)` sets `activeWork = false`, and the signal stays as it was.
5. Once the holder completes, the request reaches the app, which approves a tool call of cancelled work.

**Evidence.** My test (resume, announce running, request, `idle(cancelled)`, answer) fails at the tip with
`expected Cancelled but was Selected(optionId=allow)`.

**Contradicted contracts.**
- The `ClientSubagentSession` KDoc (`ClientSubagentSession.kt:32-34`, "answered `cancelled` ... if the subagent's work
  is cancelled ... meanwhile").
- `AGENTS.md:164` ("an `idle(cancelled)` ... answers a request that still waits for the holder").
- The RFD rule quoted in the `SubagentPendingPermissions` KDoc.

**Direction (small decision on where the effect lands).**
- Option a: hold the cancellation too. A non-live `idle(cancelled)` sets a `heldCancelled` flag, and a later held state
  clears it. `onOpened(promote = true)` then calls `cancelPending()` and `clearCancellation()`.
- Option b: on a non-replay opening (`root.replayRequested == false`), answer pending requests at arrival. If the
  opening fails, those requests would fail anyway.
- For a load, keep dropping it (history).
- Add the test.

### 3. nit (plausible, from the channel contract): residual (a), an item handed to a suspended pre-marker `receiveCatching` is lost if the consumer is cancelled before it resumes

`ClientSessionHolder.kt:496`.

**Defect.** `receive`'s prompt-cancellation guarantee drops an element that was handed to a suspended receiver whose
job is cancelled before it resumes. No `onUndeliveredElement` is set (`:113`).

**Scenario.**
1. During a load, `c`'s consumer waits for history.
2. An update for `c` is handed to it.
3. The agent's error response fails the root. The cascade cancels the consumer before it is dispatched.
4. The update reaches neither `notify` nor the global handler. The same can happen with `removeSessionTree` for a child
   that hasn't opened.

**Severity.**
- It needs a scheduling race: the consumer was dispatched first.
- It loses one update of a session that is ending.
- The root's `deliverEarly` (`:406`) and `drainHistory` (`:360`) already behave the same way for a cancelled caller.

Not worth fixing now. If fixed: use `Channel(UNLIMITED, onUndeliveredElement = ...)` into a side slot that
`failAndDrainQueue` drains first. That keeps the order.

### 4. nit (plausible): residual (b), a waiting request can get an error instead of `cancelled` when its holder fails at the moment its signal completes

`BaseClientSessionRuntime.kt:55-64`.

**Defect.** If `session()` fails after `select` has picked the cancel branch, the failure of `async` wins: it is the
first non-cancellation exception of the child job, so `coroutineScope` rethrows it.

**Reach.** I found no deterministic path:
- `removeSessionTree` closes signals that `onClosing` closed long before.
- `failSessionTree` never completes a signal.

It needs true parallelism, for example a live `idle(cancelled)` on the read loop while the child's `notify` throws on
another thread. Exactly one response still holds, and both outcomes are allowed for a failing child.

**Direction (optional).** Catch a failure of `session()`, and answer `cancelledAnswer()` if the signal has completed.

### 5. nit (plausible): the race's `answer` re-resolves the id instead of awaiting the holder whose permissions it took

`Client.kt:115-119` (`sessions.getSessionOrThrow(params.sessionId)`).

**Defect.** `async { answer() }` starts after a dispatch. By then the id may map to another holder.

**Scenario.**
1. The child of a failed, reaped root waits for dispatch.
2. Another opening is in progress, so `getOrCreateSessionHolder` creates a speculative *root* holder for the child id.
   That holder blocks the id until the reaping.
3. In a rarer case, the id has been re-registered under another root. The request then reaches the new holder's
   operations, but races the old signal.

Every realistic case still ends with one response, and usually an error.

**Direction.** Have the registry return the child holder at arrival. Use its `subagentPermissions`, and await
`holder.session` directly.

### 6. nit: the arrival-time join depends on an undocumented `Protocol` ordering

`AGENTS.md:164` and the comment at `Client.kt:110-114`.

**Defect.** Both rely on request handlers starting in arrival order on the single-threaded `handlerDispatcher`, behind
the notification barrier `withContext(handlerDispatcher) {}` (`Protocol.kt:477`, `:523`). Only the spec states this
(spec `:1551`). The `AGENTS.md` and `RpcMethodsOperations` ordering rules cover only notifications.

**Scenario.** Someone gives request handlers their own dispatcher, or adds a suspension before the typed handler runs.
The idle-cancelled test would then turn into a race instead of failing.

**Direction.** Record the request-side ordering in the notification-ordering bullet of `AGENTS.md`, or in the
`setRequestHandler` KDoc.

### 7. nit: the spec's decision 25 names the wrong actor

Spec `:2318`: "The removal reads a separate `removed` flag inside the registration's update of the map." In fact the
registration reads it inside its own update, and the removal sets it (`ClientSessionRegistry.kt:168`, `:318`).

### 8. nit: the wording on when a subagent is closed is slightly off

- `AGENTS.md:168` says "a child whose holder fails, because ... its root closes, is marked closed". At a root close, a
  completed child's holder doesn't fail, but `removeSessionTree` still marks it closed (`ClientSessionRegistry.kt:186`).
- The `ClientSubagentSession` KDoc (`:37-41`) names only the subagent's own stream failing and the root closing. The
  cascade also closes it when a parent's or the root's opening fails (`failSessionTree`, `:405`).

**Direction.** Say "a child is marked closed when its holder fails (its own `notify` before `Opened`, or a failed
ancestor) or when its root's tree is removed".

### 9. nit: residual (c), the test gaps (accept)

- **Gap b.** The new test in `ClientSubagentCloseTest` ("a subagent's consumer stopped by the close hands the updates
  it has not taken to the global handler") also passes on the old loop. There, `withContext` rethrows the
  cancellation when the swallowing `notify` returns. The old loss needed a concurrent cancel between the exit of
  `withContext` and `ensureActive()`. So this is coverage, not a regression test. The commit body doesn't claim
  otherwise.
- **Gap c** has no test. By reading, the ordering is sound:
  1. `addChild` succeeds only before the walk's `closeChildren`, so the child is in `subtree`.
  2. `markRemoved()` precedes the removal compare-and-set.
  3. The registration reads `isRemoved` after reading the storage it compares against.
  4. So either the removal retries and sees the child, or the registration sees the flag.

### 10. nit: M4 finding 12 is still open

There is no regression test for agent N3 (`remove()` vs. a sent announcement). The programmer calls it unreachable. The
M4 review sketched a deterministic test: close the root from `onSend` of the announcement, gated on a deferred. Accept it
or record it as a follow-up.

### 11. nit: stale code cites in the spec (known)

For example spec `:1201` (`client/Client.kt:236`, `:787`) and `:1328` (`client/ClientSessionImpl.kt:111`). The
Deliverables section still reads as a plan.

## Status of the M4 review findings 1-13

| # | Status |
|---|--------|
| 1 | Resolved by `f684ec3` per decision 1, apart from the resume pre-answer `idle(cancelled)` gap (finding 2 above) |
| 2 | Resolved: the removal closes permissions (`1bb9dcc`), and the `closing` flag works per decision 2 (`f684ec3`) |
| 3 | Resolved by `0bd1962`, with accuracy nits 1, 6 and 8 above |
| 4 | Resolved per decision 3: `markClosed` in `failSessionTree` and in `setUp`, and patches ignored while closed |
| 5 | Resolved: the `close` KDoc is reworded |
| 6 | Resolved: `registerSubagent` checks inside the update, and a closed-root entry counts as free. Deterministic test |
| 7 | Resolved: `drainHistory` goes through `deliverOpening`, and the factory CE becomes ISE |
| 8 | Resolved: the reaping and `replacing()` remove descendants, and `registerChild` reads the root's failure |
| 9 | Resolved (narrowed): `ensureActive()` before each pre-marker receive. Deterministic regression test |
| 10 | Resolved: `SubagentOperations` KDoc |
| 11 | Resolved, but the fix regresses into finding 1 |
| 12 | Open, accepted (finding 10 above) |
| 13 | Resolved: virtual-time caller timeout, deterministic |

## Decisions: conformance

- **(1) Arrival-time join.** `subagentPermissionsOrNull` is non-suspending and called before anything suspends.
  `getSessionOrThrow` runs inside `answer`. The app sees the request only after the holder completes. Roots are
  unchanged. The behaviour conforms, except finding 2, which is in the held-state logic and not in the join.
- **(2) `closing` flag.** It sits on the root holder next to `answered` and is read through `root`.
  `closeDescendantPermissions` sets it first. `registerChild` reads it after `addChild`. It is never reset and uses no
  lock. Conforms. The separate `removed` flag is sound (finding 9).
- **(3) Failed children.** They are marked closed, stay listed, `canCancel` is false, `cancel()` throws, and patches
  and held-back promotion are skipped. Conforms.
- **Factory and history CE.** They surface as `IllegalStateException` while the caller is active, and the cause is
  kept. Conforms.
- **Cancelled or failing `notify` during `Opened` and queued delivery.** It fails the opening. Unchanged, and conforms.

## Rationale in spec decisions 6, 19 and 25

- **Decision 6** (accepting a re-open): matches the S3 research summary. v1 is silent, no error code fits, and v2, Rust
  and the pre-subagents agent accept it. Accurate.
- **Decision 19** ("Leaving it live was rejected"): matches M4 finding 4's scenario. Accurate.
- **Decision 25** ("Narrowing the KDoc was rejected"): consistent with the RFD quote the spec cites (`:1174-1175`,
  "cleans up their requests together with the parent's"). Accurate, apart from the reversed sentence in finding 7. Its
  own-consumer bullet carries finding 1.

## What I verified

- **Public API.** No `.api` file changed in `ced95e7..c1d1378`. The public changes are KDoc only: `ClientSession.close`,
  `BaseClientSession.subagents`, `ClientSubagentSession` and `SubagentOperations`.
- **Working-file references.** None in `AGENTS.md`, the spec, or code, comments and tests in the range.
- **Comment and KDoc rules.** No single-line KDoc, and no runs of `//`.
- **Exactly one response per permission request**, on every path I traced:
  - before the holder completes;
  - holder failure (an error, as documented);
  - root close (`cancelled`, since the signal is closed at `onClosing`);
  - a child registered during or after the close (closed at registration);
  - `cancel()`;
  - live `idle(cancelled)`;
  - connection end (the handler is cancelled, nothing is sent).

  `answerUnlessCancelled` is the only answering path, and `Protocol`'s slot allows one reply.
- **Post-marker loop** (`ClientSessionHolder.kt:522-528`):
  - the queue is closed before the loop, so `tryReceive` sees a fixed set;
  - there is no suspension between `ensureActive()` and handing the item to `deliverLive`;
  - a cancellation inside `notify` exits through `deliverLive`'s `ensureActive()`, and the rest stays for
    `failAndDrainQueue`;
  - no loss, duplication or reordering, except when a close runs in a child coroutine of the consumer while the
    consumer continues (two receivers, still no loss).
- **Cascade and setup interplay.** The order `stopConsumer` then `markClosed` in `failSessionTree`, and `attach` then
  `startConsumer` in `setUp`, closes every interleaving.
- **Agent `registerSubagent`.** The `check` inside the update aborts it without writing. `replaced.remove()` only
  changes the phase. The old root's cleanup filters on `root ===`, so it keeps the new entry. The test is deterministic
  (`invokeOnCancellation` plus an `UNDISPATCHED` launch).
- **Regression tests.** The ones in `f684ec3` and `1bb9dcc` use deferreds and `awaitHandled()` barriers, not sleeps.
- **Test runs** in the tip copy, which differs from the tip only by my experimental tests:
  - `ClientSubagentCloseTest`: 13 pass, plus my failing finding 1 test.
  - `ClientSubagentSessionsTest`: 24 pass, plus my failing finding 2 test.
  - `ClientSessionRegistryTest` (10), `ClientSessionOpenedTest` (20), `AgentSubagentsTest` (85) and
    `ClientPermissionCancellationTest` (13): all pass.

## What I could not verify

- Residuals (a) and (b), and gap c, under real parallelism. These are by reading only.
- `./gradlew check`, JS and native targets, and the integration tests. I didn't run them.
