# Review: `d49de38..de68045`, close cancels subagent openings without joining them

Scope: one commit, `de68045` "refactor(client): cancel subagent openings on close without joining them".

Verdict: the `Finished` claim and `removeSessionTree`'s decision are correct for every interleaving I traced. I found
no blocker and no should-fix. There are three nits: one race the change widens, one inaccurate KDoc sentence, and one
test that can flake in theory.

## What I ran

- `./gradlew :acp:jvmTest --tests 'com.agentclientprotocol.client.*'` on `de68045`: green.
- `ClientSubagentCloseTest` with `--rerun`, 8 runs in a row: all green.
- A scratch copy of `de68045` with the claim removed from `completeOpening` (the two lines before
  `_session.complete(session)`), then `ClientSessionRegistryTest`: exactly one failure, the new race test, at
  `"a stopped opening doesn't complete its holder"`. So the test fails for the original reason.
- A scratch copy with the old main sources (`d49de38` versions of `ClientSessionRegistry.kt`,
  `SubagentSessionHolder.kt` and `ClientSession.kt`) plus the new `ClientSubagentCloseTest`: 4 of 23 tests time out.
  - "two roots that close each other …", which is the deadlock;
  - "an announcement racing the close …";
  - "close fails the subagents that have not finished opening …";
  - "a subagent's opening stopped by the close …".

  So the adapted tests and the new cross-close test check the new behavior, not the old.
- Read the kotlinx.coroutines 1.9.0 sources for `withContext`, `UndispatchedCoroutine` (JVM, native, js/wasm) and
  `ScopeCoroutine`.
- I removed the scratch copies afterwards. Nothing in the product tree was edited.

## Findings (most severe first)

### 1. nit (plausible, by reading): a cancelled opening that turns the cancellation into another exception now races the close's failure of the same holder

- **Where:** `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/SubagentSessionHolder.kt:243-248` (the
  `catch (t: Throwable)` that calls `failSessionTree(this, …)`). The same applies to
  `ClientSessionHolder.kt:401-410` (`failSetup` after a non-cancellation throw from `createSubagentOperations`) against
  `ClientSessionRegistry.kt:181-191`.
- **Defect:** the close no longer waits for the cancelled opening. A `notify` before `Opened` (or a factory) that
  catches the cancellation and throws something else, such as `IllegalStateException`, sends the opening into its
  own `failSessionTree`. That call now runs at the same time as the close's step 4 `failSessionTree(holder, "closed")`.
- **Failure scenario:**
  1. A subagent's `notify` is suspended on an update before its marker. The app wraps cancellation:
     `catch (e: CancellationException) { throw IllegalStateException(e) }`.
  2. The root's close is answered. `removeSessionTree` cancels the opening and goes on to step 4 without waiting.
  3. On another thread, the opening's `catch (t: Throwable)` calls `failSessionTree` with "Failed to deliver the
     history of subagent session X".

  Two things follow:
  - Which error wins is nondeterministic. A request waiting in `awaitSession` may get "Failed to deliver the history …"
    instead of "Session X closed". The brief and the spec say a closed, unfinished subagent fails with "closed".
  - Both calls run `failAndDrainQueue` on the same queue. Two consumers then call `tryReceive` and the suspending
    `GlobalSessionUpdateHandler`, so that subagent's queued updates can reach the global handler out of order.

  Before the change, the join put the opening's failure first, so the outcome was deterministic. The failure cascade
  had the same race already. This change extends it to the close.
- **Suggested fix:** in `completeOpening`'s `catch (t: Throwable)`, and in `setUpSubagent`'s non-cancellation catch,
  don't fail the holder when the opening has been stopped. Check `!currentCoroutineContext().isActive`, or check that
  the opening state isn't `Running`, and return. The stop's caller already fails the holder. A registry unit test can
  cover it: stop and cancel an opening whose `notify` rethrows as `IllegalStateException`, then assert the "closed"
  message.

### 2. nit (confirmed, by reading): the step 2 KDoc of `removeSessionTree` says no opening completes its holder after the stop

- **Where:** `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionRegistry.kt:128`
- **Defect:** "Every subagent's opening is stopped, so none starts or completes its holder afterwards" is false for an
  opening the stop finds `Finished`. That opening completes its holder right after the stop, as the
  `markOpeningStopped` KDoc and step 4 of the same KDoc say.
- **Failure scenario:** a maintainer who trusts step 2 could add a later `isCompleted` read, or assert that a
  `Finished` holder is still incomplete. That brings back the exact race the claim exists to close.
- **Suggested fix:** "…so none starts, and none that hasn't claimed its completion (`Finished`) completes its holder
  afterwards…".

### 3. nit (plausible, by reading; 8 of 8 runs green): the cross-close test answers the first close before it knows the second was sent

- **Where:** `acp/src/jvmTest/kotlin/com/agentclientprotocol/client/ClientSubagentCloseTest.kt:572`
  (`repeat(2) { c.agent.answer(c.agent.receiveRequest(CLOSE.methodName), …) }`)
- **Defect:** the test answers the first `session/close` as soon as it arrives. Nothing makes sure the other child's
  `notify(Opened)` has already sent its own close.
- **Failure scenario:**
  1. `child`'s close of `other-root` arrives and is answered.
  2. The client handles that response before `other-child`'s opening reaches `notify`.
  3. The removal of `other-root` stops `other-child`'s opening, so `other-child` never sends its close of `root`.
  4. The second `receiveRequest` times out after 5 s.

  This is a spurious failure, not a false pass. In the same interleaving, the test also stops showing the mutual wait.
- **Suggested fix:** receive both close requests first, then answer both:
  `val requests = List(2) { c.agent.receiveRequest(CLOSE.methodName) }`. Both closes are then in flight before
  either removal runs. That is the deadlock setup, and it makes the test deterministic.

## Checks with no finding

### `Finished` claim and `removeSessionTree` decision

- The opening's read of `Running` plus its compare-and-set to `Finished`, against `markOpeningStopped`'s
  `getAndSet(Stopped)`, picks exactly one winner. Only `startOpening` writes `Running`.
- Close against each point of `completeOpening`:

  | Where the opening is when the close stops it | Outcome |
  |---|---|
  | Suspended in `receiveCatching` | Cancelled. The close fails the holder "closed". |
  | In `notify` before the marker, or in `notify(Opened)` | Cancelled. The next `ensureActive` throws. The holder fails "closed". Only finding 1's exception-conversion case differs. |
  | In `parent.session.join()` | Cancelled. The holder fails "closed". |
  | Returning on `parent.isFailed` | Returns without completing. The close fails the holder. |
  | In the live loop | Cancelled. The next `ensureActive` throws. Remaining items are drained to the global handler. |
  | Past its last check, before the claim | The claim fails and the opening returns. The stop found `Running`, so the close fails the holder. The new unit test shows this with a stop without cancellation. |
  | After the claim, before `complete` | The stop finds `Finished`: the holder is marked closed, not failed, and completes right after. |

- **Two closes in flight:** the second stop finds `Stopped` and reads `isCompleted`.
  - Usually the result is the same.
  - On a multi-threaded dispatcher, one window differs: the first stop found `Finished` and the opening hasn't run
    `complete` yet. The second close then fails that holder "closed", so the first close's "opened" decision loses.
    The holder still ends in one of the two allowed states, failed "closed" or completed and closed.
  - Requests waiting in `awaitSession` and items waiting in `lateDeliveries` follow the deferred, so they stay
    consistent. I didn't count this as a defect.
- **Close against a setup still running:** the stop finds `NotStarted` and the close fails the holder "closed".
  `startOpening` later fails its compare-and-set, cancels the lazy job and marks the session closed. A sealed parent
  drops the session in `listSubagent`.
- **Close against the failure cascade:** whichever completes the deferred first sets the reason. A cascade's stop
  that finds `Finished` is ignored, which is correct. The spec's argument holds: an opening reaches the claim only
  after its parent completed, and a completed holder never fails, because the reaping and `replacing` take only
  incomplete or failed roots and a close doesn't fail a `Finished` subagent. A `Finished` grandchild under a
  `Running` parent can't exist, because the grandchild's claim comes after `parent.session.join()`.

### Final states, waiters and late updates

- Every subagent ends failed with "Session … closed" or completed and closed, apart from finding 1 and the two-close
  window above.
- `awaitSession` waiters get the error. `lateDeliveries` items take the `_session.join()` path and go to the global
  handler for a failed holder, or to the closed session for a `Finished` one. That matches the KDoc ("an update that
  arrived before the response and waits behind such a call, can still run after this function returns").

### Caller cancellation

- Steps 1 and 2 don't suspend: `sealChildren`, `markOpeningStopped` and `Job.cancel` are all non-suspending.
- `withContext(NonCancellable)` checks only the new context on entry, and `NonCancellable` is always active.
- `removeClosedSession`'s loop after the removal (`ElicitationSessionStore.removeBySession`) doesn't suspend.

### Self-close from a subagent's own `notify`

The author's claim is right.

- `withContext` on the same dispatcher takes `UndispatchedCoroutine`. If the block returns without suspending,
  `undispatchedResult` returns the state without checking the outer job.
- If the block suspends, for example in the global handler, `UndispatchedCoroutine.afterResume` on JVM, and
  `ScopeCoroutine.afterResume` on native and js/wasm, call `uCont.resumeWith` directly. There is no
  `resumeCancellableWith`, so the cancelled outer job isn't checked.
- `ClientSessionImpl.close` returns `response` right after `onClosed()`.
- The tests "a close that a subagent's own notify starts finishes" and the `NonCancellable`/`Job()`/other-scope
  variants pass.

### Ordering change

- The cancellation now happens before the map removal, with no suspension in between.
- An update that arrives in that gap is queued in the still-open queue and drained to the global handler by step 4.
  That is the same as during the old join window, only shorter.
- An old cancelled opening that still runs after a reload touches only its old holder: `listSubagent` on a sealed
  parent drops the session, and patches don't apply to a closed session. Nothing depended on the old order.

### Tests

- The race test fails without the guard, as confirmed above.
- It bypasses the removal's own stop: it calls `markOpeningStopped` itself, so `removeSessionTree` takes the
  `Stopped → isCompleted` branch. The `Running` and in-flight `Finished` branches can't be reached
  deterministically, because a cancelled opening throws at its next `ensureActive`. That gap is acceptable.
- Dropping `treeRegisteredAtCancel` is justified. The cancellation now comes before the removal and runs the
  `notify`'s catch on another thread, so that value is nondeterministic. The test still shows that the close doesn't
  wait, because `releaseNotify` completes only after `closed.await()`.
- The other adapted tests release the held `notify` only after the close returned. They fail with the old code.

### Docs

- `ClientSession.close`, `removeSessionTree` (apart from finding 2), `markOpeningStopped`, `completeOpening`,
  `AGENTS.md` and the spec match the code and follow the KDoc and comment rules: no single-line KDoc, and no stacked
  `//` lines.
- I found no leftover `SubagentOpening`, `runsIn`, `isSelfOrAncestorOf`, `ownOpenings` or "wait for itself" in
  `acp/src`, `AGENTS.md` or the spec.
- The remaining "join" hits are about the permission race or explicitly say "without joining".
- No `.agents/` references.

### API surface

- No `.api` file changed in the range.
- `OpeningState` is nested in `internal class SubagentSessionHolder`, so it stays internal and is in the `internal`
  package.

## Follow-ups (not caused by this commit)

- If the caller of `close()` is cancelled while it awaits the `session/close` response, `onClosed()` is skipped even
  though the agent may have closed the session. An example is a subagent's `notify` closing another root without
  `NonCancellable`, when that root's opening gets cancelled. The client tree then stays registered. This was the same
  before.
- "Nothing that arrives after the response reaches the subagents" (`ClientSession.close` KDoc) isn't strict for a
  subagent that has finished opening. An update handled by the read loop between the close response and the caller's
  `onClosed()` is delivered synchronously to its `notify`. This was the same before, and an agent shouldn't send one.
