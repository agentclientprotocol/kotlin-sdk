# How should the v1 client's session registry synchronize its state?

**Scope:** `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/` at `b7da571` (branch
`eugenethedev/subagents`, clean tree), plus the callers in `client/Client.kt` and `ClientSessionImpl.kt`.
Analysis only. **Nothing was run**: no build, no test. Every claim comes from reading the code.

**Abbreviations:** `Registry` = `ClientSessionRegistry.kt`, `Holder` = `ClientSessionHolder.kt`,
`Root` = `RootSessionHolder.kt`, `Subagent` = `SubagentSessionHolder.kt`, `Impl` = `ClientSessionImpl.kt`,
`Client` = `client/Client.kt`, `Protocol` = `protocol/Protocol.kt`, `state` = `.agents/state.md`.
RL = the read loop and the non-suspending first part of a handler, which never overlap each other
(`Protocol:70`, `:441-443`, `:596-601`).

## Summary and recommendation

All three designs work. I found no interleaving that breaks the current code, and none that breaks either
alternative. They differ in what keeps the cross-variable rules true: written ordering arguments (C), one writer
lock (A), or one value (B). **I recommend A**, with the lock passed to the holders. The rule the user is adopting
sends this site to "lock, or fold into one snapshot" at step 0, because `registerChild` checks three variables
(`_sessions`, the parent's `children`, the root's `_phase`) and returns several results. A is the smallest change
that satisfies it: no behaviour change, no user decision reopened except the words "No lock" in M4 answer (2)
(`state:29-31`), about 3 test lines touched. B gives the cleaner end state and removes about twice as much code,
but it reopens R17 D1 (`state:1019-1023`), changes one behaviour that a test pins
(`ClientSubagentCloseTest.kt:265`), and touches about 33 test call sites. A does not block B: B's data model can
later replace the two-place tree under the same lock. C stays valid only as a documented exception to the rule, and
suggestion #7 has one trap in it (see "C: what #7 must not touch").

| | C: status quo + #7 | A: writer lock + published snapshot | B: one snapshot, pure CAS |
|---|---|---|---|
| Works | yes, by 3 ordering arguments | yes, by mutual exclusion | yes, by one value per rule |
| Interleavings checked | C1-C7 | A1-A10 | B1-B8 |
| Cross-variable arguments left | 3 (+ read-loop order for markers) | 0 (+ read-loop order for markers) | 1, the `CLOSING` pair, or 0 if folded (+ read-loop order) |
| Rollback `removeChild` | stays | goes | goes |
| Impure CAS lambdas left in the client registry and holders | 2 (reads only: `Registry:319`, `:399`) | 0 (no lambdas) | 1 (`Registry:399`) |
| Behaviour change | none | none | 1 corner: a setup that ends after its parent was sealed |
| User decisions reopened | none | "No lock" in M4 (2) | R17 D1; partly R17 D2 and D2 item 9 of 2026-11-21 |
| Product lines (est.) | about -12 | about -25 to -35 | about -45 to -60 |
| Tests touched | 0 | 3 constructor helpers | about 33 call sites, 2 tests rewritten, 1 assertion flipped |
| Risk | low | low to medium | medium |
| Rule step 0 | fails (invariants held by ordering) | passes (lock) | passes (fold) |
| Rule steps 1-2 | fine | not applicable | fine: O(sessions) scans in rare lambdas |
| Rule step 3 | fine | lock taken on the read loop, never nested, never suspends | fine |
| Rule step 4 | arguments in comments | straight-line check then write; tree still in two places | tree in one place; two walks move into the registry |

## Facts the three assessments rest on

### Who writes what, and where it runs

| State | Write | Runs in |
|---|---|---|
| `_sessions` map (`Registry:51`) | speculative create, `Registry:65-83` | RL handler (`Registry:197`), response hook (`Registry:292`, `Client:644`), opening caller (`Registry:278`) |
| | `registerChild`, `Registry:244-253` | RL handler (`Registry:218`) |
| | `removeSessionTree`, `Registry:118-125` | closing caller (`Client:99`) |
| | `failRootTree`, `Registry:316-321` | failed opening's caller (`Client:675`), reap (`Registry:412`) |
| `openingCallsCount` (`Registry:49`) | `Registry:382`, `:387-408` | opening caller |
| `children` (`Holder:128`) | `addChild` `Holder:173`, `removeChild` `Holder:188` | RL handler, through `registerChild` |
| | `listSubagent`, `Holder:198` | a consumer (`Holder:321`): the opening caller, a handler continuation, or a subagent's opening job |
| | `sealChildren`, `Holder:208` | closing caller (`Registry:104`); any cascade (`Registry:341`), which a consumer or an opening job can start (`Holder:304`, `:334`, `Subagent:213`) |
| `_phase` (`Root:86`) | claim, `Root:138` | opening caller (`Registry:279`) or hook (`Registry:293`) |
| | answer, `Root:155` | hook (`Client:645`) |
| | closing, `Root:122` | closing caller (`Impl:136`) |
| `opening` (`Subagent:63`) | `Subagent:120`, `:132`, `:205` | consumer; cascade or removal; opening job |

Two refinements of "per-message traffic only reads `_sessions`":
- It holds for a connected session: `Registry:59` returns from the fast path.
- An update for a session the client is not connected to falls into `_sessions.update` and returns the same
  storage (`Registry:78-81`). atomicfu's `update` then does a successful `compareAndSet(cur, cur)`
  (`client-register-child-race.md`, "Verdict", cites `AtomicFU.common.kt:162-167`). So that path writes the atomic
  once per message today. All three sketches below return before it.

### The rules that span several variables today

1. **A child registers only under a live root and an unsealed parent, with a free id.** `children` and
   `_sessions`. Kept by `addChild` first (`Registry:239`), then the map update that checks the root's entry
   (`Registry:250-252`), then the rollback (`Registry:259`).
2. **A close removes the whole tree.** Each holder's seal and `_sessions`. Kept by the top-down seal walk before
   the map update (`Registry:100-107`, `:114-125`).
3. **A cascade's last update removes every descendant.** `children` is read inside the `_sessions` lambda
   (`Registry:319`).
4. **A child registered during a close has its permissions closed.** `_phase` and `children`. Kept by a
   write-then-read pair: `Root:122` then `:125`, against `Holder:173` then `:180`.
5. **Each subagent gets one `Opened` marker.** `_phase`, `children` and the child's queue (`Root:155-159`,
   `Subagent:73-77`). Kept by read-loop order, not by atomics: both sides run on RL. No design changes this,
   because the marker's place in a queue is a wire-order fact (`AGENTS.md:185`).

Single-variable state that no design touches: the claim and the answer in `_phase` (`Root:133-139`, `:151-156`),
the opening state (`Subagent:115-132`, `:203-206`), the queue, `lateDeliveries`, the permission signals.

### What must never run under a lock, and where it is today

- Completing a deferred: `permissions.close()` (`PendingPermissions.kt:52`), called at `Holder:180`,
  `Registry:129`, `Root:123-125`; `_session.completeExceptionally` (`Holder:236`).
- Cancelling a job, which runs completion handlers inline: `Registry:357`.
- App code: the global handler in `failHolder` (`Registry:371-373`).
- Logging: `Registry:260`, `:390`.

The client has no `SynchronizedObject` today, so nothing can nest. The only one in `:acp` is the agent's
(`agent/internal/SubagentSessionWrapper.kt:55`). `ClientNegotiator.kt:106` is a `Mutex` and is not held around any
registry call.

## Design C: status quo plus suggestion #7

### Does it work

Yes. Interleavings checked against the current code:

- **C1, announcement against a close.** `addChild` succeeds only before its parent's seal (`Holder:173-177`), so
  the child is in the walk's `subtree` (`Registry:101-107`). If its map update commits before the removal's, the
  removal takes it by identity (`Registry:121`). If after, it sees the root's entry gone (`Registry:250`) and
  returns `null`; the removal still stops and fails it (`Registry:110`, `:128-132`).
- **C2, announcement against a failing root.** `failTree` seals each holder when it reaches it (`Registry:341`).
  A child added before that seal is in the returned list and is failed (`Registry:343-346`). The last update
  removes it (`Registry:319`).
- **C3, announcement under a holder that the cascade skipped.** The cascade skips a child whose opening was
  already stopped or completing (`Registry:345`), so that child may be unsealed when the last update runs. This is
  still sound, for a reason the code does not state. A grandchild's `addChild` comes before its own map update
  (`Registry:239`, `:244`). If that update commits first, the cascade's CAS fails, its lambda reruns and reads
  `children` again (`Registry:319`), and it removes the grandchild. If it commits later, it sees the root gone.
- **C4, announcement against the reap.** The reap removes the root's entry in the count update (`Registry:401-404`)
  and fails the tree afterwards (`Registry:409-413`). A child that committed before is failed by the cascade and
  removed by `Registry:319`. One that commits after sees the root gone.
- **C5, id taken.** The lambda leaves the storage (`Registry:251-252`), and `removeChild` takes the child back
  out (`Registry:259`). A cascade that sealed the parent in between fails a holder that never entered the map,
  which changes nothing, because every removal compares by identity (`Registry:121`, `:318-319`).
- **C6, announcement against `startClosing`.** Rule 4 above. Atomic reads and writes are sequentially consistent,
  so at least one side sees the other.
- **C7, two closes, or a close against a subagent's own failure.** The first `markOpeningStopped` settles the
  holder (`Subagent:132`, `Registry:353-362`), and a second removal removes nothing (`Registry:133`).

### What #7 changes here

Five lambdas lose their captured `var`s: the lookup (`Registry:60-84`), `registerChild` (`Registry:242-253`), the
reap (`Registry:386-408`), `addChild` (`Holder:172-176`), `listSubagent` (`Holder:197-201`). The lookup stops
allocating a `RootSessionHolder` per attempt (`Registry:74`). About -12 lines in these two files.

### What stays

- Rules 1-4 stay as ordering arguments, with their comments (`Registry:100`, `:114-117`, `:245-249`, `:255`,
  `:258`, `:312-315`, `Holder:179`, `Root:124`) and two `AGENTS.md` bullets (`AGENTS.md:184`, `:190`).
- The `removeChild` rollback.
- `isOpen()` then `startClosing()` are two steps (`Impl:127`, `:136`). The gap is harmless: `startClosing` is
  idempotent (`Root:69-73`), and a second removal removes nothing.

### C: what #7 must not touch

`Registry:319` reads other holders' `children` inside the `_sessions` lambda. #7's proposed rule, "a CAS lambda is
a pure function of the old value" (`simplify-final-report.md:434-435`), would flag it. The read is load-bearing
(C3): hoisting the walk out of the lambda lets a grandchild that registers between the walk and the update stay
in the map for ever, with its id taken. The comment above it, "the cascade sealed every holder, so the subtree is
final" (`Registry:313-314`), is not exact for a skipped holder, so it invites that hoist. Confidence: by reading.

### Conformance, lines, risk, tests

- Rule: fails step 0 at rules 1-4. Steps 1-4 are fine: retried lambdas are map steps, writers are rare.
- Lines: `Registry` about -8, `Holder` about -4. Risk: low. Tests: none expected.

### Sketch

```kotlin
private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
    _sessions.value.sessions[sessionId]?.let { return it }
    // Created at most once, however often the update reruns.
    val speculative by lazy(LazyThreadSafetyMode.NONE) { RootSessionHolder(sessionId, protocol, subagentScope, ::failSubagentTree) }
    return _sessions.updateAndGet { storage ->
        if (sessionId in storage.sessions || storage.openingCallsCount == 0) storage
        else storage.copy(sessions = storage.sessions.put(sessionId, speculative))
    }.sessions[sessionId]
}

private fun registerChild(parent: ClientSessionHolder, childId: SessionId): SubagentSessionHolder? {
    val child = SubagentSessionHolder(childId, parent)
    if (!parent.addChild(child)) return null

    val root = parent.root
    val before = _sessions.getAndUpdate { storage ->
        val registers = storage.sessions[root.sessionId] === root && childId !in storage.sessions
        if (registers) storage.copy(sessions = storage.sessions.put(childId, child)) else storage
    }
    if (before.sessions[root.sessionId] !== root) return null   // the removal or cascade fails the child
    if (childId in before.sessions) {
        parent.removeChild(childId)
        logger.warn { "…" }
        return null
    }
    return child
}
```

`removeSessionTree` and the close path stay as they are (`Registry:99-135`, `Impl:122-150`, `Root:121-126`).

## Design A: one writer lock, published immutable snapshot

One `SynchronizedObject` in the registry. Every write of `_sessions` and of a holder's `children`, and the
`CLOSING` transition, happens under it. `_sessions` and `children` stay atomics, so every reader keeps its
lock-free load.

### Does it work

- **Memory model.** A write under the lock is still a volatile store of an immutable value (`SessionsStorage`,
  `Children` and the persistent collections have only final fields). A lock-free reader gets the same guarantee as
  from a CAS today.
- **Stale reads.** A reader can load the snapshot from just before a write. That is the state today: the caller
  already handles a holder that failed or left since the lookup (`Registry:199-202`).
- **Double-checked fast path.** Sound, because the field is volatile and the value immutable. The lookup reads the
  published snapshot, returns a hit, returns `null` when no opening runs, and only then takes the lock and reads
  again. An unconnected session's updates never take the lock.
- **Readers of two variables.** Publishing `children` and `_sessions` in one section is two stores. No lock-free
  reader needs both consistent: `queuedItemFor` and `holderForRequest` run on RL, like the registration that
  writes them (`Registry:209-221`, `:154-164`); `markOpened`'s walk runs on RL (`Root:159`); `isOpen` reads only
  the map (`Registry:91`). The other walks (`Root:125`, `Registry:101-107`, `:319`) move under the lock.

Interleavings checked:

- **A1, announcement against a close.** The registration and the removal are two sections. Registration first:
  the removal's walk finds the child. Removal first: the registration sees the root's entry gone and adds nothing.
- **A2, against a failing root.** `failTree`'s seal is a section. Registration first: the child is in the sealed
  list and the cascade fails it. Seal first: `addChild` refuses.
- **A3, under a holder the cascade skipped.** The last removal walks the tree under the lock, so it sees every
  child that registered, and a later registration sees the root gone. The C3 argument goes.
- **A4, against the reap.** As C4, with the root's entry checked under the lock.
- **A5, id taken.** Checked before any write. Nothing is added, so nothing is rolled back and no cascade can see a
  rejected child.
- **A6, against `startClosing`.** `startClosing` sets the stage and collects the descendants in one section. A
  registration before it is collected; one after it reads `CLOSING` in its own section. The write-then-read pair
  goes.
- **A7, speculative create against a registration of the same id.** Both are sections. The second one finds the
  id taken, which is what the CAS gives today.
- **A8, a setup's listing against a seal.** Both are sections; same result as the single CAS today
  (`Holder:198-201`).
- **A9, the hook waiting for an application thread's section.** Bounded: a section calls nothing outside and never
  suspends. One lock, so no lock order.
- **A10, reentrancy.** Nothing completes a deferred or cancels a job under the lock, so no continuation or
  completion handler can run inside a section.

### Which writes move under the lock

| # | Section | Today | File |
|---|---|---|---|
| 1 | speculative create (after the lock-free miss) | `Registry:65-83` | `Registry` |
| 2 | `registerChild`: root entry, id, `addChild`, map put, read of `CLOSING` | `Registry:239-253`, `Holder:173-180` | `Registry` |
| 3 | `removeSessionTree`: seal walk and map removal, as one section | `Registry:101-107`, `:118-125` | `Registry` |
| 4 | `failTree`: `sealChildren` | `Registry:341` | `Registry` |
| 5 | `failRootTree`: walk and map removal | `Registry:316-321` | `Registry` |
| 6 | count up | `Registry:382` | `Registry` |
| 7 | count down and the reap's choice | `Registry:387-408` | `Registry` |
| 8 | `startClosing`: stage and the list of descendants | `Root:122`, `:125` | `Root` |
| 9 | `listSubagent` | `Holder:198-201` | `Holder` |

All `_sessions` writes move, including the count. A lock holder that stores a new value would lose a concurrent
CAS, so "the lock only where the rule forces it, CAS for the rest" does not work on one atomic.

What does not move: the claim and the answer in `_phase`, the opening state, `markOpened` (rule 5).

### Can the lock be taken at each site

- **Inside app code:** no site. `listSubagent` runs after `createSubagentOperations` has returned
  (`Holder:308-321`). The cascades call the app only in `failHolder`, after the seal (`Registry:341-342`).
- **Inside another lock:** none exists on the client (see "What must never run under a lock").
- **Inside a CAS lambda:** no site is called from one.
- **While completing a deferred:** no site runs in a completion handler. The only handler on the client is
  `PendingPermissions.kt:180`, which touches no registry state.
- **On the read loop:** sections 1 and 2. Section 1 runs in the response hook for every `session/new` and
  `session/fork` answer whose id had no early update (`Registry:292`, `Client:644`), and in a handler for the first
  early update of an unknown id. Section 2 runs once per announcement. `AGENTS.md:161` says the hook "must not
  suspend, block or throw". A section waits only for another section, so the wait is bounded, but the sentence
  needs one clause: the hook may take the registry's lock.

What each section must keep outside, which the sketches do:

- `child.permissions.close()`: today inside `addChild` (`Holder:180`); it moves after the section.
- `logger.warn` and `logger.error` (`Registry:260`, `:390`).
- `stopOpening`, because of `job.cancel()` (`Registry:357`). Today it runs between the seal walk and the map
  update (`Registry:110`); it moves after the section. Outcomes are the same: an opening that completes in that
  window is found `Completing`, as one that completes just before the stop is today (`Registry:361`).
- `failHolder` (`Registry:131`, `:342`).
- Building the `SubagentSessionHolder`, whose `init` sends to its queue (`Subagent:73-77`): before the section.

### How the holders reach the lock

Sections 8 and 9 are in the holders. Two options:

- **(b) Pass the lock to the holders** (recommended). `ClientSessionHolder` gets a `treeLock` constructor
  argument next to `protocol`, `openingScope` and `failSubagentTree` (`Holder:94-99`); a subagent takes its
  parent's (`Subagent:34`). `startClosing` and `listSubagent` stay where the user put them (`state:974-977`,
  `state:1019-1023`) and take the lock themselves. `addChild` and `sealChildren` do not lock; their only product
  callers are registry sections. Cost: one more constructor argument, and "the caller holds the lock" as a KDoc
  sentence on two methods.
- **(a) Only the registry writes.** `listSubagent` becomes a second callback beside `failSubagentTree`, and
  `ClientSessionImpl` calls a registry `startClosing` through a lambda, replacing `isOpen` (`Impl:44`,
  `Client:89`). That closes the `isOpen`/`startClosing` gap, but it undoes item 9 of 2026-11-21
  (`state:974-977`).

Reentrancy is not needed with (b) as sketched: no section calls a method that locks.

### What goes

- Ordering arguments: `addChild` before the map update and "the root's entry leaves no later than its
  descendants'" (`Registry:114-117`, `:245-249`); the top-down seal (`Registry:100`); the read after the add and
  the stage before the walk (`Holder:179`, `Root:124`); "the cascade sealed every holder" (`Registry:312-315`);
  "look the id up again on every attempt" (`Registry:61-64`).
- Rollback: `removeChild` (`Holder:184-189`, `Registry:258-259`).
- Flags and captured results: `rootEnded`, `blocked` (`Registry:242-243`), `added` (`Holder:172`), `listed`
  (`Holder:197`), `clientSessionHolder` (`Registry:60`), `hangingSessions` and its reset (`Registry:386-388`).
  No state flag goes: `sealed` and `CLOSING` stay.
- `AGENTS.md:190` (the whole bullet) is replaced by one bullet: what the lock covers and what never runs under
  it. Its second sentence stays true and is worth keeping in short form: `CLOSING` does not keep a late child
  out, the root's entry does. `AGENTS.md:184` keeps its meaning; "share one atomic state" becomes "are written
  under the registry's lock".
- Suggestion #7 at its five client sites; the agent and `Protocol` sites of #7 are unaffected.

What stays: the tree in two places (written together now), read-loop order for markers, the first-stop rule, the
parent-first cascade, and the removal of a failed root after its drains (`Registry:312-313`, first sentence).

### Conformance, lines, risk, tests

- Rule: passes step 0 by the lock. Step 3: the lock is taken on the read loop and in the hook, never suspends,
  never nests, and no section calls out.
- Lines (est.): `Registry` -15 to -25, `Holder` -10 to -14, `Root` +3 to +4, `Subagent` 0, `Client` and `Impl` 0
  with option (b). `AGENTS.md`: one bullet replaced, one clause added at `:161`.
- Risk: low to medium. The semantics do not change. The new ways to go wrong are a write outside the lock (a
  lost update, silent) and a foreign call inside a section. Both are checkable by eye: `_sessions.value =` and
  `children.value =` appear only inside `synchronized(treeLock)`.
- Tests: the three helpers that build a `RootSessionHolder` get the lock argument
  (`ClientSubagentSessionTest.kt:717`, `ClientSessionHolderTest.kt:269`, `ClientSessionRegistryTest.kt:248`), or
  none if the argument has a default. The direct calls of `addChild`, `listSubagent` and `sealChildren`
  (`ClientSubagentSessionTest.kt:571-630`, `ClientSessionRegistryTest.kt:211`, `:242`) keep working if `addChild`
  keeps returning `false` for a sealed holder, as sketched. `ClientSessionRegistryTest.kt:125-143` is the
  regression test for A6 and should pass unchanged. No new deterministic test is possible for the races
  themselves (`client-register-child-race.md`, "Q3").

### Sketch

```kotlin
// Registry
private val treeLock = SynchronizedObject()

// Written only under [treeLock], with the holders' children; read without it.
private val _sessions = atomic(SessionsStorage())

private fun getOrCreateSessionHolderOrNull(sessionId: SessionId): ClientSessionHolder? {
    val published = _sessions.value
    published.sessions[sessionId]?.let { return it }
    if (published.openingCallsCount == 0) return null

    return synchronized(treeLock) {
        val storage = _sessions.value
        // Read again: the id may have been registered, or the last opening may have ended, since the read above.
        storage.sessions[sessionId] ?: if (storage.openingCallsCount == 0) null else {
            val speculative = RootSessionHolder(sessionId, protocol, subagentScope, treeLock, ::failSubagentTree)
            _sessions.value = storage.copy(sessions = storage.sessions.put(sessionId, speculative))
            speculative
        }
    }
}

private enum class Registration { REGISTERED, REGISTERED_WHILE_CLOSING, PARENT_ENDED, ID_TAKEN }

private fun registerChild(parent: ClientSessionHolder, childId: SessionId): SubagentSessionHolder? {
    val child = SubagentSessionHolder(childId, parent) // takes the root's opened marker if answered
    val root = parent.root
    val registration = synchronized(treeLock) {
        val storage = _sessions.value
        when {
            storage.sessions[root.sessionId] !== root -> Registration.PARENT_ENDED
            childId in storage.sessions -> Registration.ID_TAKEN
            !parent.addChild(child) -> Registration.PARENT_ENDED   // sealed; checked last, so nothing is rolled back
            else -> {
                _sessions.value = storage.copy(sessions = storage.sessions.put(childId, child))
                if (root.phase.isAtLeast(Stage.CLOSING)) Registration.REGISTERED_WHILE_CLOSING else Registration.REGISTERED
            }
        }
    }
    return when (registration) {
        Registration.REGISTERED -> child
        // Still in the handler's first part, so no request for the child is handled before this.
        Registration.REGISTERED_WHILE_CLOSING -> child.also { it.permissions.close() }
        Registration.PARENT_ENDED -> null
        Registration.ID_TAKEN -> {
            logger.warn { "…" }
            null
        }
    }
}

suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
    val (subtree, removedIds) = synchronized(treeLock) {
        val subtree = buildList {
            fun visit(holder: ClientSessionHolder) {
                add(holder)
                holder.sealChildren().forEach(::visit)   // sealed for a setup that returns later
            }
            visit(root)
        }
        val storage = _sessions.value
        val owners = subtree.filter { storage.sessions[it.sessionId] === it }
        _sessions.value = storage.copy(sessions = storage.sessions.mutate { map -> owners.forEach { map.remove(it.sessionId) } })
        subtree to owners.mapTo(mutableSetOf()) { it.sessionId }
    }

    // Running openings are cancelled, not joined, so a subagent's `notify` may still run after this returns.
    val settlesHolder = subtree.filterIsInstance<SubagentSessionHolder>().associateWith(::stopOpening)
    return withContext(NonCancellable) {
        for ((holder, settles) in settlesHolder.entries.reversed()) {
            holder.permissions.close()
            if (settles) failHolder(holder, AcpExpectedError("Session ${holder.sessionId} closed")) else holder.markClosed()
        }
        removedIds
    }
}

// Holder: the caller holds the tree lock.
fun addChild(child: SubagentSessionHolder): Boolean {
    val current = children.value
    if (current.sealed) return false
    children.value = current.copy(byId = current.byId.put(child.sessionId, child))
    return true
}

// Root; `ClientSessionImpl.close` stays as it is (`Impl:122-150`).
fun startClosing() {
    val descendants = synchronized(treeLock) {
        _phase.update { current -> checkNotNull(current.closingOrNull()) { "Root session $sessionId is closed before its answer" } }
        buildList { forEachDescendant(::add) }
    }
    permissions.close()
    descendants.forEach { it.permissions.close() }
}
```

## Design B: the tree only in the registry's snapshot, pure CAS

The registry's snapshot becomes the only record of the tree, as in the agent's `SessionTable`
(`agent/Agent.kt:55-66`, `:422-513`). A holder already knows its `parent` and `root` (`Subagent:33`, `:79`), so the
map answers "children of P" and "descendants of R".

### What moves, and what `subagents` becomes

- **Out of the holder:** `Children.byId` and `Children.sealed` (`Holder:107-111`), `childOrNull` (`:164`),
  `addChild` (`:171-182`), `removeChild` (`:187-189`), `sealChildren` (`:208`), `forEachDescendant` (`:213-218`).
- **Into the snapshot:** a `sealed` set of failing or failed holders beside the map. A close needs no seal: its
  one update removes the tree, and a later registration sees the root gone.
- **`subagents` stays in the holder**, as its own atomic list (`Holder:134`). It cannot move into the snapshot: it
  must outlive the map entry, because a closed session still lists its subagents (`AGENTS.md:195`).
- **Into the registry:** the two walks that `Root` does today. `markOpened`'s (`Root:159`) and `startClosing`'s
  (`Root:125`) need the tree, so `Client:645` and `Impl:136` call the registry, or `Root` gets a "descendants"
  callback. Suggestion #18 notes this cost (`simplify-final-report.md:961-963`).
- **Outside, unchanged:** `_phase`, the opening state, the queue, the deferred, the permission signals.

### Does it work

- **B1, announcement against a close.** Two updates of one atomic. Registration first: the child is in the
  snapshot that the removal replaced, so it is stopped and failed. Removal first: `accepts` is false.
- **B2, against a failing root.** The seal of P is `getAndUpdate`, and P's children are read from the snapshot it
  replaced. Registration first: in that list. Seal first: refused, P is in `sealed`.
- **B3, under a holder the cascade skipped.** The last removal computes the descendants from the snapshot inside
  its own lambda, with no read of another atomic. The C3 argument goes.
- **B4, against the reap.** The reap removes the root's entry; `accepts` checks it. The cascade still finds the
  children, because `childrenOf` filters by `parent`, not by the root's entry. `sealed` must drop the root and its
  descendants in the last removal, or it leaks.
- **B5, id taken.** `accepts` is false; nothing was added.
- **B6, a setup's listing against a seal.** This is the behaviour change, see below.
- **B7, markers.** `markOpened` reads `_sessions.value` on RL, and registrations run on RL. Same order as today.
- **B8, against `startClosing`.** With `CLOSING` left in `_phase`, the write-then-read pair stays, between
  `_phase` and `_sessions` instead of `_phase` and `children`. Folding it in (a `closing` set in the snapshot,
  `Stage.CLOSING` removed) makes it one update too, at the price of reopening M4 answer (2) (`state:29-31`).

### The behaviour change

Today a setup that ends after its parent was sealed is not listed (`Holder:321-324`, `AGENTS.md:195` last
sentence). Without the per-holder seal, the listing has nothing to check in the same step. Two forms:

- **List always.** `startOpening` finds the opening stopped and closes the session (`Subagent:120-126`), so the
  subagent is listed and closed. That matches M4 answer (3) (`state:32-34`), but it flips
  `ClientSubagentCloseTest.kt:265` ("the closed parent's list stays as it was").
- **Check the snapshot, then list.** The test still passes, but check and append are two steps again, with
  "listed and closed" in the window between them.

### What goes, what stays

- Goes: rules 1-3 as arguments, the rollback, the seal walk, `listSubagent`'s seal check, `AGENTS.md:190`, the
  second sentence of `AGENTS.md:184`, and the last sentence of `AGENTS.md:195` (or it changes).
- Stays: read-loop order for markers; the `CLOSING` pair unless folded; the reap's read of `session.isCompleted`
  inside its lambda (`Registry:399`), which is stable there because no opening runs at count zero.

### Conformance, lines, risk, tests

- Rule: passes step 0 by the fold, fully only with `CLOSING` folded in. Step 1: `childrenOf` and `descendantsOf`
  scan every session of the connection inside a lambda. They run once per close, cascade step or answer, against
  rare writers, so a rerun is cheap and unlikely. The per-message `childOf` is one map lookup, as today.
- Lines (est.): `Holder` -50 to -60, `Registry` +5 to +10 (helpers and the two walks in, the rollback and the seal
  walk out), `Root` about -5, `Client` and `Impl` about +3. Suggestion #18 estimates -40 to -60 in total
  (`simplify-final-report.md:936-937`).
- Risk: medium. It changes the cascade's inputs, which the user chose not to refactor further on 2026-11-26
  (`state:109-111`), and it relies on `persistentMapOf()` iterating in insertion order for the bottom-up order
  that `ClientSessionRegistryTest.kt:232-236` pins. I did not check that in the library's sources.
- Tests: 22 `childOrNull` calls on holders (`ClientSessionRegistryTest.kt:101-615`,
  `ClientSubagentSessionTest.kt:627-628`) need a registry accessor. Part 2 counted about 14; the number has grown.
  The four `addChild` assertions (`ClientSessionRegistryTest.kt:211`, `:242`, `ClientSubagentSessionTest.kt:620`,
  `:625`) and the two `sealChildren` calls (`:623`, `:630`) need rewriting through `deliverSessionUpdate`. The test
  `a sealed holder keeps its subagents and refuses later children and listings`
  (`ClientSubagentSessionTest.kt:616-631`) is rewritten or dropped, and `ClientSessionHolderTest.kt:228-244` moves
  if `startClosing` moves.

### Sketch

```kotlin
private data class SessionsStorage(
    val openingCallsCount: Int = 0,
    val sessions: PersistentMap<SessionId, ClientSessionHolder> = persistentMapOf(),   // insertion order: parents first
    val sealed: PersistentSet<ClientSessionHolder> = persistentSetOf(),                // failing or failed: take no child
) {
    fun childOf(parent: ClientSessionHolder, id: SessionId): SubagentSessionHolder? =
        (sessions[id] as? SubagentSessionHolder)?.takeIf { it.parent === parent }

    fun childrenOf(parent: ClientSessionHolder): List<SubagentSessionHolder> =
        sessions.values.filterIsInstance<SubagentSessionHolder>().filter { it.parent === parent }

    fun descendantsOf(root: RootSessionHolder): List<SubagentSessionHolder> =
        sessions.values.filterIsInstance<SubagentSessionHolder>().filter { it.root === root }

    fun accepts(parent: ClientSessionHolder, childId: SessionId): Boolean =
        sessions[parent.root.sessionId] === parent.root && parent !in sealed && childId !in sessions

    fun withoutTree(root: RootSessionHolder): SessionsStorage = copy(
        sessions = sessions.mutate { map ->
            if (map[root.sessionId] === root) map.remove(root.sessionId)
            descendantsOf(root).forEach { map.remove(it.sessionId) }
        },
        sealed = sealed.removeAll { it.root === root },
    )
}

private fun registerChild(parent: ClientSessionHolder, childId: SessionId): SubagentSessionHolder? {
    val child = SubagentSessionHolder(childId, parent) // takes the root's opened marker if answered
    val before = _sessions.getAndUpdate { storage ->
        if (storage.accepts(parent, childId)) storage.copy(sessions = storage.sessions.put(childId, child)) else storage
    }
    if (!before.accepts(parent, childId)) return null   // warn when only the id was taken

    // Read after the registration: `startClosing` sets the stage before it reads the tree.
    if (parent.root.phase.isAtLeast(Stage.CLOSING)) child.permissions.close()
    return child
}

suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
    val before = _sessions.getAndUpdate { it.withoutTree(root) }
    val subagents = before.descendantsOf(root)
    val settlesHolder = subagents.associateWith(::stopOpening)
    return withContext(NonCancellable) {
        // settle bottom-up, as today (`Registry:127-132`)
        buildSet {
            if (before.sessions[root.sessionId] === root) add(root.sessionId)
            subagents.mapTo(this) { it.sessionId }
        }
    }
}

// The close path: `Impl:136` calls this through a lambda instead of `holder.startClosing()`.
fun startClosing(root: RootSessionHolder) {
    root.startClosing()   // the stage and the root's own permissions (`Root:122-123`)
    _sessions.value.descendantsOf(root).forEach { it.permissions.close() }
}

// In `failTree`, instead of `failing.sealChildren()`:
val children = _sessions.getAndUpdate { it.copy(sealed = it.sealed.add(failing)) }.childrenOf(failing)
```

The speculative lookup is the same as in C.

## Open questions for the user

1. **A lock on the read loop.** A takes the registry's lock in the response hook of every `session/new` and
   `session/fork`, and in the first part of an announcement's handler. Is a bounded, non-suspending lock acceptable
   there, with `AGENTS.md:161` amended? If not, only B and C remain.
2. **How the holders reach the lock** (A): the lock as a constructor argument (b), or registry-only writes through
   callbacks (a), which also folds `isOpen` and `startClosing` into one step but undoes item 9 of 2026-11-21.
3. **Reopen R17 D1?** B needs it, and needs a choice for the listing corner: list always (flips
   `ClientSubagentCloseTest.kt:265`) or check then list (two steps again).
4. **Fold `CLOSING` into B's snapshot?** Without it B keeps one write-then-read pair and passes step 0 only in
   part. With it, M4 answer (2) is reopened.
5. **One style for both sides.** The agent's `SessionTable` is still pure CAS at this tip (`agent/Agent.kt:422-513`),
   and `state:488-491` lists it as "tie → lock + snapshot". If it moves to a lock, A matches it; if it stays CAS,
   B matches it.
6. **#7 after A.** A supersedes #7 at five client sites. Should #7 still go ahead for the agent and `Protocol`
   sites, and should its "pure lambda" rule name `Registry:319` as an exception if C is kept?
7. **The deferred close follow-up** (`state:1042-1045`, removal in the close response hook) would add one more
   read-loop site: a lock section under A, a CAS under B. Neither design blocks it.

Follow-ups noticed, not part of this question:

- The comment at `Registry:313-314` is not exact for holders the cascade skips (see "C: what #7 must not touch").
- `failRootTree` can run for a root whose `_session` has already completed, if the caller is cancelled between
  `Root:237` and the end of `coroutineScope` (`Client:635-664`). `failHolder` then changes nothing on the root, and
  its tree leaves the map. Plausible from reading; not run. It is the same under all three designs.

## Not verified

- Nothing was compiled or run.
- That atomicfu's `SynchronizedObject` behaves the same on every target of the build (`jvm`, `js`, `wasmJs`,
  `iosArm64`, `iosSimulatorArm64`; `build-logic/src/main/kotlin/acp.multiplatform.gradle.kts:34-40`). The agent
  already uses it in common code (`agent/internal/SubagentSessionWrapper.kt:55`), so it compiles there.
- That `persistentMapOf()` iterates in insertion order (kotlinx-collections-immutable 0.3.8,
  `gradle/libs.versions.toml:6`). B's bottom-up order depends on it.
- The line estimates are counts from the sketches against the current functions, not from a real diff.
