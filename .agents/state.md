# State: implement v1 subagents per docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md

## Settings
mode: single · branch: eugenethedev/subagents · reviews: on (one reviewer per finished milestone)

## Decisions (final)
- The spec above is the only design document; every design question in it is decided. Do not re-ask them.
- Scope: protocol v1 only. Do not change v2 runtimes (v2 model usages of `MaybeUndefined` are updated by the move).
- All work lands on `eugenethedev/subagents`, one commit per slice. The opening-boundary commits (M3) stay
  contiguous so the user can split them into their own PR later.
- The user approved the milestone plan below (shown 2026-11-15).

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

## Next
- M2.2 (opus) programmer running: `session/cancel` routing, other methods rejected on child ids, feed-end
  `unknown` rule. Then M2.3 (opus): subtree cleanup, replay window, `forkSession` KDoc. Then the M2 review over
  `63f54c5..HEAD` (excluding the M1.4 commit `6b4dc10`).
- Owed in M3's release note: `ToolCallContent.Session` and new `SessionUpdate` variants (exhaustive `when`),
  `ClientCapabilities` constructor/`copy` change.
- Owed in M6: `CLAUDE.md` rules (nothing updated yet).
- Follow-ups outside this goal: `V1ClientCapabilities.toV2()` and `subagents`; `PromptResponse.usage` failing the
  whole response on one bad field; v2 string patch fields under lenient `ACPJson`.
- Temporary `.agents/` references left in code by programmers: none reported.

## Open questions (waiting on the user)
- M2.1: the factory throws a `CancellationException` AFTER the announcement while the caller is still active.
  Implemented: rethrown unchanged (spec: "rethrows the factory's exception unchanged"). Alternative: convert to
  `IllegalStateException` as in the nothing-sent case, so a foreign CE cannot tear down the caller's scope. Asked
  2026-11-15; work continues with the implemented behaviour; a change would be a small fix slice.
