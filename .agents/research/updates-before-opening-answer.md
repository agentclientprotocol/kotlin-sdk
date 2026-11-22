# May an agent send `session/update`, `session/request_permission` and `subagent_update` for a session before its response to the call that opens it (`session/new`, `session/fork`, `session/resume`, `session/load`)?

**Sources checked:**
- spec: `agent-client-protocol` @ `01a54c84` (2026-11-22 08:03 UTC, `main`, `pull --ff-only` fast-forwarded; 165 commits after the pinned `c392d7e9`)
- Rust SDK: `rust-sdk` @ `34aea54f` (2026-11-21, `main`, `pull --ff-only` fast-forwarded)
- this repo: `acp-kotlin-sdk` @ `874525e` plus the uncommitted working-tree change to `SubagentPendingPermissions.kt` (read as it is on disk)

**Confidence:** high for load/resume (normative text quoted below); high that v1 is silent for `session/new`
and `session/fork` (a finding from reading the v1 session-setup, error, prompt-turn and subagents pages, the
schema descriptions and the fork RFD, not a quoted statement).

## Answer

**Big change since `c392d7e9`:** the Subagent Sessions RFD moved to **Completed** on 2026-11-20 (spec commit
`9d44c77a`), and subagents are now in the **stable** v1 schema (`schema/v1/schema.json`) with a stable v1 page
`docs/protocol/v1/subagents.mdx`. The rules the design spec paraphrases from the RFD now live, reworded, on that
page, and a Completed RFD defers to the docs. Their meaning hasn't changed.

Per call, in **v1**:

- **`session/new`**: not specified. No v1 text allows or forbids notifications or requests for the new id before
  the response, or says anything about ordering. The diagram shows only the response
  (`docs/protocol/v1/session-setup.mdx:21-25`). Only the **v2** page settles it (Agents SHOULD put initial state in
  the response, SHOULD NOT send session-scoped requests before it, Clients MAY buffer or drop), and that doesn't
  bind v1.
- **`session/fork`**: unstable (RFD stage **Draft**, unstable schema only). Not specified for v1 either. The RFD
  says nothing about updates during the fork. It suggests the fork's history and cursors come later, "for example
  through `session/resume` with replay".
- **`session/resume`**: stable (RFD **Completed**, in `schema/v1/schema.json`). The Agent **MUST NOT** replay
  history before responding. Live updates before the response are neither forbidden nor described for the root
  session. For subagents, every `subagent_update` and child update is **current** and "can arrive before or after
  the response". Child controls still wait for success, and a failed request discards what came in during it.
- **`session/load`**: stable. The Agent **MUST** replay the whole conversation as `session/update` before the
  response, and responds only after all of it has been streamed. For subagents, the successful response is the
  freshness boundary: child `capabilities` and `state` before it are history and never take effect, and current
  values follow the response. Recorded requests **MUST NOT** be sent again. New requests and other live child
  updates may come during the replay.

The SDK's hold-back rule matches the latest text for load and resume, and for a failed opening. For
`session/new` and `session/fork` it rests on an inference, since v1 has no rule there (see Discrepancies). The
Rust SDK parks early v1 notifications for an unrouted id and delivers them once the session's handler is
installed. It answers early session requests for an unrouted id with `-32601`, and it routes load/resume traffic
to the session from before the request goes out.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|-------------|------|-------|-----------------|----------|
| 1 | `session/new`: the response carries a unique `sessionId`. Nothing on notifications or requests before it | MUST (response); pre-response traffic **unspecified** | agent | stable | spec `docs/protocol/v1/session-setup.mdx:73` |
| 2 | `session/load`: replay the entire conversation as `session/update` notifications | capability:`loadSession`, MUST | agent | stable | spec `docs/protocol/v1/session-setup.mdx:136` |
| 3 | `session/load`: respond once **all** entries have been streamed (so replay comes before the response) | capability:`loadSession`, MUST | agent | stable | spec `docs/protocol/v1/session-setup.mdx:191` |
| 4 | `session/resume`: don't replay history via `session/update` before responding | capability:`sessionCapabilities.resume`, MUST NOT | agent | stable | spec `docs/protocol/v1/session-setup.mdx:258` |
| 5 | Announce a child with `subagent_update` before any request or notification that carries its id | capability:`subagents`, MUST | agent | stable | spec `docs/protocol/v1/subagents.mdx:88-95` |
| 6 | On load/resume of a session, discard the state and capabilities held for its children: effective caps start empty, state unconfirmed | capability:`subagents`, MUST | client | stable | spec `docs/protocol/v1/subagents.mdx:499-501` |
| 7 | Don't send `session/cancel` for a child until the load/resume succeeds **and** a current `subagent_update` advertises `cancel` | capability:`subagents`, MUST NOT | client | stable | spec `docs/protocol/v1/subagents.mdx:502` |
| 8 | If the load/resume fails, discard the child state and capabilities received during it | capability:`subagents`, plain prose (no keyword) under the MUST of row 6 | client | stable | spec `docs/protocol/v1/subagents.mdx:503` |
| 9 | Resume: every `subagent_update` and child update is current, before or after the response | capability:`subagents`, descriptive | both | stable | spec `docs/protocol/v1/subagents.mdx:507` |
| 10 | Load: `capabilities`/`state` before the successful response are history. They MUST NOT permit sending anything for the child or confirm work, and never become effective. Current values come after the response | capability:`subagents`, MUST NOT | client (agent sends current after) | stable | spec `docs/protocol/v1/subagents.mdx:508` |
| 11 | Ancestors first: before live child traffic, every ancestor is announced during this load/resume. A replayed announcement counts | capability:`subagents`, descriptive (agent) | agent | stable | spec `docs/protocol/v1/subagents.mdx:512` |
| 12 | Child `config_option_update` before the `session/load` response is history. Client MUST NOT present it, or pre-load config, as confirmed current | capability:`subagents`, MUST NOT / SHOULD | client / agent | stable | spec `docs/protocol/v1/subagents.mdx:517` |
| 13 | Send all replay history selected for the replay before the load response. Recorded child state isn't sent after it as current | capability:`subagents`, MUST | agent | stable | spec `docs/protocol/v1/subagents.mdx:530` |
| 14 | Replay MUST NOT re-send recorded `session/request_permission`/`elicitation/create`/other requests. A new request is live and may be sent once the child and ancestors are announced | capability:`subagents`, MUST NOT / MAY | agent | stable | spec `docs/protocol/v1/subagents.mdx:532` |
| 15 | Other live child updates may arrive during the replay if per-session order holds and replay can't overwrite newer content | capability:`subagents`, MAY (conditional) | agent | stable | spec `docs/protocol/v1/subagents.mdx:533` |
| 16 | `session/fork` traffic before the response | **unspecified** | — | unstable (RFD Draft) | spec `docs/rfds/session-fork.mdx:66-67,156` |
| 17 | v2 only: `session/new` pre-response updates may arrive. Agents SHOULD put initial state in the response, SHOULD NOT send session-scoped requests before it. Clients MAY buffer and apply in order, or drop. A non-idle state MUST come after the response | SHOULD / SHOULD NOT / MAY / MUST | both | v2 (SDK: unstable) | spec `docs/protocol/v2/session-setup.mdx:100-105` |

## Details

### Stages and status

- `rfds/subagents`: **Completed** (`docs/docs.json:248` group, entry `:274`). Revision history:
  "2026-11-20: Moved to Completed. Subagent sessions are part of the default v1 and v2 schemas, and the v1 rules
  are on the stable v1 Subagents page" (`docs/rfds/subagents.mdx:1720`). Announcement:
  `docs/announcements/subagents-stabilized.mdx`. Stable v1 schema has the subagent types (`schema/v1/schema.json`, 20
  `subagent` matches). Commit `9d44c77a` says: "The `session/fork` rules for child sessions and the `usage` field on
  the idle state stay unstable with their own RFDs."
- `rfds/session-resume`: **Completed** (`docs/docs.json:255`). `session/resume` is in `schema/v1/meta.json`
  (`session_resume`), so stable v1.
- `rfds/session-fork`: **Draft** (`docs/docs.json:201,203`). `session/fork` is only in `schema/v1/meta.unstable.json:18`
  and `schema.unstable.json`.
- The v1 stable and draft subagents pages have identical "Loading and resuming" sections, apart from link targets
  (diffed: stable `:493-533`, draft `:503-543`).

### `session/new` (v1)

- `docs/protocol/v1/session-setup.mdx:21-25` (diagram): `Client->>Agent: session/new`, Agent creates context and
  connects MCP, then `Agent-->>Client: session/new response (sessionId)`. No `session/update` arrow before it, but a
  diagram is not normative.
- `:73`: "The Agent **MUST** respond with a unique Session ID that identifies this conversation".
- `docs/protocol/v1/error.mdx:53`: "A session is active once it is created, loaded or resumed on the connection,
  until it is closed." It doesn't say whether "created" means the request or the response. Contrast v2,
  `docs/protocol/v2/session-setup.mdx:381`: "A session that was not active before the request becomes active with
  the successful response".
- `NewSessionResponse` / `SessionNotification` schema descriptions say nothing about ordering. `SessionNotification`:
  "Used to stream real-time progress and results during prompt processing."
- Searched `docs/protocol/v1/*.mdx` (stable) for "before (it) respond", "before the … response", "after the
  response", "buffer", "unrecognized". No hit concerns `session/new`.
- **Finding:** v1 doesn't specify this. An agent isn't forbidden from sending `session/update` or
  `session/request_permission` for the new id before the response, and the client gets no rule for them. The v2
  rule (row 17) came from spec commit `85a45516` "docs(protocol): define session messages sent before the
  session/new response", which changed only the v2 pages. v1 was left out on purpose or by omission, so the v2 rule
  is the only upstream statement of intent:

  > Agents **SHOULD** put initial state in the `session/new` response rather than sending notifications before it.
  > […] Agents **SHOULD NOT** send session-scoped requests, such as permission requests or elicitations, before the
  > response, because the Client cannot yet show them for that session. While a `session/new` is pending, Clients
  > **MAY** buffer notifications for a `sessionId` they don't recognize and apply them in order once the response
  > arrives, or drop them. Agents cannot rely on such notifications being seen. (`docs/protocol/v2/session-setup.mdx:105`)

- Subagents and `session/new`: the v1 subagents page covers load and resume only. Nothing addresses a child
  announced under a new session before its response. Row 5, announce before any traffic, still applies.

### `session/fork` (v1, unstable)

- `docs/rfds/session-fork.mdx:66-67`: the agent responds with the new `sessionId`, "the same as `session/new`". "As
  with `session/new`, the forked session is active once the agent responds".
- `:156`: "The client gets the new session's cursors from `cursor_update` notifications on that session, for example
  through `session/resume` with replay. A cheaper way to get the IDs and cursors of a new fork is an open question."
  This implies the fork call itself doesn't stream the copied history, but nothing requires it.
- `docs/protocol/v1/draft/subagents.mdx:487-495` ("Forking the parent (unstable)"): Clients MUST NOT fork a child.
  When fork copies child history, the Agent MUST remap the copied ids consistently. "Copying history does not start
  child runtimes" (`:495`). Nothing on when copied child state is sent, or whether it's current.
- **Finding:** unspecified for pre-response traffic, like `session/new`.

### `session/resume` (v1, stable)

- `docs/protocol/v1/session-setup.mdx:258`: "Unlike `session/load`, the Agent **MUST NOT** replay the conversation
  history via `session/update` notifications before responding. Instead, it restores the session context,
  reconnects to the requested MCP servers, and returns once the session is ready to continue."
- Only *replay* is forbidden. Live root-session updates or requests before the response are not mentioned for the
  root session.
- Subagents (`docs/protocol/v1/subagents.mdx`):
  - `:499-503`, quoted:
    > When the Client sends `session/load` or `session/resume` for a session, it **MUST** discard the state and
    > capabilities it holds for that session's children:
    > - Each child's effective `capabilities` start empty, and its state starts unconfirmed. History the Client
    >   already shows can stay visible.
    > - The Client **MUST NOT** send `session/cancel` for a child until the request succeeds and a current
    >   `subagent_update` advertises `cancel`.
    > - If the request fails, the Client discards the child state and capabilities it received during that request.
  - `:507`: "**`session/resume`.** Nothing is replayed, so every `subagent_update` and every child update is current.
    They can arrive before or after the response."
  - `:512`: before live updates or requests for a child, every ancestor is announced during this load or resume,
    parent before child.
  - `:516`: Agents SHOULD send current `cwd`/`additionalDirectories` after the load or resume when they differ.
- Child requests (`session/request_permission`) during resume: no special rule beyond announcement order. The RFD
  is explicit: "Agent-to-Client requests retain their ordinary handling; their responses need not wait for resume
  to finish." (`docs/rfds/subagents.mdx:1047-1049`). The v1 page doesn't repeat this.

### `session/load` (v1, stable)

- `docs/protocol/v1/session-setup.mdx:136`: "The Agent **MUST** replay the entire conversation to the Client in the
  form of `session/update` notifications (like `session/prompt`)."
- `:191`: "When **all** the conversation entries have been streamed to the Client, the Agent **MUST** respond to the
  original `session/load` request."
- `:139-147`: compactions replay with live semantics. Live-only notices SHOULD NOT be replayed, and a new notice
  after loading is a new live event.
- Subagents (`docs/protocol/v1/subagents.mdx`):
  - `:508`: "**`session/load`.** The successful response is the boundary. The `capabilities` and `state` in a
    `subagent_update` that arrives before the response are historical. They **MUST NOT** permit the Client to send
    anything for the child or confirm that the child is working, and they never become the child's effective
    capabilities. The Agent sends the current `capabilities` and `state` after the response."
  - `:517`: a child's `config_option_update` before the load response is historical. After `session/load`, current
    config is sent after the response.
  - `:530`: "The Agent **MUST** send all history it selected for the replay before the response. It does not send
    recorded child state after the response as if it were current."
  - `:532`: "Replay **MUST NOT** send a recorded `session/request_permission`, `elicitation/create`, or other Agent
    request again. A new request for a child is live. The Agent can send it once the child and its ancestors are
    announced."
  - `:533`: "Other live updates for a child can arrive during the replay, if the Agent keeps each session's order
    and the replay cannot overwrite or duplicate newer content."
- Root-session `session/request_permission` during load: no v1 rule. Only the child rule (`:532`) exists.

### Verification of the design spec's paraphrased RFD lines (pinned `c392d7e9`)

The text is unchanged except for one v1/v2 split. Line numbers shifted by about 4:

| Design spec cites (`c392d7e9`) | Current `01a54c84` | Change |
|---|---|---|
| `:1014-1016` | `docs/rfds/subagents.mdx:1010-1013` | identical: "The Client **MUST NOT** enable child mutations until the load/resume succeeds and a current capability grants them. If the operation fails, current state and authorization received during that attempt are invalidated." |
| `:1026-1034` (`:1027-1035` exactly) | `:1023-1033` | Now scoped to v1: "**When replay is requested**, the successful response is the freshness boundary for child work-state and mutation-capability snapshots in v1. In v2 the boundary is the parent's `replay_complete`. […] In v1: - Before the response, these snapshots are historical. They **MUST NOT** authorize Client-initiated mutations or establish confirmed current activity. - Current snapshots are sent after the response." |
| `:1045-1048` (`:1046-1051` exactly) | `:1044-1049` | identical: "**When no replay is requested**, announcements, current snapshots, and ordinary child traffic may arrive before or after the resume response, subject to announcement ordering. These snapshots are current observations, not history replay; controls still wait for successful resume. Agent-to-Client requests retain their ordinary handling; their responses need not wait for resume to finish." |

Because the RFD is now Completed, cite `docs/protocol/v1/subagents.mdx:499-508` (normative docs) instead of the
RFD lines.

## SDK implications

All of this is `:acp`, `client` package, v1 runtime. The v2 counterpart has its own rule (row 17, and
`replay_complete` as the v2 boundary).

- **Early buffering for `session/new`/`session/fork`** (speculative holder in
  `ClientSessionRegistry.kt:63-97`, `claimAnsweredSessionHolder` `:369-375`). v1 doesn't specify this traffic, so
  buffering it isn't required but is conforming. It matches the v2 MAY ("buffer … and apply them in order once the
  response arrives, or drop them") and the Rust SDK (below). Early updates delivered before `SessionEvent.Opened`
  for a new or forked session are an SDK choice, not a v1 rule.
- **Early `session/request_permission` for a `session/new` id**: v1 doesn't specify it. v2 says the Agent SHOULD
  NOT. The Rust v1 client answers it `-32601` when no handler is routed. Whatever the Kotlin SDK does, it is SDK
  behavior. A test can show it, but the spec doesn't constrain it. (I didn't trace what the Kotlin client does for
  this case. Route it to a code check if it matters.)
- **Hold-back of child `capabilities`/`state`** (`ClientSubagentSession.applyPatch`/`onParentOpened`
  `ClientSubagentSession.kt:166-198`; `ClientSessionHolder.markOpened` `:550-565` with
  `promote = opening?.replayRequested != true`; `SubagentPendingPermissions.onState`/`onOpened` `:61-99`):
  - **load** (`replayRequested = true`): held values are dropped. This matches `:508`, which says they "never
    become the child's effective capabilities" and MUST NOT confirm work. The pending-permission rule matches
    too: a held `idle(cancelled)` sets requests aside, and after a load they stay pending. A replayed `idle` is
    history and must not answer live requests (`:532`, `:472`).
  - **resume** (`replayRequested = false`): held values take effect at the answer. This matches `:502` (no
    `session/cancel` until success and a current `cancel` grant) and `:507` (they're current). Holding back `state`
    until the answer is stricter than the spec, which lets the Client show pre-response state as current at
    once. It is conforming, since nothing requires showing it early.
  - **failure**: the holder fails and nothing is applied. This matches `:503` and RFD `:1011-1013`.
  - **new / fork**: promoted like resume. This is an inference: no v1 rule covers children before a new or fork
    response. See Discrepancies.
- `title`/`description`/`cwd`/`additionalDirectories` take effect at once even during a load
  (`ClientSubagentSession.kt:169-172`). Conforming: `:501` lets history stay visible, and `:516` only asks Agents
  to send current directories after. Replayed `cwd` "describe the recorded context", so an app showing them as
  current until updated is the app's concern.
- Child `config_option_update` before the load response (`:517`): the SDK models no child config. The update
  reaches the app before the parent's `Opened`, which marks it as history by position. That is enough for an app
  to obey the MUST NOT.
- What tests would show:
  - conforming load: child caps and state from before the answer are absent afterwards, and only post-answer
    patches apply;
  - conforming resume: pre-answer caps apply at `Opened`, and `cancel()` is rejected before it;
  - failed load/resume: nothing applied, and the id can be reopened;
  - a non-conforming agent that sends a load's current state only *before* the response leaves the child with
    empty caps and unconfirmed state. That is the spec-mandated outcome, not an SDK bug.

## Discrepancies

1. **v1 vs v2 on `session/new`:** v2 has explicit rules (`docs/protocol/v2/session-setup.mdx:100-105`), and v1 has
   none. The commit that added them (`85a45516`) touched only v2 pages. This is not a conflict, but the SDK's v1
   behavior here can only cite v2 intent and the Rust SDK.
2. **New/fork promotion is not backed by v1 text.** The SDK treats a new or forked session's pre-answer child
   `capabilities`/`state` as current. For `session/new` that is the only sensible reading, because there's no
   history to replay (hypothesis, no citation). For `session/fork`, if an Agent streamed *copied* child history
   during the fork call, those snapshots would be history, yet the SDK would promote them. The spec says
   copying "does not start child runtimes" (`docs/protocol/v1/draft/subagents.mdx:495`) but doesn't say whether or
   when copied history is sent. The RFD suggests it isn't sent during the fork (`docs/rfds/session-fork.mdx:156`).
   This is a possible divergence for unstable fork only.
3. **Design spec citations are stale but correct in meaning.** The RFD line numbers shifted (table above), and the
   freshness-boundary paragraph is now marked "in v1". The RFD is Completed, so the normative source is now
   `docs/protocol/v1/subagents.mdx:497-533`.
4. **Stability:** upstream made subagents **stable v1** on 2026-11-20. This SDK still marks the v1 subagent model
   `@UnstableApi` (`acp-model/.../model/Subagents.kt:33,53,70`, `SessionUpdate.kt:301`). This is outside the question
   and is listed under Open questions.
5. Rust SDK vs spec: none that matter here. Rust's v1 client has no subagent logic at all (no `subagent` matches in
   `src/agent-client-protocol/src`), so it gives no evidence for the hold-back rule.

## Rust SDK (supporting evidence only)

- **`session/new` (v1):** the session's route is installed only after the response
  (`src/agent-client-protocol/src/session.rs:264-277`, `attach_session` → `prepare_session_routing`).
  A v1 client's role default handler marks any unhandled Agent message carrying a `sessionId` for retry
  (`src/agent-client-protocol/src/role/acp.rs:294-313`). Unhandled *notifications* are parked, at most 1024 per
  connection with the oldest dropped on overflow (`src/agent-client-protocol/src/jsonrpc/incoming_actor.rs:650-652,
  700-713`), and retried in order when a dynamic handler is added (`incoming_actor.rs:330-363`). So early
  `session/update`s for a new id reach the new session's stream once it is routed. Unhandled *requests* (e.g. an
  early `session/request_permission`) are parked only while a deactivated handler awaits removal. Otherwise they
  get `-32601` at once (`incoming_actor.rs:593-619`; doc `jsonrpc.rs:433-456`, which says the mechanism exists for
  "`session/update` notifications for that session [that] may already be arriving").
- **`session/load` / `session/resume` (v1):** routing is installed and acknowledged *before* the request is
  published (`session.rs:197-199`, `:281-297`, `:362-366`, `:386-388`, `:466-471`, `:500-506`). Pre-response
  replay is kept on the restored `ActiveSession` (test
  `tests/session_restore.rs:109` `load_session_preserves_pre_response_replay_and_exact_response`). An error
  response removes the route before later messages are dispatched (test `tests/session_restore.rs:351`
  `failed_restore_removes_routing_before_later_messages`). Rust makes no history/current distinction in v1.
- **`session/fork` (v1):** Rust has no v1 fork helper (`session.rs:1116` says to send it raw). The v2 helper sits
  behind the `unstable_session_fork` feature (`src/agent-client-protocol/src/session/v2.rs:190-219`).

## Open questions

- Upstream stabilized v1 subagents (`subagent_update`, `session_message`, `session_message_chunk`, `session` tool
  call content, `clientCapabilities.subagents`) on 2026-11-20. Should this SDK drop `@UnstableApi` from them? That
  is a public API decision for the orchestrator or user, and it needs a model diff against `schema/v1/schema.json` @
  `01a54c84`.
- What should the Kotlin v1 client do with a `session/request_permission` for an id whose `session/new` is still
  pending? The spec is silent in v1. v2 says SHOULD NOT (agent) and Rust answers `-32601`. I didn't trace the
  Kotlin path.
- For unstable `session/fork`: if an agent sends copied child history during the fork call, should the client
  treat it as history (drop held state, like load) or as current? No upstream text covers it. It would need an
  upstream clarification of the fork RFD.
