# Readability suggestions vs the simplification review, Part 1 (non-client-internals sections)

Scope: the sections of `.agents/research/readability-suggestions.md` from `Client.kt` (line 145) to the end, checked
against the code at `58db90c` (clean tree) and against the four Part 1 reports:
`simplify-agent.md` (SA), `simplify-client-registry.md` (SR), `simplify-client-sessions.md` (SC),
`simplify-protocol.md` (SP). Analysis only, nothing run. Every claim is by reading.

Paths are relative to `acp/src/commonMain/kotlin/com/agentclientprotocol/` unless they start with a module name.

## Summary

15 suggestions in scope.

| Class | Count | Suggestions |
|---|---|---|
| Holds | 7 | CS1, SW2, M1, M2, M3, M4, B1 |
| Holds, merged | 3 | A1 (SA item 6), R1 (SA item 10), P1 (SP §13 nit) |
| Superseded | 4 | C1, C2, C3 (SC item 19), SW1 (SA item 17) |
| Conflicts | 0 | |
| Obsolete | 1 | O1 (premise gone, and the hook would be outside the child's lock) |

Findings beyond the classification:

- SC item 19's sketch calls `markOpened` twice for `session/new` and `session/fork`. The second call throws in the
  read-loop hook. See "Disagreements".
- SW1 rests on a wrong premise: `factoryState` is not reset when the factory ends, and it has to outlive the factory.
- SA item 11 (drop `isReplayed`) is correct, but it removes a field whose name the user explicitly kept (R15).

## Table

| Id | Suggestion | Code | Class | Related Part 1 item | Reason |
|---|---|---|---|---|---|
| C1 | `failOpening` helper for the four `failSessionTree` catches | `client/Client.kt:642`, `:667`, `:702`, `:751` | Superseded | SC item 19 (one opening flow) | the merged flow has one catch, which carries the rationale once; holds as is if item 19 is rejected |
| C2 | Rename the three opening helpers | `client/Client.kt:655`, `:681`, `:718` | Superseded | SC item 19 | the three helpers become one `openSession`; holds if item 19 is rejected |
| C3 | Sealed result instead of `CompletableDeferred<Pair<…>?>` for `claimed` | `client/Client.kt:680-708` | Superseded | SC item 19 (sketch: `atomic(claimedBefore)`) | the merged flow uses a nullable atomic, which also drops the opt-in; if item 19 is rejected, a nullable `atomic<RootSessionHolder?>` beats the 3-state sealed type |
| CS1 | Split `applyPatch` into descriptive and held fields | `client/ClientSubagentSession.kt:181-196` | Holds | SC items 17-18 say "keep, minimal" (no change proposed) | independent of Part 1 |
| SW1 | `FactoryRun?` for `factoryRunning`, `factoryState`, `isCancelLatched` | `agent/internal/SubagentSessionWrapper.kt:69-84`, `:176-197` | Superseded | SA item 17 (cancel latch to one deferred), user decision S2 | item 17 removes two of the three fields; the premise is also wrong for `factoryState` |
| SW2 | `sendOwnUpdate`/`sendPlainUpdate` to `sendOnParentStream`/`sendOnOwnStream` | `agent/internal/SubagentSessionWrapper.kt:334-347`, `:373`; callers `RemoteClientSessionOperations.kt:207-209` | Holds | none (SA item 17's sketch only mentions `sendOwnUpdateLocked`) | independent; rename `sendOwnUpdateLocked` too |
| A1 | `SessionTable.replacingRoot` / `removingRoot` | `agent/Agent.kt:420-430`, `:515-523` | Holds, merged | SA item 6 (`modifySessions` helper); SA item 9 (inline `discardFailedLoad`) | the same goal, removing the captured `var`s; the two shapes combine; keeps the PR round 2 decision (one atomic snapshot) |
| R1 | Rename `replayedCollections` to `startsAfterReplay` | `agent/internal/RootSessionWrapper.kt:72` | Holds, merged | SA items 10-11 (replay window as one atomic, drop `isReplayed`) | item 10 replaces the field, but its sketch names the new atomic `replayed`, which has the same naming problem |
| P1 | `ResponseSlot.onSuccessQueued` to `onResponseQueued`, getter `responseQueuedHookOrNull()` | `protocol/Protocol.kt:500`, `:568`, `:723-738` | Holds, merged | SP §13 open nit (the getter's success re-check is redundant); SP §6 (batches) | rename the field and drop the getter instead of renaming it; moot if batches go (a user decision outside this PR) |
| O1 | `open fun checkUpdate(notification)` beside `checkCanSend` | `agent/internal/RemoteClientSessionOperations.kt:21-24`, `:47-58`, `:198-211` | Obsolete | none | the KDoc no longer explains the exception (one clause); the hook removes no override and would sit outside the child's lock |
| M1 | `Json.decodeLenientlyOrNull` for the four broad catches | `acp-model/…/model/JsonObjectSerializer.kt:68-73`, `MaybeUndefined.kt:99-104`, `:128-134`, `Subagents.kt:113-117` | Holds | none | four copies confirmed, three with the same comment |
| M2 | One naming scheme for the tri-state decoders | `acp-model/…/model/SubagentSessionUpdateSerializers.kt:26-34`, `:106-145`; `MaybeUndefined.kt:91`, `:111` | Holds | none | the file was renamed from `SubagentSessionUpdates.kt` (`07ba47e`); rename only the private helpers, since v2 models call `decodeMaybeUndefined` |
| M3 | `tagged(type, serializer, value)` in `SessionUpdateSerializer.serialize` | `acp-model/…/model/SessionUpdate.kt:398-512` (16 branches) | Holds | none | 16 identical seven-line branches; 13 come from `master`, so the diff also touches pre-PR code |
| M4 | Shared discriminator constants in `StateUpdateSerializer` | `acp-model/…/model/SubagentState.kt:171-179`, `:186-190`, `:216-233` | Holds | none | each wire string is spelled in both directions; `STATE_KEY` etc. at `:151-155` set the precedent |
| B1 | `findPublicDeclarationsInInternalPackages` to `findClassEntriesInInternalPackages` | `build-logic/src/main/kotlin/CheckInternalPackagesNotPublic.kt:64-71`; test `build-logic/src/test/kotlin/CheckInternalPackagesNotPublicTest.kt:12` | Holds | none | the KDoc says "class entries", and the predicate is `isClassEntryInInternalPackage` |

## Suggestions that need reasoning

### C1-C3. The opening helpers in `Client.kt` (SC item 19)

All three are about code that SC item 19 rewrites. The proposal merges `withFactoryInitializingSession`,
`withAnsweredSessionId` and `withEarlyInitializingSession` into one `openSession`, with one catch and a `callApp`
helper for the cancellation conversion.

**C1.** The four catches are at `Client.kt:642`, `:667`, `:702` and `:751`. Only `:638-641` explains why the cause
is wrapped (`-32603`, not `-32800`). In the merged flow there is one catch (SC sketch, line 349), so the rationale
sits there once and no helper is needed. Superseded.

If item 19 is rejected, C1 holds as written. Two notes for whoever does it then:
- The comments next to the catches (`:643`, `:696-698`, `:750`) say that `withInitializingSession` reaps the failed
  holder. SR item 1 (remove a failed root's tree when it fails) changes that fact. Write `failOpening`'s KDoc after
  deciding SR item 1, or it describes the reap that SR item 1 removes.
- `failOpening` and SC's `callApp` are different concerns: wrapping the opening's failure for waiting requests,
  versus turning an app `CancellationException` into a failure. Don't fold them into one helper.

**C2.** Superseded: one function needs no names on two axes. If item 19 is rejected, the proposed names are
accurate:
- `withFactoryInitializingSession` serves load and resume, with a known id and a factory.
- `withAnsweredSessionId` serves new and fork, with an answered id and a factory.
- `withEarlyInitializingSession` serves load and resume, with a known id and early operations.

Both the rename and the merge become smaller once the `ClientOperationsFactory` TODO (issue 167) lands, since two of
the three paths then go.

**C3.** Superseded. SC's sketch replaces the deferred with `atomic(claimedBefore)`, which also drops
`@OptIn(ExperimentalCoroutinesApi::class)` (`Client.kt:680`).

The three-state sealed type the suggestion proposes is more than the code needs:
- In the catch (`:699`), "not answered" and "refused" lead to the same action: fail nothing.
- After a normal return, the hook has always run, so only "refused" or "claimed" remain.

A nullable `atomic<RootSessionHolder?>(null)` holds both distinctions. The `Pair` can go too, because
`RootSessionHolder.sessionId` carries the id. If item 19 is rejected, do that two-state form rather than the sealed
type.

User decisions touched: (a) cancellable `Opened` delivery. SC item 19 keeps it, and its sketch still fails the
holder on a late cancellation. The rejected failure-cascade refactor is not touched: `failSessionTree` is still
called the same way.

### SW1. The factory-run fields (SA item 17, user decision S2)

SA item 17 replaces the cancel latch with one `CompletableDeferred<AgentSubagentSession?>`. That removes
`factoryRunning` and `isCancelLatched` (`SubagentSessionWrapper.kt:72`, `:84`), the `synchronized` blocks of
`runFactory` (`:177`, `:181-185`, `:190-194`), `latchCancel` and `deliverLatchedCancel`. Only `factoryState` stays,
as the one plain field guarded by the lock (SA §14-15). With that, a `FactoryRun` holder has nothing to group.
Superseded.

The suggestion's premise is also wrong. It says the three fields are "reset together when the factory ends". In
fact:
- `runFactory` resets only `factoryRunning` and `isCancelLatched` (`:181-185`, `:190-194`).
- `factoryState` must survive the factory's failure. `announce` calls `reportFactoryFailure()` (`:158`) after
  `runFactory` has thrown, and `sendFactoryFailureCorrection` reads `factoryState` (`:352`).

So `factoryRun = null` at the factory's end, as proposed, would lose the state that the `unknown` correction needs,
and the SDK would leave a failed child shown as `running` (the case AST:1392 and SA item 18 describe).

If item 17 is rejected, a correct form groups only `factoryRunning` and `isCancelLatched`, with `factoryState`
separate. That saves little, so I would not do it on its own.

S2 detail: item 17 changes "two held cancels are delivered once" (AST:1108) to "each is delivered". SA flags that,
and the user decides.

### A1. Named table operations in `Agent.kt` (SA item 6)

The captured `var`s are at `Agent.kt:420-421`, written in the lambda at `:426-427`, and at `:516`, written at
`:518`. SA item 6 proposes a generic `modifySessions { table -> next to result }` CAS helper. The suggestion proposes
`SessionTable.replacingRoot(root): Pair<SessionTable, Replaced?>` and `removingRoot`. They are not alternatives; they
compose:

```kotlin
val (replaced, removed) = modifySessions { it.replacingRoot(sessionWrapper) }
```

The table method names the rule ("a root leaves or is replaced together with its subagents"), and the helper makes
the CAS lambda pure. Do both in the SA item 6 slice. The `check(sessionWrapper.sessionId !in table.subagents)` at
`:423` moves into `replacingRoot`; a throw inside a CAS lambda is fine.

SR §6 has a lighter idiom: derive the results from the snapshot that `getAndUpdate` returns. Here `replaced` and
`removed` follow from the old table alone, so `val old = _sessions.getAndUpdate { it.replacingRoot(w) }` works
without `modifySessions`. Choose one idiom for both runtimes.

The same slice can take SA item 9 (inline `discardFailedLoad`, `:464-466`), since it only calls `removeRoot`. The PR
round 2 user decision (one atomic snapshot, one CAS per change) is kept by both forms.

### R1. `replayedCollections` (SA items 10-11)

SA item 10 replaces `replayLock`, `_isReplaying` and `replayedCollections` (`RootSessionWrapper.kt:58-94`) with one
`atomic<PersistentList<() -> Unit>?>`. The field this suggestion renames goes away. But the sketch names the new
atomic `replayed`, and that name has the same problem: the list holds deferred starts, not collections or replayed
things. So the suggestion carries over. When item 10 is done, name the atomic `startsAfterReplay` (or
`pendingStarts`). If item 10 is rejected, R1 holds as is.

SA item 11 drops `Sub.isReplayed` (`SubagentSessionWrapper.kt:99`, `:169`). I checked the equivalence:
- `isReplayed` is `root.isReplaying` read at construction.
- The window only closes, never reopens: a re-open creates a new `RootSessionWrapper`.
- So `isReplayed == false` implies the window is closed at `:169`, and `startAfterReplay` then runs `start` at once.

The change is correct. It does touch user decision R15, which kept `isReplayed` and its name ("snapshot, not live
state"). Mark it for the user: R15 decided the name, not that the field must exist.

### P1. `ResponseSlot.onSuccessQueued` (SP §13 nit; SP §6)

SP §13 notes that `successQueuedHook()` re-checks for a success reply (`Protocol.kt:736-737`), although the field is
set only on the success path (`:568`). I confirmed it by reading:
- `:568` sets the field directly before `:569` completes the response with a success.
- No suspension falls between them.
- The other completers can't run first: `:529` belongs to a duplicate id whose body never runs, `:571` is the catch,
  and `:545` runs only after the handler job completes.

So the right merged change is: rename the field to `onResponseQueued` (the suggestion's first half) and read it
directly at `:500`, deleting the getter and its `ExperimentalCoroutinesApi` opt-in, rather than renaming the getter
to `responseQueuedHookOrNull()`. If SP §6 (drop batch support) ever lands, `ResponseSlot` goes and P1 with it.
That is a user decision outside this PR.

The user decided the shape of `RequestOutcome.onResponseQueued`. Renaming the internal slot field to match it agrees
with that decision.

### O1. `checkCanSend` and the subagent's `notify`

The suggestion's reason no longer matches the code. After the comment trim, the KDoc of `checkCanSend`
(`RemoteClientSessionOperations.kt:21-23`) is one sentence plus "Every operation but [notify] calls it". It no longer
explains why.

The proposed hook would also not simplify:
- `SubagentClientOperations` overrides `notify` for routing (`:200-211`): its own update goes on the parent's stream,
  the rest on its own. The override stays with or without a `checkUpdate` hook.
- The subagent's real check, `checkAnnounced` and `checkRegistered`, must run inside the child's lock, together with
  the send (`SubagentSessionWrapper.kt:336-338`, `:373-374`). AGENTS.md says "A child's lock orders its sends against
  dropping or removing it". A `checkUpdate` called before the send, outside the lock, would either duplicate that
  check or look like the guard while not being one.
- The root's checks and the subagent's are different rules. The root rejects every `SubagentUpdate`. The subagent
  accepts only its own (`:202-205`). One hook would join them in name only.

Classified Obsolete and not recommended. AGENTS.md also says to keep the root's checks "in the one place every root
path sends through", which `notify` already is.

### M2. Tri-state decoder names (note only)

M2 holds, but the cited file is now `SubagentSessionUpdateSerializers.kt` (renamed in `07ba47e`). `decodeMaybeUndefined`
is shared with the v2 models (`model/v2/SessionUpdate.kt`, `v2/Terminal.kt`, `v2/ToolCall.kt`). Renaming it would
touch v2 files, so fit the private `lenientMaybeString`, `strictMaybeString`, `strictMaybeStringList` and
`lenientMaybeMeta` (`:106-145`) to one scheme around it instead.

M1's helper changes only function bodies, so its v2 callers are untouched. One detail: keep the `JsonNull` and absent
checks in the callers, so a `null` result from the helper means only "failed to decode".

## Disagreements with Part 1

1. **SC item 19's sketch calls `markOpened` twice for new and fork, and the second call throws in the read loop.**
   Confirmed by reading. The sketch's hook (SC, line 335) is:

   ```kotlin
   val holder = claimed.value ?: claimAnswered(r).also { claimed.value = it }
   holder?.markOpened(r)
   ```

   `claimAnsweredSessionHolder` already calls `sessionHolder.markOpened(response)` (`client/internal/ClientSessionRegistry.kt:291`).
   The second call reads `phase.answered()`. That is `null` once the stage is `ANSWERED`
   (`client/internal/RootSessionHolder.kt:65`), so `checkNotNull` at `:147` throws `IllegalStateException`. It throws
   inside a response hook, which AGENTS.md says must not throw. Every `session/new` and `session/fork` would hit it.

   Fix the sketch: either call `markOpened` only for `claimedBefore`, or make `claimAnswered` claim without marking.
   This is a sketch defect, not a flaw in the proposal, but Part 3 must not copy it.

2. **SC item 19 understates how different `drainHistory` and `deliverEarly` are.** SC says they are "the same loop"
   (SC, line 316). They differ in three ways:
   - Sink: `drainHistory` (`RootSessionHolder.kt:163-170`) delivers through `session.deliverUpdate`, which tracks
     the deprecated mode and config state on the session. `deliverEarly` (`:180-197`) calls `operations.notify` and
     stashes `earlyMode`/`earlyConfigOptions` for `applyEarlyState`.
   - Cancellation conversion: per item in `drainHistory` (`deliverDuringOpening`, `:240-247`); once around the whole
     `async` in the early path (`Client.kt:730-739`).
   - Closed queue: `drainHistory`'s `receive()` throws, while `deliverEarly` returns `null`, and the caller turns that
     into a `checkNotNull` failure (`Client.kt:742`).

   They can still merge, but `deliverUntilOpened(ops, session?)` needs the deprecated-state branch until that API
   goes, so the LOC saving is likely nearer the low end of SC's "-50 to -60". The behaviour claim ("no behaviour
   change") still holds if those three differences are kept.

No other disagreements. I checked these Part 1 claims while classifying, and they hold:
- SA item 6: the captured `var`s are safe today, because every run of the lambda overwrites them.
- SA item 11: dropping `isReplayed` is equivalent (shown under R1).
- SA item 17: only `factoryState` would remain as a plain guarded field.
- SP §13: the success re-check is redundant.
- SR item 23 and SC item 22: the cancellation-conversion sites listed in `Client.kt` (`:628-632`, `:732-739`) are
  correct.
