# Can the v1 client's session registry and holders become simpler?

**Scope:** `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/` — `ClientSessionRegistry.kt` (424 lines),
`ClientSessionHolder.kt` (389), `RootSessionHolder.kt` (248), `SubagentSessionHolder.kt` (233); 1294 lines in total.
Branch `eugenethedev/subagents` @ `e609e49`, working tree clean. Analysis only, nothing changed, nothing run.

**Sources:** the four files, `Client.kt` and `Protocol.kt` as context, the tests under `acp/src/{commonTest,jvmTest}/…/client/`,
`AGENTS.md`, `.agents/state.md`, and the research reports named in the brief. Spec and Rust SDK statements are taken
from those reports (spec `01a54c84`, Rust SDK `34aea54f`, both 2026-11-22). I did not fetch upstream again: no claim
below needs more than what those reports already quote.

**Abbreviations:** files are `Registry`, `Holder`, `Root`, `Subagent` followed by a line number.

## Summary

Most of the machinery is needed. It follows from four things that are either spec-driven or explicit user decisions:
the answer's wire position matters (markers), the opening can still fail after the answer (cancellable `Opened`),
the close doesn't join openings, and a subagent's permission request joins the cancel race at arrival. I found no
mechanism that guards a race which cannot happen.

What can go is the state that exists only because two things are loose:

1. **A failed root lingers in the session map until the last opening ends.** That one fact needs `blocksId`,
   `replacing`, the `isFailed` clause of the reap, the reap's second map update and seven `takeIf { it.blocksId }`
   reads. Removing the failed tree when it fails makes "in the map" mean "owns the id". About −30 lines, no
   behaviour the spec or a user decision asks for is lost. **Top recommendation.**
2. **The tree lives in two places** (the registry's map and each holder's `Children`), tied by ordering arguments
   ("`addChild` before the map update", "the root's entry leaves no later than its descendants'", `removeChild` as a
   rollback). Keeping the tree only in the registry snapshot, as the agent's `SessionTable` does, removes those
   arguments. About −40 to −60 lines, medium risk. **Second recommendation, as a separate step.**
3. **CAS lambdas with captured `var`s** (five sites). Each result can be derived from the snapshot that
   `getAndUpdate` returns, which makes every lambda a pure function. About −10 lines, no risk.

Rough total: 80 to 110 of 1294 lines (6-9%), and 5 of about 20 mechanisms: `blocksId`, `replacing`, the two-stage
reap, the `removeChild` rollback and the per-holder seal.

**CAS vs locks:** keep CAS, don't add a lock. Contention is nil (session lifecycle events against one read loop), and
the real hazard, lambdas that read other atomics, goes away with recommendations 1-3 rather than with a lock. A
registry lock is a workable alternative to recommendation 2 only; it is sketched below with its costs.

## Threading facts the analysis rests on

- The sorting of a `session/update` (`Registry:182-214`) runs in the first part of its handler, on the handler
  dispatcher (`Protocol.kt:70`, parallelism 1), while the read loop waits for it (`Protocol.kt:472-476`).
- The response hook (`markOpened`, `claimAnsweredSessionHolder`) runs on the read loop itself (`Protocol.kt:632`).
- So the sorting, the hook and the first part of a request handler never overlap each other. They do overlap with
  application coroutines: opening calls, `close()`, a consumer's `setUpSubagent`, and the subagents' opening jobs
  in the connection scope (`Protocol.kt:125`).

Every race below is "read loop against an application coroutine" or "two application coroutines". None is hot.

## Inventory

| # | Mechanism | Location | Real? | Spec or invention | Verdict | Lines |
|---|---|---|---|---|---|---|
| 1 | Speculative root holders, `initializingSessionsCount` | `Registry:58-86`, `:358-406` | marginal: needs updates before a `session/new`/`fork` answer | invention (v1 silent; Rust parks them too); on `master` already | keep, narrow (see §4) | 0 to −25 |
| 2 | Failed root stays in the map until the reap; `blocksId` | `Holder:154`, `Root:101`, `Subagent:85`, `Registry:60,67,94,101,165,244` | no: an artefact | invention | **drop** (§1) | −30 |
| 3 | `replacing` / `removeDescendantsOf` | `Registry:414-424`, `:77`, `:245` | only because of 2 | invention | **drop** with 2 | in 2 |
| 4 | Two-stage reap (roots, then descendants) | `Registry:363-404` | only because of 2 | invention | simplify with 2 | in 2 |
| 5 | One opening per holder (`claimForOpening`, `CLAIMED`) | `Root:127-133`, `Registry:266-293` | yes: two loads of one id put two markers and two consumers on one queue | invention (v1 silent on re-open) | keep, minimal | 0 |
| 6 | `RootPhase` in one atomic (`UNCLAIMED`…`CLOSING` + `Opening`) | `Root:45-81` | yes | invention | keep, minimal | 0 |
| 7 | `Opened` marker queued by the response hook | `Root:145-155`, `Holder:80` | yes: the caller resumes on another thread, later updates would overtake | spec for load with subagents (the response is the history boundary); the `Opened` event itself is SDK API | keep | 0 |
| 8 | Subagent markers: walk at the answer plus `init` | `Root:152-153`, `Subagent:77-81,99-101` | yes | follows from 7 | keep; relies on read-loop order, not atomics | 0 |
| 9 | `awaitSession` fails at once for an unclaimed id | `Registry:160-171` | yes: the wait had no bound | invention, matches Rust | keep | 0 |
| 10 | Arrival-time sorting into update / announcement / patch | `Registry:202-214` | yes: child traffic follows the announcement at once | spec (announce before child traffic) | keep | 0 |
| 11 | `registerChild`: `addChild`, then map update, `removeChild` rollback | `Registry:230-260`, `Holder:173-191` | yes: close or failure against an announcement in flight | invention (tree in two places) | **simplify** (§2) | −25 |
| 12 | `Children(byId, listed, sealed)` | `Holder:108-112,129,173-220` | yes | invention; **user decision** R17 D1 | simplify with 11 (§2) | −20 |
| 13 | `CLOSING` stage, read after `addChild` | `Root:116-120`, `Holder:182` | marginal: an announcement and a permission request crossing the close | by analogy with the `session/cancel` rule; **user decision** M4 (2) | keep, 3 lines | 0 |
| 14 | `removeSessionTree`: seal walk, stop, remove by identity, settle | `Registry:109-144` | yes | invention; identity is **user decision** (close identity) | simplify with 11 | −8 |
| 15 | `isOpen`, close acts on its own holder | `Registry:94` | yes: a stale `ClientSession.close()` would close a reloaded session | invention | keep | 0 |
| 16 | `failSessionTree` cascade, parent first | `Registry:310-325` | yes | invention; a wider refactor was **rejected by the user** 2026-11-26 | keep | 0 |
| 17 | `OpeningState`, first stop settles the holder | `Subagent:39-63,121-138,186-189`, `Registry:330-339` | yes: two failures draining one queue | invention; **user decision** (no-join close) | keep, minimal for no-join | 0 |
| 18 | `ensureActive` in the opening's loops | `Subagent:157,181` | marginal: a cancelled opening keeps taking the drain's items | follows from 17 | keep, 2 lines | 0 |
| 19 | Subagent opening waits for `parent.session` | `Subagent:170-172` | yes, given cancellable `Opened` | follows from **user decision** (a) of 2026-11-15 | keep (§5) | 0 |
| 20 | Queue hand-off: close the queue, `lateDeliveries` chain, fast path | `Holder:350-388` | yes: each update is its own coroutine | ordering is implied by the spec | keep (§3) | 0 |
| 21 | Failed holder's queue goes to the global handler | `Holder:225-240`, `Registry:345-352` | yes for unclaimed holders | invention | keep | 0 |
| 22 | `listSubagent` refuses after the seal | `Holder:198-205,324-327` | no: `startOpening` already covers it | invention | drop with 11 (§2) | −8 |
| 23 | `CancellationException` to `IllegalStateException` at five sites | `Root:240-247`, `Holder:309-315`, `Subagent:190-197,225-228`, `Client.kt:626-632,729-738` | yes (`withTimeout` inside `notify`) | invention; **user decision** S1 / (a) | simplify: one helper | −15 |
| 24 | `earlyMode` / `earlyConfigOptions` plain fields | `Root:83-91,199-208` | safe: written by the `async`, read after its `await` | deprecated API | keep until the API goes | 0 |
| 25 | Captured `var`s written inside CAS lambdas | `Registry:61,235-236,363`, `Holder:174,199` | not a bug, but each lambda must reset them | — | **simplify** (§6) | −10 |

## §1. Remove a failed root's tree when it fails (items 2, 3, 4)

### What happens today

A failed root holder stays in the map until `initializingSessionsCount` reaches zero (`Registry:376-378`). Until
then every reader has to ask "is this entry still an owner?":

- `blocksId` (`Holder:154`), with two different rules (`Root:101`, `Subagent:85`);
- `takeIf { it.blocksId }` in the lookup, twice (`Registry:60,67`), in `isOpen` (`:94`), `subagentHolderOrNull`
  (`:101`), `awaitSession` (`:165`) and `registerChild` (`:244`);
- `replacing`, which swaps a non-blocking entry and removes its descendants (`Registry:414-424`);
- the reap filter `!it.session.isCompleted || it.isFailed` (`:377`) and a second update for descendants (`:399-403`);
- `claimForOpening`'s `_session.isCompleted` check (`Root:129`), because a failed holder could otherwise be claimed.

`blocksId` is read inside two CAS lambdas (`Registry:67,244`). It reads two other holders' deferreds. The earlier
analysis (`client-register-child-race.md`, "`storage.sessions[childId]?.blocksId`") found that sound only because
"doesn't block" is monotonic.

The subagent rule also has an accidental result. Under a live root, a failed child keeps its id, but a failed
grandchild of that failed child frees it (pinned at `ClientSessionRegistryTest.kt:159-160`). Nothing asks for that.
The spec forbids reusing a child id at all (`client-close-identity.md`, requirement 9).

### Is the lingering needed?

No. A failed root answers every update with "unconnected" (`Holder:380`), which is also what a missing entry gives
(`Registry:190`). The only user of the lingering entry is its own replacement.

### Proposal

`failSessionTree` removes a root's tree from the map once the cascade has ended. The reap keeps removing roots that
no opening completed, in its one update.

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
- `blocksId` and its overrides go; a holder in the map owns its id. `isOpen(root)` is `sessions[id] === root`.
- `replacing` goes; `registerChild` uses `childId in storage.sessions`, a read of the snapshot only.
- The reap filter is `it is RootSessionHolder && !it.session.isCompleted`, and its second update goes.
- The root is removed after the cascade, not before the drain. Removing it first would let an update that arrives
  during the drain reach the global handler before the queued ones
  (`ClientSessionHolderTest` "update arriving during the reap is reported after the queued updates are drained").

### Behaviour changes

- A failed grandchild under a failed child of a live root keeps its id until the root's tree goes. Only an agent
  that reuses a child id notices.
- While the cascade drains (it calls the app's global handler), a concurrent opening of the same id from another
  coroutine is refused instead of replacing the holder. The failed caller itself is unaffected: it retries after
  `failSessionTree` returns.

### Tests that change

`ClientSessionRegistryTest`: "a failed subagent keeps its id…" (the two `blocksId` assertions), "the subagents of a
failed root leave the session map when the root is reaped" and "…when a new opening replaces the root" (both become
"when the root fails"). `ClientEarlySessionUpdatesTest` "a retry after a failed load gets the updates while another
session initializes" should pass unchanged and is the regression test for this change.

## §2. Keep the tree in one place (items 11, 12, 14, 22)

### What happens today

A subagent is recorded twice: in its parent's `children.byId` (`Holder:129`) and in `_sessions` (`Registry:52`). No
single CAS covers both, so correctness rests on written arguments:

- `addChild` runs before the map update (`Registry:232-246`), so that a removal's walk finds the child;
- "every removal of a root's tree takes the root's entry out no later than its descendants'" (`Registry:238-242`,
  and an `AGENTS.md` bullet);
- `removeChild` rolls a rejected child back (`Registry:252`), with the note that a cascade may have seen it meanwhile;
- `removeSessionTree` seals top-down before it reads (`Registry:110-117`);
- `removeDescendantsOf` reads other holders' `children` atomics inside a `_sessions` lambda (`Registry:401,418,423`),
  which is sound only because those holders are sealed by then.

The interleavings are real: an agent's announcement can cross a `session/close`, or arrive while an application
coroutine fails the parent. So the handling is needed. Its shape is what costs.

### Proposal: the registry snapshot is the only record

A holder already knows its `parent` and `root`. So the map alone can answer "children of P" and "subtree of R". The
one thing a holder's own atomic adds is the seal of a subagent that failed alone, and that can be a set in the same
snapshot.

```kotlin
private data class SessionsStorage(
    val initializingSessionsCount: Int = 0,
    val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf(), // insertion-ordered: parents first
    val sealed: PersistentSet<ClientSessionHolder> = persistentSetOf(),              // failing or failed holders, roots included, that take no child
) {
    fun childOf(parent: ClientSessionHolder, id: SessionId): SubagentSessionHolder? =
        (sessions[id] as? SubagentSessionHolder)?.takeIf { it.parent === parent }
    fun childrenOf(parent: ClientSessionHolder): List<SubagentSessionHolder> =
        sessions.values.filterIsInstance<SubagentSessionHolder>().filter { it.parent === parent }
    fun subtreeOf(root: RootSessionHolder): List<ClientSessionHolder> = sessions.values.filter { it.root === root }
}

private fun registerChild(parent: ClientSessionHolder, childId: SessionId): SubagentSessionHolder? {
    val child = SubagentSessionHolder(childId, parent)
    val root = parent.root
    val before = _sessions.getAndUpdate { s ->
        if (s.accepts(parent, root, childId)) s.copy(sessions = s.sessions.put(childId, child)) else s
    }
    if (!before.accepts(parent, root, childId)) return null   // log when the id was taken
    if (root.phase.isAtLeast(Stage.CLOSING)) child.permissions.close()   // the same read-after-write as today
    return child
}

// A pure function of the snapshot.
private fun SessionsStorage.accepts(parent: ClientSessionHolder, root: RootSessionHolder, childId: SessionId) =
    sessions[root.sessionId] === root && parent !in sealed && childId !in sessions

suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
    val before = _sessions.getAndUpdate { s ->
        val subtree = s.subtreeOf(root)
        s.copy(sessions = s.sessions.mutate { map -> subtree.forEach { map.remove(it.sessionId) } }, sealed = s.sealed.removeAll(subtree))
    }
    val subtree = before.subtreeOf(root)
    // stop the openings, then close permissions and fail or mark closed, as today
}
```

`failTree` keeps its recursion: "seal P and return its children" becomes one
`_sessions.getAndUpdate { it.copy(sealed = it.sealed.add(p)) }.childrenOf(p)`. This is not the "stop the whole
subtree first" refactor that the user rejected on 2026-11-26.

### What goes

- `Children.byId`, `Children.sealed`, `addChild`, `removeChild`, `sealChildren`, `forEachDescendant` in the holder
  (`Holder:108-112,166-220`). `listed` stays as a small list of its own.
- The seal walk of `removeSessionTree` (`Registry:110-117`): a child that commits before the removal is in `before`,
  and one that commits after sees the root gone. No ordering argument is left.
- `listSubagent`'s seal check (`Holder:198-205`, `:324-327`). Every child that has an `Announcement` item is in the
  map, so a removal or a cascade stops its opening, and `startOpening` then closes the session (`Subagent:128-132`).
  The check only decides whether such a child is "not listed" or "listed and closed". "Listed and closed" is already
  the rule for a child that fails (**user decision** M4 (3)).
- Two `AGENTS.md` bullets ("A holder's children and its `subagents` list share one atomic state…" and "Every removal
  of a root's tree takes the root's entry out no later than its descendants'…") become one: the tree is one snapshot.

### Costs

- `childrenOf` and `subtreeOf` scan the connection's sessions. They run once per cascade, close or answer.
  `childOf` is a map lookup, so the per-update path costs the same as today.
- It revisits **user decision** R17 D1 (one atomic `Children` in the holder). It follows the user's own choice for
  the agent (PR review round 2: one snapshot, every rule that spans two maps in one update).
- Behaviour change: a setup that ends after its parent was sealed leaves a listed, closed subagent instead of none.
- Medium risk. Nearly every test in `ClientSessionRegistryTest` and `ClientSubagentCloseTest` exercises this code,
  though few would need to change: they assert outcomes, except the ones that call `childOrNull` on a holder.

### The lock alternative

One `SynchronizedObject` in the registry (atomicfu, already used in `agent/internal/RootSessionWrapper.kt:62` and
`SubagentSessionWrapper.kt:55`) around a mutable map and mutable per-holder children gives the same result:
`registerChild` checks and inserts in both places in one critical section. `Mutex` (used in `ClientNegotiator.kt:105`)
is not usable, because the sorting and the response hook must not suspend.

I don't recommend it over the snapshot:
- every reader needs the lock or a published copy: the lookup for each `session/update`, `childOrNull`, the public
  `subagents` getter;
- nothing may run under it that completes a deferred or calls the app, so `markOpened`'s walk, `startClosing`'s walk
  and both cascades still "collect, then act outside", which is the shape `getAndUpdate` already gives;
- the agent side settled the same problem with one snapshot, and two styles for one problem cost more than either.

## §3. The queue hand-off and `lateDeliveries` (item 20): keep

`handleOrQueue` (`Holder:350-376`) has three paths: queue, direct delivery, and the chain. The chain is needed:
after the consumer closes the queue, each update is its own handler coroutine waiting for `_session`, and nothing
else orders their resumption (pinned by `ClientSessionOpenedTest` "updates that miss the closed queue wait for the
updates queued after the marker").

Two simplifications are possible, and I recommend neither:

- **Drop the fast path** (`Holder:357-359`), so every update takes the chain. It removes the `Announcement` special
  case and 6 lines. But it changes live delivery from "starts in order, may overlap" to "one at a time", so a
  `notify` that suspends would hold back every later update of its session. That is a public behaviour change with a
  deadlock risk for apps that wait inside `notify`.
- **Never close the queue; one consumer coroutine per session for its whole life.** This would remove the chain,
  the fast path, `completeOpening`'s close-and-drain (`Root:224-227`, `Subagent:174-185`) and the `Finished` claim.
  It also moves live `notify` calls off the handler coroutine onto another dispatcher, and it conflicts with the
  `AGENTS.md` rule "keep this the only delivery path". It is the largest possible simplification of delivery, and it
  belongs to the delivery reviewer's area. I list it only so that it is weighed once, not to propose it here.

## §4. Speculative holders (item 1): keep, optionally narrow

### Is the race real?

Since the response hook exists, the common race (the `session/new` answer followed at once by an update) no longer
needs a speculative holder: the hook claims the id on the read loop before the next message
(`Registry:284-293`). What is left is an agent that sends updates for a new id **before** its answer. v1 doesn't
say whether it may; v2 says clients MAY buffer or drop; the Rust SDK parks such notifications
(`updates-before-opening-answer.md`, "Answer"). The mechanism was on `master` before this branch.

So it is an SDK guarantee for a case v1 leaves open. Keeping it matches Rust and costs little once §1 is done.

### Its cost today

- While any opening runs, an update for **any** unknown id is buffered, not delivered. A legal update for an
  unconnected session (a `session/list` status change) waits until the last opening ends
  (`ClientHangingSessionUpdateDeliveryTest` pins that it arrives eventually). A long `session/load` delays it for as
  long as the load runs.
- `session/load` and `session/resume` know their id and claim it before the request (`Client.kt:662,725`). They need
  no speculation, yet they are counted, so they cause the delay above.
- The lambda at `Registry:66-84` allocates a holder, with its channel, on every CAS attempt.

### Options

- **Narrow it (small):** count only the openings whose id is unknown (`withAnsweredSessionId`, `Client.kt:686`).
  `claimSessionHolder` then creates its holder in one CAS instead of going through the speculative lookup. With §1
  the load and resume paths need no reap. This touches `Client.kt`, the other reviewer's file.
- **Replace speculative holders with a side buffer (larger, about −25 lines):** keep early updates for unknown ids
  in `SessionsStorage` as `PersistentMap<SessionId, PersistentList<…>>`, and let the claim in the hook adopt them.
  Then `UNCLAIMED`, the `CLAIMED` check in `awaitSession` and the reap's cascade go. But a subagent announced before
  a `session/new` answer would be registered only at the answer, so its early permission request would fail at once
  instead of joining the cancel race. That narrows **user decision** M4 (1) for a case v2 says agents should avoid.
  I would not do this now: the gain is small and it changes behaviour in a corner.

Whichever option is taken, allocate the candidate holder once, outside the lambda (see §6).

## §5. Decisions that cause the rest: no change proposed

These items are complicated, and the complication comes from a decision, not from the code:

- **Cancellable `Opened` delivery** (user, 2026-11-15 (a)): the opening can fail after the answer. That is why a
  subagent's opening waits for its parent's holder (`Subagent:170-172`), why a holder completes only at the end of
  `completeOpening` (`Root:229-231`), and why agent requests wait for the holder. If a session counted as open at
  its answer, items 19 and part of 20 would go. The price is a session the agent considers open after the client's
  call failed. I don't argue to reopen it.
- **No-join close** (user, 2026-11-26): `OpeningState` with four states and "the first stop settles the holder"
  (`Subagent:39-63`) is the minimum for a close that doesn't wait. The joined version needed self-close detection and
  deadlocked for two roots closing each other (`close-no-join-review.md`, "What I ran"). Keep.
- **`CLOSING` and the read after `addChild`** (user, M4 (2)): three lines for a narrow window, the time between
  `startClosing` and the close response. A pull-based check of the root's phase in the permission handler would
  replace the line in `addChild` and gain nothing. Keep.

One small cleanup inside these: the conversion "a `CancellationException` from app code while the caller is active
becomes `IllegalStateException`" is written out five times (item 23). One helper would state the rule once:

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

`Subagent:225-228` logs instead of throwing, so it keeps its own `catch`.

## §6. CAS vs locks

### Lock sites

None in this area. The repo offers atomicfu `SynchronizedObject`/`synchronized` (atomicfu 0.25.0,
`gradle/libs.versions.toml:13`; used in `agent/internal`) and `kotlinx.coroutines.sync.Mutex` (coroutines 1.9.0;
used in `ClientNegotiator.kt:105`, and unusable on the read loop because it suspends).

### CAS sites

| Site | Guards | Lambda pure? | Reads other atomics? | Contention | Recommendation |
|---|---|---|---|---|---|
| `Registry:66-84` `_sessions.update` (lookup or speculative holder) | the id's owner | no: allocates a `RootSessionHolder` per attempt, writes a captured `var` | yes: `blocksId` (two deferreds); `replacing` reads `children` | read loop vs opening start/end; rare | restructure: §1 removes `blocksId`; allocate once and use `updateAndGet` (below) |
| `Registry:128-135` `getAndUpdate` (removal) | the tree's ids | yes | no (identity against the snapshot) | rare | keep CAS |
| `Registry:237-246` `update` (`registerChild`) | the child's id, and that the root still owns its id | writes two captured `var`s | yes: `blocksId`; `replacing` reads `children` | read loop vs close or failure; rare | keep CAS; with §1 it reads only the snapshot; derive the outcome from `getAndUpdate` |
| `Registry:359` `update` (count up) | the count | yes | no | rare | keep CAS |
| `Registry:364-387` `update` (count down, reap) | the count and the hanging roots | no: logs inside, writes a captured `var` | yes: `session.isCompleted`, `isFailed`; stable, because no opening can complete a holder at count 0 | rare | keep CAS; log outside, derive the reaped set from `getAndUpdate` |
| `Registry:400-402` `update` (reap descendants) | the failed subagents' ids | yes | yes: `children`, final after the seal | rare | goes with §1 (moves into `failSessionTree`) |
| `Holder:175-178` `children.update` (`addChild`) | `byId` against the seal | writes a captured `var` | no | read loop vs seal; rare | keep CAS, or goes with §2 |
| `Holder:190` `children.update` (`removeChild`) | rollback | yes | no | — | goes with §2 |
| `Holder:200-203` `children.update` (`listSubagent`) | `listed` against the seal | writes a captured `var` | no | consumer vs seal; rare | goes with §2 |
| `Holder:210` `getAndUpdate` (`sealChildren`) | the seal and its snapshot | yes | no | — | keep CAS, or goes with §2 |
| `Holder:368` `lateDeliveries.getAndSet` | the tail of the chain | no lambda | — | read loop only | keep |
| `Root:117` `_phase.update` (`startClosing`) | the stage | yes; throws for an unanswered root, which changes nothing | no | — | keep CAS |
| `Root:130-132` read, then `compareAndSet` (`claimForOpening`) | one claim per holder | one attempt; a lost CAS is a lost claim, which is correct | reads `_session.isCompleted` before it; not needed after §1 | two openings of one id; rare | keep CAS |
| `Root:146-149` read, `trySend`, `compareAndSet` (`markOpened`) | the stage | one attempt, checked | no | none: only a claimed, unanswered phase reaches it, and nothing else moves that phase | keep |
| `Subagent:123` `_subagent.compareAndSet(null, …)` | set once | — | no | none | keep |
| `Subagent:126` `opening.compareAndSet(NotStarted, Running)` | start against stop | one attempt; the lazy job is cancelled on a lost CAS | no | setup vs close or cascade | keep CAS: a state machine whose loser must act outside any lock |
| `Subagent:138` `opening.getAndSet(Stopped)` | the first stop | no lambda | no | — | keep |
| `Subagent:187-188` read, `compareAndSet(running, Finished)` | completion against stop | one attempt | no | opening vs close or cascade | keep CAS |

No lambda has a side effect that a retry would repeat harmfully. The worst are a repeated `logger.error` for an
assertion that never fires (`Registry:367`) and a wasted allocation (`Registry:75`).

### Make every lambda pure

Five sites pass a result out through a captured `var` that the lambda must reset on each attempt
(`Registry:61,235-236,363-365`, `Holder:174,199`). The lambda is a pure function of the old snapshot, so the same
predicate applied to the snapshot that `getAndUpdate` returns gives the result:

```kotlin
fun addChild(child: SubagentSessionHolder): Boolean {
    val before = children.getAndUpdate { if (it.sealed) it else it.copy(byId = it.byId.put(child.sessionId, child)) }
    if (before.sealed) return false
    …
}

private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
    val current = _sessions.value
    current.sessions[sessionId]?.let { return it }                      // after §1: in the map means owner
    if (current.initializingSessionsCount == 0) return null
    val candidate = RootSessionHolder(sessionId, protocol, subagentScope, ::failSessionTree)   // allocated once
    return _sessions.updateAndGet { s ->
        if (sessionId in s.sessions || s.initializingSessionsCount == 0) s else s.copy(sessions = s.sessions.put(sessionId, candidate))
    }.sessions[sessionId]
}
```

The second sketch still looks the id up on every attempt, which the comment at `Registry:62-65` asks for.

### Overall

- **Keep CAS.** Each site is one small transition on an immutable snapshot, contention is negligible, and the losers
  of the state-machine CASes must act outside any critical section anyway.
- **Don't mix in a lock.** The area has none, and adding one beside six atomics would add a second style without
  removing an atomic.
- **Fix the actual weakness:** lambdas that read other atomics. After §1 the `_sessions` lambdas read only their
  snapshot, except the reap's read of `session.isCompleted`. After §2 no lambda walks another holder's `children`.
- The earlier finding that soundness needs every cleanup writer to allocate a fresh `SessionsStorage`
  (`client-register-child-race.md`, "The invariant that makes it sound") no longer applies to `registerChild`: it
  now reads `storage.sessions[root.sessionId] !== root` from its own snapshot (`Registry:243`), not a flag.

## Suggested order

1. §6 pure lambdas (no behaviour change, about −10 lines).
2. §1 remove a failed root's tree when it fails (about −30 lines, two small behaviour changes in corners).
3. Item 23 helper (about −15 lines, touches `Client.kt`).
4. §2 one snapshot for the tree (about −40 to −60 lines, revisits R17 D1; needs the user's agreement).
5. §4 narrowing, only if the delay of unconnected updates during a long load matters to the user.

## Open questions

- §2 and §4's side buffer revisit user decisions (R17 D1, M4 (1)). They need the user's answer before any slice.
- §1 makes a concurrent opening of a failing id from another coroutine fail while the cascade drains. If that
  matters, the root's entry can leave the map right after the root's own drain, at the price of the second update
  that §1 otherwise removes.
