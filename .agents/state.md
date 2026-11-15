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

## Next
- M6a docs programmer running: `AGENTS.md` agent-side subagent lifecycle rules (review N7), both internal
  `Protocol` hooks, block-comment nit in `ClientSessionRegistry.kt`.
- BLOCKED on the user's `NonCancellable` answer: M3.6 fix slice, then M4.2 (opus) registration, sorting, setup,
  delivery, child consumers, `Opened(null)`, wiring of `SubagentPendingPermissions`; M4.4 (opus) close and subtree
  removal; M4 review (include `5bca6d3`).
- M3.6 fix slice after the user answers the `NonCancellable` question: should-fix 1-3, release-note wording,
  block comments. Must land before M4.2, which builds on `completeOpening`.
- Agent S3 fix slice after the user answers the reopen question.
- Then M5 (integration tests), M6 (docs: `AGENTS.md` subagent rules incl. `invokeOnResponseQueued` and the replay
  window ending when the transport accepts the response; final `apiDump` review; `check`).
- Release note text exists in the body of `d505b25` (no changelog file in the repo); ask the user where it goes.
- Owed in M6: `CLAUDE.md` rules (nothing updated yet).
- Follow-ups outside this goal: `session/new`/`resume`/`fork` leave the root registered if building the response
  fails; `Agent.kt` registers a `session/resume` handler twice (about lines 425 and 496); `V1ClientCapabilities.toV2()` and `subagents`; `PromptResponse.usage` failing the
  whole response on one bad field; v2 string patch fields under lenient `ACPJson`.
- Temporary `.agents/` references left in code by programmers: none reported.

## Open questions (waiting on the user)
- S3 (agent): `session/load`/`session/resume` for a root already open on the connection: clean up the old subtree
  on replace, reject on the agent too, or leave and document. The user expected a refusal; explained that only the
  client refuses and the agent replaces (`Agent.kt` `createSession`). Awaiting the choice.
- M3 `NonCancellable`: A restore cancellable delivery (a cancellation or `notify` failure during the drain fails
  the opening as before `d505b25`; orchestrator recommends) or B keep "once `Opened` is delivered the opening
  cannot fail" and document `Client.getSession`.
- Where the release note should live (no changelog file in the repo).
