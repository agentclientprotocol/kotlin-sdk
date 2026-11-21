# Does the v1 client's `ClientSessionRegistry.registerChild` have the agent-side check-then-act race?

**Sources checked:** this repo, branch `eugenethedev/subagents` @ `b9a7963` (working tree clean);
atomicfu 0.25.0 sources (`gradle/libs.versions.toml:13`), kotlinx-collections-immutable 0.3.8
(`gradle/libs.versions.toml:6`). No upstream ACP source applies: this is an SDK-internal concurrency question.
**Confidence:** high. Every interleaving below reduces to two facts I checked in source: atomicfu `update` CASes
by identity, and every `_sessions` writer that follows a flag write allocates a new `SessionsStorage`.

## Verdict

**No hole.** The agent-side bug needs the cleanup's CAS to leave the reference unchanged. On the client, that
can't happen. `_sessions` holds a `SessionsStorage` data-class wrapper (`ClientSessionRegistry.kt:45-47`), and
each cleanup writer returns `storage.copy(...)`. That is always a new instance, even when the inner
`PersistentMap` comes back unchanged from `mutate`:
- removal: `ClientSessionRegistry.kt:181`;
- reap of descendants: `:480`;
- count decrement / reap of roots: `:457`, `:459`;
- replacing: `:81`.

atomicfu `update` loops on `compareAndSet(cur, upd)` (atomicfu `commonMain/kotlinx/atomicfu/AtomicFU.common.kt:162-167`),
and the JVM CAS is `AtomicReferenceFieldUpdater.compareAndSet`, which compares by identity
(`jvmMain/kotlinx/atomicfu/AtomicFU.kt:105-106`). So once a cleanup has set its flag and done its update, any
registration attempt that read the stale flag fails its CAS, retries, and reads the flag as set.

`registerChild` does read other atomics' `.value` inside its update lambda (Q2: yes). Each read is sound because
of the invariant below, not because the reads are atomic with the CAS.

## The invariant that makes it sound

For each way a root's tree can end, the following hold in this order:
1. (a) Every holder's children map is closed, or the tree walk reads it, before (b).
2. (b) The flag that `registerChild` reads inside its lambda becomes true.
3. (c) A reference-changing `_sessions` update removes every descendant reachable through `children.byId`, each
   only if its id still maps to it.

A registration first does `addChild` (`:318`). Either that fails because the parent is closed (`ClientSessionHolder.kt:303-306`),
or the child is in the tree that (c) walks. Then the registration's CAS falls into one of two cases:
- It commits before (c). (c)'s successful lambda reads the post-registration storage, finds `map[child.id] === child`,
  and removes the child.
- It commits after (c). Its successful attempt read a storage reference written at or after (c), so it
  happens-after (b), reads the flag as true, and doesn't commit.

## Q1, per read

### `root.isRemoved` (`:331`) vs `removeSessionTree` (`:145-195`)
- (a) The top-down `closeChildren` walk (`:148-154`) builds `subtree`.
- (b) `markRemoved()` (`:170`).
- (c) The update at `:171-182`, which always writes a new wrapper (`:181`).

Concrete interleaving modeled on the agent example. The registration R reads `s0` and `isRemoved = false`, and
computes `s1 = s0 + C`. Then the removal does `markRemoved`, reads `s0`, and removes nothing because every subtree
holder is absent or replaced. Its `mutate` returns the same `PersistentMap`, but `storage.copy` gives `s0' !== s0`,
and `CAS(s0, s0')` succeeds. R then runs `CAS(s0, s1)`, which fails. R retries, reads `isRemoved = true`, and
returns `null`.

In practice the inner map isn't a no-op anyway: an open root blocks its own id (`ClientSessionHolder.kt:187`), so
the removal at least removes the root. The wrapper copy makes that fact irrelevant.

A child that ends up with `rootEnded` is in `subtree`: its `addChild` came before its parent's `closeChildren`,
and that came before `markRemoved`. So the bottom-up loop (`:185-190`) closes its permissions, marks it closed,
and fails it.

Could the parent be in the session map but missing from `subtree`? A holder enters the map only after `addChild`
to its own parent succeeded (`:318` before `:324`). `removeChild` (`:341`) runs only for a child that never
entered the map. Holders are found by map lookup (`:261`), so by induction from the root every parent is in
`subtree`.

### `root.isFailed` (`:331`) vs the failure cascade plus reap or replace
- (a) `failSessionTree` calls `closeChildren` per holder (`:415`).
- (b) The root's deferred fails at the end of the root's `failAndDrainQueue` (`ClientSessionHolder.kt:401`).
  That is after the root's own `closeChildren`, but before the children are recursed (`:423-425`).
- (c) Either the reap's descendant update (`:479-481`, new wrapper, runs after `failSessionHolder` returns at
  `:471`), or `replacing` → `removeDescendantsOf` (`:81`, `:492-505`).

Every root failure happens inside `withInitializingSession` (`Client.kt:635`, `:665`, `:702`, `:756`, plus the reap
at `ClientSessionRegistry.kt:471`). So the count eventually drops to 0 and the reap (c) follows, unless a replace
did (c) first.

**Edge case: the unclaimed speculative root.** The count update (`:449-457`) can remove a speculative root before
it has failed. A registration that looked the root up just before that removal (the read loop runs in parallel
with the reaper) can still `addChild` and commit C, because `isFailed` is still false. The cascade then fails C,
since C is in the root's children snapshot, and the reap at `:479` removes C. If R's CAS instead comes after
`:479`, the CAS fails and the retry sees `isFailed = true`.

**Replace during a cascade.** `replacing` can run while the cascade is still in progress: the root has failed,
but deeper holders aren't closed yet. A registration under such a holder still succeeds at `addChild`, but its
lambda reads `isFailed = true`, so it stays out of the map.

**Subagent parent failing while the root lives.** C can enter the map already failed by the cascade. That is the
same state as a child registered just before its parent failed. With a failed parent, C no longer blocks its id
(`ClientSessionHolder.kt:187`), and it leaves the map with the root's removal, reap or replace, like any failed
subagent. This is by design, not an orphan.

### `closing` (`:320`) vs `closeDescendantPermissions` (`:112-116`)
This is a correct Dekker pair:
- The registration writes its parent's children atomic (`addChild`), then reads `closing`.
- The close writes `closing` (`ClientSessionHolder.kt:223`), then reads the children atomics (`forEachDescendant`, `:328-333`).

All atomicfu accesses are volatile/sequentially consistent, so at least one side sees the other's write. Either
the walk closes C's permissions, or C's registration closes them. The walk reaches C's parent because the parent
was in its own parent's `byId` before it entered the map. The mark only closes permissions. It doesn't reject
registrations, and nothing is read inside an update lambda, so no CAS question arises. The removal also closes
every `subtree` holder's permissions at `:187`.

### Closed children map (`addChild`, `ClientSessionHolder.kt:301-308`)
The check and the act happen inside one `children.update`. `closeChildren` uses `getAndUpdate` (`:322`). So the
snapshot that `closeChildren` returns contains exactly the children whose `addChild` won. `removeChild` (`:313-315`)
keeps `closed`. Sound.

### `storage.sessions[childId]?.blocksId` (`:332`), which reads another holder's deferred and its parent's
`blocksId = !isFailed || parent?.isFailed == false` (`ClientSessionHolder.kt:187`). Both deferreds only move from
active to failed, so "doesn't block" is monotonic:
- A stale "doesn't block" stays true, so the replacement is valid.
- A stale "blocks" is a rejection that was correct when the announcement arrived. The announcement is delivered
  to the parent as a plain update (`:340-346`). That's benign.

When the rejected child was already in a removal or cascade snapshot, failing a holder that never reached the map
does nothing to the map. The removal's `map[id] === holder` check (`:175`) leaves the real owner in place.

### `root.isAnswered` (`:317`) vs `markOpened` (`ClientSessionHolder.kt:269-280`)
This pair is not safe under true parallelism. The registration reads `answered` before it writes `children`,
which is the wrong order for Dekker. In the interleaving below, C never gets its `Opened(null)` marker:
1. The registration reads `answered = false`.
2. `markOpened` sets `answered = true` and walks the descendants, which don't include C yet.
3. The registration runs `addChild(C)`.

It can't happen, because both run in read-loop order. `markOpened` runs in the opening call's response hook
(`Client.kt:659`, `:746`, `ClientSessionRegistry.kt:381`). `registerChild` runs only from the non-suspending first
part of the `session/update` handler (`:256-262`). AGENTS.md documents both orderings. This is a dependency on
`Protocol`'s ordering, not on atomics.

## Q2

Yes. Inside the `_sessions.update` lambda (`:324-334`), `registerChild` reads:
- `root.isFailed`, the root's `CompletableDeferred` state;
- `root.isRemoved`, the root's `removed` atomic;
- `storage.sessions[childId]?.blocksId`, the deferreds of another holder and of that holder's parent.

The agent-side second race was a read of another atomic inside an update lambda. Here, each of these reads is
covered: either the writer of that atomic follows up with a reference-changing `_sessions` update (`removed`,
`isFailed`), or the value is monotonic in the safe direction (`blocksId`).

## Q3

No fix is needed. One latent fragility is worth guarding. Correctness against the same-instance no-op depends
entirely on the cleanup writers returning a fresh `SessionsStorage`. A future micro-optimization would reopen
exactly the agent-side hole, for the `isRemoved` case and the reap case. An example:
`if (sessions === storage.sessions) storage else storage.copy(sessions = sessions)` at `:181` or `:480`.

Optional hardening, at the orchestrator's discretion (pick one):
- A block comment at `:171` and `:479` saying the update must always change the reference, because
  `registerChild` reads `isRemoved`/`isFailed` inside its own update.
- A stronger structural fix that doesn't rely on allocation: bump a counter in `SessionsStorage` in those two
  updates.

A deterministic test of the interleaving isn't possible without a production seam. The window is inside
`registerChild`'s update lambda on the read-loop thread, and the removal's window is inside `NonCancellable` in
another coroutine. No suspension point or injectable hook separates them. Only a stress test (many iterations of
close racing against an announcement on a multi-threaded dispatcher) could catch a regression, and only
probabilistically.

## Discrepancies

None between `AGENTS.md`, the KDoc and the code. The KDoc at `:166-169` ("either enters the map before this update,
which removes it, or stays out") is correct. It leaves unstated the identity-CAS precondition it relies on.

## Open questions

- Out of scope, noted only: once a subagent holder no longer blocks (it failed and its parent failed), the
  speculative path in `getOrCreateSessionHolderOrNull` (`:71-88`) can replace it with a root-level speculative
  holder while openings are in progress. The `:59` KDoc ("a subagent's id never gets a speculative holder: it has
  its own while its parent lives") still holds literally, because the parent is dead. Whether that is intended
  could be confirmed separately.
