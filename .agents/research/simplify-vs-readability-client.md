# Readability suggestions (client internals) vs the concurrency-simplification review, Part 1

Scope: the 26 suggestions in the client-internals sections of `.agents/research/readability-suggestions.md`
(`ClientSessionRegistry.kt` 1-14, `ClientSessionHolder.kt` 1-5, `RootSessionHolder.kt` 1-2,
`SubagentSessionHolder.kt` 1-4, `PendingPermissions.kt` 1). Checked against the code at `58db90c`, which has the
same product code as the Part 1 base `e609e49` (`git diff --stat e609e49..HEAD` lists only `.agents/` files), so Part 1
line numbers are still valid. Analysis only. Nothing was changed or run.

Paths: `Registry`, `Holder`, `Root`, `Subagent`, `PP` are the files under
`acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/`. `Client.kt` and `ClientSubagentSession.kt` are in
`client/`. Part 1 reports: **REG** = `simplify-client-registry.md`, **SES** = `simplify-client-sessions.md`. The agent
and protocol reports (`simplify-agent.md`, `simplify-protocol.md`) overlap with none of these suggestions.

## Summary

| Class | Count | Suggestions |
|---|---|---|
| Holds | 12 | Reg 3, 4, 5, 8, 14; Holder 1; Root 1, 2; Subagent 1, 2, 3, 4 |
| Holds, merged | 8 | Reg 2, 7, 9, 10, 11, 12; Holder 3, 5 |
| Superseded | 6 | Reg 1, 6, 13; Holder 2, 4; PP 1 |
| Conflicts | 0 | none |
| Obsolete | 0 | none: every suggestion still matches the code |

Main points:

- Five suggestions touch user decisions: Reg 4 (the "settles" wording, which the user still plans to reword),
  Holder 2 (R17 D1), Subagent 1 and 4 (R19 item 2 chose `completeOpening` and `_subagent`), and PP 1 (the final
  `activeWork` decision).
- Reg 10's proposed name `unclaimedRoots` is wrong, both today and after REG §1: the reap also removes roots that a
  late response hook claimed.
- The SES item 19 sketch has two defects, and both line up with readability suggestions (Reg 3 and Reg 13). REG's
  §6-first ordering is unsafe for `registerChild`. See "Disagreements with Part 1".

## Table

| # | Suggestion | Code | Class | Related Part 1 item | Reason |
|---|---|---|---|---|---|
| Reg 1 | `blocksId` → `ownsId` | Holder:149-154, Root:101, Subagent:85, Registry:60,67,94,101,165,244 | Superseded | REG §1 | §1 deletes `blocksId`. Holds if §1 is rejected (Holder:150 already says "owns its session id"). |
| Reg 2 | `getOrCreateSessionHolderOrNull` → `holderOrSpeculativeRoot` | Registry:54-86 | Holds, merged | REG §6 (lookup sketch), §1 | §6 rewrites this exact function, so rename it in that rewrite. |
| Reg 3 | inline `getOrCreateSessionHolder` | Registry:88-89, 266-276 | Holds | (REG §4 "narrow", optional) | Only `claimSessionHolder` calls it. §4 narrowing would rewrite the claim, but it is the last, optional step. |
| Reg 4 | `stopOpening` result: `mustFail` | Registry:120,137-140,312,319,327-339 | Holds | none; user-deferred follow-ups | Keep the function name and "settles" in docs (user vocabulary). Renaming the locals is enough. |
| Reg 5 | `subagentsBottomUp` local | Registry:137 | Holds | REG §2 (survives) | Under §2 the order comes from the map's insertion order, still parents first, so the name stays right. |
| Reg 6 | `removedIds` local | Registry:142 | Superseded | REG §2 | Under §2 the subtree is read from the removed snapshot, so the filter goes. Holds if §2 is rejected. |
| Reg 7 | `blocked` → `idTaken` | Registry:235-258 | Holds, merged | REG §6 (`registerChild`), §1 | §6 turns the captured `var`s into values derived from `getAndUpdate`. Name the derived value `idTaken` there. |
| Reg 8 | `followSubagentState` → `feedSubagentPermissions` | Registry:207,212,216-223 | Holds | SES items 13/14 (survives) | The function stays in every SES variant. SES's own sketch keeps it. |
| Reg 9 | `withInitializingSession` → `withOpeningCall` | Registry:50,355-406, Client.kt:661,686,724 | Holds, merged | SES item 19; caveat REG §4 | Item 19 leaves one call site. If §4 narrowing lands, the count covers only answered-id openings and needs another name. |
| Reg 10 | `hangingSessions`/`reaped` → `unclaimedRoots` | Registry:363-404 | Holds, merged | REG §6 (reap), §1 | §6 removes the captured `var` and the copy. The proposed name is inaccurate: use `uncompletedRoots`. |
| Reg 11 | extract `waitableHolderOrNull` | Registry:160-171 | Holds, merged | SES item 15 (`holderForRequest`) | Item 15 needs exactly this non-suspending selection. Extract it once there. |
| Reg 12 | `claimBeforeRequest` / `claimOnAnswer` | Registry:262-293, Client.kt:662,691,725 | Holds, merged | SES item 19 | The merged flow rewrites every call site, and its parameters already use the timing axis. |
| Reg 13 | answered claim also marks opened | Registry:284-293 | Superseded | SES item 19 | Item 19 has to move `markOpened` out of the claim (see Disagreement 2), and then the asymmetry is gone. Holds if item 19 is rejected. |
| Reg 14 | new/fork answered with an open id (behaviour) | Registry:289-290, Client.kt:698-700 | Holds | none | No Part 1 report covers it. The user decides: document it, or send `session/close`. |
| Holder 1 | `opened`/`parentOpened` → `streamOpened` | Holder:255-277,291-295,322, Subagent:106-107, ClientSubagentSession.kt:177-181 | Holds | SES item 17 (keep) | The patch hold-back that this flag drives stays in every variant. |
| Holder 2 | `Children.byId`/`listed` names | Holder:101-112 | Superseded | REG §2 | §2 deletes `byId` and `sealed`, and `listed` becomes a plain list. Holds if §2 is rejected. R17 D1 named them. |
| Holder 3 | `failSessionTree` property → `failSubagentTree` | Holder:92-99, Root:31-32, Subagent:34, Registry:75,310-325 | Holds, merged | REG §1 (adds a root branch) | §1 plus this suggestion split `failSessionTree` into a root function and a subagent function, with no type tests. |
| Holder 4 | `handleOrQueue` fast-path local | Holder:350-376 | Superseded | SES item 29 / D2 | The `Mutex` deletes the fast path. Holds if item 29 is rejected. REG §3 and SES disagree about this. |
| Holder 5 | `failSetupUnlessStopped` / `failOpeningUnlessStopped` | Holder:331-343, Subagent:207-217 | Holds, merged | REG item 23 §5, SES items 19/22 (`callApp`) | `callApp` rewrites the catch blocks that call these functions. Watch the cancellation pitfall (Disagreement 5). |
| Root 1 | `claimedBy`/`answered`/`closing` → `…OrNull` | Root:56-74, 117,131,147 | Holds | none | REG keeps `RootPhase` unchanged (items 5, 6). |
| Root 2 | `appliesHeldState` property, `applyHeld` param | Holder:247, Root:152, Subagent:80; params Subagent:97-99, PP:145-148, ClientSubagentSession.kt:205 | Holds | SES item 14 (partial) | Item 14 would drop the permission half of `promote`, but Holder:247 stays and item 14's sketch adds a fourth copy. |
| Subagent 1 | `completeOpening` → `runOpening` | Subagent:125,140-205 | Holds | none | Reverses R19 item 2 (the user mirrored the root's names). Ask the user before doing it. |
| Subagent 2 | `OpeningState.Finished` → `Completing` | Subagent:51-55,188, Registry:338 | Holds | REG item 17 (keep) | `OpeningState` stays. Also update AGENTS.md:193 ("the finished opening"). |
| Subagent 3 | `claimCompletion()` | Subagent:186-189 | Holds | REG §6 table (keep CAS) | The CAS stays. Pairs with Subagent 2. |
| Subagent 4 | `_subagent` → `setUpSession` | Subagent:65-69,92,107,114,123 | Holds | none | R19 item 2 named it `_subagent`. The underscore suggests a backing property, but R19 item 3 removed the public `subagent`. |
| PP 1 | `WorkState.activeWork` → `confirmsWork` | PP:100-109,121,128,137,150,164 | Superseded | SES item 13 (only if the user reopens it) | Item 13 deletes `activeWork`. It reverses a final decision, so the rename most likely holds. |

## Suggestions that need reasoning

### Reg 1: `blocksId` → `ownsId` (Superseded by REG §1)

REG §1 removes a failed root's tree when it fails. After that, "in the map" means "owns the id", and `blocksId`, its two
overrides and every read of it go. If §1 is rejected, the rename still holds as is: the KDoc at Holder:149-153 already
uses "owns".

### Reg 2: rename `getOrCreateSessionHolderOrNull` (Holds, merged with REG §6)

REG §6 rewrites this function: one allocation outside the lambda, `updateAndGet`, and no `takeIf { it.blocksId }`
after §1. Rename it in the same commit. One caveat: REG §4's "side buffer" option would remove the speculative root
holders, and the name `holderOrSpeculativeRoot` would then be wrong. REG doesn't recommend that option, so the name is
fine.

### Reg 3: inline `getOrCreateSessionHolder` (Holds)

Its only caller is `claimSessionHolder` (Registry:271). The suggestion keeps the precondition "must run under
`withInitializingSession`" next to the call that depends on it. SES item 19's sketch shows why that matters: it
breaks this precondition (Disagreement 1). REG §4's "narrow" option would create the load/resume holder in one CAS,
which needs no count, so the claim would be rewritten and the precondition would disappear. That option is last in
REG's order and optional, so do the inline now.

### Reg 4: `stopOpening` result and "settles" (Holds, narrowed; touches a user decision)

"Settles" is the user's word. AGENTS.md:193 says "settles its holder", and the user-deferred follow-up in `state.md`
plans to reword the `stopOpening` KDoc as "settles the opening in the holder" and "terminal state". Renaming the
function to `stopOpeningMustFail`, or adding an enum, works against that wording. Recommendation: rename only the
locals (`settlesHolder` → `mustFail`, `settles` → `mustFail`). The KDoc already says "whether the caller must now fail
the holder", so the locals then match it. Do it together with the two deferred follow-ups, "move the `stopOpening`
check into `failTree`" and the KDoc rewording, because they edit the same lines. No Part 1 proposal changes
`stopOpening`: REG item 17 keeps it.

### Reg 5: `subagentsBottomUp` (Holds; survives REG §2)

Today the list is a preorder walk, reversed (Registry:111-117, 137). Under REG §2 the subtree comes from
`subtreeOf(root)`, which filters the session map. `persistentMapOf()` is insertion-ordered, and a child is always put
after its parent (`childId !in sessions`), so the reversed list still has children before their parents. The name
stays correct in both versions. If §2 lands, check this ordering argument in that slice: it then rests on the map's
insertion order, not on a walk.

### Reg 6: `removedIds` (Superseded by REG §2)

Under §2, everything in the removed snapshot with `root === root` is removed, so the return value is just the
subtree's ids, and the identity filter at Registry:142 goes. If §2 is rejected, name the result: its only reader is
`Client.removeClosedSession` (Client.kt:99).

### Reg 7: `blocked` → `idTaken` (Holds, merged with REG §6 and §1)

REG §6 replaces the captured `rootEnded`/`blocked` with values derived from the snapshot that `getAndUpdate`
returns. Name the derived value `idTaken` there. REG §1 then removes `blocksId` from the condition, so it becomes
`childId in before.sessions`. The order of the two steps matters (Disagreement 3). R17 D2 named the outcome
`Blocked`, and the user later dropped the helper for an inline CAS. Renaming the local doesn't reopen that decision.

### Reg 9: `withInitializingSession` → `withOpeningCall` (Holds, merged with SES item 19)

SES item 19 merges the three opening flows into one, so there is one call site to rename instead of three
(Client.kt:661, 686, 724). Caveat: REG §4's "narrow" option counts only openings whose id first appears in the answer.
The suggestion's reason ("the count is of opening calls in flight") would then be false, and the name should say
`answeredIdOpenings` or similar. Choose the name once the user has decided on §4.

### Reg 10: `hangingSessions` → `unclaimedRoots` (Holds, merged with REG §6/§1; use another name)

REG §6 recommends logging outside the lambda and deriving the reaped set from `getAndUpdate`. That removes both the
captured `var` and the `val reaped = hangingSessions` copy, so give the derived value the new name.

`unclaimedRoots` is inaccurate:

- Today the filter (Registry:376-378) also takes failed roots that were claimed.
- After REG §1 the filter is `!it.session.isCompleted`. It still takes a root claimed by a response hook that ran after
  `withAnsweredSessionId` checked `claimed` (Client.kt:694-697: "leaves the holder to the reaping at the end of the
  last opening"). That root is claimed and answered, but nobody completes it.

Use `uncompletedRoots` or `abandonedRoots`, which match the KDoc at Registry:355-356 ("root holders that no opening
completed").

### Reg 11: extract `waitableHolderOrNull` (Holds, merged with SES item 15)

SES item 15 proposes `sessions.holderForRequest(id)`, "a claimed root or subagent, else -32002", for the merged
permission path. That is the same rule as `awaitSession`'s `when` at Registry:165-169. Extract it once, give it the
rationale comment from Registry:161-164, and let both `awaitSession` and the permission handler use it. Without
item 15, the extraction still holds on its own.

### Reg 12: claim names by timing (Holds, merged with SES item 19; came from a user discussion)

SES item 19's `openSession` already names its parameters on the timing axis (`claimedBefore`, `claimAnswered`). Do
the rename in that slice, so the registry functions and the flow's parameters match. If item 19 is rejected, the
rename holds alone. The suggestion came from the user's own discussion (2026-11-26).

### Reg 13: the answered claim also marks opened (Superseded by SES item 19)

The SES item 19 sketch calls `claimAnswered(r)` and then `holder?.markOpened(r)` in the hook, for both paths. That
works only if the answered claim stops calling `markOpened` itself (Registry:291): see Disagreement 2. Once that is
split, both paths call `markOpened` in the hook, and the asymmetry this suggestion wants to name is gone. Claim and
marker still run back to back on the read loop, so the marker keeps its wire position. If item 19 is rejected, the
suggestion holds: one KDoc line, or the name `claimOnAnswer` from Reg 12.

### Reg 14: new/fork answered with an open id (Holds; behaviour, needs the user)

None of the four reports covers it. The agent report's item 7 covers a re-open through load/resume on the agent
side, which is a different case. REG §1 doesn't change the outcome: the claim at Registry:289-290 still refuses an open
id, and Client.kt:698-700 still throws. The agent has created a session that no one closes. Sending `session/close`
needs the agent's close capability, so even that option wouldn't fully cover the case. The user decides.

### Holder 2: `Children.byId` / `listed` (Superseded by REG §2; touches R17 D1)

REG §2 keeps the tree only in the registry's snapshot. `byId`, `sealed`, `addChild`, `removeChild`, `sealChildren`
and `forEachDescendant` go, and `listed` stays as a plain list. §2 revisits user decision R17 D1, which created
`Children(byId, listed, sealed)`, so it needs the user's agreement. If §2 is rejected, the rename holds and doesn't
reopen D1: R17 D1 decided that both collections stay, not what they are called.

### Holder 3: `failSubagentTree` (Holds, merged with REG §1)

The constructor property is called only with a subagent's holder (Holder:305, 337, Subagent:212). The root receives it
only to pass it down (Root:31-32). Today `failSessionTree` starts with `if (holder is SubagentSessionHolder &&
!stopOpening(holder))` (Registry:312). REG §1's sketch adds `if (holder is RootSessionHolder) { remove the tree }`
after the cascade. With both, the function would test the holder's type at both ends. Split it instead:

- `failRootTree(root)`: called by `Client.kt` and the reap; cascade, then the map removal from §1;
- `failSubagentTree(child)`: the holder callback; `stopOpening`, then the cascade.

Then the narrowed property type comes for free. This also fits the user-deferred follow-up "move the `stopOpening`
check into `failTree`". Cost: Registry:75 passes `::failSessionTree` today, and tests that build holders with a
`failSessionTree` lambda need the new type.

### Holder 4: the `handleOrQueue` fast-path condition (Superseded by SES item 29)

SES item 29 replaces the fast path and the `lateDeliveries` chain with a fair `Mutex`, so the condition at
Holder:357 goes. Any fix for SES D2, even a smaller one that sends the fast path through the chain, changes this
condition too. If neither lands, the suggestion holds, but the proposed name is wrong for the first half:
`_session.isCompleted` means "the consumer finished: the session took over or the holder failed", not "no late update
waits". Use two names (`sessionTookOver && noLateUpdateWaits`), or name only the second half. The two Part 1 reports
disagree about this code (Disagreement 4).

### Holder 5: `failSetupUnlessStopped` (Holds, merged with the `callApp` helper)

REG §5 (item 23) and SES items 19/22 propose one `callApp` helper that replaces the inline `CancellationException`
conversions at Holder:309-319 and Subagent:190-197. Those are the catch blocks that call `failSetup` and
`failOpening`, so rename both functions in that slice. The functions themselves stay. When that slice lands, mind
the pitfall in Disagreement 5.

### Root 2: `appliesHeldState` (Holds; partial overlap with SES item 14)

The double negative `opening?.replayRequested != true` appears three times (Holder:247, Root:152, Subagent:80). If
the user reopens SES item 14 (drop the permission hold-back, which the user rejected as D1 on 2026-11-22), the
permission half goes: `onOpened(promote)`, `Subagent.markOpened(promote)`, and the walk at Root:152-153. But the
`ClientSubagentSession` patch hold-back at Holder:247 → `onParentOpened(promote)` stays (SES item 17, spec-driven).
Item 14's own sketch also adds a fourth copy (`phase.opening?.replayRequested != true` in `followSubagentState`).
So the property is worth adding in every outcome.

Put it on `RootPhase`, not `Opening`: `phase.opening` is `null` before the claim, so a property on `Opening` would
bring the `?.` and `!= false` back at every call site.

### Subagent 1: `completeOpening` → `runOpening` (Holds; reverses R19 item 2)

R19 item 2 (agreed by the user on 2026-11-22) renamed `consumeSubagentQueue` to `completeOpening()` so that the
subagent's names mirror the root's. After review, the user also kept the root's `completeOpening(session, opened)`.
This suggestion argues against that choice, so the user should decide. No Part 1 proposal changes the function. REG §3's
"never close the queue" alternative would remove most of its body, but REG doesn't recommend it.

### Subagent 2: `Finished` → `Completing` (Holds)

`OpeningState` stays (REG item 17, needed by the user's no-join close decision). The rename changes no behaviour.
Update AGENTS.md:193 ("the finished opening completes it") and the `state.md` wording in the same change. Pairs with
Subagent 3: `claimCompletion()` moves the state to `Completing`.

### Subagent 4: `_subagent` → `setUpSession` (Holds; touches R19 item 2)

R19 item 2 renamed `builtSubagent` to `_subagent`, and R19 item 3 removed the public `subagent`. So `_subagent` now
uses backing-property naming (as in `_session`/`session` and `_phase`/`phase`) with no property behind it. That
supports the suggestion, but the user picked this name, so ask first.

### PP 1: `activeWork` → `confirmsWork` (Superseded by SES item 13, only if the user reopens it)

SES item 13 drops the post-cancel window, and with it `Live.activeWork` and `Held.activeWork`. Item 14 alone would
keep `activeWork` as a plain `atomic(false)`. The window is a final user decision, and the decision text names the
field ("`SubagentPendingPermissions.onState` sets `activeWork` before `clearCancellation()`", `state.md` "Decisions
(final)"). So the most likely outcome is that item 13 is rejected and the rename holds. It doesn't change that
decision, only its wording.

## Disagreements with Part 1

1. **SES item 19's sketch claims load/resume outside `withInitializingSession`** (should-fix in the sketch, by
   reading). `openSession(claimedBefore: RootSessionHolder?, …) = sessions.withInitializingSession { … }` means the
   caller claims before the count goes up. `claimSessionHolder` gets its holder from `getOrCreateSessionHolder`
   (Registry:88-89, 271). For a new id at count 0 that lookup returns `null` (Registry:79-82), so the claim throws
   "Session … not found", and every load or resume of an id the client doesn't hold yet would fail. Registry:263-264
   states the precondition. The merged flow has to claim inside the block (pass the id, or a claim lambda), or REG §4
   "narrow" has to land first. This is the reason for Reg 3.

2. **SES item 19's sketch calls `markOpened` twice on the answered path** (should-fix in the sketch, by reading). In
   the hook, `claimAnswered(r)` is today's `claimAnsweredSessionHolder`, which already calls `markOpened` (Registry:291).
   The sketch then calls `holder?.markOpened(r)` again. The second call finds the phase `ANSWERED`, so
   `checkNotNull(claimed.answered())` at Root:147 throws inside the response hook, which AGENTS.md says must not
   throw. The merged flow has to take `markOpened` out of the answered claim. That split is Reg 13.

3. **REG's "Suggested order" puts §6 (pure lambdas) before §1, but for `registerChild` the derivation is safe only
   after §1** (nit to should-fix, plausible race). REG §6 derives the result by applying the lambda's predicate again
   to the snapshot that `getAndUpdate` returns. Before §1, that predicate reads `blocksId` (Registry:244), which reads
   two deferreds, not the snapshot, and can change from `true` to `false`. Failure scenario:
   - an announcement reuses an id whose holder still blocks it, so the lambda sees it blocked and inserts nothing;
   - a cascade then fails the blocking holder's parent;
   - the predicate, applied again, sees it not blocked and reports the child as registered, though it never entered
     the map;
   - `removeChild` doesn't run, so the child stays in its parent's `byId` but not in the map, and its permission
     requests then fail with `-32002`.

   REG's own table says "with §1 it reads only the snapshot", which shows the dependency. Do §6 for
   `registerChild`, and for the lookup (whose sketch is already marked "after §1"), only after §1. Alternatively, keep
   computing the result inside the lambda until then.

4. **REG §3 and SES D2/item 29 make opposite recommendations about the live fast path** (needs a Part 3 or user
   decision, by reading). REG §3 says to keep the fast path ("starts in order, may overlap"), because dropping it would
   make live `notify` sequential and could deadlock an app that waits inside `notify`. SES D2 calls that overlap a
   defect and proposes a fair `Mutex`. Evidence for SES's reading:
   - Holder:353-356 already sends an announcement through the chain, because "its setup may suspend and the updates
     after it must wait for it". So the SDK already serializes behind a setup that suspends, but not behind a `notify`
     that suspends.
   - The public promise "exactly once and in arrival order, before and after the answer" (Client.kt:410) doesn't say
     whether it means the order in which calls start or the order in which they finish.

   Both reports are right about the cost. Which way to go is a user decision, and it decides Holder 4 and the
   AGENTS.md "`lateDeliveries` chain" bullet.

5. **`callApp` can swallow the caller's cancellation in `setUpSubagent`** (should-fix pitfall for REG §5 and SES
   items 19/22, by reading). Today (Holder:309-319) the `catch (ce: CancellationException)` block calls `ensureActive()`,
   which throws out of a catch block, and the sibling `catch (t: Throwable)` does not catch it. So the caller's
   cancellation propagates. A rewrite as `try { callApp("createSubagentOperations") { … } } catch (t: Throwable) {
   failSetup(child, t); return }` would catch the `CancellationException` that `callApp` rethrows:
   - `failSetup` would see `!isActive`, log at debug level and return;
   - the consumer would then go on to `deliver(item.update, item.meta)`, cancelled.

   That breaks the AGENTS.md rule to preserve cancellation. The rewrite needs an explicit
   `catch (ce: CancellationException) { throw ce }` first. The same applies to Subagent:190-204. There it is less
   harmful, because it is the top of a launched job, but the log level changes.

6. **REG §1 miscounts the `blocksId` reads** (nit, by reading). REG says "seven `takeIf { it.blocksId }` reads". The
   registry reads `blocksId` six times (Registry:60, 67, 94, 101, 165, 244), and four of those are `takeIf`.

7. **REG §4 "side buffer" says the reap's cascade goes** (nit, plausible). The reap also removes a root that a hook
   claimed after the caller's failure check (Client.kt:694-697). That root is claimed, answered and never completed. A
   side buffer for unknown ids doesn't create that holder, so something still has to remove it. Low impact: REG
   doesn't recommend the side buffer.
