# Can the v1 agent runtime's concurrent code get simpler?

Scope: `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/` without `v2/`, at `e609e49` (clean tree).
Analysis only; nothing was changed and no tests were run for this report.

Paths below are relative to `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/`:
`Agent.kt`, `AgentSubagents.kt`, `internal/RootSessionWrapper.kt` (Root), `internal/SubagentSessionWrapper.kt`
(Sub), `internal/RemoteClientSessionOperations.kt` (Ops). Tests: `acp/src/jvmTest/.../agent/AgentSubagentsTest.kt`
(AST), `AgentTest.kt` (AT).

Sources:
- ACP spec checkout at `927e3186` (pulled for this report): `docs/protocol/v1/prompt-turn.mdx` (`pt:`),
  `docs/protocol/v1/subagents.mdx` (`sub:`).
- Rust SDK: not re-checked. The only Rust fact used comes from `.agents/research/reopen-open-session.md`
  (rust-sdk `05fd3893`): the Rust SDK has no agent-side session registry, so none of the mechanisms below exists
  there; the app owns all of it.
- Design spec `docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md` ("Agent side"), `.agents/state.md`
  "Decisions (final)", `review-m2-agent.md`.

## Summary

The area is close to minimal for the guarantees it gives. Of about 750 lines that carry concurrency (the session
table in `Agent.kt`, Root, Sub), I see **60 to 80 lines** that can go without changing a documented guarantee, and
**3 mechanisms** that can be replaced by a simpler one. Anything larger means dropping a guarantee, and most of
those are user decisions.

Every race in the inventory is real or cheap enough that removing its handling isn't worth it. None is a fantasy.
Three are "marginal" (need a misbehaving client or an in-process-fast peer).

Top recommendations, in order of value:

1. **Cancel latch → one `CompletableDeferred` for the factory's session** (Sub). Removes `factoryRunning`,
   `isCancelLatched`, `latchCancel`, `deliverLatchedCancel`, and the three `synchronized` blocks of `runFactory`.
   About −35 LOC. One visible change: two `session/cancel`s held during the factory are each delivered, as they
   are after the factory returns, instead of once. Touches user decision S2 only in that detail.
2. **Replay window → one atomic** (Root). `replayLock` + `_isReplaying` + the mutable list become one
   `AtomicRef<PersistentList<() -> Unit>?>`, and `Sub.isReplayed` goes because `startAfterReplay` already does the
   right thing for a live child. About −15 LOC, no behaviour change, no test change.
3. **Session table: keep the CAS, make the lambdas pure** (`Agent.kt`). Two `update` lambdas write captured
   `var`s. A small `modify` helper that returns a result removes that. LOC neutral, clearer.
4. **`PromptSession`: optional `Mutex` rewrite** (Root). Counter + `ANSWERING` sentinel + flag + CAS loop become
   one mutex and two plain fields. About −15 LOC; serializes duplicate cancels of one turn. Worth doing only if
   the file is touched anyway.
5. Small trims: inline `discardFailedLoad`, drop the redundant scope check in `reportFactoryFailure`, flatten the
   `announce` catch. About −10 LOC.

Not recommended: a lock for the session table, merging `factoryState` and `eventsState`, rejecting a re-open.

## Inventory

| # | Name | Location | Real? | Spec or invention | Verdict | LOC |
|---|------|----------|-------|-------------------|---------|-----|
| 1 | One turn per session | Root:144-147, 167-170 | yes | invention (v1 is silent on overlapping prompts) | keep, minimal | 0 |
| 2 | Cancel targets the turn present at arrival | Root:174-191 | yes | invention serving `pt:652` | keep | 0 |
| 3 | Cancelled turn answers after every `cancel()` returned (`runningCancels`, `ANSWERING`) | Root:149-154, 194-233 | yes | spec (`pt:652`, `pt:663`), SDK enforces it | keep; optional Mutex rewrite | −15 |
| 4 | Cooperative stop still answers `cancelled` (`isCancelRequested`) | Root:159-166, 201-215 | yes | spec (`pt:652`) | keep | 0 |
| 5 | Turn failure kept inside the turn's job | Root:110-142 | yes | follows from 3 and 4 | keep | 0 |
| 6 | One atomic `SessionTable` (register vs close/replace) | Agent:55-66, 422-429, 491-523 | yes | invention; user decision (PR round 2) | keep; purify lambdas | 0 |
| 7 | Re-open of an open root replaces it and cleans the old subtree | Agent:415-430 | yes (odd client) | invention; user decision (b) | keep | 0 |
| 8 | Root removed by identity (close vs re-open) | Agent:515-523, 494 | marginal | invention | keep, one `===` | 0 |
| 9 | Failed load drops root and registrations (two paths) | Agent:432-447, 464-466 | yes | spec-adjacent (`sub:508`) | keep; inline alias | −6 |
| 10 | Replay window flag + deferred collections (`replayLock`) | Root:58-94 | yes | spec (`sub:508`, `sub:530`) | simplify to one atomic | −8 |
| 11 | `isReplayed` snapshot | Sub:95-99, 169 | no (redundant) | invention | drop | −7 |
| 12 | Window closes in `onResponseQueued`, not `afterResponse` | Agent:448-457 | marginal/yes | spec boundary (`sub:508`) | keep | 0 |
| 13 | History grants never in effect; history state not "active" | Sub:376-384, 392, 237-238 | marginal (grant), yes (state) | spec (`sub:502`, `sub:508`, `sub:515`) | keep, 3 lines | 0 |
| 14 | `Phase` (PENDING/ANNOUNCED/DROPPED/REMOVED) | Sub:49, 61, 123-134, 202-229 | yes | spec (`sub:88`) + invention | keep | 0 |
| 15 | Child lock around sends (no update after removal; announcement first) | Sub:55, 334-347, 373-394 | yes | invention (matters for the Kotlin client) | keep | 0 |
| 16 | Grant recorded before the send, restored on failure | Sub:377-390 | marginal | invention serving `sub:428` | keep "before"; rollback optional | −5 |
| 17 | Cancel latch during the factory | Sub:70-84, 176-197, 282-319 | yes for slow factories | invention; user decision S2 | simplify (deferred) | −35 |
| 18 | Factory failure after the announcement: revoke grant, report `unknown` (`factoryState`) | Sub:74-78, 236-241, 349-362 | yes | spec (`sub:415`, `sub:419`), made the SDK's job by its API | keep; drop one check | −1 |
| 19 | Factory `CancellationException` → `IllegalStateException` | Sub:149-161 | yes | invention; user decision S1 | keep; flatten | −3 |
| 20 | Feed end → `unknown`; app's `CancellationException` is a feed failure | Sub:243-276 | yes | spec (`sub:415`, `sub:420`) | keep, minimal | 0 |
| 21 | Lazy `subtreeScope` | Root:52-56, 99-101 | n/a | invention | keep (or make eager, no gain) | 0 |
| 22 | Requests of a removed child may follow the close response | Ops:198, Sub:123-126 | accepted gap | user decision N1 | keep as is | 0 |
| 23 | `_nesSessions` | Agent:68, 314, 332 | n/a | n/a | keep | 0 |

## Items

### 1-5. The prompt turn and `session/cancel` (Root:103-233)

**Interleavings that break without the handling.**

- *Without 3:* `session/cancel` arrives; the handler calls `agentSession.cancel()` (Root:181), which tells the
  app's turn to stop and then suspends to send a final update. The turn's flow ends, `prompt` answers. The final
  update from `cancel()` then follows the `session/prompt` response. `pt:663`: the agent "MUST ensure that it
  does so before responding". Pinned by AT:1012.
- *Without 4:* same start, but the app's `cancel()` stops the turn cooperatively, so the flow completes normally
  before `promptJob.cancel()` (Root:186) runs. `promptJob.isCancelled` is false, the turn answers `end_turn`.
  `pt:652`: MUST answer `cancelled`. Pinned by AT:1060, AT:1088.
- *Without 2:* the cancel arrives just after turn A answered; `agentSession.cancel()` suspends; prompt B starts;
  `promptJob.cancel()` read from `_activePrompt` after the suspension would kill B. Pinned by AT:1187, AT:753.
- *Without 1:* two overlapping `prompt` flows on one `AgentSession`, and a cancel with no single target.

**Spec or invention.** 3 and 4 are the spec's MUSTs for the agent as a whole; the SDK takes them over because it,
not the app, sends the response. 1 and 2 are SDK guarantees; v1 says nothing about overlapping prompts
(no match for it in `prompt-turn.mdx` or `session-setup.mdx`). They are what makes 3 and 4 enforceable with one
slot, so they pay for themselves.

The agent relies on one Protocol guarantee here: the `session/prompt` handler reaches `_activePrompt.compareAndSet`
(Root:145) before its first suspension (`executeWithSession` and `coroutineScope` don't dispatch), so a
`session/cancel` that follows the prompt on the wire finds the turn.

**Can it be simpler?** Only `PromptSession`. It uses a `MutableStateFlow<Int>` as a counter with an `ANSWERING`
sentinel, a CAS-then-wait loop (Root:224-228) and a separate atomic flag. It is correct and local (35 lines), but
it takes a minute to see why the loop is needed. A `Mutex` says the same thing directly:

```kotlin
private class PromptSession(val promptJob: Job) {
    private val cancels = Mutex()
    private var answering = false          // guarded by cancels
    var isCancelRequested = false          // guarded by cancels; read by the turn after closeForCancels()
        private set

    /** Runs [notifySession] and cancels the turn, unless the turn is already answering. */
    suspend fun cancelWith(notifySession: suspend () -> Unit): Boolean = cancels.withLock {
        if (answering) return false
        isCancelRequested = true
        notifySession()
        promptJob.cancel()
        true
    }

    /** Waits for running cancels; later ones no longer target this turn. */
    suspend fun closeForCancels() = cancels.withLock { answering = true }
}

suspend fun cancel() {
    val turn = _activePrompt.value
    if (turn == null || !turn.cancelWith { agentSession.cancel() }) agentSession.cancel()
}
```

- Pros: one mechanism, no sentinel, no retry loop, about −15 LOC. `Mutex` is FIFO, so cancels queued before the
  turn's `closeForCancels()` still run first, as the counter guarantees today.
- Cons: two `session/cancel`s for one turn call `agentSession.cancel()` one after the other, not concurrently.
  A coroutine `Mutex` is held across app code; no deadlock follows, since the app's `cancel()` can't wait for the
  prompt *response*, only for its own flow, which doesn't need the mutex to finish.
- Tests: AT:1012-1187 should pass unchanged; none sends two concurrent cancels as far as the names show (not run).

Verdict: optional. The current code is fine; I would not open a slice only for this.

Side note, not a simplification: Root:131-138 carries a FIXME (#166) for an app `CancellationException` in the
prompt flow, the same bug class item 19 fixes for factories.

### 6. One atomic `SessionTable` (user decision)

**Interleaving.** An app coroutine on another thread calls `announce` for the first child of root R while the
`session/close` handler for R runs. With two maps, or a flag read in one update and applied in another, the child
registers after `cleanUpSubtree`: it stays in the table for the connection's life, its id is taken, its collection
runs in a scope nobody cancels, and its updates follow the close response. Real: `announce` is called from app
coroutines by design.

**Invention**, and the right one. Keep the single snapshot.

**Simplify the lambdas, not the mechanism.** `createSession` (Agent:420-429) and `removeRoot` (Agent:516-521)
assign captured `var`s inside `update`. That is safe today, because a retry overwrites them and nothing else is
done in the lambda, but it is exactly the pattern question 2 warns about, and the next edit can break it. A helper
makes the result explicit:

```kotlin
private inline fun <R> modifySessions(change: (SessionTable) -> Pair<SessionTable, R>): R {
    while (true) {
        val table = _sessions.value
        val (next, result) = change(table)
        if (_sessions.compareAndSet(table, next)) return result
    }
}

val (replaced, removed) = modifySessions { table ->
    check(sessionWrapper.sessionId !in table.subagents) { ... }
    val replaced = table.roots[sessionWrapper.sessionId]
    val removed = replaced?.let { table.subagentsOf(it) }.orEmpty()
    SessionTable(table.roots.put(sessionWrapper.sessionId, sessionWrapper), table.withoutSubagents(removed)) to
        (replaced to removed)
}
```

LOC neutral. No test change.

### 7-8. Re-open of an open root, removal by identity (user decision b)

- Real only for a client that loads or resumes an id it still has open; v1 neither allows nor forbids it
  (`reopen-open-session.md`).
- Cost in code: the `replaced`/`removed` handling in `createSession` (about 8 lines) and two `===` checks
  (Agent:494, Agent:519).
- Rejecting a re-open would save only those 8 lines. The identity check in `registerSubagent` is needed anyway:
  after close-then-open of the same id, an old child's wrapper still points at the old root.

Keep. Six tests pin it (AST:1978-2258).

### 9. Failed load, two paths

- Path A (Agent:433-438): `replayHistory` throws, no `RequestOutcome` exists yet.
- Path B (Agent:444-447): the response was built but never reached the transport (`mapResponse` threw, or the
  send failed); detected by "still replaying" in `onCompletion`.

Both are real. Without B, a load whose response can't be built leaves a root with its window open for good on a
live connection (review M2, N4; AST:1880). They can't be merged, since A has no outcome to hang a callback on.

`discardFailedLoad` (Agent:461-466) is a one-line alias of `removeRoot`; inline it and keep the comment at the
call sites. −6 LOC.

Observation only: for `session/new`, `resume` and `fork` a response that never reaches the transport leaves the
root registered. That is pre-subagents behaviour and outside this question.

### 10-11. The replay window (Root:58-94, Sub:95-99, Sub:169)

**Interleaving.** `replayHistory` announces a recorded child; its factory returns before the load response. If
the collection of its live `events` started then, live updates would precede the response and the client would
treat them as history (`sub:508`), losing the grant and the state. So the start must wait for the response. Real,
spec-required.

The race `startAfterReplay` vs `endReplay` (one runs in an app coroutine, the other in the coroutine that queued
the response frame) needs an app that launches `announce` during the replay without waiting for it. Marginal, but
a lost collection would be silent, so it must be handled.

**Simplification.** Today: a lock, an atomic boolean written under that lock and read without it, and a mutable
list swapped under the lock. One atomic holds all of it, `null` meaning "closed":

```kotlin
/** The collections waiting for the replay window to close, or `null` once it is closed. */
private val replayed = atomic<PersistentList<() -> Unit>?>(if (replaying) persistentListOf() else null)

val isReplaying: Boolean get() = replayed.value != null

fun startAfterReplay(start: () -> Unit) {
    val before = replayed.getAndUpdate { it?.add(start) }
    if (before == null) start()
}

fun endReplay() {
    replayed.getAndSet(null)?.forEach { it() }
}
```

The lambda is pure (a persistent-list add). Removes `replayLock`, `_isReplaying`, `replayedCollections`.

`Sub.isReplayed` can go with it: Sub:169 becomes `root.startAfterReplay { startCollection(session) }` for every
child. For a child created outside the window `startAfterReplay` runs `start` at once, which is what the `else`
branch does now. `isReplayed` has no other reader.

About −15 LOC together, no behaviour change, no test change expected.

### 12. `onResponseQueued` instead of `afterResponse`

Without it, a grant sent by a child's work between the response frame and the start of `afterResponse` is treated
as history by the agent and as live by the client: the client shows a cancel button that the agent ignores
(review M2, N2; AST:1915). It also lets replayed collections start before a slow `postInitialize()` (AST:1778).
The hook is one lambda here (Agent:453-457). Keep.

### 13. History grants and states

- Grant (Sub:376-384): a conforming client never sends `session/cancel` on a history grant (`sub:502`), so the
  `isLive` test protects only against a misbehaving client. It costs three lines and keeps the agent's view equal
  to the spec's. Keep.
- State (Sub:392, Sub:238): keeps the SDK from sending a correction into history. Keep.

### 14-15. `Phase` and the child's lock

**Interleaving for the lock.** A child's collection on `Dispatchers.Default` passes `checkAnnounced`; the close
handler removes the subtree and the response is queued; the collection then sends its update after the close
response. The spec doesn't forbid that outright (`sub:483`), but the Kotlin client turns an update for an unknown
id into a speculative root holder, which then blocks a later subagent with that id (AGENTS.md, "v1 client"). So it
is a real defect against our own client. Check-and-send must be one step, and the senders are arbitrary app
coroutines, so only a lock does it. The send inside it is serialization plus `trySend`
(`transport/StdioTransport.kt:166-169`), so the lock is never held across a suspension or blocking I/O.

**`Phase`.** DROPPED and REMOVED look alike (both make operations fail) but differ where it matters:
`dropUnlessAnnounced` (Sub:202-215) returns `false` for REMOVED, so an `announce` whose announcement went out
doesn't fail because a close raced it (review M2, N3). Four phases are the minimum.

**Mixed mechanisms.** `_phase`, `_cancelGranted` and `_session` are atomics written only under the lock;
`factoryRunning`, `factoryState`, `isCancelLatched` are plain fields under the lock; `eventsState` is confined to
the collection coroutine. It reads as mixed, but the rule is consistent: "atomic = written under the lock, read
without it". After items 17 and 16 only `_phase` and `_cancelGranted` remain as such atomics, and `factoryState`
as the one plain guarded field. No further change proposed.

### 16. Grant before the send, rollback on failure

- *Before the send:* the cancel must arrive between the transport accepting the frame and the next statement of
  the sender. Over real I/O that can't happen; it happens with in-process transports (AST:1037). Costs nothing:
  it is statement order. Keep.
- *Rollback* (Sub:385-390): a failed send leaves a grant the client never saw. Only a misbehaving client could use
  it, or the connection is already dead. Dropping the `try/catch` saves 5 lines and one AGENTS.md clause. Harmless
  either way; I would drop it only together with item 17.

Rejected alternative: make `cancel()` read the grant under the child's lock, so the order stops mattering. It
blocks the read loop on a lock held during frame serialization and would deadlock AST:1037's blocking transport.

### 17. Cancel latch during the factory (user decision S2)

**Interleaving.** The factory sends the announcement with a cancel grant, then keeps suspending (waits for the
native child to start). The user cancels; `session/cancel` finds no session and is dropped; the client waits for
`idle(cancelled)` that never comes, and a notification can't fail. Real for factories that suspend after granting.
For a factory that announces as its last step the window is too short for any client.

**Invention**, caused by the SDK's own API: the cancel target (`AgentSubagentSession`) doesn't exist until the
factory returns. The spec only says cancel is allowed while the grant stands (`sub:428`, `sub:440`).

**Cost today:** `factoryRunning`, `isCancelLatched`, three `synchronized` blocks in `runFactory` (Sub:176-197),
`latchCancel` (Sub:291-306), `deliverLatchedCancel` (Sub:308-319) with its own launched job, and a second grant
check. About 60 lines.

**Simplification.** Let the notification handler wait for the factory. Each notification handler already runs in
its own coroutine, and "either latched or finds the session" is what a deferred gives for free:

```kotlin
/** The session the factory returned, or `null` if it threw. Not completed while the factory runs. */
private val factorySession = CompletableDeferred<AgentSubagentSession?>()

private suspend fun <S : AgentSubagentSession> runFactory(factory: AgentSubagentFactory<S>): S {
    val session = try {
        executeWithSession { factory.createSubagent(context) }
    } catch (e: Throwable) {
        factorySession.complete(null)
        throw e
    }
    factorySession.complete(session)
    return session
}

suspend fun cancel() {
    // Checked at arrival, before anything suspends: a cancel without a grant is never held.
    if (!isCancelGranted) return logIgnored("cancel is not granted")
    val session = factorySession.await() ?: return logIgnored("it has no session")
    // Revoked or removed while the factory ran.
    if (!isCancelGranted || !isAnnounced) return logIgnored("cancel is no longer granted")
    callCancel(session)
}
```

`factoryRunning` in `sendOwnUpdateLocked` (Sub:392) becomes `!factorySession.isCompleted`; the race this opens
with a concurrent sender at the moment the factory ends exists today too, between Sub:181-185 and the correction.

What stays the same: held while the factory runs with a grant, delivered after it returns and outside
`announce`, dropped if the factory throws or the grant is revoked, never held without a grant or during a replay
(the grant is never in effect then). The deferred is completed outside the child's lock, as AGENTS.md requires.

What changes:
- Two cancels held during the factory are both delivered. Today exactly one is (AST:1108 sends two and asserts
  one). After the factory returns, every `session/cancel` already calls `cancel()`, so "once" is the odd case,
  not the rule. If "once" must stay, one `atomic(false)` CAS before the `await` keeps it, at +3 lines.
- The held cancel runs in the handler's coroutine and dispatcher, like a direct one, not in `subtreeScope`. A
  removed root no longer cancels it, hence the `isAnnounced` check after the wait.
- `val session` (Sub:118, read by AST:675) becomes a completed-or-null read of the deferred.

Tests to adapt: AST:1108 (the "once" assertion), possibly AST:1173/1203 for log-only differences. About −35 LOC,
two guarded flags and one launched job fewer. This is the best single simplification in the area.

### 18. Factory failure after the announcement

**Interleaving.** The factory announces a `running` child with a grant, then throws. Without the correction the
client shows a running, cancellable child for the session's life. The caller can't repair it: the root's `notify`
rejects any `SubagentUpdate` (Ops:49-54) and the id is taken. `sub:415` and `sub:419` require `unknown` and new
`capabilities`. The SDK closed the app's way to do it, so it owes the correction.

Could it be dropped? Only by moving the duty to the app ("catch in your factory and send the correction through
the context before rethrowing"), about −45 LOC and five tests. That is easy to forget and silent when forgotten.
Keep.

`factoryState` can't be merged with `eventsState`: a child that reports state through `context.client` and
returns the default empty `events` would get `unknown` the moment the empty flow completes. AST:1392 pins the
separation.

One trim: `!root.subtreeScope.isActive` in Sub:238 is redundant. A removed child fails `checkRegistered` inside
the send, a dead protocol fails the send, and both land in the `runCatching` two lines below. It also forces the
lazy scope into existence. Drop the clause.

### 19. Factory `CancellationException` (user decision S1)

Real: a `withTimeout` in the factory, or awaiting a cancelled deferred, throws a `CancellationException` into an
active caller, usually the prompt flow, which then answers `cancelled` and loses the failure.

The catch in `announce` (Sub:151-161) encodes a 2x2 matrix through two early throws and a flag. Same behaviour,
flatter:

```kotlin
} catch (e: Throwable) {
    val callerCancelled = e is CancellationException && !currentCoroutineContext().isActive
    val announced = !dropUnlessAnnounced()
    if (announced) reportFactoryFailure()
    throw when {
        callerCancelled -> e
        announced && e !is CancellationException -> e
        announced -> IllegalStateException("Subagent $sessionId is announced, but its factory failed", e)
        else -> IllegalStateException("Subagent $sessionId is not announced: its factory failed", e)
    }
}
```

A further option, a behaviour change I don't push: always wrap a non-cancellation failure, announced or not. It
removes the "rethrows unchanged" row from the `announce` KDoc matrix (`AgentSubagents.kt:193-198`).

### 20-23

- 20 (feed end): the `isActive` test at Sub:251-253 is the smallest way to tell "the SDK cancelled the collection"
  from "the app's flow threw a cancellation". Keep.
- 21 (lazy scope): `cancelSubtreeScope()` creates the scope to cancel it, so laziness helps only sessions that are
  never closed. Not worth changing.
- 22: accepted by the user (N1); closing it would need the lock around requests, which suspend. Leave.
- 23: trivial pure updates.

## CAS vs locks

Primitives available in `commonMain` today: atomicfu 0.25.0 (`gradle/libs.versions.toml:13`) with `atomic`,
`SynchronizedObject`/`synchronized` (already used at Root:62, Sub:55), and `kotlinx.coroutines.sync.Mutex`
(coroutines 1.9.0). `MutableStateFlow.update/getAndUpdate` is a CAS loop too.

| Site | Guards | Lambda pure and cheap? | Contention realistic? | Recommendation |
|------|--------|------------------------|-----------------------|----------------|
| Agent:422 `_sessions.update` (open/replace) | root put + old subtree removal | writes captured `replaced`/`removed` (idempotent), may throw; `subagentsOf` is O(children) | low: one per session open | keep CAS, restructure to return the result (item 6) |
| Agent:493 `registerSubagent` | root-alive and id-unique checks + put | pure, may throw, O(log n) | yes with many children announced in parallel, but a retry costs one map put | keep CAS |
| Agent:506 `unregisterSubagent` | identity-checked remove | pure | low | keep CAS |
| Agent:517 `removeRoot` | root remove + subtree removal | writes captured `removed` (idempotent); O(children) | low | keep CAS, restructure with 422 |
| Agent:314, 332 `_nesSessions.update` | NES map | pure | no | keep CAS |
| Agent:256, 469, 478, 481 reads of `_sessions.value` | lookups in handlers | n/a | n/a | the reason not to use a lock: handlers read without one |
| Root:145 `_activePrompt.compareAndSet(null, …)` | the single turn slot | no lambda | no | keep CAS; the ideal use |
| Root:169 `_activePrompt.value = null` | frees the slot | n/a | n/a | keep |
| Root:179 read of `_activePrompt` | the turn a cancel targets | n/a | n/a | keep |
| Root:212 `runningCancels.getAndUpdate` | cancel count unless answering | pure | no | keep, or Mutex (item 3) |
| Root:213 `_isCancelRequested` | flag set after 212 | n/a; set after the count, read only after `awaitCancels`, so the gap is covered | no | keep, or fold into the Mutex |
| Root:218 `runningCancels.update` | decrement | pure | no | keep |
| Root:225 `compareAndSet(0, ANSWERING)` loop | close the turn for cancels | no lambda; loop with a suspending wait | no | keep, or Mutex; the one CAS site that is harder to read than a lock |
| Root:62-94 `replayLock` + `_isReplaying` | window flag + deferred starts | lock; callbacks run outside it | marginal | restructure into one atomic (item 10); here CAS is simpler than the lock |
| Root:56 `by lazy` | scope creation | synchronized by `lazy` | no | keep |
| Sub:55 `lock` at 334-347, 349-367, 373-394 | check-and-send, grant, phase | holds serialization + `trySend`, no app code, no suspension | yes: several senders per child | keep the lock; CAS can't cover a send |
| Sub:177, 181-186, 190-194 `synchronized` in `runFactory` | `factoryRunning`, latch, `_session` | plain writes | no | remove with item 17 |
| Sub:203-212, 222-228 `synchronized` (drop/remove) | phase transition vs sends | plain writes; `unregisterSubagent` correctly outside | low | keep |
| Sub:296-300 `latchCancel` | latch vs factory end | has a non-local `return` out of `synchronized` | low | remove with item 17 |
| Sub:61 `_phase`, Sub:67 `_cancelGranted`, Sub:91 `_session` | lock-free reads of lock-written state | no CAS used, plain `.value` | n/a | keep as "volatile" fields; `_session` becomes the deferred |

Overall:

- **CAS is the right tool wherever it is used here.** Every lambda is a persistent-map or integer operation with
  no side effect beyond two idempotent captured writes, and the write rate is per session or per announce, not per
  message. A retry wastes a map put. A lock around the session table would force either locking every handler's
  lookup or keeping the immutable snapshot anyway, so it buys nothing.
- **The lock is the right tool where it is used.** The child's lock exists because a send must be atomic with a
  state check, which a CAS can't express. It obeys the constraints: never suspends, never nested, no app code, no
  deferred completed under it (true today, and still true after item 17).
- **Two places use the wrong one of the two, mildly:** the replay window uses a lock for what is one atomic value
  (item 10), and `PromptSession` builds a suspending lock out of a CAS loop over a `StateFlow` (item 3), where a
  `Mutex` is the direct form because it may be held across the app's suspending `cancel()`.
- Rule worth writing down if the helper from item 6 is adopted: a CAS lambda returns its results; it never assigns
  captured variables.

## Not verified

- No test was run. "No test change expected" is from reading test names and the lines cited.
- Rust SDK behaviour was taken from an earlier report, not re-checked.
- `acp-integration-test` was not read for pinned agent behaviour.
