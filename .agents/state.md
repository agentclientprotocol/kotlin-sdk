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

## Plan
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

## Next
The goal is done. The branch is pushed and the PR is open against `eugenethedev/permission-cancellation-fix`
(stacked on #156): PR #158.
Remaining for the user: delete `.agents/state.md` and `.agents/research/` before the PR merges; retarget the PR to
`master` once #156 merges. Follow-up candidates: review finding 4; the fresh-context close deadlock; the dropped
pre-marker update; regression test for agent nit N3; stale Kotlin file:line cites in the spec.

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
(none)
