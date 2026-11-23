# State: implement v1 subagents per docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md

## Settings
mode: single · branch: eugenethedev/subagents · reviews: on (one reviewer per finished milestone)

## Decisions (final)
- The spec above is the only design document; every design question in it is decided. Do not re-ask them.
- Scope: protocol v1 only. Do not change v2 runtimes (v2 model usages of `MaybeUndefined` are updated by the move).
- All work lands on `eugenethedev/subagents`, one commit per slice. The opening-boundary commits (M3) stay
  contiguous so the user can split them into their own PR later.
- The user approved the milestone plan below (shown 2026-11-15).
- M2 review answers (user, 2026-11-15): S1 a factory `CancellationException` after the announcement with an active
  caller is converted to `IllegalStateException` (cause kept); S2 a `session/cancel` arriving while the factory
  runs with a grant in effect is latched and delivered to `cancel()` once the factory returns (dropped if it
  throws); N1 late child requests / `$/cancel_request` after the close response are accepted and documented.
- User answers 2026-11-15 (later round): (a) client opening: restore cancellable delivery: a cancellation or
  `notify` failure while delivering `Opened` and the queued updates fails the opening as before `d505b25` (holder
  fails, id free, retry works); the rule becomes "an opening that fails before its answer gets no `Opened`".
  (b) agent re-open of an open root via `session/load`/`session/resume`: keep accepting; run subtree cleanup for the
  old root's children when the root is replaced; the old root's running turn stays untouched. (c) the release note
  lives in the PR description only (text in commit bodies); no changelog file.
- `SubagentPendingPermissions.onState` sets `activeWork` before `clearCancellation()`; "cancel racing a state"
  means: state then cancel blocks until a further state; cancel then state blocks only between the two.
- M4 review answers (user, 2026-11-16, final):
  (1) A subagent's `session/request_permission` joins the cancel race when it arrives: the handler takes the
  child holder's `SubagentPendingPermissions` at arrival (non-suspending, before anything suspends) and the wait
  for the holder (`getSessionOrThrow`) moves inside the race's `answer` block. The app still sees the request only
  after the holder completes. Roots unchanged.
  (2) A `closing` flag on the root `ClientSessionHolder` (next to `answered`, read through `root`):
  `closeDescendantPermissions` sets it FIRST, then walks the descendants; `registerChild` reads it AFTER
  `addChild` and closes the new child's permissions. A failed close keeps the flag set. No lock.
  (3) A child whose holder fails (e.g. its `notify` throws before its `Opened`) is marked closed (`canCancel`
  false, `cancel()` throws, later patches not applied) and stays in `subagents`; the list only grows. KDoc: a
  listed child may be closed because its stream failed or its root closed.
  (4) Update the design spec file so it is accurate with every decision taken after it was written.

- PR review round 1 (user, 2026-11-19), agent wrappers, refactor only (no behaviour or API change):
  `Agent.SessionWrapper` → top-level `RootSessionWrapper`; `Agent.ChatSessionWrapper` → top-level `SessionWrapper`;
  `SubagentWrapper` → `SubagentSessionWrapper` (file `SubagentSessionWrapper.kt`); `SessionSubagents` keeps its
  name; `root` → `subtreeRoot` with KDoc "the root of the subtree this session belongs to: itself for a root";
  `BaseSessionWrapper`, `SessionWrapper`, `RootSessionWrapper`, `NesSessionWrapper` and `PromptSession`
  (file-private) at top level in new `agent/SessionWrapper.kt`, with the internal context helpers; public
  `CoroutineContext.agent/clientInfo/client` stay in `Agent.kt` (`AgentKt` facade); `SubagentClientOperations`
  moves to its own file. Note 2 (re-open KDoc) dropped by the user.

- PR review round 2 (user, 2026-11-19): `registerSubagent` reads `isSubtreeClosed` inside its update of
  `_subagentSessions`, but `closeSubtree`'s update is a no-op CAS (same `PersistentMap` instance) when the root has
  no registered child, so a first child racing a close can be registered after the cleanup (orphan, updates after
  the close response). Fix (user's choice): replace `_rootSessions`, `_subagentSessions` and the subtree-closed flag
  with ONE atomic snapshot of both maps; every registration/opening/close/failed-load/re-open is one CAS; the flag,
  the `replaced` workaround and the `resolveParent` check go; close removes the root by identity. No stress test.
  Orchestrator choice (reported to the user): a root whose id is already a registered subagent's fails its opening.
- M0 research: wire shapes of the RFD and Rust v1 models at current upstream vs the spec's pinned `c392d7e9`
  → `.agents/research/subagents-wire-shapes.md`.
- M1 model (`:acp-model`): (1) move `MaybeUndefined` to `model`; (2) capability, `StateUpdate`, `IdleStopReason`
  types; (3) `SubagentUpdate`, `SessionMessage`, `SessionMessageChunk`, `ToolCallContent.Session`, serializers,
  JSON tests, `:acp-model:apiDump`.
- M2 agent (`:acp`): (1) `Protocol.newConnectionScope`, public API, `announce`, routing, root `notify` checks;
  (2) per-child tracking, `session/cancel` routing, other methods rejected on child ids, feed end;
  (3) lifetimes, subtree cleanup, replay.
- M3 opening boundary: (1) `clearCancellation()` fix to #156; (2) extract `ClientSessionRegistry`, close callbacks,
  session-request helper, no behaviour change; (3) deprecate `ClientSession.client`; (4) `Protocol` response hook,
  `SessionEvent`/`Opened`, one opening per holder, samples moved, release note.
- M4 client: (1) types and `SubagentOperations`; (2) registration, sorting, setup, delivery; (3) effective grants
  and `SubagentPendingPermissions`; (4) close and subtree removal.
- M5 end-to-end tests in `:acp-integration-test`.
- M6 docs and checks: `Protocol` KDoc, `CLAUDE.md` rules, final `:acp:apiDump` review, `./gradlew check`.
- R10 (AgentSubagents.kt review): `AgentSubagentContext` and `AgentSubagents` become final classes with internal
  constructors, both in `AgentSubagents.kt` (SDK implements, apps consume; matches `ClientSubagentSession`).
  `SessionSubagents` is merged into `AgentSubagents`. Same slice: restructure the subagent KDoc (bullet the
  `announce` failure matrix, state replay/removal once and link, trim `@throws` repetition, headings in the
  `AgentSubagents` class doc), no information lost.
- R13: promote the v1 `session/close` family out of `@UnstableApi` (user decision 2026-11-20).
- v2 agent close items 1-3 (v2 close research) are deferred to the next v2 PR, not this one.
- AGENTS.md long-comment example fixed by the orchestrator in place at the user's explicit request (`0f54be5`).
- R14 (BaseClientSession review): keep `baseClientSession` (only way for subagent operations and
  `createSubagentOperations` to reach their session); keep `@UnstableApi` on `BaseClientSession` but rewrite its and
  the accessor's banner (a Kotlin API for client subagents, not a spec capability; check other new client types for
  the same copied banner); keep it an interface (`ClientSession` is a public interface; class = binary break) and
  expand the `BaseClientSessionRuntime.kt` comment with that reason; drop member-level `@UnstableApi` on
  `subagents`/`cwd`/`additionalDirectories` only if the compiler confirms it's redundant.
- R15 bundle: restore `@UnstableApi` on `BaseClientSession.subagents`; merge `subagentOperations` into a narrowed
  `override val clientOperations: SubagentClientOperations`; `isReplayed` keeps its name (snapshot, not live state),
  KDoc says it's fixed at creation and stays true after the window closes; rename agent `subtreeRoot` back to `root`
  (it's the top-most root; KDoc "The root session at the top of this session's tree: itself for a root session"),
  `subtreeScope`/`cancelSubtreeScope` stay; add public `@UnstableApi` `CoroutineContext.clientSubagentSession`
  (name follows the type like `clientSession`; `baseClientSession` already follows `BaseClientSession`, no rename).
  `SubagentClientOperations` <-> `SubagentSessionWrapper` cycle kept (inherent; KDoc states the split).

## Done
- M0 research — report `.agents/research/subagents-wire-shapes.md` (upstream `13b76582`): no wire-shape change since
  `c392d7e9`; only line numbers shifted and the RFD is now Preview. Report lists decoding details the spec omits.
- M1.1 move `MaybeUndefined` to `model` — `fc3ea32` — pure package move, `:acp-model` dump updated, `check` green.
  Note: `decodeMaybeUndefined` swallows malformed values (becomes `Undefined`), so strict fields (`cwd`,
  `additionalDirectories`) must not use it as is.
- M1.2 capability and state types — `51b582d` — `SubagentCapabilities`, `SubagentSessionCapabilities`,
  `SessionCancelCapabilities`, `StateUpdate`, `IdleStopReason`, `ClientCapabilities.subagents` (after `_meta`);
  internal `JsonObjectSerializer`, `decodeOrNull`, `lenientMeta` helpers; `check` green. Possible follow-up: v1
  `Usage` decodes strictly, so one bad optional field nulls the whole `usage` (Rust nulls only that field).
- M1.3 session update variants — `7acd10d` — `SubagentUpdate`, `SessionMessage`, `SessionMessageChunk`,
  `ToolCallContent.Session`; v1→v2 `toV2()` throws `removedV1EnumVariant` for the three new updates; sample
  `render()` got print-only branches; `check` green. M1 (model layer) complete: range `a0dcdc5..7acd10d`.
- M1 review — `.agents/research/review-m1-model.md` — no blockers; nits 2-4 (untested conversion branches, `//`
  comment block, stale `MaybeUndefined` KDoc) go to fix slice M1.4; nit 1 (`V1ClientCapabilities.toV2()` drops
  `subagents`) is a v2 follow-up; lenient-`ACPJson` and strict-`Usage` nits accepted/pre-existing.
- M2.1 agent announce and routing — `9bd0d26` — `agent/AgentSubagents.kt` (public API), `agent/SubagentWrapper.kt`
  (`SessionSubagents`, `SubagentWrapper`), `ChatSessionWrapper` supertype and `_subagents` table in `Agent.kt`,
  root checks in `RemoteClientSessionOperations`, `Protocol.newConnectionScope`; 35 tests; `check` green.
  Seams: `SubagentWrapper.isCancelGranted`/`.session`/`onFeedEnd(failure)`/`eventsState`, `Agent.subagentOrNull`,
  `SessionWrapper.subtreeScope`, `Agent.unregisterSubagent`. Public KDoc already states the M2.2/M2.3 contract.
  Programmer choices for the M2 reviewer: corrective `SubagentUpdate` sent inline (not launched); per-child
  atomicfu lock around sends; lazy subtree scope; `.subagents` throws ISE outside a session; `announce` throws ISE
  when its collection is cancelled before announcing.
- M1.4 model review fixes — `6b4dc10` — conversion-branch tests, block comments, `MaybeUndefined` KDoc; `check` green.
- M2.2 child cancel, rejections, feed end — `6105d21` — `SubagentWrapper.cancel()`, `Agent.checkNotSubagent`
  ("Session X is a subagent", `-32602`), `onFeedEnd` sends `unknown`; 54 tests in `AgentSubagentsTest`; `check`
  green; no API change. Programmer choices for the M2 reviewer: a cancel arriving while the factory still runs is
  dropped; a feed that ends after the SDK cancelled its job sends nothing even if the flow swallowed the cancel.
- M2.3 subtree cleanup and replay — `7ab77d2` — cleanup on `session/close` (`Phase.REMOVED`), replay window on
  `SessionWrapper`, deferred collections started in `afterResponse` before `postInitialize()`, failed load drops
  registrations, `forkSession` KDoc; 69 tests; `check` green; no API change. M2 (agent side) complete: product
  commits `9bd0d26`, `6105d21`, `7ab77d2`. Known gap for the M2 reviewer: a child's permission/fs/terminal/
  elicitation request racing cleanup can go out after the close response (phase check then send, no lock).
- M3.1 `clearCancellation()` fix — `955965c` — replaces `startTurn()` in `PendingPermissionCancellation`; v1 and
  v2 callers switched; regression tests in `ClientPermissionCancellationTest` and v2 `ClientSessionTest` failed
  before the fix; `check` green; no API change.
- M3.2 extract `ClientSessionRegistry` — `a095659` — `client/ClientSessionRegistry.kt` owns the session map
  (`deliverSessionUpdate`, claims, reaping, `failSessionHolder`, plus `getSession`, `isSessionCompleted`);
  `ClientSessionImpl` takes `onClosing`/`onClosed`; `Client.handleSessionRequest`, `createSessionElicitation`,
  `createClientSession`, `removeClosedSession`; no test file touched; `check` green; API dump untouched.
- M3.3 deprecate `ClientSession.client` — `751a644` — WARNING-level deprecation; no usages in the repo; `check`
  green; API dump untouched.
- M2 review — `.agents/research/review-m2-agent.md` — 0 blocker, 4 should-fix, 7 nit. S1 factory CE after the
  announcement escapes into an active caller; S2 cancel during a running factory is dropped; S3 load/resume of an
  already-open root leaves the old subtree registered; S4 test gaps. N1 late child requests after close (accept),
  N2 grant between response queued and `afterResponse` treated as history, N3 `remove()` vs "not announced",
  N4 load-response build failure leaves replayed children, N5 two weak tests, N6 `@property` on an interface KDoc,
  N7 `AGENTS.md` (M6).
- S3 research — `.agents/research/reopen-open-session.md` — v1 is silent on load/resume of an already-open
  session (no error code fits); v2 docs, Rust SDK and the pre-subagents Kotlin agent accept it; replacing the
  wrapper is pre-existing and cleans nothing (old turn keeps running).
- M3.4 `Protocol` response hook — `21966d5` — internal `sendRequestRaw(method, params, sessionId, onResponse)`,
  typed `Protocol.sendRequest(method, request, onResponse)` in `protocol/Protocol.extensions.kt`,
  `encodeRequestParams`/`decodeResponseResult`, ordering KDoc, `ReadLoopOrderingTest` (12); `check` green; no API
  change. Beyond the design: a cancelled call drops its hook; remaining race: the hook may have run for a call that
  then ends with `CancellationException` (M3.5 must tolerate it).
- M3.5 opening boundary — `d505b25` (`feat!`) — `common/SessionEvent.kt`, `notify(SessionEvent)` + extension,
  `QueuedItem` (`Update`, `Opened`), `markOpened`, `claimForOpening(replayRequested)`, one opening per holder
  (`Opener` gone), registry `claimSessionHolder`/`claimAnsweredSessionHolder`, all four openings use the typed
  hook, samples/e2e/README/AGENTS.md moved, `ClientSessionOpenedTest`; `check` green; `:acp` dump reviewed.
  M3 complete: product commits `955965c`, `a095659`, `751a644`, `21966d5`, `d505b25`. Programmer choices for the M3
  reviewer: `Opened` is delivered only after the request returned (`completeOpening`); once delivered the opening
  cannot fail (rest delivered in `NonCancellable`, `notify` failures logged) — a behaviour change vs. today.
- M3 review — `.agents/research/review-m3-opening-boundary.md` — 0 blocker, 3 should-fix, 4 nit. Should-fix 1-3
  all concern `completeOpening` delivering `Opened` + rest in `NonCancellable` (cancelled caller loses the session
  but it stays registered and blocks a retry; app `notify` uncancellable; test gap). Nits: release-note wording,
  `//` runs in `ClientSessionRegistry.kt:46-49`, `:117-121`, `elicitation/complete` quirk, unused
  `replayRequested`/`isAnswered` (M4 uses them). Pre-existing follow-ups: `close()` removes the holder by id not
  identity; failed `session/close` leaves the id taken.
- M2.4 agent review fixes — `5bca6d3` — S1 converted to ISE; S2 latched cancel (grant at delivery time decides);
  S4 tests; N1 KDoc; N2 fixed via new internal `CoroutineContext.invokeOnResponseQueued(hook)`
  (`Protocol.extensions.kt`, runs when the transport accepts a success response; `endReplay()` moved there);
  N3 fixed without a regression test; N4 `discardFailedLoad` also removes the root; N5, N6; 78 tests; `check`
  green; no API change. Not yet reviewed: include `5bca6d3` in the M4 review brief.
- M4.1 client types — `57b948c` — `BaseClientSession` (+ `CoroutineContext.baseClientSession`),
  `ClientSubagentSession(sessionId, parentSessionId, operations, protocol, parentCwd, parentAdditionalDirectories,
  children: SubagentList)` with `applyPatch(update, parentOpened)`, `onParentOpened(promote)`, `markClosed()`;
  `SubagentOperations`; `SubagentList`; `BaseClientSessionRuntime.kt` extensions (`deliverUpdate`,
  `answerPermissionRequest`, `executeWithSession`, `asContextElement`); holder has `subagents`, `rootParameters`,
  `claimForOpening(replayRequested, parameters)`; 25 tests; `check` green; `:acp` dump reviewed. TODO seams left
  for `SubagentPendingPermissions` in the child constructor, `cancel()` and the child permission branch.
- M4.3a `SubagentPendingPermissions` — `c03d9df` — class + 19 unit tests; `ClientSubagentSession` takes
  `pendingPermissions` (last constructor parameter), `cancel()` calls `cancelByClient()` in `finally`; child branch
  of `answerPermissionRequest` races through it; `check` green (after one flaky Kotlin/JS internal compiler error,
  second occurrence); no API change. Caller contract for M4.2:
  `if (update.state != MaybeUndefined.Undefined) onState(update.state.valueOrNull(), live)` with `live` = the
  root's `answered` flag at arrival; `onOpened(promote = !replayRequested)` from `markOpened` on each descendant;
  `close()` on descendants at root close (M4.4). Deviation: `onState` sets `activeWork` before
  `clearCancellation()` (race-safe order).
- M6a agent-side docs — `9ce0d52` — nine `AGENTS.md` lifecycle bullets (notification ordering,
  `invokeOnResponseQueued`, `newConnectionScope`, announcement/routing, cancel grant, `unknown`, close cleanup,
  replay window, per-child lock); block comments in `ClientSessionRegistry.kt`; `check` green.
- M3.6 cancellable opening — `2f35a77` — `completeOpening` no longer in `NonCancellable`; cancel or `notify`
  failure from the marker on fails the opening through `failSessionHolder`; six new tests in
  `ClientSessionOpenedTest` (all failed before the fix); KDoc and `AGENTS.md` holder bullet reworded; corrected
  release note in the commit body (replaces `d505b25`'s); `check` green; no API change.
- M2.5 agent reopen fix — `38a25dc` — `createSession` runs `closeSubtree(old)` right after replacing an open
  root (after the app's factory, before the new replay and the response); a throwing factory leaves the old tree;
  a failed reload leaves the id absent with no orphans; six tests (four failed before); `AGENTS.md` + KDoc;
  `check` green; no API change. Not done: rare transient "already registered" on reload under true parallelism
  (old root's `announce` between cleanup's scan and its own re-check).
- M4.2 client registration and delivery — `d18dc66` — `QueuedItem.Announcement`/`Patch`, nullable `Opened`;
  registry sorting (`queuedItemFor`, `registerChild`, `blocksId`, `failSessionTree`); `SubagentSetup.kt`;
  `consumeSubagentQueue`; `markOpened` walk + `markSubagentOpened`; `followSubagentState`; consumer state;
  `ClientSubagentSessionsTest` (21), `ClientSessionRegistryTest` (6); `check` green (one JS flake, third
  occurrence); no API change. Deviations for the M4 reviewer: a child's consumer waits for its parent's holder to
  complete before switching to synchronous delivery (so child live updates and requests wait for the root's opening
  call); child `notify` failures after its `Opened(null)` are only logged; the cascade cancels consumers without
  joining; late-path announcements go through `lateDeliveries`; `createSubagentOperations` runs with no session
  context on the early path. Gaps: no test for connection end during a factory; root `configOptions` not checked
  against a child's `config_option_update`.
- M4.4 client close — `ced95e7` — `closeDescendantPermissions` in `onClosing`; `removeSessionTree` (replaces
  `removeSessionHolder`); closable `SubagentList`; `SubagentConsumer` context element so a close started from a
  child's own consumer does not join itself; `ClientSubagentCloseTest` (9) + registry test; mutation-checked;
  `check` green (JS flake, fourth occurrence); no API change. M4 (client side) complete. Residual risks for the
  reviewer: deliveries already under way when `close()` runs can still reach a closed child's operations once; a
  child announced after `onClosing` but before the close response gets fresh (open) permissions.
- M4 review — `.agents/research/review-m4-client.md` — 0 blocker, 3 should-fix, 10 nit. (1) child permission
  requests enter the cancel race only after the holder completes; (2) `removeSessionTree` never closes
  `subagentPermissions`, and a child announced during a close gets open permissions; (3) `AGENTS.md` lacks the
  client rules. Nits: (4) child whose `notify` fails before `Opened` stays live in `subagents`; (5) close KDoc
  "no more calls"; (6) agent reopen race, 3-line fix; (7) factory-path `drainHistory` CE unwrapped; (8) failed
  child holders of a failed root never leave the map; (9) cascade without join; (10) `createSubagentOperations`
  context undocumented; (11) `SubagentConsumer` element inherited by app coroutines; (12) N3 no regression test;
  (13) 500 ms wall-clock test.
- M5 integration tests — `c4b62c9` — `SubagentsTest` (16 scenarios) × STDIO and WebSocket on JVM; helpers in
  `SubagentTestSupport.kt`; 24 reruns clean; `check` green; no product bugs. Notes: during a prompt turn a root's
  updates go to the prompt flow, not `notify`; the spec's relay example `announce(id, parent = x) { }` does not
  compile (`parent` is the last parameter). Not covered: terminal call from a child.
- M4.5a review nits — `1bb9dcc` — removal closes `subagentPermissions`; close KDoc; agent `registerSubagent`
  reads the closed flag inside the update (reopen race); factory `drainHistory` wraps a `notify` CE; failed roots'
  descendants leave the map when reaped/replaced (`replacing()`); `ensureActive()` before each pre-marker receive;
  `createSubagentOperations` context KDoc; own-consumer detection by job ancestry (the `SubagentConsumer` element
  is gone); deterministic timeout test; 7 regression tests failed before; `check` green (JS flake again); no API
  change. Not done: nit 12 (unreachable from a test).

- M4.5b M4 review decisions — `f684ec3` — (1) `Client.kt` takes `subagentPermissionsOrNull` at arrival and
  `SubagentPendingPermissions.answerPermissionRequest` runs `getSessionOrThrow` inside the race; (2) `closing` flag
  on the root holder (`isClosing`, `markClosing()`); (3) `failSessionTree` and `SubagentSetup.setUp` (when
  `startConsumer` returns false) call `markClosed()`, `applyPatch`/`onParentOpened` no-ops on a closed child; gap a
  factory CE → ISE; gap b post-marker loop takes an item only after `ensureActive()`; gap c separate `removed`
  flag read inside `registerChild`'s update. Six regression tests failed before; the gap b test guards the new
  loop only; gap c has no test (needs real parallelism); `check` green (JS flake, sixth occurrence); no API
  change. Residual for a reviewer: an update handed to a pre-marker consumer's suspended `receiveCatching` and
  then cancelled is dropped by the channel (would need `onUndeliveredElement`); a waiting request whose holder
  fails at the moment its signal completes can get an error instead of `cancelled`.

- M6b docs — `0bd1962` — twelve client-side subagent bullets in `AGENTS.md` (`CLAUDE.md` is a symlink to it); the
  design spec updated in place for every later decision, relay example fixed, pin moved to `13b76582`, RFD and
  `client.rs` line cites shifted, RFD status Preview. Not done: Kotlin file:line cites in the spec are stale; the
  spec's Deliverables section still reads as a plan. Programmer-worded rationale the user may want to skim: spec
  decisions 6 (accepting a re-open), 19 ("Leaving it live was rejected"), 25 ("Narrowing the KDoc was rejected").
- Final checks (orchestrator, at `0bd1962`) — `./gradlew check` green (no flake); `.agents/` reference grep empty;
  cumulative dumps vs `39ecba6`: `acp.api` adds the agent subagent API, `BaseClientSession`,
  `ClientSubagentSession`, `SubagentOperations`, `SessionEvent`, and replaces the abstract
  `ClientSessionOperations.notify(SessionUpdate, JsonElement)` with `notify(SessionEvent)` plus an extension (the
  intended `feat!` break); `acp-model.api` moves `MaybeUndefined` out of `model.v2` and adds the new types.

- Final review — `.agents/research/review-final-fixes.md` — 0 blocker, 2 should-fix, 9 nit. (1) a close called
  from a subagent's own `notify` inside `withContext(NonCancellable)` (or another scope) leaves the consumer's job
  tree, joins its own consumer and hangs (regression from `1bb9dcc`'s job-ancestry detection); (2) on a resume, an
  `idle(cancelled)` arriving before the answer never answers permission requests already waiting. Nits: 3 and 4
  residual risks (leave / optional), 5 permission handler looks the id up again inside the race, 6 handler
  start order undocumented, 7 spec `:2318` names the wrong side for `removed`, 8 `AGENTS.md:168` + KDoc misstate
  when a child is marked closed, 9 accept, 10 N3 test still open, 11 stale Kotlin cites (known).
- User instruction 2026-11-16: fix the review findings (bring only judgement calls to the user), then push and
  open a PR, stacked on #156 (base `eugenethedev/permission-cancellation-fix`), comprehensive but not verbose.
- Orchestrator choices for the fix slice (reported to the user): (1) own-consumer detection = job ancestry OR a
  context element (a false "own consumer" only skips a join; a missed one deadlocks); (2) a held cancellation is
  promoted when a non-replaying opening is answered, like the held active-work value.

- M7 final review fixes — `28348bb` — finding 1: own consumer detected by the `SubagentConsumer` context element
  OR job ancestry (`runsIn`); limitation in the `ClientSession.close` KDoc: a close in a fresh-context coroutine of
  another scope that `notify` awaits non-cancellably still deadlocks. Finding 2: `setPendingAside()` +
  `heldCancellation`; a non-live `idle(cancelled)` is promoted at `onOpened(promote = true)` and answers exactly
  the requests pending at its arrival (later requests reach the app; a later held `running` doesn't undo it);
  dropped on a load. Finding 5: `subagentHolderOrNull`, the handler awaits that holder. Finding 6: request handler
  start order documented (`Protocol` KDoc, `AGENTS.md`), caveat: a response right after a request may be handled
  before that request's handler starts. Findings 7, 8 docs. Finding 4 not done (fix would touch the shared
  `PendingPermissionCancellation.answerUnlessCancelled`, also used by v2). `check` green at `28348bb`
  (programmer and orchestrator); no API change; `.agents/` grep empty.

- Rebase onto `master` (2026-11-16) — #156 was squash-merged as `ed806e0`; the branch was rebased with
  `git rebase --onto origin/master 9941000`, so every commit hash above is the pre-rebase one (match by subject).
  One conflict, in `PendingPermissionCancellation.kt` at M3.1: upstream landed the same fix under the name
  `startTurn`; our `clearCancellation` version was kept. Upstream's three v2 `ClientSessionTest` tests came in and
  pass. `check` green after the rebase.

- R1 agent wrapper renames and split — `51c0349` — `SessionWrapper.kt` (base, `SessionWrapper`,
  `RootSessionWrapper`, NES, file-private `PromptSession`, context helpers), `SubagentSessionWrapper.kt`,
  thin `SubagentClientOperations.kt` (send state and locked sends stay in the wrapper; internal `sendOnStream`
  pass-through); `subtreeRoot`; spec symbol names; `check` and `apiCheck` green; no API change.
- R2 direct sends — `e5b28b5` — built on the user's own edit; `sendOnStream` and
  `RemoteClientSessionOperations.sendUpdate` deleted; both wrapper sends and the root `notify` call
  `AcpMethod.ClientMethods.V1.SessionUpdate` directly; `check` and `apiCheck` green; no API change.
- R3 agent single snapshot — `29e3516` — `_sessions = atomic(SessionTable(roots, subagents))`; one update per
  open/register/close/failed load/re-open; `removeRoot` by identity; `cancelSubtreeScope()`; flag, `replaced` and
  `resolveParent` check gone; a root opened under a subagent's id fails ("... as a subagent"); 2 regression tests
  (re-open during close, id collision) failed before; `AGENTS.md` + spec step 2; `check` green; no API change.
- Client race check — `.agents/research/client-register-child-race.md` — no hole: every client removal/reap update
  returns `storage.copy(...)` (always a new reference); `closing` is a correct Dekker pair; `isAnswered` read before
  `addChild` is safe only by read-loop order (documented). Fragility: correctness rests on `copy` allocating.
  Out of scope: a failed subagent whose parent failed stops blocking its id; the speculative path may reuse it.
- R4 client `registerChild` — `a211818` — `removed` flag and both `isFailed` reads gone; root check
  `storage.sessions[root.sessionId] !== root`; invariant verified path by path; `check` green; no API change.
- R5 client holder split — `a4403b8` — `sealed class ClientSessionHolder`, `RootSessionHolder` (`RootPhase`/`Stage`,
  `phase.isAtLeast`, `markOpened` strict), `SubagentSessionHolder` (non-null `parent`, `permissions`, consumer), typed
  lookups and queued items; one file (709 lines); 2 new holder tests; `check` green (JS flake once); no API change.
  `markClosing` left tolerant: `closeDescendantPermissions(rootId)` looks the root up by id, so a stale session's
  close can reach a later unanswered holder of the id; it's now left unchanged (before: its `closing` flag was set).
- Close identity research — `.agents/research/client-close-identity.md` — v1 `session/close` is keyed by id only;
  an id may be active again after a later load/resume; close is idempotent (agent SHOULD answer empty for unknown/
  closed id, client SHOULD treat `-32002` as success). `ClientSessionImpl.close()` has no guard: a stale object's
  close marks/removes the newer holder AND sends `session/close`, closing the newer session on the agent.
  Recommended (b): send only while `sessions[id] === holder && holder.blocksId`, callbacks on the holder; stale close
  after a successful close returns an empty response locally; open choice for an object whose opening failed (b1
  throw ISE, recommended; b2 send if no other holder owns the id). Adjacent: client fails on `-32002`; agent answers
  `-32602` for an unknown id; `close` KDoc says "not part of the spec yet"; v2 client also removes by id.
- R6 client close by own holder — `ca58895` — `ClientSessionRegistry.isOpen(root)`; stale close after success →
  empty locally; failed opening → ISE; callbacks take the holder; `rootHolderOrNull` gone; `markClosing` strict;
  `removeClosedSession` drops elicitations only for removed ids; tests failed before; `check` green (JS flake once).
- R7 `-32002` as success — `a015588` — catch `RESOURCE_NOT_FOUND` in `close()`; KDoc drops "not part of the spec
  yet"; `@UnstableApi` kept (later removed in R13); `check` green.
- R8 agent unknown-id close → empty result — `0da96b6` — subagent id still `-32602`; `check` green; e2e READMEs and
  TCK (ACP-CLOSE-001/002) don't conflict.
- v2 close research — `.agents/research/v2-close-identity.md` — same bug in the v2 client; v2 allows re-resume of an
  active id (two objects, one live session), so the fix is an internal "ended" flag set by `removeSession`, not
  v1's registered-holder rule; `-32002` → success for `close` and `deleteSession`. v2 agent: unknown-id close goes to
  the app (default `-32602`), `-32602` instead of `-32002` for prompt/set_config_option, re-resume overwrites the
  wrapper without closing the old `AgentSession`; replaced client object's `updates` ends but can still close.
- R9 v2 client close — `4ae65f2` — private `ended` flag set by `removeSession` on the object it removes and by a
  successful `close()` on its own object (beyond brief: a replaced object would otherwise close a later resume);
  ended → empty locally; `-32002` success for `close` and `deleteSession`; KDoc incl. replaced-by-resume; 10 tests
  (6 failed before); `check` green; no API change. v2 agent untouched.
- R10 AgentSubagents classes + KDoc — `41a88a9` — `AgentSubagentContext`/`AgentSubagents` final classes with
  internal constructors in `AgentSubagents.kt`; `SessionSubagents` merged and deleted; `acp.api` shows only the two
  types changing shape, no constructors; KDoc restructured (Removal/Replay run-in headings, `announce` before/after
  bullets), every fact kept, but not much shorter (class doc 33→30, `announce` 39→38 lines); spec updated;
  `check` green.
- R11 KDoc subheaders — `640fade` — `**Removal.**`/`**Replay.**` → `### Removal`/`### Replay` in the `AgentSubagents`
  class doc (first `#` headers in repo KDoc; level 3 under Dokka's headings); KDoc only, `apiCheck` green.
- R12 AGENTS.md long-comment rule — `b086a68` — new "Structuring long comments" subsection in Comments and KDoc:
  no wall of text, short paragraphs, bullets for case matrices, `###`/`####` headings for long KDoc, short comments
  plain prose, block comments structured without headings; Use/Avoid example.
- AGENTS.md example fix — `0f54be5` — orchestrator, at the user's request: three-section Use example, same content in Avoid.
- R13 v1 close stable — `4a32688` — `@UnstableApi` and banners removed from `CloseSessionRequest`/`Response`,
  `SessionCloseCapabilities`, `SessionCapabilities.close`, `AcpMethod.AgentMethods.V1.SessionClose` and its deprecated
  alias, `AgentSession.close`, `ClientSession.close`(+impl); upstream v1 schema `d98a032`; one opt-in removed (Agent.kt
  close handler); `.api` dumps unchanged; v2 untouched; `check` green.
- R14 BaseClientSession banners — `acc6937` — banner "Kotlin API for client-side subagents" on `BaseClientSession`,
  `baseClientSession`, `ClientSubagentSession`, `SubagentOperations`; interface reason in `BaseClientSessionRuntime.kt`
  and spec; compiler: type marker doesn't reach members read via `ClientSession`, so `cwd`/`additionalDirectories`
  keep `@UnstableApi`; `subagents` lost it (warns via its element type) — restore suggested to the user; `check` green
  (orchestrator rerun).
- R15 review bundle — `53f3fb9` — `@UnstableApi` back on `BaseClientSession.subagents`; `subagentOperations` merged
  into narrowed `clientOperations`; `isReplayed` KDoc (snapshot); agent `subtreeRoot` → `root`; new public
  `CoroutineContext.clientSubagentSession` in `BaseClientSession.kt` (+3 tests, `acp.api` +1 getter); `check` green
  (2nd run, 1st JS flake).

- R16 client review cleanups — `b3e320b` — renames (`answerPermissionRequest`, `failSessionTree`, `getRootSession`);
  subagent permission answer on `SubagentSessionHolder`, unreachable dispatcher removed; spec refs removed;
  `deliverOpened` inlined; `removeSessionTree` uses `getAndUpdate`; `runsIn` helpers top-level; tests use a real
  holder (`openChild`); `check` green (2nd run, JS flake); no API change. `runCatching` in `failSessionTree` kept
  (runs under `NonCancellable`; user accepted as is).

- R17 children state + `registerChild` — `e618862` — one atomic `Children(byId, listed, sealed)` in the holder,
  `SubagentList` deleted, `listSubagent`, `sealChildren()`; marker taken in the `SubagentSessionHolder` `init`; closing
  check in `addChild`; map CAS inline in `registerChild` (user dropped the `enterSessionMap`/`MapEntry` helper after
  review); `ClientSubagentSession(children: () -> List<…>)`; `awaitSession`; KDoc restructured; AGENTS.md; 2 new
  tests; `check` green (2nd run, JS flake); no API change.

## Next
Subagent examples goal (user 2026-11-23): comprehensive samples of subagent use from the agent side and the client
side, with straightforward structured comments. Plan APPROVED (user 2026-11-23):
- Layout like Koog `examples/simple-examples`: same module (`samples/kotlin-acp-client-sample`, name kept), one
  package per sample under `com.agentclientprotocol.samples` (`support/`, `simple/`, `gemini/`, `v2/`, `auth/`,
  `subagents/`), a `registerSampleTask(name, mainClass)` JavaExec helper with one task per sample (`runSimpleAgent`,
  `runGeminiClient`, `runV2SimpleAgent`, `runV2Negotiation`, `runAuthStatus`, `runSubagents`); plain `run` keeps the
  simple agent default, `-PmainClass` stays. New `samples/README.md` (table: what it shows, protocol version, task,
  external needs; how to run; section per group); root README "Sample projects" shrinks to a pointer; AGENTS.md
  samples paragraph updated.
- Subagents sample: runnable scripted app (pipe/in-memory, one process), agent side + client side + app files; KDoc
  overview per file listing scenarios, numbered section comments, inline comments only for non-obvious protocol
  rules. Agent scenarios: capability null check; announce with factory (title/description/grant/running) + events;
  child work via context.client incl. permission; parent links (ToolCallContent.Session, session_message after
  announce returns); patches as events; cancel → idle(cancelled); idle(error) + SDK `unknown` on an active feed end;
  nested grandchild; root close stops children; relay pattern `announce(…, parent = …)` as a short last section (user:
  yes). Client scenarios: `subagents` capability in ClientInfo; root ops implement SubagentOperations (child ops too);
  parent notify renders announcement/patches from `subagents` + session links; `clientSubagentSession` in child ops;
  child stream history → Opened → live; child permission answered, pending one answered `cancelled` on cancel;
  `canCancel`/`cancel()`; root close closes children. Script: init, new session, one prompt with three children (one
  completes, one cancelled by the client while its permission is pending, one fails and has a grandchild), close.
- Anti-rot: a JVM test in the samples module, run by `check`, runs the same scripted entry point and asserts key
  events and order.
- Slices: E0 restructure (moves + tasks + samples/README + root README + AGENTS.md) → E1 subagents live flow (+ test
  infra + README row) → E2 `session/load` replay (record children, replayHistory re-announces, fresh grant after load;
  client sees history → Opened, canCancel false until the fresh grant; test extended). Each uncommitted for the user's
  review. E1/E2 on opus. Reviewer (user: yes) over E0–E2 at the end, then the user reviews.
- E0 COMMITTED `7eff9a3` (user approved 2026-11-23). E1 COMMITTED `e03539a` (user approved 2026-11-23). E2 DONE, UNCOMMITTED (12/12 tests, `--rerun` 5x,
  stress 480, `check --rerun-tasks` green): `agent/SessionHistory.kt` new; load via early `loadSession(…, operations)`;
  reviewer gets a fresh grant after load and is cancelled; others history-only. OPEN for the user: replayed parent
  stream interleaving differs from live (factory sends the child's whole history before returning, per the
  `AgentSubagentFactory` KDoc; exact interleaving would need sends through the child context after the factory
  returned). Reviewer over E0–E2 DONE → `.agents/research/review-examples.md`: 0 blockers, 4 should-fix (replay reorders parent
  stream — reviewer recommends live order via the child's stored context; tree at loaded root's Opened can miss
  grandchildren off a single thread; follow-up session_message links an unsent message; CancellationException as a
  domain signal), nits; SDK follow-up: `AgentSubagentFactory.createSubagent` KDoc. Fix plan shown to the user. E1 restructure was (build 7/7, `--rerun` 5x, stress 420, runSubagents ok;
  `check` green from cache). Tree: subagents/{SubagentsApp,Transcript}, agent/{DelegatingAgentSupport,
  DelegatingAgentSession,DelegatedSubagentFactory,DelegatedSubagentSession,DelegatedTask,SampleTasks,
  RemoteProbeSubagentSession,NativeRelayedSubagentSession,FakeNativeAgent,SessionUpdates}, client/{SubagentsClient,
  TreeClientSessionOperations,RootClientSessionOperations,SubagentClientSessionOperations,ScriptedUser,ClientTranscript};
  no fake/ pkg; 2 deferreds instead of 4; E2 hooks: DelegatingAgentSession.children, DelegatedSubagentSession.events.
  Was IN PROGRESS (user 2026-11-23: files hard to read; name files after the SDK component they implement/use,
  no `…AgentSupport`/`…ClientSupport` names that aren't those types; split into agent/ + client/ subpackages per the
  proposed tree; remaining calls delegated to the Opus programmer; lands with E1 as one commit). Before that: E1 DONE, UNCOMMITTED, awaiting the user's review
  (module build 7/7 tests, `--rerun` 5x, stress 420 runs x2, mutation checks, `check --rerun-tasks` green). Files in
  `samples/.../subagents/`: SubagentsApp, SubagentsAgentSupport, Worker, DelegatedTasks, NativeAgentRelay,
  SubagentsClientSupport, test SubagentsSampleTest, `logback-subagents.xml` (runSubagents only). Calls to raise with
  the user: root updates during a turn arrive through the prompt Flow (unlimited channel), so `subagents` getters can
  be ahead there — sample renders root-stream events from the update, getters shown in child notify; theoretical
  race for the probe's `unknown` (never seen in 840+ runs); WARN `CancelRequest for unknown request` every run (SDK
  follow-up: log level); module-wide logback config / `libs.logback.classic` follow-ups. Was: module build, each local task run, `check` green
  1st run. Calls: `TerminalClientSupport.kt` → `support/` (simple + gemini use it); `SimpleAgentSupport`/`V2SampleSupport`
  stay with their samples (auth imports both); Gemini KDoc names `runGeminiClient`; task group `samples`. Pre-existing:
  `runSimpleAgent` JVM doesn't exit after "Goodbye" (documented in samples/README, not fixed). Draft commit:
  `refactor: split the samples into one package per sample`.

TODO (user 2026-11-23, later goal, not now): consider a proper `docs/` directory with contribution and usage
guidelines; move suitable parts of AGENTS.md there (AGENTS.md keeps agent rules and points to it).

Explicit response hook goal (user 2026-11-23, design approved). R23 COMMITTED `8b8257d` (user will review later; not
pushed). Was: R23 DONE, UNCOMMITTED, awaiting the user's review
(focused tests, `:acp:jvmTest`, `:acp-integration-test:jvmTest`, `apiCheck`, `check` green 1st run; mutation: hook=null
fails 11 load/replay tests; no new opt-in warnings; `.api` diff = RequestOutcome only, as expected). Deviations: extra
`@OptIn` on `Protocol.requestHandlers`; error-reply hook test now cancels the handler locally while it prepares an
outcome (a throwing handler has no outcome). Open nit: `ResponseSlot.successQueuedHook()` still checks for a success
reply although the field is only set on the success path. Draft commit message in the programmer's report
(`refactor: return the response-queued hook through RequestOutcome`, body has the release note).
- Why (user): common and v2 code avoid implicit coroutine-context dependencies; v1 keeps its context accessors only
  for API compatibility. `JsonRpcRequestContextElement.slot` + `CoroutineContext.invokeOnResponseQueued` violate that.
- Decided (user): extend the existing `RequestOutcome` instead of an internal type. Drop `data` (no shims, no
  deprecated constructor/copy/componentN; small break: `copy`, destructuring, value `equals`; none in our code,
  verified). New field `onResponseQueued: (() -> Unit)? = null`, runs synchronously right after the transport accepted
  the success response frame, before `afterResponse`; skipped on error reply / frame never queued (like
  `afterResponse`); must not suspend/block/throw (throw logged). `mapResponse` forwards it. Whole class
  `@UnstableApi`; `setRequestOutcomeHandlerRaw` becomes `@UnstableApi`; opt-ins only at outcome-touching SDK sites
  (setRequestHandlerRaw default body, Protocol override + handleRequest, two test files file-level); no new
  unstable warnings elsewhere. Release note (PR description): `data` removal + stability downgrade.
- Protocol: `handleRequest` copies the hook into the slot before completing the reply (plain field; atomic + "already
  registered" check go); context element back to `request` only (master shape); `invokeOnResponseQueued` deleted;
  `Agent.createSession` returns `onResponseQueued = { sessionWrapper.endReplay() }` when replaying.
- AGENTS.md (user): explicit rule: no new implicit coroutine-context dependency passing in common and v2 code; prefer
  explicit dependencies; v1 legacy accessors stay for compatibility. Plus reword the two hook rules; spec lines ~615,
  ~2326 updated.

File layout goal (user 2026-11-23, DONE):
- Decided (user): internal machinery goes to `agent.internal` / `client.internal` (public types and sealed members of
  public types, e.g. `ClientSessionImpl`, stay). Why (user): avoid multi-component long files, fixed rules for
  agents/devs, discoverable file tree for onboarding, but no file per small helper.
- R22a pure move — DONE, committed `d42a7f9`: 14 main files (incl. `ClientSessionImpl.kt`) + 3 commonTest
  + `ElicitationSessionMapTest` moved, imports only; public `CoroutineContext.clientSession` moved verbatim into `client/BaseClientSession.kt`
  next to `baseClientSession`/`clientSubagentSession` (user: context accessors live with the sealed base, consistently),
  file gets `@file:JvmName("ClientSessionImplKt")` (master facade kept; the two PR-new getters move into it); `.api` diff = only the two PR-new getters moving facade; AGENTS.md `:acp` paragraph; `./gradlew check` green.
- R22b (agreed by user): a task wired into `check` that fails if anything public lives in an `internal` package.
  DONE, UNCOMMITTED for review: `buildSrc/.../CheckInternalPackagesNotPublic.kt` + registration in
  `acp.multiplatform.gradle.kts` (all 5 BCV modules, wired into `check`), fails on class entries under `/internal/` in
  `api/*.api`; negative control with a public probe failed as expected; AGENTS.md bullet; `./gradlew check` green.
- R22b tests (user request): extract matcher, JUnit unit tests + ProjectBuilder task test in buildSrc, wired so root
  `./gradlew check` runs them (proven by a temporary break). Tests done (11). User 2026-11-23: replace the nested
  `GradleBuild` wiring with an included build: `git mv buildSrc build-logic`, `includeBuild` in pluginManagement, root
  `base` + `tasks.check { dependsOn(gradle.includedBuild("build-logic").task(":check")) }`; use type-safe task
  accessors (`tasks.check`) wherever they exist, incl. the old `named("check")` in rust-agent-e2e. Then
  `./gradlew clean build`, commit R22b, start R22c. R22b DONE — committed `61fc0a4`.
- R22c file split — DONE, committed `2849640` (content verified identical modulo imports/loggers/one `@OptIn` moved to the class; `.api` unchanged; `./gradlew check` green; AGENTS.md `### File layout`). Rule ("File
  layout", AGENTS.md Kotlin Style):
  1. One concern per file, named after its main declaration; helpers live with what they serve (private decls,
     parameter/result types, extensions); coroutine-context elements and their accessors live with the family base.
  2. A file with several top-level declarations stays under ~400 lines; a single component may be longer.
  3. Hierarchies: for each class with subclasses, either its whole subtree is in its file, or each direct subclass
     gets its own file (its subtree by the same rule); keep together while it fits rule 2; nested subclasses are
     inside by definition; siblings sharing one concern may share a file named after that concern.
  4. Internal runtime machinery in `<pkg>.internal`; public types and direct subtypes of public sealed types stay.
  Splits: `client/internal/ClientSessionHolder.kt` → `ClientSessionHolder.kt` (base + nested `QueuedItem`),
  `RootSessionHolder.kt`, `SubagentSessionHolder.kt` (+ `SubagentOpening`); `agent/internal/SessionWrapper.kt` +
  `SubagentSessionWrapper.kt` → `BaseSessionWrapper.kt` (base + context element + accessors), `NesSessionWrapper.kt`,
  `SessionWrapper.kt` (abstract), `RootSessionWrapper.kt` (+ private `PromptSession`), `SubagentSessionWrapper.kt`;
  merge `SubagentClientOperations` into `RemoteClientSessionOperations.kt`. Unchanged: `PendingPermissions.kt` (whole),
  `AgentSubagents.kt` (one concern, user rejected the 4-file split), public client session files. Moves only, no
  `.api` change. No "legacy files" clause (v1 agent/client already comply; `protocol/Protocol.kt` out of scope).
- R22d spec paths — DONE, committed (9 path mentions in the v1 subagents spec now point at the current layout).
Current goal (2026-11-19): the user reviews PR #158 piece by piece; each round's notes become a refactor/fix slice.
Review round 20 (2026-11-22, notes on `PendingPermissionCancellation`/`SubagentPendingPermissions`/`SubagentOperations`/
`SubagentSetup`/`SessionEvent`). Answers given; discussion IN PROGRESS. The user has an UNCOMMITTED edit to the class
KDoc of `SubagentPendingPermissions.kt` (spec typo + shortened "followed at arrival" paragraph): keep it, build on it.
- D4 (agreed, user 2026-11-22), refactor only, no API change: `SubagentSessionHolder.startConsumer(session, scope, block)`
  → non-suspending `startOpening(session): Unit`: stores `_subagent`, claims the opening state, launches the opening in
  the connection scope (scope and `failSessionTree` reached through the holder's `setup`), and calls
  `session.markClosed()` itself when the opening was already stopped (no `Boolean`). The coroutine body stays a PRIVATE
  `suspend fun completeOpening()` (name kept: it does complete) and absorbs `SubagentSetup.consume`'s failure handling;
  `consume` and the block parameter go; `setUp`'s last line becomes `child.startOpening(session)`, and the "stays in the
  list" comment moves into the holder, reworded: the session object stays in the parent's `subagents`, closed; "removed"
  = removed with its root's tree after `session/close`; the failure/removal may have run before the store and missed it.
  Renames: `ConsumerState` → `OpeningState`, `consumer` field → `opening`, `markConsumerStopped` → `markOpeningStopped`,
  `SubagentConsumer` element → `SubagentOpening`, "a child's consumer" in AGENTS.md/KDoc → its opening ("the parent's
  consumer" stays: whoever delivers the parent's queue, incl. the root's opening call). Why not one public
  `completeOpening`: `setUp` runs in the parent's delivery and the child's opening waits for `parent.session.join()`, so
  inline would deadlock; a non-suspending launcher named "complete" would mislead.
- D1 REJECTED (user 2026-11-22): keep the hold-back, it follows the RFD (no-replay pre-answer snapshots are current
  but controls wait for success; a failed attempt invalidates what arrived during it). So N4 becomes ONE atomic state
  `Held(activeWork, setAside) | Live(activeWork)` replacing `activeWork`/`heldActiveWork`/`heldCancellation`;
  `setPendingAside` moves into the subagent class via D2's base (protected); `onOpened` KDoc restructured (N5).
- D2 (agreed, user 2026-11-22), refactor only, no API change: ONE file `PendingPermissions.kt` replaces
  `PendingPermissionCancellation.kt` + `SubagentPendingPermissions.kt` (precedent `agent/SessionWrapper.kt`); order:
  base, root, subagent. `internal sealed class PendingPermissions`: `protected val signal`; public
  `answerUnlessCancelled`, `close`; `protected open` `cancelPending`/`clearCancellation` (keep the `CLOSED` rule in one
  place; `CLOSED` stays in the base). `RootPendingPermissions`: `public override fun cancelPending() =
  super.cancelPending()` and same for `clearCancellation` (primitive names kept: commands; `ClientSessionImpl` call
  sites unchanged; field `pendingPermissions` keeps its name). `SubagentPendingPermissions`: `on…` names = events, the
  class decides the effect: `onState`, `onOpened`, `cancelByClient` → `onClientCancel`; `setPendingAside` PRIVATE here
  (uses `signal`); N4 single atomic `Held(activeWork, setAside) | Live(activeWork)`. AGENTS.md mention of
  `PendingPermissionCancellation` updated. Tests stay in their files (`ClientPermissionCancellationTest`,
  `SubagentPendingPermissionsTest`), renamed calls only.
- D3 (agreed, user 2026-11-22), KDoc only: `SubagentOperations` stays a plain interface (found by `as?` on the
  operations object; a lambda can't also be `ClientSessionOperations`). Interface KDoc: banner, summary (+
  `ClientSubagentSession` link), `### Implementing`, `### Capability`, `### Failure` (bullets: not in `subagents`,
  logged, announcement/patches reach parent's `notify` as plain updates, own + nested updates →
  `GlobalSessionUpdateHandler`, agent's requests fail, parent/siblings unaffected). Method KDoc: when called,
  `### Context` (subagent in returned ops; parent during the call; none for early load/resume), `### Ordering`
  (must not wait for a later parent event), `@return` (capability interfaces), one-line link to `### Failure`.
- Note-3-part-2 cut-off "So let's maybe": dropped by the user; the D4 comment wording stands.
- SPP class KDoc (agreed, user 2026-11-22), on top of the user's uncommitted edit: summary ("by the client or by the
  agent"; "a subagent's requests"), spec paragraph, `### Arrival order` ("States and requests are followed when they
  arrive, in wire order, not at delivery: the parent's stream can lag behind the requests: see [onState] and
  [answerPermissionRequest]" — reason clause restored), `### What answers the pending requests` (bullets
  onClientCancel / live idle(cancelled) via onState / close), `### Later requests after a client cancel` (bullets
  running|requires_action yes until next live state; idle no + reason; unconfirmed|unknown no + reason), `### History`
  (held until onOpened; applied after a no-replay call, dropped after a load), closing line "As in
  [PendingPermissions], the race … exactly one response". Stray `*` fixed.
- SubagentSetup KDocs (agreed, user 2026-11-22): class KDoc drops "runs the consumer"; `@param scope` = the subagents'
  openings; `failHolder` → `failSessionTree` ("fails the holder of a subagent whose setup or opening failed, and every
  holder below it, see [ClientSessionRegistry.failSessionTree]"); `scope`/`failSessionTree` become non-private `val`s
  (the holder reaches them via `setup`). `setUp` KDoc: `### Steps` numbered (create ops; build session + patch; list in
  parent's `subagents`; start opening → [SubagentSessionHolder.startOpening]), caller delivers announcement after;
  `### Failure` bullets (no SubagentOperations/factory throws incl. CE with active caller → only child's holder fails,
  logged; caller cancelled → rethrown; parent failed/closed during factory → not listed, not opened, holder never gets
  the session; only child's holder failed or removed with its root's tree → listed, closed, not opened). "added" →
  "listed".
- `SessionEvent.Opened` KDoc (agreed, user 2026-11-22), KDoc only: summary unchanged; `### Delivery` bullets
  (success → exactly one before any live update; fails before answer → none; after answer can still fail — caller
  cancelled or `notify` throws while delivering Opened + pre-takeover updates — Opened may have been delivered, treat a
  failed call as failed opening); closing line "Only the client runtime delivers it; agent code can't create one.";
  `@property response` unchanged.
- `onOpened` KDoc (agreed, user 2026-11-22): summary "Resolves what [onState] held back, when the root's answer
  arrives, and makes later states live."; pending-requests paragraph ("waits for its holder, which completes only
  after the answer"); `### After a call that replayed no history` (bullets: set-aside answered cancelled; latest held
  state decides foreground work); `### After a load` (bullets: nothing answered, set-aside stay pending, later
  cancel/close answers them; no foreground work); "parent's answer" → "root's answer"; `@param promote` unchanged.
- All R20 code and KDoc items agreed 2026-11-22.
- Plan (agreed 2026-11-22): R20a permissions (opus; D2, N4, SPP class + onOpened KDocs, AGENTS.md) → R20b setup/opening
  (D4, `failSessionTree`, setup KDocs) → R20c docs (`SubagentOperations`, `SessionEvent.Opened`). Each uncommitted for
  the user's review. R20a COMMITTED `f5594c3` and pushed (user 2026-11-22). R20b DONE, UNCOMMITTED, awaiting the user's review (client jvmTest 245
  green, `apiCheck` green, `acp.api` unchanged, `check` green 1st run). Deviations: base holder `setup` now `protected`;
  `setUp` Failure bullets corrected (factory failure also fails the holders below; rethrow only when the caller is
  cancelled AND the factory throws CE; a stopped opening happens only when the parent was sealed after the listing);
  registry test uses an `openingScope` on a test dispatcher; generic "consumer" wording kept where it means any queue
  reader / the parent's deliverer.
  (user 2026-11-23, folded into R20b, RESUMED same programmer) Dissolve `SubagentSetup`: `setUp`/`failSetup` → private
  base `ClientSessionHolder` methods (e.g. `setUpSubagent`); base takes `protocol`, `openingScope`, `failSessionTree`
  as ctor params; `SubagentSessionHolder(sessionId, parent)` inherits them from the parent; only `RootSessionHolder`
  is built with them (registry); `SubagentSetup.kt` deleted. `SubagentSessionHolder` stays in `ClientSessionHolder.kt`.
  The user approved the rest of the R20b diff (2026-11-23); after the merge lands, review only the merge delta, then commit.
  Merge DONE, uncommitted (client jvmTest 245 green, `apiCheck` green, `check` green on 2nd run, JS flake). Deviations:
  base ctor deps are plain `val`s (`protected` can't be read as `parent.protocol` in the subagent's super call);
  method named `setUpAnnouncedSubagent` (`setUpSubagent()` already exists in the subagent holder); registry
  `protocol`/`subagentScope` became `private val`. Awaiting the user's look at the merge delta, then commit + push.
  (user 2026-11-23) Renames: getter `setUpSubagent()` → `requireSubagent()` (it isn't a verb: it returns the stored
  session or throws); `setUpAnnouncedSubagent(...)` → `setUpSubagent(...)`. Sent to the same programmer. Done, uncommitted
  (client jvmTest 245 green, `apiCheck` green; `check` not re-run for the renames).
  R20b COMMITTED `563865d` and pushed (user 2026-11-23; `check` green before the commit). R20c DONE, uncommitted, awaiting the user's review
  (`:acp:compileKotlinJvm` + `apiCheck` green; no statement needed correcting). Note: the banner "not part of the spec
  yet" is stale since upstream made subagents stable; belongs to the `@UnstableApi` follow-up.
  R20c COMMITTED `56519ca` and pushed (user 2026-11-23). R20d STARTED (design spec sync for R19+R20 names; base
  `56519ca`; RFD line cites untouched). R20d DONE, uncommitted, awaiting the user's review (spec only, +204/−155).
  Also fixed: `failSessionHolder`→`failSessionTree`, claim function names, `getSessionOrThrow`→`awaitSession`,
  `closeChildren`/`SubagentList`→`sealChildren`/`Children.listed`, root state-order names; the Ownership bullet and
  decision 24 now say `ClientSubagentSession` takes its holder privately (matches R18 D3); one Tests item "child's
  consumer" → "parent's consumer". Kept as history: `PendingPermissionCancellation.kt` at line 30 (PR #156),
  `startTurn()`, `Opener`, `ElicitationRegistry`, `removeSessionHolder`. ~25 stale Kotlin file:line cites listed, left
  (existing follow-up).
  R20d COMMITTED `eeb3b8b` (with the user's `ToolCallContent.Session` KDoc trim), NOT pushed (the combined commit+push
  was blocked by the permission classifier; the user asked only to commit this time). Round 20 done.
  R21 DONE, uncommitted, awaiting the user's review (client jvmTest, integration SimpleAgentTest, `apiCheck`, `check`
  green 1st run; old code fails 3 new tests for the original reasons). `awaitSession` reads the map once: claimed root
  or subagent holder → wait; else `JsonRpcException(RESOURCE_NOT_FOUND)` "Session <id> not found". v2 untouched (own
  lookup, still `-32602`). New `ClientEarlySessionRequestsTest`; `SimpleAgentTest` fail-fast test now real; subagent
  test: request-created holder no longer blocks an announcement; one AGENTS.md rule added. Follow-ups: `data.sessionId`
  on unknown-session errors (spec `error.mdx:53-55`); v2 `-32602` alignment. (Design spec follow-up folded in.)
  R21 COMMITTED `9f0220d` with the design spec update (user 2026-11-23); pushed with R20d. Round 20 + R21 done; waiting
  for the next review round. Open follow-ups: `@UnstableApi` drop (upstream stable), spec cites (Kotlin file:line,
  RFD → stable page), `data.sessionId`, v2 `-32602`, class-file organisation brainstorm (TODO above), fork history.
Round 21 follow-ups (user 2026-11-23):
- (decided) Promote ONLY the v1 subagent MODEL types in `:acp-model` (drop their `@UnstableApi`); the Kotlin runtime API
  (`SubagentOperations`, `ClientSubagentSession`, `BaseClientSession.subagents`, `clientSubagentSession`,
  `AgentSubagents` …) stays unstable. Types still unstable upstream (fork, possibly `ToolCallContent.Session`) stay.
  Research DONE (`.agents/research/subagents-stable-schema-diff.md`, upstream `01a54c84`): all v1 subagent wire shapes
  identical to `c392d7e9`; every item MATCH. Except `StateUpdate.Idle.usage`: not in stable (Draft end-turn-usage RFD)
  → keep `@property:UnstableApi` on it (like `PromptResponse.usage`); `Usage` stays unstable. 10 annotations to drop
  (Subagents.kt:33,53,70; Capabilities.kt:98; SessionUpdate.kt:301,342,370; SubagentState.kt:33,106; ToolCall.kt:115)
  + their UNSTABLE KDoc banners. Conversions unaffected. ASKED the user: promote `MaybeUndefined`/`update()` and
  `MessageId` too (exposed by the stable types)? Unrelated stale annotations (PlanUpdateV2, PlanRemoved,
  SessionInfoUpdate, UsageUpdate, chunk messageIds stable upstream; PromptRequest.messageId/PromptResponse.userMessageId
  removed upstream) → follow-up, not this slice.
- (decided, no discussion) Fix the design spec's stale Kotlin file:line cites and move RFD cites to the stable page,
  using research `.agents/research/subagents-rfd-cite-mapping.md` (DONE: cites match RFD `13b76582`; ~110 refs / 65
  ranges, short forms `sub:`/`pt:`/`tc:`/`init:`/`rfd:`/`v2sub:`; material changes: cost incl. descendants, late-response
  restart MUST NOT→SHOULD NOT, ungranted child requests SHOULD be rejected with -32602 (our agent already does via
  acpFail), `session` content "only" w/o MUST, ~12 quoted phrases reworded). Slice C1 STARTED (programmer, docs + 2
  KDoc cite lines in `RemoteClientSessionOperations.kt`), uncommitted for review.
  C1 DONE, uncommitted, awaiting the user's review (spec + KDoc; `:acp:compileKotlinJvm` green). ~121 short-form refs;
  material edits as listed; Table E quotes reworded/dropped; Kotlin cites → symbol names; RemoteClientSessionOperations
  KDoc cites the stable pages. Left: Rust refs (lines 23-29), delete-vs-close, long lines not reflowed. For the
  promotion slice: spec ~165 still says `SessionReference` only in `schema.unstable.json` / Rust `unstable_subagents`.
- (user 2026-11-23) Promotion also covers `MaybeUndefined`/`update()` and `MessageId` (Rust's `MaybeUndefined` is
  public, ungated). Slice P1 STARTED (programmer, base `6e0d46c`), `:acp-model` files only; C1 stays uncommitted
  alongside (disjoint files). Spec ~165 fix deferred until C1 is committed.
  P1 DONE, uncommitted, awaiting the user's review (`:acp-model:apiDump` no diff; apiChecks, acp-model jvmTest, client
  jvmTest green; `check` green on 2nd run after JS flake, 2nd run short/incremental). 10 annotations + banners dropped;
  `Idle.usage` `@property:UnstableApi` + class `@OptIn` (like PromptResponse); `MaybeUndefined`/`update()`/`MessageId`
  promoted; 14 redundant opt-ins removed (compiler-confirmed); 10 runtime banners reworded ("protocol stable in v1,
  Kotlin API unstable"). Follow-up (API decision): `StateUpdate.Idle(...)` still warns for non-opt-in callers because
  `Usage` is in its constructor (same as `PromptResponse`). Draft commit body in the programmer's report (records
  upstream `01a54c84` / `9d44c77a`).
  C1 COMMITTED `5f0a72f`, P1 COMMITTED `7ca2356` (user 2026-11-23; `check` green before), pushed. Next (small, docs):
  spec ~165 still says `SessionReference` only in `schema.unstable.json` / Rust `unstable_subagents` — fix as a slice.
- Fork-history ambiguity: LEAVE IT (user 2026-11-23); raise upstream once fork nears stable. No code change. R21 (agreed, user 2026-11-22), a SEPARATE behaviour slice
  after R20 (R20c, R20d): `ClientSessionRegistry.awaitSession` stops creating speculative holders; a request waits
  only for a completed holder, a claimed root or a subagent holder; anything else (unknown id, or an unclaimed
  speculative holder an update created) fails at once with `-32002` (resource not found), also in the no-opening case
  (today `-32602`). Tests: `SimpleAgentTest:943` updated; new test: early request rejected with `-32002` while the
  `session/new` succeeds; cover both client paths. Research: `.agents/research/request-before-new-answer.md`. Design spec
  update (user: "update design doc as necessary") planned as R20d after R20c, covering all R20 renames. Was: (client jvmTest 245 green,
  `apiCheck` green, `acp.api` unchanged, `check` green on 2nd run, JS flake). Deviations: v2 `ClientSession` also uses
  `RootPendingPermissions`; `[close]` bullet corrected (no path closes a subagent's permissions when its holder
  fails: callers are `startClosing`, `addChild` while closing, `removeSessionTree`); "root's answer" in `onState`
  too; unreachable non-live-after-Live `onState` and second `onOpened` are no-ops; `workContinues` local. Open: the
  design spec `docs/superpowers/...` still names `PendingPermissionCancellation`/`cancelByClient` (~12×), left as is.
- Research DONE → `.agents/research/request-before-new-answer.md`: Kotlin DIFFERS from Rust. Kotlin: no opening in flight
  → `-32602` "not found" at once; any opening in flight (connection-wide counter) → a request creates a speculative
  root holder and waits unbounded on `session.await()` (answer names it → app sees it after the opening; otherwise
  `-32602` at the reap / `-32603` if the opening fails after the answer; no answer → hangs). Rust: never waits,
  `-32601` (data = method) at once for an unrouted id; load/resume route before sending (Kotlin same). Options (NOT
  decided, user to choose): (1) requests don't create speculative holders, wait only on a completed or claimed holder,
  else `-32601` at once (fixes: an early request's speculative holder can block a later subagent announcement of that
  id; `SimpleAgentTest:943` changes); (2) + case (a) `-32602` → `-32601` for requests; (3) codes only; (4) `-32002`.
- (superseded) Research STARTED (user asked to trace and confirm parity with Rust): a `session/request_permission` (and other session
  requests) before the `session/new`/`fork` answer → `.agents/research/request-before-new-answer.md`.
- Follow-ups recorded (not this round): drop `@UnstableApi` from v1 subagent types now that upstream is stable (start
  with a researcher diffing the stable schema vs our models); a `session/fork` streaming copied child history would be
  promoted as current (upstream ambiguity, fork unstable).
- Research done → `.agents/research/updates-before-opening-answer.md` (spec `01a54c84`, Rust `34aea54f`): subagents RFD
  Completed 2026-11-20, now stable v1 (`docs/protocol/v1/subagents.mdx:497-533` normative). v1 new/fork: pre-answer
  traffic unspecified (only v2 says SHOULD NOT / client MAY buffer or drop). resume: child updates current, may arrive
  before/after; no cancel until success + current grant; discard on failure. load: pre-answer caps/state are history.
  Hold-back conforms (resume state hold stricter but fine; new/fork promotion is an inference; a fork streaming copied
  child history would be promoted). Rust parks early v1 updates for unrouted ids, early requests get -32601.
  NEW open question for the user: drop `@UnstableApi` from v1 subagent types now that upstream is stable? Also:
  spec cites in code/docs should move to the stable page; unchecked: a `session/request_permission` before a
  pending `session/new` answer.
- (Superseded D1 text below kept for reference.) Still to discuss (proposed, NOT agreed): D1 states live when `answered || !replayRequested`, pre-answer states of a load
  ignored; `heldActiveWork`/`heldCancellation`/`setPendingAside`/`SubagentPendingPermissions.onOpened` go (behaviour
  change: a pre-answer `idle(cancelled)` of a resume answers at arrival; a failed resume then gives `cancelled` not an
  error; reverses the "held back, its effect included" rule; `ClientSubagentSession` held caps/state unchanged). D2
  abstract base `PendingPermissions` (public `answerUnlessCancelled`/`close`, protected `cancelPending`/
  `clearCancellation`), `RootPendingPermissions` (`onPromptStarted`/`onCancel`), `SubagentPendingPermissions`
  (`onState`, `cancelByClient` → `onClientCancel`). D3 `SubagentOperations` not a `fun interface` (found by `as?` on the
  operations object, a lambda can't be both); split KDoc: interface = what/discovery/capability/failure sections,
  method = call context, must-not-wait, return. Docs: `SubagentPendingPermissions` class KDoc sections (Cancellation
  triggers, Later requests by last live state) + stray `*` at line 19; `setUp` KDoc Steps/Failure; `failHolder` param →
  `failSessionTree` ("the holder of a subagent whose setup or opening failed, and every holder below it");
  `SessionEvent.Opened` `### Delivery` bullets. User's note 3 part 2 ended with an unfinished "So let's maybe" — ask.
- Draft slices: R20a permissions (opus; D1 if approved, D2, rename, KDoc, tests, AGENTS.md); R20b setup/naming (D4,
  param, `setUp` KDoc); R20c docs (D3, `Opened`). One at a time, uncommitted for the user's review.
Review round 19 (2026-11-21/22, `Client.kt`/`ClientSessionHolder.kt` notes). Plan agreed 2026-11-22; R19 done.
- R19 (agreed), refactor/docs only, no API change:
  1. Root: `drainHistory` private; new `completeOpening(session)` (factory path: history, opened marker, rest);
     `completeOpening(session, opened)` stays for the early path. `Client.createSession` and the test call the new one.
  2. Subagent names mirror root: `markSubagentOpened` → `markOpened`, `consumeSubagentQueue` → `completeOpening()`,
     `stopConsumer` → `markConsumerStopped`, `builtSubagent` → private `_subagent`.
  3. (D1, user 2026-11-22) The subagent object is stored once, privately: `startConsumer(session, scope, block)` sets
     `_subagent` then claims the consumer state (`attachSubagent` goes); `completeOpening()` takes no parameter and reads
     `_subagent`; the setup block becomes `{ consume(child) }`. Public `subagent` goes: holder methods `applyPatch(update,
     opened)` (parent's `Patch` branch) and `markClosed()` (`failSessionTree`, `removeSessionTree`, setup); `sessionCwd`
     stays. Outside the holder, `session` is the only handle (completion signal). Rationale (user accepted): the app gets
     the object at setup (parent's `subagents`, announcement `notify`), `session` completes only after the child's opened
     marker and the parent's completion; patches, nested cwd and close-on-failure need the object in between.
  4. Drop the redundant `ensureActive()` before `_session.complete` in the subagent `completeOpening` (the loop exits
     only right after its own check; `withContext` rethrows a cancellation at exit). Keep the two loop checks; reword
     their comments: receive/tryReceive don't check when they don't suspend; the client application's `notify` may not
     suspend; `deliverLive` swallows its failures; the cascade cancels without joining.
  5. `deliverLive` catch: keep `ensureActive()`; one-line comment (rethrow only the consumer's own cancellation; a CE the
     client application's `notify` throws, e.g. its own `withTimeout`, is logged like any live failure).
  6. KDoc: `markOpened` "exactly once" as bullets (before the answer: this walk; after: the holder's `init`); root
     `completeOpening` "Must run after" + failure cases as bullets; subagent `completeOpening` steps as bullets ("its own
     subagents" = nested); `permissions` KDoc rewritten (stale "setup hands them" sentence); "opened marker" instead of
     bare "marker" across client-package comments/KDoc; AGENTS.md mentions of renamed symbols updated.
- (user, 2026-11-22, after review) Undo item 1: the early path needs `completeOpening(session, opened)` anyway, so two
  overloads read worse. `drainHistory` public again, the no-marker overload goes, `Client.createSession` and the test
  call `drainHistory` + `completeOpening` as before; the item-6 KDoc rewrites and "opened marker" wording stay.
- R19 committed — `c6cbe87` — after the user's review; pushed. Round 19 done; waiting for the next review round. Programmer: client jvmTest 245
  green, `apiCheck` green, `acp.api` unchanged, `check` green on 2nd run (JS flake); a later comment-only fix in
  `deliverLive` compiled (`:acp:compileKotlinJvm`). Deviations: AGENTS.md unchanged (named no renamed symbol); test
  helper `childHolder` → `setUpChild` returning `Child(holder, session)`; two internal error strings say "opened marker".
- Answered, no change: the agent child's lock around sends (`SubagentSessionWrapper.kt:418`) is fine: non-suspend
  functions, `Transport.send` is encode + `trySend` on an unlimited channel, no app code, deferred completed outside;
  relies on the `Transport.send` non-blocking contract.

R1-R15 done. Deferred to the next v2 PR: v2 agent close items 1-3 — unknown-id close default (goes to app `AgentSupport.closeSession`, `-32602`), `-32002` instead of `-32602` for unknown sessions in `prompt`/`set_config_option`, re-resume overwriting the wrapper without closing the old `AgentSession`.

Review round 18 (2026-11-21, `ClientSession`/`ClientSessionHolder`/`ClientSubagentSession` notes). Agreed. Same workflow (uncommitted, user reviews).
- R18 (agreed), refactor/docs only, no API change:
  1. `ClientSession.close`: drop the leftover `**UNSTABLE**` banner (R13 removed only the annotation).
  2. Restructure the `ClientSession.close` KDoc and the `ClientSessionHolder` class KDoc per "Structuring long
     comments" (sections, bullets), every fact kept.
  3. Queue comment (`ClientSessionHolder.kt:126`) → one block comment: why unbounded (a bounded queue would suspend
     the read loop in the update handler, so the opening's response, which starts the drain, is never read).
  4. `deliver` lambda: 4 of 5 call sites pass `session.deliverUpdate` with `session.operations`; only
     `deliverEarly` has no session (tracks deprecated early mode/options, then `operations.notify`). Add
     `deliverItem(item, session, opened)` for the 4; keep the lambda form private for `deliverEarly`.
  5. `deliverOpening` → `deliverDuringOpening` + KDoc: only the opening uses it; it turns an app cancellation with
     an active caller into `IllegalStateException` so the opening fails (live deliveries log instead).
  6. `completeOpening`: drop the `when` with the unreachable "second opening marker" log (`markOpened` is strict,
     one marker per holder); the loop calls `deliverItem`, whose `Opened` branch already throws.
  7. (user, 2026-11-21) `sessionDeferred` → `_session` (backing property of `session`, like `_phase`/`phase`); KDoc
     on `session`: completes when the session takes over, after the drain; fails when the holder fails; only the
     holder completes it. `ClientSessionRegistry.kt:461` uses `isFailed` instead of the hand-written check.
  8. (D1, user, 2026-11-21) `QueuedItem.Received`: sealed supertype of `Update`/`Announcement`/`Patch` with abstract
     `update`/`meta` (`Announcement`/`Patch` override `update` as `SessionUpdate.SubagentUpdate`); `Update` keeps its
     name; `failAndDrainQueue` has one `Received` branch.
  9. (D2, user, 2026-11-21) `RootSessionHolder.startClosing()`: the CLOSING stage update, then
     `forEachDescendant { it.permissions.close() }`; `markClosing` and `ClientSessionRegistry.closeDescendantPermissions`
     go; `ClientSessionImpl` calls it on its `holder` (the `onClosing` lambda goes if nothing else needs it). The CLOSING
     stage and its behaviour (sticky after a failed close, `addChild` read) unchanged; update KDoc/AGENTS.md mentions.
  10. (D3, user, 2026-11-21) `ClientSubagentSession` internal constructor `(holder: SubagentSessionHolder, operations,
     protocol)`: `sessionId`, `parentSessionId`, `cwd`/`additionalDirectories` fallback (`holder.parent.sessionCwd()`
     etc.), `subagents`, `pendingPermissions` read from the holder; the lambdas go. Tests: `child(...)` helper takes
     `parent: ClientSessionHolder` (default a claimed root holder) and attaches; the two-level and "subagents read from
     the view" tests use real holders (`listSubagent`); `openChild` and `ClientSessionRegistryTest:376` simplified.
     `cwd`/`additionalDirectories` stay public on `BaseClientSession` (user, 2026-11-21: uniform read from a handler's
     context session; the SDK computes RFD inheritance).
- Answered, no change proposed: note 6 (`drainHistory(session)`: used by all four factory overloads; kept after the factory deprecation too, since
  new/fork with ready operations also start their consumer only after the answer; the holder publishes a session only by completing
  `sessionDeferred` after the drain; before that the opening call owns it); note 8 (the consumer takes the marker
  and returns it undelivered, since only the opening call knows whether it succeeded; `completeOpening` delivers
  that same item).
- Discussion done (D1 → 8, D2 → 9, D3 → 10). Plan agreed 2026-11-21; R18 delegated as one slice.
- R18 committed — `10ea5ac` — after the user's review; pushed. Round 18 done; waiting for the next review round.
  Programmer: jvmTest client,
  `apiCheck` and `check` green (`check` 2nd run, JS flake); `acp.api` unchanged. Deviations: the lambda `deliverItem`
  is `protected` (subclass `deliverEarly` calls it); `ClientSubagentSession.pendingPermissions` removed (unused
  outside; `cancel()` uses `holder.permissions`); `parentSessionId` documented in the class body.

Review round 16 (2026-11-20, client notes). Plan agreed; R16 then R17.
Workflow (user, 2026-11-20): one slice at a time; programmers leave changes UNCOMMITTED; the user reviews the diff
and tells the orchestrator to commit and continue. R16 (`b3e320b`) and R17 (`e618862`) committed; round 16 done. Waiting for the user's next review round.
- R16 (agreed, no discussion), refactor only, no API change:
  1. `ClientSessionImpl.handlePermissionResponse` → `answerPermissionRequest`.
  2. Subagent `answerPermissionRequest`: receiver `SubagentSessionHolder` (not `ClientSubagentSession`, which doesn't
     exist yet when the request arrives), no `session` lambda; it uses the holder's `permissions` and awaits its
     `session`. The `ClientSubagentSession` branch of `BaseClientSession.answerPermissionRequest` looks unreachable
     (the handler routes every subagent id through `subagentHolderOrNull` first): verify, then drop it.
  3. Remove the spec references `ClientOperationsFactory.kt:39` and `agent/SessionWrapper.kt:201`; grep code for
     `docs/superpowers`.
  4. Inline the one-line `ClientSessionImpl.deliverOpened` at its only call site.
  5. `removeSessionTree`: no `removed` side effect inside the CAS lambda (it may run more than once, hence `clear()`);
     use `getAndUpdate` and derive the removed ids from the previous snapshot.
  6. `failSessionHolder` → `failSessionTree`, its recursion as a local function.
  7. `runsIn` and `isSelfOrAncestorOf` → private top-level functions at the bottom of `ClientSessionRegistry.kt`.
  8. Registry `getSession` → `getRootSession` (public `Client.getSession` unchanged).
- Answered, no change: note 3 (registration racing `removeSessionTree` is covered: `addChild` before the parent's
  `closeChildren` ⇒ in the subtree; after ⇒ refused; the map CAS conflicts because every update allocates);
  note 11 (only a root can be closed: `ClientSubagentSession` has no close and the agent rejects a subagent id).
- Discussion outcomes (user, 2026-11-20, final) → R17, which also rewrites the KDoc of `removeSessionTree` and
  `registerChild` per "Structuring long comments" (registry notes 1 and 8; restate the note-3 race argument plainly):
  D1 one atomic `Children(byId, listed, sealed)` state in `ClientSessionHolder` replaces the `SubagentList` class
     (`addChild` and the listing of a set-up `ClientSubagentSession` check the same `sealed` flag; sealing is one
     update); `closeChildren()` → `sealChildren()`. Both collections stay: holders at arrival vs session objects at
     delivery. No holder close helper (registry note 4): it would serve only the removal path; the cascade keeps
     its behaviour.
  D2 `registerChild`: the answered check (marker) moves into the `SubagentSessionHolder` constructor (`init`); the
     closing check (read after the add, pairs with `markClosing`) moves into `addChild`; the session-map CAS moves
     into a private helper returning `Registered`/`RootEnded`/`Blocked`; add-then-remove on `Blocked` stays.
  D3 `runsIn` keeps both signs (element for `withContext(NonCancellable)`, ancestry for a fresh context around a
     child job). No change.
  D4 registry `getSessionOrThrow` → `awaitSession` (KDoc: throws for an unknown id, may create a speculative holder
     during an opening, never a session).

Earlier: the goal is done. PR #158 targets `master`. Remaining for the user: delete `.agents/state.md` and
`.agents/research/` before the PR merges. Follow-up candidates: review finding 4; the fresh-context close
deadlock; the dropped pre-marker update; regression test for agent nit N3; stale Kotlin file:line cites in the spec.

Notes:
- Flaky build: `./gradlew check` fails now and then with a Kotlin/JS "Internal compiler error" in
  `:acp:compileTestDevelopmentExecutableKotlinJs` (six occurrences); it passes on rerun; cause unknown.
- `commonTest` test names must not contain commas (native targets reject them).
- Not covered end to end: a terminal call from a child. No regression test for agent nit N3 (unreachable).
- Follow-ups outside this goal (do not fix unless the user asks): `Agent.kt` registers `session/resume` twice;
  `session/new`/`resume`/`fork` leave the root registered if building the response fails; a re-opened root's old
  turn keeps running; `V1ClientCapabilities.toV2()` drops `subagents`; `PromptResponse.usage` fails the whole
  response on one bad field; v2 string patch fields under lenient `ACPJson`; client `close()` removes the holder
  by id not identity; a failed `session/close` leaves the id taken; `elicitation/complete` failed-holder quirk;
  no protocol signal for a finished child, so child holders live until their root closes.
- Temporary `.agents/` references left in code by programmers: none reported.

## Open questions (waiting on the user)
- None.
