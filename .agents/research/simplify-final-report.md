# Concurrency simplification of the v1 runtimes and `Protocol`: final report

Branch `eugenethedev/subagents`, tip `0ed3f48`, clean tree; product code identical to `e609e49`. Analysis only:
nothing was changed, nothing was run, nothing is committed.

Inputs: the four Part 1 reports (`simplify-agent.md`, `simplify-client-registry.md`, `simplify-client-sessions.md`,
`simplify-protocol.md`), the two Part 2 cross-checks (`simplify-vs-readability-client.md`,
`simplify-vs-readability-rest.md`), `readability-suggestions.md`, `AGENTS.md`, and `.agents/state.md`
"Decisions (final)". Every suggestion kept below was checked against the code by reading it again. Spec lines were
re-read in the local checkout at `927e3186` where a verdict depends on them.

Abbreviations for paths under `acp/src/commonMain/kotlin/com/agentclientprotocol/`:

| Short | File |
|---|---|
| `Registry` | `client/internal/ClientSessionRegistry.kt` |
| `Holder` | `client/internal/ClientSessionHolder.kt` |
| `RootHolder` | `client/internal/RootSessionHolder.kt` |
| `SubHolder` | `client/internal/SubagentSessionHolder.kt` |
| `Impl` | `client/internal/ClientSessionImpl.kt` |
| `PP` | `client/internal/PendingPermissions.kt` |
| `Client` | `client/Client.kt` |
| `Agent` | `agent/Agent.kt` |
| `RootWrapper` | `agent/internal/RootSessionWrapper.kt` |
| `SubWrapper` | `agent/internal/SubagentSessionWrapper.kt` |
| `Protocol` | `protocol/Protocol.kt` |

## 1. Overview

### How much simpler can it get

The code is close to minimal for the guarantees it gives. No race in the four inventories is imaginary. What can go
is state that exists because of two loose ends (a failed root that lingers in the client's map, three copies of the
opening flow), a few mechanisms that duplicate a guarantee given elsewhere, and impure CAS lambdas.

About 3 680 lines take part in concurrency (agent 750, `Protocol` 738, client registry and holders 1 294, client
sessions, delivery and permissions about 900).

| Area | Lines now | Removable, no user decision | More, if the user agrees | Mechanisms dropped (no decision / with decision) |
|---|---|---|---|---|
| Agent (`Agent`, `RootWrapper`, `SubWrapper`) | 750 | 32 to 40 | 30 to 35 (cancel latch) | 3 / 1 |
| `Protocol` | 738 | 46 to 51 | about 150 (batches, outside this PR) | 3 / 1 |
| Client registry and holders | 1 294 | about 43 | 58 to 78 (live delivery, one tree) | 3 / 2 |
| Client sessions, delivery, permissions | about 900 | 73 to 93 | 25 (post-cancel window) | 4 / 1 |
| **Total** | **about 3 680** | **195 to 225 (5 to 6%)** | **113 to 138 inside this PR** | **13 / 5** |

All line counts are estimates from reading; none was measured on a patch.

Mechanisms that go without a user decision (13):
- client: `blocksId`, `replacing`/`removeDescendantsOf` in the lookup, the reap's second map update, two of the
  three opening flows with three of the four fail sites, the root/subagent branch of the permission handler, the
  `isClosedForSend`-then-`send` check, the `ElicitationSessionStore` counter with `Entry` and the sort;
- agent: `replayLock` with its flag and list (one atomic instead), `isReplayed`, and optionally the
  counter-with-sentinel CAS loop of `PromptSession`;
- `Protocol`: the hook strip on cancel, the `IncomingRequest` flag, the `additionalContext` wrapper with its own
  cleanup.

Also, ten CAS lambdas that write captured variables or have a side effect become pure.

Mechanisms that go only with a user decision (5): the agent's cancel latch, the client's `lateDeliveries` chain
with its fast path, the tree kept in two places, the subagent post-cancel window, and batch support.

### How many suggestions

21 numbered suggestions:

| Kind | Count | Numbers |
|---|---|---|
| Defect fix | 5 | 1, 2, 3, 4, 5 |
| Simplification | 13 | 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20 |
| CAS / lock | 2 | 7, 17 |
| Readability (one heading, 20 items that hold) | 1 | 21 |

User decisions:
- Four behaviour decisions inside this PR: 3 (sequential live `notify`), 12 (S2: a held cancel delivered once or
  each time), 18 (R17 D1: the tree in one place), 19 (the final post-cancel window decision).
- One outside this PR: 20 (batch support, public API on `master`).
- Two confirmations with no observable change: 10 (M4 answer 1 says "Roots unchanged"), 11 (R15 kept the name
  `isReplayed`).
- Four inside suggestion 21: Reg 4 wording, Reg 14 behaviour, Subagent 1 and Subagent 4 names (R19 item 2).

### CAS versus locks

No CAS site has to become a lock. Contention is low everywhere (session lifecycle events against one read loop),
and every retried lambda is a persistent-map or integer step. Ten lambdas are impure today, all benign: they write
captured variables, and three have a repeatable side effect (`Registry:75` allocates a holder with its channel,
`Protocol:318` cancels deferreds, `ElicitationSessionStore.kt:29` moves a counter). The fix is purity, not a lock
(suggestions 7 and 17).

The existing locks are the right tool: the child's lock in `SubWrapper` makes a check and a send one step, which a
CAS cannot, and the negotiation `Mutex` is held across a suspension. Three sites use the wrong one of the two:
`replayLock` guards what is one atomic value (11), `PromptSession` builds a suspending lock from a CAS loop where a
`Mutex` is the direct form (13, optional), and the `lateDeliveries` chain is a hand-made fair mutex (3, a user
decision).

### Recommended order

1. Defects that need no decision: 1, 4.
2. Low-risk refactors, each its own slice: 6, then 7 (its `registerChild` and lookup parts need 6 first), 8, 11,
   14, 15, 16, 17.
3. Larger refactors: 10, then 9 (needs 8; take the Reg 9, 12, 13 renames with it), then 13 if wanted.
4. After the user's answers: 3 with 2 (2 has a one-line form either way), 12, 19, and 18 last.
5. Outside this PR: 5 (v2 conformance), 20 (batches).
6. Readability items of 21 that are not merged into a slice above: any time, one commit per file.

## 2. Suggestions

Each suggestion has: a short description, a summary of the change, then the detail. "Tag line" fields: behaviour
change, public API change, user decision touched, risk, estimated lines removed, mechanisms or scenarios removed,
tests affected, dependencies.

### 1. Route turn updates with `trySend`, and clear the turn by identity

An update that arrives while a turn ends is lost, and a turn's cleanup can unregister the next turn. Both come from
check-then-act on `activePrompt`. Both exist on `master`.

**Change:** replace `isClosedForSend` + `send` with one `trySend`, and replace the two `getAndSet(null)` with
`compareAndSet(promptSession, null)` plus closing the turn's own channel.

Behaviour change: yes, a bug fix (the update goes to `notify` instead of being lost) · API: no · decision: none ·
risk: low · lines: −5 · removes: one check-then-act and a `DelicateCoroutinesApi` opt-in · tests: one new
regression test for each defect · depends on: nothing.

**Verified.** Lost update: **confirmed by reading**, should-fix. Unregistered later prompt: **confirmed by
reading**, nit (needs an app that starts a turn before it saw the previous `PromptResponseEvent`).

Lost update, `Impl:221-231` against `Impl:98`:

1. The agent answers `session/prompt` and sends one more update (legal: `session_info_update`, a subagent's
   `subagent_update` on the root's stream).
2. The read loop completes the prompt's deferred; the collector resumes on its own thread.
3. The update's handler reads `activePrompt.value` and sees `isClosedForSend == false` (`Impl:224`).
4. The collector runs `activePrompt.getAndSet(null)?.updateChannel?.close()` (`Impl:98`).
5. The handler's `send` (`Impl:226`) throws `ClosedSendChannelException`, which `Protocol.handleNotification` logs
   (`Protocol:610-618`). The update reaches neither the flow nor `notify`.

Before (`Impl:221-231`):

```kotlin
val promptSession = activePrompt.value
@OptIn(DelicateCoroutinesApi::class)
if (promptSession != null && !promptSession.updateChannel.isClosedForSend) {
    promptSession.updateChannel.send(notification)
} else {
    operations.notify(notification, _meta)
}
```

After:

```kotlin
// The channel is unlimited, so this fails only once the turn has closed it.
val delivered = activePrompt.value?.updateChannel?.trySend(notification)?.isSuccess == true
if (!delivered) operations.notify(notification, _meta)
```

Cleanup, before (`Impl:98` and `Impl:105`): `activePrompt.getAndSet(null)?.updateChannel?.close()`. Between `:98`
and `:105` the coroutine suspends in `join` and `send`; a `prompt()` that registers there has its channel closed by
the first turn's `finally`. After, at both places:

```kotlin
activePrompt.compareAndSet(promptSession, null)
promptSession.updateChannel.close()
```

**Spec or invention:** the `Flow<Event>` API and its "one turn at a time" are the SDK's. Keep both; only the
check-then-act goes.

### 2. Let the turn wait for the deliveries chained before its response

A slow `createSubagentOperations` during a turn lets `PromptResponseEvent` overtake the turn's last updates.

**Change:** before `prompt()` closes its channel, it waits for the late deliveries that were queued when the
response arrived.

Behaviour change: yes, a bug fix · API: no · decision: none, but its form follows suggestion 3 · risk: low ·
lines: +3 · tests: one new regression test · depends on: 3 for the form only.

**Verified: confirmed by reading**, should-fix, narrow (needs an app factory that suspends during a turn).

1. During a turn the agent announces a subagent. An announcement never takes the fast path (`Holder:357`), so its
   handler takes the chain and suspends in the app's `createSubagentOperations` (`Holder:310`).
2. More root updates arrive. `lateDeliveries.value` is not completed, so they wait in the chain (`Holder:367-371`).
3. The agent answers `session/prompt`. The read loop does not wait for suspended handlers.
4. `prompt()` closes the channel, joins the drain, emits `PromptResponseEvent` (`Impl:98-103`).
5. The factory returns. The announcement and the updates behind it find no open channel and go to `notify`, after
   the turn's flow has ended.

Nothing is lost, but the comment at `Impl:94-96` and every collector assume the turn's updates precede the
response event. The notification barrier (`Protocol:472-477`) guarantees that every update that preceded the
response on the wire has reached its first suspension, so it is already in the chain when `prompt()` resumes.

With today's chain:

```kotlin
// ClientSessionHolder
suspend fun awaitLateDeliveries() = lateDeliveries.value.join()

// ClientSessionImpl.prompt, before closing the channel
holder.awaitLateDeliveries()
```

With the `Mutex` of suggestion 3: `suspend fun awaitLateDeliveries() = liveDelivery.withLock {}`.

Not fixed by this, and already recorded in `state.md`: an update that arrives after the response goes to `notify`
while the flow still drains.

### 3. Live delivery: one fair `Mutex` instead of the chain and the fast path (user decision)

After the opening, the SDK guarantees only that `notify` calls start in arrival order. A `notify` that suspends is
overtaken by the next update of the same session. Part 1 disagreed about whether that is a defect.

**Change (option A, recommended):** replace `lateDeliveries` and the fast path with a `Mutex`, so the live
updates of one session are delivered one at a time. **Option B:** keep the code and document "starts in order, may
overlap" in the public KDoc.

Behaviour change: A yes, B no · API: no signature change; the KDoc contract of `notify` changes either way ·
decision: new, the user's · risk: A medium (re-entrancy, see below), B none · lines: A −18, B 0 · removes (A): the
chain, the fast path, the announcement special case · tests: A adds one test for the overlap;
`ClientSessionOpenedTest` "updates that miss the closed queue wait for the updates queued after the marker" should
pass unchanged · depends on: nothing; Holder 4 of the readability list is superseded by A.

**Verified.** The overlap is **confirmed by reading**. It is not a regression: `master` has the same fast path
(`master:…/client/ClientSessionHolder.kt:163`). Whether it is a defect depends on the contract, which is why this
is a decision.

1. Update A arrives; the session is open; the fast path calls `deliverToSession` (`Holder:357-359`), and the app's
   `notify(A)` suspends, for example in `withContext(Dispatchers.IO)`.
2. Update B arrives. The fast path did not register in `lateDeliveries`, so `lateDeliveries.value.isCompleted` is
   still `true`, and `notify(B)` starts while `notify(A)` is suspended.

A root's turn is not affected, because the prompt channel never suspends. A subagent is: all of its content goes
through `notify` (`BaseClientSessionRuntime.kt:35`), so its message chunks can reorder in an app whose `notify`
switches to a multi-threaded dispatcher.

Evidence for A:
- The spec orders updates per session (v2 `transports.mdx:61-63`; Part 1 cites v1 `prompt-turn.mdx:483` and
  `subagents.mdx:210`, not re-read).
- The SDK is already sequential before the answer (`drainHistory`, `deliverEarly`), behind an announcement
  (`Holder:353-356`) and during the opening window. Only the open, uncontended case overlaps.
- `Client.loadSession` promises "exactly once and in arrival order, before and after the answer" (`Client:410`).
- The Rust SDK holds its dispatch loop until the callback returns (Part 1, `concepts/ordering.rs:13-15`, not
  re-checked).

Evidence against A:
- It changes a `master` behaviour. A `notify` that hangs then stalls the later updates of its session.
- Re-entrancy, which neither Part 1 report mentions: an app that calls `session.prompt()` inside `notify` and
  collects the flow there holds the lock, so the turn's updates cannot reach the channel. With suggestion 2 the
  turn then waits for the lock that its own caller holds, and deadlocks. Today that pattern works. The KDoc must
  forbid it ("`notify` must not wait for a later update of its own session"), as `Client:412-413` already does for
  the opening.

Before (`Holder:350-376`): `trySend`, then the fast path, then `getAndSet` on `lateDeliveries`, `previous.join()`,
`_session.join()`, `deliverToSession`, `delivered.complete(Unit)` in `finally`.

After (A):

```kotlin
private val liveDelivery = Mutex()

suspend fun handleOrQueue(item: QueuedItem): Boolean {
    if (queue.trySend(item).isSuccess) return true
    // Fair, and handlers reach it in arrival order, so the updates that missed the closed queue keep that order.
    return liveDelivery.withLock {
        _session.join()
        deliverToSession(item)
    }
}
```

Why it keeps the order: handlers start in arrival order on one thread and run to their first suspension
(`Protocol:70`, `:472-477`); `Mutex.lock` is first-in first-out and hands the lock to the first waiter, and an
uncontended `lock()` does not suspend. `Mutex` is not on the arrival path that must not suspend: the sorting in
`Registry.queuedItemFor` runs before `handleOrQueue`.

**Spec or invention:** per-session order is the spec's; the delivery mechanism is ours. The `AGENTS.md` bullet
"Late updates keep their order through the `lateDeliveries` chain" is reworded with A.

Rejected variant: one long-lived consumer coroutine per holder with a queue that is never closed. It moves `notify`
off the caller's coroutine during the opening, against user decision (a) of 2026-11-15, and needs an end-of-turn
marker.

### 4. Two cancellation nits in the client

`elicitation/complete` swallows `CancellationException`, and `ClientNegotiator` caches one.

**Change:** one non-suspending registry lookup for `elicitation/complete`; wrap a cached cancellation.

Behaviour change: error reporting only · API: no · decision: none · risk: low · lines: −4 · tests: one for the
negotiator · depends on: nothing. Both are on `master`.

**Verified: both confirmed by reading**, nits.

- `Client:187-189`: `sessions.isSessionCompleted(sessionId)` and then
  `runCatching { sessions.awaitSession(sessionId) }.getOrNull()`. `AGENTS.md` asks to rethrow cancellation. The
  second lookup can also find another, incomplete holder and suspend. One `fun completedSessionOrNull(sessionId):
  BaseClientSession?` in the registry replaces both lookups and the `runCatching`.
- `ClientNegotiator.kt:116-121`: `catch (cause: Throwable) { state = State.Failed(cause); throw cause }`. If the
  first `negotiate()` caller is cancelled, later callers get that `CancellationException` at `:112` in an active
  coroutine, which structured concurrency treats as a silent cancellation. Store
  `IllegalStateException("Negotiation was cancelled", cause)` when `cause` is a `CancellationException`.

### 5. A v2 connection accepts a JSON-RPC batch (outside this PR)

Nothing checks the negotiated version for framing, so a v2 connection answers a batch that the spec says to reject.

**Change:** in `handleIncomingFrame`, answer a `TransportFrame.Batch` on a v2 connection with one `-32600`,
`id: null`.

Behaviour change: yes, conformance · API: no · decision: scope ("Do not change v2 runtimes" in this PR) · risk:
low · tests: one new · depends on: nothing; moot for v1.

**Verified: confirmed by reading**, should-fix. `negotiatedProtocolVersion` has one reader,
`client/v2/Client.kt:158`. `Protocol.handleIncomingFrame` (`Protocol:449-453`) treats `Batch` the same for every
version. The spec: "ACP does not use JSON-RPC 2.0 batches. Senders MUST NOT send a JSON array of messages, and
receivers reject one with a single `-32600` (Invalid request) response with `id: null`" (v2 `transports.mdx:67`,
re-read at `927e3186`).

### 6. Remove a failed root's tree from the client's map when it fails

A failed root stays in the session map until the last opening ends. Every reader therefore asks whether an entry
still owns its id.

**Change:** `failSessionTree` removes a root's tree from the map after its cascade. Then "in the map" means "owns
the id", and `blocksId`, `replacing` and the reap's second update go.

Behaviour change: two corners, below · API: no · decision: none · risk: low to medium · lines: −30 · removes:
`blocksId` and its two overrides, six reads of it, `replacing`, the second reap update, the `isFailed` clause of
the reap · tests: `ClientSessionRegistryTest` "a failed subagent keeps its id while its parent lives…" (the
`blocksId` assertions at `:159-160`) and the two "leave the session map" tests change; `ClientEarlySessionUpdatesTest`
"a retry after a failed load…" is the regression test and should pass unchanged (not re-read) · depends on:
nothing; 7 depends on it.

**Verified.** The registry reads `blocksId` six times (`Registry:60, 67, 94, 101, 165, 244`), not seven as Part 1
says; four are `takeIf`. Two of them are inside CAS lambdas (`:67`, `:244`) and read other holders' deferreds.

Is the lingering needed? No. A failed root answers every update with "unconnected" (`Holder:380`), which is what a
missing entry gives (`Registry:190`). Its only user is its own replacement.

After:

```kotlin
suspend fun failSessionTree(holder: ClientSessionHolder, cause: Throwable): Boolean = withContext(NonCancellable) {
    if (holder is SubagentSessionHolder && !stopOpening(holder)) return@withContext false
    failTree(holder, cause)
    // A failed root owns nothing. The cascade sealed every holder, so the subtree is final.
    if (holder is RootSessionHolder) {
        _sessions.update { storage ->
            storage.copy(sessions = storage.sessions.mutate { map ->
                if (map[holder.sessionId] === holder) map.remove(holder.sessionId)
                map.removeDescendantsOf(holder)
            })
        }
    }
    true
}
```

Then:
- `isOpen(root)` is `sessions[root.sessionId] === root && !root.isFailed`. Keep the `isFailed` half: during the
  cascade a failed root is still in the map, and `close()` must not send for it.
- `registerChild` tests `childId in storage.sessions`.
- The reap filter is `it is RootSessionHolder && !it.session.isCompleted`, in its one update.
- The removal comes after the cascade, not before the drain, or an update that arrives during the drain would reach
  the global handler before the queued ones (`ClientSessionHolderTest.kt:83`).

Two corrections to Part 1:
- Keep the `_session.isCompleted` check in `claimForOpening` (`RootHolder:129`). Part 1 says it is not needed after
  this change. It is: a speculative holder that the reap is failing is unclaimed, completed, and still in the map
  during its cascade.
- Take readability suggestion Holder 3 in the same slice: split into `failRootTree(root)` (called by `Client` and
  the reap) and `failSubagentTree(child)` (the holders' callback, `Holder:99`), so the function does not test the
  holder's type at both ends.

Behaviour changes:
- A failed grandchild under a failed child of a live root keeps its id until the root's tree goes. Today it frees
  it, by accident of the `blocksId` rule (`SubHolder:85`). Only an agent that reuses a child id notices, and the
  spec forbids that (`subagents.mdx:99`).
- While a cascade drains, an opening of the same id from another coroutine is refused instead of replacing the
  holder, and an update for it goes to the global handler instead of a new speculative holder. The failed caller
  itself retries after `failSessionTree` returns.

**Spec or invention:** invention, an artefact of the reap.

### 7. Make every CAS lambda pure

Ten lambdas write captured variables or have a side effect that a retry repeats. None is a bug today.

**Change:** derive each result from the snapshot that `getAndUpdate` returns, and move side effects out of the
lambda. One idiom for all three areas, no helper.

Behaviour change: no · API: no · decision: none · risk: low · lines: −13 · removes: 9 impure lambdas (the tenth is
suggestion 17) · tests: none expected · depends on: 6 for `registerChild` and the lookup.

| Site | Today | After |
|---|---|---|
| `Registry:61-85` lookup | captured `var`, allocates a `RootSessionHolder` per attempt | allocate once, `updateAndGet`, read the result from the new snapshot (after 6) |
| `Registry:235-246` `registerChild` | two captured `var`s, reads `blocksId` | `getAndUpdate`, test the old snapshot (after 6) |
| `Registry:363-387` reap | captured `var`, `logger.error` inside | `getAndUpdate`, filter the old snapshot, log outside |
| `Holder:174-178` `addChild` | captured `var` | `if (children.getAndUpdate { … }.sealed) return false` |
| `Holder:199-203` `listSubagent` | captured `var` | same |
| `Agent:420-429` `createSession` | two captured `var`s | `getAndUpdate`, read `replaced` and `removed` from the old table |
| `Agent:516-521` `removeRoot` | captured `var` | same |
| `Protocol:315-322` batch cleanup | `response.cancel()` inside | cancel first, then a pure `removeAll` |
| `Protocol:424-429` | captured `var` | `getAndUpdate { it.remove(id) }[id]`, as `Protocol:626` |

Order matters for `registerChild` (Part 2, confirmed). Before suggestion 6 its predicate reads `blocksId`, which
can change from `true` to `false` between the lambda and a second evaluation. A child rejected inside the lambda
could then be reported as registered while it never entered the map. After 6 the predicate reads only the snapshot.

`registerChild` after 6 and 7 (also takes the readability rename Reg 7):

```kotlin
val root = parent.root
val before = _sessions.getAndUpdate { storage ->
    val canRegister = storage.sessions[root.sessionId] === root && childId !in storage.sessions
    if (canRegister) storage.copy(sessions = storage.sessions.put(childId, child)) else storage
}
if (before.sessions[root.sessionId] !== root) return null
val idTaken = childId in before.sessions
```

Agent, with the readability suggestion A1 (`SessionTable.replacingRoot`):

```kotlin
val old = _sessions.getAndUpdate { it.replacingRoot(sessionWrapper) }   // the check moves into replacingRoot
val replaced = old.roots[sessionWrapper.sessionId]
replaced?.let { cleanUpSubtree(it, old.subagentsOf(it)) }
```

The reap's filter reads `session.isCompleted`, not the snapshot. Evaluating it again on the old snapshot is safe:
a root that left the map at count zero has no opening that could complete it.

Rule worth adding to `AGENTS.md`: a CAS lambda is a pure function of the old value; its results are read from the
snapshot, and nothing is assigned or done inside it.

### 8. One `callApp` helper for "the app's cancellation is a failure"

The conversion of a `CancellationException` from app code, while the caller is active, into an
`IllegalStateException` is written out at five places.

**Change:** one helper, used at the three places where it fits as is.

Behaviour change: no · API: no · decision: none (it implements S1 / decision (a)) · risk: low, with one pitfall ·
lines: −10 · tests: none · depends on: nothing; 9 uses it.

```kotlin
/**
 * Runs app code. Its own cancellation, such as a timeout, is a failure; only the caller's cancellation propagates.
 */
internal suspend inline fun <T> callApp(what: String, block: () -> T): T =
    try {
        block()
    } catch (ce: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("$what was cancelled", ce)
    }
```

Use it at `Client:626-632` (`createClientOperations`), `Client:730-739` (the early delivery `async`) and
`RootHolder:240-247` (`deliverDuringOpening`).

**Pitfall, confirmed by reading.** In `setUpSubagent` (`Holder:309-319`) the `catch (ce: CancellationException)`
block calls `ensureActive()`, which throws out of the catch block, and the sibling `catch (t: Throwable)` does not
catch it. A rewrite as `try { callApp(…) { … } } catch (t: Throwable) { failSetup(child, t); return }` would catch
the caller's cancellation that `callApp` rethrows: `failSetup` logs at debug level and returns, and the consumer
goes on to `deliver(item.update, item.meta)` in a cancelled coroutine. So either leave `Holder:309-319` and
`SubHolder:190-204` as they are, which I recommend (the saving there is two lines), or write:

```kotlin
val childOperations = try {
    callApp("createSubagentOperations") { factory.createSubagentOperations(childId) }
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    failSetup(child, t)
    return
}
```

`SubHolder:225-228` logs instead of throwing and keeps its own `catch`. If the functions at `Holder:331` and
`SubHolder:211` are touched, take the rename Holder 5 (`failSetupUnlessStopped`, `failOpeningUnlessStopped`).

### 9. One opening flow in `Client.kt`, with a corrected sketch

`withFactoryInitializingSession`, `withAnsweredSessionId` and `withEarlyInitializingSession` (`Client:655-754`,
with `createSession` at `:618-646`) are one algorithm written three times, with four `failSessionTree` sites.

**Change:** one `openSession` with one catch. Part 1's sketch has three defects; the sketch below fixes them.

Behaviour change: no, if the three differences below are kept · API: no · decision: none; keeps (a) · risk:
medium, every opening passes through it · lines: −30 to −45 (Part 1 said −50 to −60) · removes: two flows, three
fail sites, the `Pair` in a `CompletableDeferred` · tests: `ClientSessionOpenedTest` runs every case over
`allOpenings` (`:118`), so it covers the merged flow unchanged · depends on: 8; takes the readability renames
Reg 9, Reg 12 and Reg 13; supersedes C1, C2, C3 and makes Reg 3 moot.

**Defects of the Part 1 sketch, all confirmed by reading:**

1. It claims load and resume outside `withInitializingSession` (the holder is a parameter of a function whose body
   is the block). `claimSessionHolder` gets its holder from `getOrCreateSessionHolder` (`Registry:88-89, 271`), and
   for a new id at count zero the lookup returns `null` (`Registry:79-82`). Every load or resume of an id the
   client does not hold yet would fail with "Session … not found". The claim must run inside the block.
2. It calls `markOpened` twice for new and fork. `claimAnsweredSessionHolder` already calls it (`Registry:291`).
   The second call finds the stage `ANSWERED`, `answered()` returns `null` (`RootHolder:65`), and `checkNotNull`
   at `RootHolder:147` throws inside the response hook, which must not throw. The answered claim must claim only.
3. It uses a local `atomic(…)`. atomicfu expects atomics as class or top-level properties (plausible, from its
   documentation, not tried). Use a small class or keep today's `CompletableDeferred`.

**"`drainHistory` and `deliverEarly` are the same loop": wrong.** They differ in three ways, so the sketch keeps
both:
- Sink: `drainHistory` (`RootHolder:163-170`) delivers through `session.deliverUpdate`, which tracks the deprecated
  mode and config state. `deliverEarly` (`RootHolder:180-197`) calls `operations.notify` and stores `earlyMode` and
  `earlyConfigOptions` for `applyEarlyState`.
- Cancellation conversion: per item in `drainHistory`, once around the `async` in the early path.
- Closed queue: `receive()` throws in `drainHistory`; `deliverEarly` returns `null`.

They can merge once the deprecated `currentMode` and `configOptions` go.

Corrected sketch:

```kotlin
private class KnownId(val sessionId: SessionId, val replayRequested: Boolean)

private class ClaimedHolder(initial: RootSessionHolder?) {
    private val ref = atomic(initial)
    var value: RootSessionHolder?
        get() = ref.value
        set(holder) { ref.value = holder }
}

/**
 * Opens a root session. [knownId] is set for `session/load` and `session/resume`, whose holder is claimed before the
 * request; for `session/new` and `session/fork` the response hook claims the answered id. Exactly one of
 * [earlyOperations], which needs [knownId], and [factory] is set.
 */
private suspend fun <R : AcpCreatedSessionResponse> openSession(
    parameters: SessionCreationParameters,
    knownId: KnownId?,
    sessionIdOf: (R) -> SessionId,
    earlyOperations: ClientSessionOperations?,
    factory: ClientOperationsFactory?,
    request: suspend (onResponse: (R) -> Unit) -> R,
): ClientSession = sessions.withOpeningCall {
    // Inside the block: the claim finds or creates its holder only while an opening is counted.
    val claimed = ClaimedHolder(knownId?.let { sessions.claimBeforeRequest(it.sessionId, it.replayRequested, parameters) })
    try {
        coroutineScope {
            val earlyDelivery = earlyOperations?.let { operations ->
                val holder = checkNotNull(claimed.value)
                async { callApp("ClientSessionOperations.notify") { holder.deliverEarly(operations) } }
            }

            val response = request { answer ->
                // Claim and marker run back to back in the read loop, so the marker keeps its wire position.
                val holder = claimed.value
                    ?: sessions.claimOnAnswer(sessionIdOf(answer), parameters).also { claimed.value = it }
                holder?.markOpened(answer)
            }
            val sessionId = sessionIdOf(response)
            // A normal return means the hook has run.
            val holder = claimed.value ?: error("Session $sessionId is already open or being opened")

            val session: ClientSessionImpl
            val opened: QueuedItem.Opened
            if (earlyDelivery != null) {
                opened = checkNotNull(earlyDelivery.await()) { "The queue of session $sessionId closed before its answer" }
                session = createClientSession(holder, sessionId, parameters, earlyOperations, response)
                holder.applyEarlyState(session)
            } else {
                val operations = callApp("createClientOperations") {
                    checkNotNull(factory).createClientOperations(sessionId, response)
                }
                session = createClientSession(holder, sessionId, parameters, operations, response)
                opened = holder.drainHistory(session)
            }
            holder.completeOpening(session, opened)
            session
        }
    } catch (t: Throwable) {
        /*
         * The only place an opening fails its holder; the hook may have claimed one before a cancellation won.
         * Wrapped, so an agent request waiting for the session gets -32603, not -32800.
         */
        claimed.value?.let { sessions.failSessionTree(it, IllegalStateException("Failed to create session ${it.sessionId}", t)) }
        throw t
    }
}
```

Registry side: `claimSessionHolder` becomes `claimBeforeRequest`; `claimAnsweredSessionHolder` becomes
`claimOnAnswer(sessionId, parameters)` without the `markOpened` call and without the `response` parameter.

Points checked:
- Today the early path calls `completeOpening` outside its `coroutineScope` (`Client:747`). Inside is equivalent:
  the `async` has completed.
- "Already open" is thrown inside the `try` with `claimed.value == null`, so nothing is failed, as today
  (`Client:708`).
- A hook that runs after the catch read `claimed`, as while the connection closes, still leaves its holder to the
  reap (`Client:694-697`).

**Spec or invention:** the marker's wire position is the spec's for a load (`subagents.mdx:508`); the three copies
are ours. The deeper simplification is the `TODO` at `ClientOperationsFactory.kt:13-16`: once `newSession` and
`forkSession` take operations and the factory is deprecated, `factory` and the `else` branch go.

### 10. One permission-request path: the root's `PendingPermissions` moves to its holder

The `session/request_permission` handler has two branches because a root's `RootPendingPermissions` lives in
`ClientSessionImpl` and a subagent's lives in its holder.

**Change:** `ClientSessionHolder` gets an abstract `permissions`; the handler takes the holder at arrival for both
kinds.

Behaviour change: none observable · API: no · decision: M4 answer 1 says "Roots unchanged"; this changes when a
root's request enters the race, not what it gets, so it needs a confirmation only · risk: low · lines: −15 ·
removes: the root/subagent branch, `subagentHolderOrNull`, `ClientSessionImpl.answerPermissionRequest`, the
extension at `BaseClientSessionRuntime.kt:46-55`, an `as? ClientSessionImpl` cast · tests: construction only ·
depends on: nothing; takes the readability suggestion Reg 11.

**Verified.** Today (`Client:116-131`) a root's request awaits the session and then enters the race in
`Impl:254-262`. A root cannot be cancelled or closed before its session object exists, so entering the race at
arrival answers the same in every case I traced: an opening that fails makes `holder.session.await()` throw inside
the race, which is the error `awaitSession` gives today.

```kotlin
// ClientSessionRegistry: the rule of awaitSession's `when` (Registry:165-169), extracted once
fun holderForRequest(sessionId: SessionId): ClientSessionHolder =
    when (val registered = _sessions.value.sessions[sessionId]) {
        // An unclaimed root is not waited for: the wait would have no bound.
        is RootSessionHolder -> registered.takeIf { it.phase.isAtLeast(Stage.CLAIMED) }
        is SubagentSessionHolder -> registered
        null -> null
    } ?: throw JsonRpcException(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, "Session $sessionId not found")

suspend fun awaitSession(sessionId: SessionId): BaseClientSession = holderForRequest(sessionId).session.await()

// Client
protocol.setRequestHandler(AcpMethod.ClientMethods.V1.SessionRequestPermission) { params ->
    // Taken before the first suspension, so the request joins its session's cancel race when it arrives.
    val holder = sessions.holderForRequest(params.sessionId)
    holder.permissions.answerUnlessCancelled(
        cancelledAnswer = { RequestPermissionResponse(RequestPermissionOutcome.Cancelled) },
    ) {
        val session = holder.session.await()
        session.executeWithSession { session.operations.requestPermissions(params.toolCall, params.options, params._meta) }
    }
}
```

`ClientSessionImpl` uses `holder.permissions` for `clearCancellation()` (`Impl:81`), `cancelPending()` (`Impl:114`)
and the close, and `RootSessionHolder.startClosing()` closes the root's and the descendants' permissions together.
The `AGENTS.md` rule "only through the `PendingPermissions` race" still holds, with one path instead of two.

### 11. Agent: the replay window as one atomic, and drop `isReplayed`

The window is a lock, an atomic flag written under that lock and read without it, and a mutable list.

**Change:** one `AtomicRef<PersistentList<() -> Unit>?>`, `null` meaning closed. `SubWrapper.isReplayed` goes,
because `startAfterReplay` already runs a live child's start at once.

Behaviour change: no · API: no · decision: R15 kept the name `isReplayed`; it decided the name, not that the field
must exist, so confirm · risk: low · lines: −12 to −15 · removes: `replayLock`, `_isReplaying`,
`replayedCollections`, `isReplayed` · tests: none expected · depends on: nothing; takes the readability rename R1.

Before (`RootWrapper:62-94`): `replayLock`, `_isReplaying`, `replayedCollections`, two `synchronized` blocks.

After:

```kotlin
/**
 * The collection starts waiting for the replay window to close, or `null` once it is closed.
 */
private val startsAfterReplay = atomic<PersistentList<() -> Unit>?>(if (replaying) persistentListOf() else null)

val isReplaying: Boolean get() = startsAfterReplay.value != null

fun startAfterReplay(start: () -> Unit) {
    val before = startsAfterReplay.getAndUpdate { it?.add(start) }
    if (before == null) start()
}

fun endReplay() {
    startsAfterReplay.getAndSet(null)?.forEach { it() }
}
```

A start is either in the list that `endReplay` takes, or it saw `null` and runs itself. The lambda is pure.

`SubWrapper:169` becomes `root.startAfterReplay { startCollection(session) }` for every child. Equivalence
(verified): `isReplayed` is `root.isReplaying` read at construction (`SubWrapper:99`); the window only closes,
because a re-open creates a new `RootSessionWrapper`; so `isReplayed == false` means the window is closed at
`:169`, where `startAfterReplay` runs the start at once.

**Spec or invention:** the window is the spec's (`subagents.mdx:508`, `:530`); the lock is ours. Here a CAS is
simpler than the lock.

### 12. Agent: the cancel latch becomes one `CompletableDeferred` (user decision S2, one detail)

A `session/cancel` that arrives while a child's factory runs with a grant is latched under the child's lock and
delivered from a launched job.

**Change:** the notification handler waits for the factory's session in a deferred.

Behaviour change: yes, one detail: two cancels held during the factory are each delivered; today exactly one is ·
API: no · decision: S2 ("latched and delivered to `cancel()` once the factory returns") · risk: low to medium ·
lines: −30 to −35 · removes: `factoryRunning`, `isCancelLatched`, three `synchronized` blocks in `runFactory`,
`latchCancel`, `deliverLatchedCancel` and its launched job · tests: `AgentSubagentsTest.kt:1108` ("…is delivered
once, after it returns") changes or keeps its assertion with the flag below · depends on: nothing; supersedes the
readability suggestion SW1, whose premise is wrong (`factoryState` must outlive the factory, `SubWrapper:158, 352`).

Before: `SubWrapper:72, 84` (fields), `:176-197` (`runFactory`), `:282-319` (`cancel`, `latchCancel`,
`deliverLatchedCancel`).

After:

```kotlin
/**
 * The session the factory returned, or `null` if it threw. Not completed while the factory runs.
 */
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

`factoryRunning` at `SubWrapper:392` becomes `!factorySession.isCompleted`. The deferred is completed outside the
child's lock, as `AGENTS.md` requires.

What stays (verified against `:282-319`): held while the factory runs with a grant, delivered after it returns,
dropped if the factory throws or the grant is revoked, never held without a grant or during a replay.

What changes:
- "Once" becomes "each". After the factory returns, every `session/cancel` already calls `cancel()`, so "once" is
  the odd case. To keep it: one `atomic(false)` CAS before the `await`, +3 lines.
- The held cancel runs in the handler's coroutine, like a direct one, not in `subtreeScope`. A removed root no
  longer cancels it, hence the `isAnnounced` check.
- A factory that never returns keeps one suspended handler coroutine per held cancel until the connection ends.
  Part 1 does not mention this. Today a flag is kept instead.
- `val session` (`SubWrapper:118`) reads the deferred with `ExperimentalCoroutinesApi`.

**Spec or invention:** invention, caused by the SDK's API: the cancel target does not exist until the factory
returns. The spec only allows cancel while the grant stands (`subagents.mdx:502`).

### 13. Agent: `PromptSession` on a `Mutex` (optional)

"A cancelled turn answers after every `cancel()` returned" is built from a `MutableStateFlow<Int>` counter with an
`ANSWERING` sentinel, a CAS-then-wait loop and a separate flag (`RootWrapper:194-233`). The code is on `master`.

**Change:** one `Mutex` and two plain fields.

Behaviour change: two `session/cancel`s for one turn call `AgentSession.cancel()` one after the other, not
concurrently · API: no · decision: none · risk: low to medium, it is the turn's core · lines: −12 to −15 ·
removes: the counter, the sentinel, the retry loop · tests: `AgentTest.kt:1012, 1060, 1120, 1187` should pass
unchanged; whether a test sends two concurrent cancels was not checked · depends on: nothing.

```kotlin
private class PromptSession(val promptJob: Job) {
    private val cancels = Mutex()
    private var answering = false          // guarded by cancels
    var isCancelRequested = false          // guarded by cancels; the turn reads it after closeForCancels()
        private set

    /**
     * Runs [notifySession] and cancels the turn, unless the turn is already answering.
     */
    suspend fun cancelWith(notifySession: suspend () -> Unit): Boolean = cancels.withLock {
        if (answering) return false
        isCancelRequested = true
        notifySession()
        promptJob.cancel()
        true
    }

    /**
     * Waits for the running cancels; later ones no longer target this turn.
     */
    suspend fun closeForCancels() = cancels.withLock { answering = true }
}

suspend fun cancel() {
    val turn = _activePrompt.value
    if (turn == null || !turn.cancelWith { agentSession.cancel() }) agentSession.cancel()
}
```

Verified against `RootWrapper:174-191`: `agentSession.cancel()` is called in every case; if it throws, the job is
not cancelled, as today; the mutex is held across app code, which is safe because the app's `cancel()` cannot wait
for the prompt response without deadlocking today too (`awaitCancels` waits for it).

**Spec or invention:** the rule is the spec's (`prompt-turn.mdx:652, 663`, per Part 1); the mechanism is ours.
Do it only if the file is touched anyway.

### 14. Agent: three small trims

Behaviour change: no · API: no · decision: none · risk: low · lines: −8 to −10 · tests: none.

- Inline `discardFailedLoad` (`Agent:461-466`), a one-line alias of `removeRoot`; keep the comment at the two call
  sites (`Agent:436, 446`).
- Drop `!root.subtreeScope.isActive` from `reportFactoryFailure` (`SubWrapper:238`). A removed child fails
  `checkRegistered` inside the send (`SubWrapper:374`) and a dead protocol fails the send; both land in the
  `runCatching` below. The clause also creates the lazy scope only to read it.
- Flatten the catch of `announce` (`SubWrapper:151-161`); I checked the four cases against today's code:

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

### 15. `Protocol`: drop the "strip the hook on cancel" swap

When a caller is cancelled, `sendRequestRaw` swaps its map entry for a copy without the response hook, so a
response in the graceful wait skips the hook.

**Change:** remove the swap; remove the entry by id.

Behaviour change: internal only: the hook may run during the graceful wait · API: no (the hook overload is
internal) · decision: none; the M3 review accepted the swap as correct, not as required · risk: low · lines: −8 ·
removes: one CAS site, `OutgoingRequest.copy`, the by-deferred removal · tests:
`ReadLoopOrderingTest.kt:135` (`hookDoesNotRunForACancelledCallEvenIfItsResponseArrivesDuringTheGracefulWait`) is
inverted or removed · depends on: nothing.

**Verified.** The hook's own contract says "it may have run for a call that then ends with
`CancellationException`" (`Protocol:186`), and all three callers undo it after `sendRequestRaw` has returned, which
is after the graceful wait: `Client:665-668`, `Client:693-704`, `Client:749-752`. In the early path the delivery
`async` is cancelled with its scope, so the queued marker is never delivered.

Before: `Protocol:215-221` and `:258-259`. After:

```kotlin
} catch (ce: CancellationException) {
    withContext(NonCancellable) { /* graceful wait, unchanged */ }
    throw ce
} finally {
    pendingOutgoingRequests.update { it.remove(requestId) }
}
```

The identity guards on outgoing ids go too (`Protocol:218, 259, 319`): ids come from `requestIdCounter` and are
never reused. Keep the guard at `Protocol:547`: the peer chooses incoming ids. Reword the KDoc at `Protocol:185`.

### 16. `Protocol`: four small trims

Behaviour change: log levels only · API: no · decision: none · risk: low · lines: −35 to −40 · tests: none
expected; `BatchProtocolTest` and `ReadLoopOrderingTest` are the net.

- **`IncomingRequest` flag (−12).** `settle()` and `cancelByCounterpart` (`Protocol:686-704`, `:539`) guard "a
  reply was collected". Both writers run on the handler slot: the reply completes at `Protocol:569, 571` in the
  handler coroutine with no suspension after `ensureActive()`, and the `$/cancel_request` handler body does not
  suspend (`Protocol:133-155`). The third completer, `Protocol:545`, runs after the job has completed, where a
  cancel is a no-op. So the flag equals `reply.isCompleted`:
  `internal class IncomingRequest(val handlerJob: Job, val reply: Deferred<JsonRpcResponse>)`. The behaviour is
  required (v2 `cancellation.mdx:34`, per Part 1) and stays. It trades an explicit atomic for a confinement
  argument, so add one inline comment.
- **`additionalContext` wrapper (−10).** `Protocol:345-356` enters `withContext(additionalContext)` and has its own
  `onCompletion` cleanup for a cancellation at the dispatcher boundary. `handleRequest` enters a second
  `withContext` (`:562`) and has the same cleanup in its `finally` (`:598-604`). Store the context with the
  handler and enter once: `withContext(handler.context + JsonRpcRequestContextElement(request)) { outcome =
  handler.handle(request) }`. A boundary cancellation then lands in the catch at `:570` and the `finally`, once.
- **Graceful-wait ladder (−12).** `Protocol:231-252` has five catch arms that only log, then `response.cancel()`.
  One `runCatching { withTimeoutOrNull(…) { response.await() } }` with one trace line does the same; the `warn`
  for an unexpected error code is lost.
- **`ResponseSlot.onSuccessQueued` (−5, readability P1 merged).** Rename the field to `onResponseQueued`, read it
  directly at `Protocol:500`, and delete `successQueuedHook()` with its opt-in. Its success re-check
  (`Protocol:736-737`) is redundant: the field is set at `:568` directly before `:569` completes the response
  with a success, and no other completer can run first.

### 17. `ElicitationSessionStore`: one pure update, or a lock

The one site where a retried lambda has a side effect (`ElicitationSessionStore.kt:29` moves a counter), and where
reads and writes are separate steps (`:36-37`, `:42-46`, `:49-56`). The code is on `master`.

**Change:** drop `Entry`, the counter and the sort; use the insertion order of the persistent map.

Behaviour change: no · API: no · decision: none · risk: low · lines: −15 to −20 · removes: the counter, `Entry`,
the sort, three read-then-write gaps · tests: `ElicitationSessionMapTest` should pass unchanged; check
"overwriting existing key does not trigger eviction" (`:119`) and "eviction order is based on insertion timestamp"
(`:144`) · depends on: nothing.

Pure CAS form, which keeps the client area lock-free:

```kotlin
// Insertion-ordered, so the oldest entries come first.
private val entries = atomic(persistentMapOf<ElicitationId, SessionId>())

fun put(elicitationId: ElicitationId, sessionId: SessionId) = entries.update { map ->
    // Removed first, so an overwrite counts as the newest entry.
    val next = map.remove(elicitationId).put(elicitationId, sessionId)
    if (next.size <= maxCapacity) next else next.mutate { m -> next.keys.take(next.size / 2).forEach(m::remove) }
}

fun remove(elicitationId: ElicitationId): SessionId? = entries.getAndUpdate { it.remove(elicitationId) }[elicitationId]

fun removeBySession(sessionId: SessionId) = entries.update { map -> map.mutate { m -> m.values.removeAll { it == sessionId } } }
```

It relies on `persistentMapOf()` being insertion-ordered (it is the ordered implementation; `persistentHashMapOf()`
is not), which the tests above pin.

Lock form, equally short: a `SynchronizedObject` around a `LinkedHashMap` (Part 1's sketch). It is the only place
in the three areas where a lock is as natural as a CAS. I recommend the pure form, so the client keeps one style.

### 18. Client: keep the subagent tree in one place (user decision R17 D1; recommended: defer)

A subagent is recorded in its parent's `children.byId` and in the registry's map. No single CAS covers both, so
correctness rests on ordering arguments.

**Change:** the registry snapshot becomes the only record; a `sealed` set in the same snapshot replaces the
per-holder seal.

Behaviour change: one corner (a setup that ends after its parent was sealed leaves a listed, closed subagent
instead of none) · API: no · decision: R17 D1 created `Children(byId, listed, sealed)` · risk: medium · lines: −40
to −60 · removes: `addChild`-before-map ordering, the `removeChild` rollback, the seal walk of
`removeSessionTree`, the `listSubagent` seal check, two `AGENTS.md` bullets · tests: more than Part 1 says:
`ClientSessionRegistryTest` and `ClientSubagentSessionTest` call `childOrNull` on holders at about 14 places ·
depends on: 6 and 7; supersedes the readability suggestions Reg 6 and Holder 2.

**Verified.** The arguments exist as described: `Registry:232-246` ("already in its parent"), `:252`
(`removeChild`), `:110-117` (seal walk), `:401, 418, 423` (other holders' `children` read inside a `_sessions`
lambda). The interleavings are real: an announcement can cross a `session/close` or a failing parent.

Sketch (Part 1, checked for consistency with 6 and 7):

```kotlin
private data class SessionsStorage(
    val initializingSessionsCount: Int = 0,
    val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf(),   // insertion-ordered: parents first
    val sealed: PersistentSet<ClientSessionHolder> = persistentSetOf(),                // failed holders that take no child
) {
    fun childOf(parent: ClientSessionHolder, id: SessionId): SubagentSessionHolder? =
        (sessions[id] as? SubagentSessionHolder)?.takeIf { it.parent === parent }
    fun subtreeOf(root: RootSessionHolder): List<ClientSessionHolder> = sessions.values.filter { it.root === root }
}
```

Why defer:
- `RootSessionHolder.markOpened` and `startClosing` walk the descendants from inside the holder
  (`RootHolder:119, 153`). With the tree in the registry they need the registry, or move there. Part 1 does not
  count that.
- The user rejected wider cascade refactors on 2026-11-26 as "not worth their risk now", and this touches the same
  code.
- After 6 and 7 the remaining cost is three ordering comments, not wrong behaviour.

The lock alternative (one `SynchronizedObject` around a mutable map and mutable children) is not better: every
reader then needs the lock or a published copy, and nothing may complete a deferred or call the app under it.

### 19. Subagent permissions: the post-cancel window (reverses a final decision)

After `ClientSubagentSession.cancel()`, later permission requests of that child are also answered `cancelled`
while the last live state is `running` or `requires_action`, until the next live state.

**Change, only if the user reopens the decision:** drop the window; a client cancel answers the requests pending
now.

Behaviour change: yes · API: one paragraph of public KDoc (`ClientSubagentSession.kt:155-158`) · decision: final
(`state.md`, "`onState` sets `activeWork` before `clearCancellation()`") · risk: low · lines: −25 · removes:
`Live.activeWork`, `Held.activeWork`, the write-then-clear order at `PP:133-138` against `PP:161-165` · tests:
nine of the 23 in `SubagentPendingPermissionsTest` (per Part 1) · depends on: nothing; supersedes the readability
suggestion PP 1 (which holds if this is rejected).

**Verified.** The interleaving it exists for: the client cancels a child; a `session/request_permission` of that
child was already on the wire and arrives after the cancel. Without the window the app sees that request, and the
agent's confirmation, `idle` with `cancelled`, which `subagents.mdx:454` requires, then answers it (`PP:132`). So
the window only hides a prompt between the cancel and the agent's confirmation. It has a cost of its own: a child
that keeps working without a new state has every later request denied silently.

**Spec or invention:** invention. The spec requires the answer to the pending requests (`subagents.mdx:471`) and
says of a descendant "Until then, the user can still answer them" (`:472`). The root's equivalent (`PP:34, 66-68`)
is different and stays: a root has no state stream, and nothing else would answer a late request.

My recommendation: ask once, with the spec line; keep the code as it is if the answer is no. If it stays, do not
put a lock around `onState` and `onClientCancel`: completing the signal under a lock runs `setPendingAside`'s
completion handler (`PP:180`) inside it.

### 20. Batch support is what complicates `Protocol`'s response path (outside this PR)

A reply is a `ResponseSlot`, slots share a `responseFrameQueued` deferred, and a collector coroutine per frame
awaits every slot (`Protocol:449-517`, `:706-738`), only because JSON-RPC wants a batch's replies in one array.

**Change, a user decision on a `master` public API:** remove batch support. A handler then sends its own reply.

Behaviour change: yes · API: yes (`sendBatchRequestRaw`, `JsonRpcCall`, `TransportFrame.Batch`, `README.md`) ·
decision: the user's, outside this PR · risk: high · lines: about −150 in `protocol/`, plus most of
`BatchProtocolTest` · depends on: nothing; makes `onResponseQueued` one line and suggestion 5 moot.

**Verified.** `sendBatchRequestRaw` has no caller outside tests (grep over the main source sets and the samples).
ACP v2 forbids batches (`transports.mdx:67`); v1 only says it follows JSON-RPC 2.0, and the Rust SDK rejects an
array for every version (both per Part 1, not re-checked).

```kotlin
val reply = try { … JsonRpcSuccessResponse(…) } catch (t: Throwable) { JsonRpcErrorResponse(…) }
sendFrame(TransportFrame.Single(reply))          // queue acceptance
if (reply is JsonRpcSuccessResponse) {
    outcome.onResponseQueued?.invoke()
    outcome.afterResponse?.invoke()
}
```

### 21. Readability suggestions that hold on their own

Twenty of the 41 suggestions in `readability-suggestions.md` hold independently of everything above. None changes
behaviour or public API except Reg 14. I re-read the client and agent ones; for M1 to M4 and B1 I only confirmed
that the cited code exists.

| Id | File | Change | Note |
|---|---|---|---|
| Reg 3 | `Registry:88-89` | inline `getOrCreateSessionHolder` into its one caller | moot if 9 lands |
| Reg 4 | `Registry:120, 137` | locals `settlesHolder`/`settles` → `mustFail` | keep the function name and "settles" in docs: the user's wording |
| Reg 5 | `Registry:137` | name `subagentsBottomUp` | |
| Reg 8 | `Registry:219` | `followSubagentState` → `feedSubagentPermissions` | |
| Reg 14 | `Registry:289-290` | `session/new` answered with an open id leaves an agent session nobody closes | behaviour; user decides: document, or send `session/close` |
| Holder 1 | `Holder:257, 294`, `SubHolder:106` | `opened`/`parentOpened` → `streamOpened` | |
| Root 1 | `RootHolder:59-74` | `claimedByOrNull`, `answeredOrNull`, `closingOrNull` | |
| Root 2 | `Holder:247`, `RootHolder:152`, `SubHolder:80` | `RootPhase.appliesHeldState`, parameter `applyHeld` | put it on `RootPhase`, not `Opening` |
| Subagent 1 | `SubHolder:147` | `completeOpening` → `runOpening` | reverses R19 item 2: ask the user |
| Subagent 2 | `SubHolder:55` | `OpeningState.Finished` → `Completing` | also `AGENTS.md` "the finished opening" |
| Subagent 3 | `SubHolder:186-189` | extract `claimCompletion()` | pairs with Subagent 2 |
| Subagent 4 | `SubHolder:69` | `_subagent` → `setUpSession` | R19 item 2 named it: ask the user |
| PP 1 | `PP:102, 105` | `activeWork` → `confirmsWork` | only if 19 is rejected |
| CS1 | `ClientSubagentSession.kt:181-196` | split `applyPatch` into descriptive and held fields | |
| SW2 | `SubWrapper:334, 345, 373` | `sendOnOwnStream`, `sendOnParentStream`, `sendOnParentStreamLocked` | |
| M1 | `JsonObjectSerializer.kt:70`, `MaybeUndefined.kt:101, 131`, `Subagents.kt:115` | one `Json.decodeLenientlyOrNull` | keep the `JsonNull` and absent checks in the callers |
| M2 | `SubagentSessionUpdateSerializers.kt` | one naming scheme for the private tri-state decoders | do not rename `decodeMaybeUndefined`: v2 models call it |
| M3 | `SessionUpdate.kt` (16 branches) | `tagged(type, serializer, value)` | 13 branches are from `master` |
| M4 | `SubagentState.kt:171-190` | shared discriminator constants | |
| B1 | `build-logic/…/CheckInternalPackagesNotPublic.kt:69` | `findClassEntriesInInternalPackages` | |

Merged into a numbered suggestion, do them there: Reg 2, Reg 7, Reg 10 (use `uncompletedRoots`, not
`unclaimedRoots`: the reap also takes a root that a late hook claimed) and A1 into 7; Holder 3 into 6; Holder 5
into 8; Reg 9, Reg 12, Reg 13 into 9; Reg 11 into 10; R1 into 11; P1 into 16.

Superseded if the numbered suggestion lands, otherwise they hold: Reg 1 (by 6), Reg 6 and Holder 2 (by 18),
Holder 4 (by 3A), C1 to C3 (by 9), SW1 (by 12; its premise is wrong either way).

## 3. Rejected proposals, and what is unverified

### Rejected

- **Drop the subagent permission hold-back** (client sessions item 14, −50): the user rejected it as D1 on
  2026-11-22, and Part 1 brings no new evidence.
- **Registry §4, count only openings with an unknown id:** removes no lines; it is a behaviour improvement
  (unconnected updates are not buffered during a long load), to raise separately.
- **Registry §4, side buffer instead of speculative holders:** narrows M4 answer 1, and the reap still has to
  remove a root that a late hook claimed.
- **Never close the queue, one consumer per holder:** moves `notify` off the caller's coroutine, against
  decision (a).
- **Agent item 16, drop the grant rollback** (`SubWrapper:385-390`): saves five lines and breaks a stated
  `AGENTS.md` rule; harmless either way.
- **Agent: a lock for the session table:** handlers read the table without one, so the immutable snapshot would
  stay anyway.
- **Agent: merge `factoryState` and `eventsState`:** a child that reports state through its context and returns
  empty `events` would get `unknown` at once.
- **Agent: reject a re-open of an open root:** user decision (b); it would save eight lines.
- **Agent: always wrap a factory failure:** a behaviour change with no simplification behind it.
- **`Protocol` §3, a barrier after each request:** not a simplification, and no caller needs it.
- **`Protocol`: drop `onResponseQueued`:** its shape is a user decision, and the window it closes is real
  (`Agent:448-457`).
- **`Protocol`: run the read loop on the handler dispatcher:** application threads touch the same client state, so
  no CAS would go.
- **A registry lock** (alternative to 18): readers need the lock or a copy; it adds a second style.
- **A lock around `SubagentPendingPermissions`:** see 19.
- **Readability O1** (`checkUpdate` hook): obsolete; the hook would sit outside the child's lock.
- **Merge `drainHistory` and `deliverEarly` now:** they are not the same loop (see 9).
- **A generic `modifySessions` helper** (agent item 6): `getAndUpdate` plus the old snapshot does the same without
  a helper (see 7).

### Unverified

- Nothing was run: no test, no build. Every "should pass unchanged" is from reading test names and the cited
  lines.
- All line savings are estimates.
- `Mutex` fairness and the non-suspending uncontended `lock()` (suggestions 3 and 13) are from the kotlinx
  documentation as I know it, not re-read.
- atomicfu's restriction on local atomics (suggestion 9) is from its documentation as I know it, not tried.
- That `persistentMapOf()` keeps insertion order (suggestions 17 and 18) was not tried; the existing eviction
  tests would show it.
- Rust SDK facts and the v1 spec lines `prompt-turn.mdx:483, 652, 663` and `subagents.mdx:210` are taken from
  Part 1. Re-read here: v2 `transports.mdx:61-67` and v1 `subagents.mdx:99, 454, 471-475, 502, 508, 530`.
- Whether any agent test sends two concurrent `session/cancel`s for one turn (suggestion 13) was not checked.
- The test counts for suggestion 19 (nine of 23) and the test names for suggestion 6 in
  `ClientEarlySessionUpdatesTest` are from Part 1; I confirmed only the total of 23 and the registry test at
  `ClientSessionRegistryTest.kt:144`.
- `acp-integration-test` and the samples were not read for behaviour that these suggestions could change.
