# Review: comment-trim milestone (`2790399..HEAD`)

Product commits: `91bdb5e a1e67b1 0bc29e3 54d4d45 ddfc35b f9bfc6f`. 24 `.kt`/`.kts` files and `AGENTS.md`.

Counts: 0 blockers, 1 should-fix, 5 nits.

## How it was checked

- **Comments-only.** I stripped comments and collapsed whitespace with a small string-aware stripper
  (`scratch/ctrim/strip.py`), then compared every changed `.kt`/`.kts` file at `2790399` and at `HEAD`.
  - 23 of the 24 files are token-identical.
  - The only difference is a formatting change in `ClientSessionRegistry.kt`, see nit N3.
  - Because the code is token-identical, I did not rerun the tests.
- **Accuracy.** I read every rewritten or kept comment in the diff against the code around it, including
  `stopOpening`, `failSessionTree`, `registerChild`, `completeOpening`, `startOpening`, `ClientSessionImpl.close`,
  `withEarlyInitializingSession`, `createSession`, `announce`, `registerSubagent`, `removeRoot`, `sendRequestRaw`
  and the `MaybeUndefined` helpers.
- **Lost information.** I compared each bullet of the old "Runtime and Lifecycle Rules" (`f9bfc6f^:AGENTS.md`) with
  the new section, and with what the code comments and the public KDocs still say. I also checked the must-keep lists
  in `.agents/state.md`.
  - Every cross-cutting invariant is still stated in the new section, or in a public KDoc: `AgentSubagents`
    "Removal" and "Replay", `AgentSubagentSession.cancel` and `events`, `BaseClientSession.subagents`, the
    `ClientSubagentSession` "Stream" and "Closing" sections, `AgentSession.prompt` and `cancel`, `RequestOutcome`.
  - Facts specific to one file are now in inline comments: the `ensureActive` checks, `startClosing` before the
    send, the `lateDeliveries` chain for an announcement, and the `ResponseSlot` hook ordering.
  - The one gap is S1.
- **Rules.** No single-line KDoc or consecutive `//` lines in comments this PR added. The hits are pre-existing
  (`Agent.kt:106`, `Client.kt:230`, `Protocol.kt:149/475/538/583/689`), and none comes from this PR's diff against
  `master`.
  - `@property` and `@param` placement is correct.
  - The `[X]` links in the rewritten KDocs all point to existing declarations or parameters.
  - The `AgentSubagents` `### Removal` and `### Replay` sections that AGENTS.md cites as its example still exist.
- **`.agents/` references.** None in product code or in AGENTS.md, apart from the existing "Working files" section.

## Should-fix

### S1. The reason a failed subagent keeps its id is gone everywhere

`acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionHolder.kt:150`

**Defect.** The `blocksId` KDoc now says only *that* a failed subagent keeps its id while its parent lives. The
reasons were trimmed in `a1e67b1`, and `f9bfc6f` dropped the old AGENTS.md clause ("a failed one keeps its id taken
while its parent lives"). The reasons were:
- its later patches stay patches;
- an update on its id stays unconnected;
- an opening of the id is refused.

**Failure scenario.** A maintainer sees that a failed root gives up its id (`!isFailed`). They "simplify"
`SubagentSessionHolder.blocksId` to match. The failed child's id then frees up while its parent lives, and two things
break:
- the parent's next `subagent_update` for that id is sorted as a new `Announcement` and registers a second holder;
- `claimSessionHolder` can claim the id as a root.

Nothing in the code, the public KDocs or AGENTS.md says why that is wrong. `.agents/state.md` records these effects
as "only in code now", but they are not in the code any more.

**Proposed wording** for the KDoc at line 150:

```
/**
 * Whether this holder still owns its session id. A failed root gives it up. A failed subagent keeps it while its
 * parent lives, so the parent's later patches for it stay patches and an opening of the id is refused.
 */
```

Confidence: confirmed by reading.

## Nits

### N1. "may not suspend" reads as a ban

`acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/SubagentSessionHolder.kt:153`

**Defect.** "`receiveCatching` and `notify` may not suspend" can be read as "must not suspend". The intended meaning
is "might return without suspending". `notify` is app code and is allowed to suspend.

**Failure scenario.** A reader concludes that `notify` must be non-suspending, or misses why the explicit
`ensureActive()` is needed.

**Proposed wording:** "`receiveCatching` and `notify` can return without suspending, and the cascade and the close
cancel this opening without joining it. Without this check a cancelled opening would keep taking the drain's items."

Confidence: confirmed by reading.

### N2. A branch comment describes only the rare case

`acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionRegistry.kt:74`

**Defect.** "// Replaces a failed holder, which handed its queue on when it failed." sits on the
`initializingSessionsCount > 0` branch. That branch mostly creates a speculative holder for an id with no holder at
all. Replacing a failed holder is the less common case.

**Failure scenario.** A reader thinks the branch is only reached for a failed holder, and misreads where speculative
holders come from.

**Proposed wording:** "// No holder owns the id: create a speculative one. A failed holder it replaces has already
handed its queue on."

A related point at line 55: "Returns the holder of [sessionId]" could say "the holder that owns [sessionId]". A failed
root's holder is never returned.

Confidence: confirmed by reading.

### N3. The one non-comment change

`acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionRegistry.kt:266-270`

**Defect.** The pilot commit `91bdb5e` rewrapped the `claimSessionHolder` signature onto several lines and added a
trailing comma. This is the only difference in the range once comments are stripped. It has no effect on behavior,
but the goal says "no code change at all".

**Failure scenario.** None at runtime. It is noise in a diff declared as comments-only.

**Proposed fix:** revert to the one-line signature, or note it as an intended formatting fix. The line was about 133
characters long.

Confidence: confirmed by the comparison.

### N4. An awkward AGENTS.md bullet drops one detail

`AGENTS.md:194`

**Defect.** "marked closed when its holder fails, after the listing too, or when its root's tree is removed" is hard
to parse. It also drops the old point that the root-close case applies even to a subagent whose holder has
completed.

**Failure scenario.** A reader takes "after the listing too" as a separate trigger. Or they assume that an opened
subagent is not marked closed when its root closes.

**Proposed wording:** "A listed subagent is marked closed when its holder fails, even after it was listed, and when
its root's tree is removed, even if it had opened. It stays in its parent's `subagents`. A setup that finishes after
its parent was sealed drops its child."

Confidence: confirmed by reading.

### N5. The spec citation for the root capability gate was dropped

`acp/src/commonMain/kotlin/com/agentclientprotocol/agent/internal/RemoteClientSessionOperations.kt:39`

**Defect.** The old KDoc cited the spec sections behind the rule that gates `session_message`,
`session_message_chunk` and `session` tool-call content on the client's `subagents` capability. The new KDoc says
only "the updates that need it". The list itself is still in `needsSubagentsCapability`, but no comment now ties the
rule to ACP.

**Failure scenario.** A maintainer who doubts the gate has no pointer to the source, and may loosen it.

**Proposed wording:** "- without the `subagents` client capability, the updates that need it are rejected, as ACP
requires (https://agentclientprotocol.com/protocol/v1/subagents)."

Confidence: plausible. The rule is still correct; only its traceability is weaker.

## Not findings, checked

- The `MaybeUndefined` class KDoc no longer mentions `explicitNulls = false`. That reason now sits inline at
  `putMaybeUndefined`.
- The "fails strictly" note for `cwd` and `additionalDirectories` is still in the public `SubagentUpdate` KDoc.
- The `Other` preservation note is still in `StateUpdate.Other`.
- The dropped `@property sessionId` tags (`SubagentUpdate`, `ToolCallContent.Session`) are covered by the class
  prose.
- Every fact the old `ClientSubagentSession.applyPatch` KDoc held is in the public `BaseClientSession.cwd` and
  `additionalDirectories`, `SubagentUpdate` "Patch semantics", and `ClientSubagentSession` "Closing" KDocs.
- The `withEarlyInitializingSession` KDoc dropped "claimed before the request". The code shows it, and the
  `claimSessionHolder` KDoc says it.
