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

## Next
- M1.2 (capability, `StateUpdate`, `IdleStopReason` types) programmer running; then M1.3, then the M1 review.
- Temporary `.agents/` references left in code by programmers: none reported.

## Open questions (waiting on the user)
(none)
