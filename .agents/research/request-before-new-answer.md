# What does the v1 Kotlin client do with an agent→client session request (`session/request_permission`, `fs/*`, `terminal/*`) for an id before the answer of the `session/new` / `session/fork` that creates it, and does it match the Rust SDK?

**Sources checked:**
- this repo: `acp-kotlin-sdk` @ `fa45bc7` (HEAD). The working tree deletes `SubagentPendingPermissions.kt` (mid-edit); I read it, and `PendingPermissionCancellation.kt`, with `git show HEAD:…`. Every other file was read on disk and is unmodified from HEAD.
- Rust SDK: `rust-sdk` @ `34aea54` (2026-11-21, `main`, `pull --ff-only` said "Already up to date"). Dependency check: `futures-channel` 0.3.34 (`Cargo.lock:1076-1077`), source read from the local cargo registry.
- spec: `agent-client-protocol` @ `01a54c84` (2026-11-22, `pull --ff-only` said "Already up to date"). Only `docs/protocol/v1/error.mdx` and `docs/protocol/v2/session-setup.mdx` were re-read. The rest of the spec position comes from `.agents/research/updates-before-opening-answer.md`, which used the same revision.

**Confidence:** high for the Kotlin and Rust behavior, which I traced end to end and which existing tests on both sides back. Medium for one Rust detail, the `-32603` after a failed load/resume, which I inferred from code and no test covers.

## Answer

**It differs from Rust.** The Kotlin v1 client looks up the id when the request arrives. If no opening is in flight anywhere on the connection, it fails the request right away with **`-32602` "Session <id> not found"**. If any opening is in flight (`session/new`, `session/fork`, `session/load` or `session/resume`, for any id), it creates a *speculative* root holder for the unknown id and **suspends the request until that holder completes or fails**. No timeout bounds the wait. Then:

- if the `session/new`/`session/fork` answer names that id, the request reaches the app's operations only after the whole opening has finished (factory, history, `Opened`, queued updates);
- if the answer names another id, or the opening fails before the answer, the holder is reaped when the *last* in-flight opening on the connection ends, and the request gets `-32602` "Session <id> not found";
- if the opening fails after the answer (factory, `notify`, or the caller cancelled), the request gets `-32603` "Failed to create session <id>".

Rust never waits for routing. With no routing for the id, an early v1 session request gets **`-32601`** right away, with `data` set to the method name. Two exceptions: a builder-level typed handler (for example `on_receive_request` for `RequestPermissionRequest`), which runs first and answers whatever the session state; and the brief window while a deactivated dynamic handler awaits removal. For load/resume, Rust installs routing before the request goes out, so early requests go straight into the session's stream, and the Kotlin outcome there is close to Rust's. v1 has no rule for either case.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|-------------|------|-------|-----------------|----------|
| 1 | Session-scoped request naming a session that doesn't exist or isn't active: respond `-32002`, `data.sessionId` = id | SHOULD | **agent** (requests the Client sends). Nothing binds the Client for requests the Agent sends | stable v1 | spec `docs/protocol/v1/error.mdx:53-55` |
| 2 | Unknown method: `-32601` | SHOULD | both | stable v1 | spec `docs/protocol/v1/error.mdx:43` |
| 3 | Client: `-32002` for a missing file on `fs/read_text_file` or an unknown terminal on `terminal/*` (not an unknown session) | SHOULD | client | stable v1 | spec `docs/protocol/v1/error.mdx:59` |
| 4 | Agent→client session requests before the `session/new` response | **unspecified in v1** | — | stable v1 | `.agents/research/updates-before-opening-answer.md` (rows 1, 16) |
| 5 | v2 only: Agents SHOULD NOT send session-scoped requests (permission, elicitation) before the `session/new` response, because the Client can't show them yet | SHOULD NOT | agent | v2 (SDK: unstable) | spec `docs/protocol/v2/session-setup.mdx:105` |
| 6 | Announce a child with `subagent_update` before any request that carries its id. Once announced, a child's new requests are live | capability:`subagents`, MUST | agent | stable v1 | spec `docs/protocol/v1/subagents.mdx:88-95, 532` (per the earlier report) |

So no upstream text fixes the Kotlin client's answer here. `-32601` (Rust), `-32602` (Kotlin) and `-32002` (by analogy with row 1) are all SDK choices.

## Details

### Kotlin: handler registration and lookup

- `session/request_permission` handler: `acp/src/commonMain/kotlin/com/agentclientprotocol/client/Client.kt:110-127`.
  1. `sessions.subagentHolderOrNull(id)` (`:117`, `ClientSessionRegistry.kt:112-113`) is a non-creating map read for a `SubagentSessionHolder` that blocks its id. If it finds one, the request takes the subagent path (below).
  2. Otherwise `sessions.awaitSession(id)` (`Client.kt:122`), then a cast to `ClientSessionImpl` (else `error("Session … is not a root session")`), then `session.answerPermissionRequest` under `executeWithSession` (`:124-126`). That races the app's `requestPermissions` against the session's `PendingPermissionCancellation` (`ClientSessionImpl.kt:247-256`, `PendingPermissionCancellation.kt:69-83` @HEAD).
- `fs/read_text_file`, `fs/write_text_file`, `terminal/create|kill|output|release|wait_for_exit`: all go through `handleSessionRequest` (`Client.kt:52-61`, registered at `:129-155`), which calls `sessions.awaitSession(id)`, casts the operations to `FileSystemOperations`/`TerminalOperations` (else `-32601` via `sessionMethodNotFound`, `Client.kt:792-794`), and calls them. These have no subagent pre-check and no cancel race. The unstable session-scoped `elicitation/create` uses the same `awaitSession` (`Client.kt:65`).
- `awaitSession` = `getOrCreateSessionHolder(id).session.await()` (`ClientSessionRegistry.kt:222-224`).
- `getOrCreateSessionHolder` = `getOrCreateSessionHolderOrNull(id) ?: acpFail("Session $id not found")` (`:97-98`).
- `getOrCreateSessionHolderOrNull` (`:63-92`):
  - a registered holder that blocks its id is returned (`:65`, `:74-79`);
  - else, if `initializingSessionsCount > 0`, a new `RootSessionHolder` is put in the map (`:80-84`);
  - else `null`.
  - The count is **connection-wide**. `withInitializingSession` increments it for every opening (`:425-428`), and all four openings run under it (`Client.kt:656, 686, 729`).
- The request handler runs in its own coroutine (`Protocol.kt:527`, `handlerScope.launch`), so the wait doesn't block the read loop, and the opening's answer is still read.

### Kotlin: error mapping

`Protocol.handleRequest` catches the handler's throwable and answers `t.toJsonRpcError()` (`Protocol.kt:559-575`). Mapping (`acp/.../protocol/Exceptions.kt:76-84`):

- `AcpExpectedError` → `-32602` (INVALID_PARAMS) with its message;
- `JsonRpcException` → its code;
- `CancellationException` → `-32800`;
- anything else, including `IllegalStateException` → `-32603` with its message.

`acpFail` throws `AcpExpectedError` (`Exceptions.kt:22`). Codes: `acp-model/.../rpc/JsonRpc.kt:129-139`.

### Kotlin: outcomes per case (root id)

| Case | What happens | Wire result |
|---|---|---|
| (a) no opening in flight on the connection, id unknown | `getOrCreateSessionHolderOrNull` → `null` → `acpFail` (`ClientSessionRegistry.kt:85-88, 97-98`) | immediate `-32602`, message `Session <id> not found`, no `data` |
| (b) any opening in flight (new/fork of an unknown id, or a load/resume of any id) | speculative `RootSessionHolder` created (`:80-84`); handler suspends on `holder.session.await()` (`:223`) | no response yet |
| (c) the `session/new`/`session/fork` answer names this id | response hook `claimAnsweredSessionHolder` claims the existing holder and puts the `Opened` marker (`ClientSessionRegistry.kt:369-378`, `Client.kt:688-692`); `createSession` runs the factory, `drainHistory`, `completeOpening` (`Client.kt:613-638`); `_session.complete(session)` is the last step of `completeOpening` (`ClientSessionHolder.kt:641-656`) | request resumes and goes to the app (`requestPermissions` / fs / terminal) after the session's `Opened` and every queued update have been delivered. The response is the app's answer, or `cancelled` if a cancel/close wins the race |
| (d1) the answer names a *different* id, or the new/fork request fails/is cancelled before the answer (no claim happens; the claim is only in the success hook) | holder stays unclaimed. When `initializingSessionsCount` drops to 0, the finally block of `withInitializingSession` reaps it: `failSessionTree(holder, AcpExpectedError("Session $id not found"))` (`ClientSessionRegistry.kt:438-467`); `failAndDrainQueue` completes the deferred exceptionally (`ClientSessionHolder.kt:299-311`) | `-32602` `Session <id> not found`, but only once the **last** opening on the connection ends |
| (d2) claimed, then the opening fails after the answer (factory throws, `notify` throws, caller cancelled) | `failSessionTree(holder, IllegalStateException("Failed to create session $id", cause))` (`Client.kt:633-637`, `:697-704`) | `-32603` `Failed to create session <id>` |
| (d3) the agent never answers `session/new` | the opening and the speculative holder both stay pending | request hangs until the `newSession` caller is cancelled or times out (then case d1), the connection ends (handler cancelled, nothing sent), or the agent sends `$/cancel_request` (`-32800`) |
| load/resume of this id in flight | the holder was claimed before the request (`claimSessionHolder`, `ClientSessionRegistry.kt:351-357`; `Client.kt:657, 730`); the request waits on it exactly as in (b)/(c), even with `ClientSessionOperations` given up front, because `_session` completes only in `completeOpening` (`Client.kt:741-752`) | app sees it after the opening completes. Failed load/resume: `-32603` `Failed to create session <id>` (`Client.kt:665, 756`) |

**Bound on the wait:** nothing in the SDK. The wait ends only when the holder completes or fails. An unclaimed speculative holder is reaped only when no opening at all is in flight (`ClientSessionRegistry.kt:438`), so overlapping openings can keep it alive indefinitely. The peer can end the wait with `$/cancel_request` (`-32800`).

**Evidence in tests:** `acp-integration-test/.../SimpleAgentTest.kt:943-1009` ("unknown session request during init should fail fast with not found"). The agent sends `fs/read_text_file` for an unknown id from `createSession`, then delays 200 ms and answers with another id. The test asserts only that the failure message contains `Session <id> not found`, after `newSession` returns. Despite its name, the request is answered at the reap, not at once.

**Side effect (from the code, not tested):** a request for an unknown id while an opening is in flight leaves a speculative root holder that blocks the id until the reap. If the agent later *announces a subagent* with that id, `registerChild` sees the id blocked and rejects the announcement as malformed (`ClientSessionRegistry.kt:326-340`). That only hurts an agent that already broke announce-before-traffic (row 6).

### Kotlin: subagent path (brief)

- A child announced before the root's answer already has its `SubagentSessionHolder`, registered in the `session/update` handler before it suspends (`ClientSessionRegistry.kt:251-278, 312-342`). `subagentHolderOrNull` finds it, and `answerPermissionRequest` joins the child's cancel race at arrival, then waits on `session.await()` of that holder inside the race (`BaseClientSessionRuntime.kt:45-54`).
- The child's holder completes only after the parent's holder completes (`ClientSessionHolder.kt:801-850`, `parent.session.join()` at `:829-830`). So the request waits for the root's whole opening.
- While it waits, a live `idle(cancelled)`, a cancel by the client, or a close answers it `cancelled` (`SubagentPendingPermissions.kt:62-91, 105-124` @HEAD). A pre-answer `idle(cancelled)` is set aside and answered at `onOpened` after a resume/new, not after a load (`:94-99` @HEAD). Tests: `ClientSubagentSessionsTest.kt:411, 485, 519` and `ClientSubagentCloseTest.kt:349`.
- If the root's opening fails, the cascade fails the child with `IllegalStateException("Session <parent>, the parent of subagent session <child>, failed", …)` (`ClientSessionRegistry.kt:403-423`). The cascade doesn't close the child's permissions (only `startClosing` and `removeSessionTree` do, `ClientSessionHolder.kt:514`, `ClientSessionRegistry.kt:190`), so the request fails with that exception: `-32603`.
- A request for a child id that hasn't been announced yet falls through to `awaitSession` and gets the root treatment: (a) or (b).
- `fs/*`/`terminal/*` for a child id go through `awaitSession` (no race) and wait the same way.

### Rust (`34aea54`)

- **Dispatch order** (`src/agent-client-protocol/src/jsonrpc/incoming_actor.rs:471-617`), first claim wins:
  1. the user's builder handlers (`:510-531`);
  2. dynamic handlers, i.e. session routes (`:533-555`);
  3. the role default (`:557-584`).
- **Role default for a v1 client** (`impl Role for Agent`, `src/agent-client-protocol/src/role/acp.rs:276-317`): returns `Handled::No { retry }` with `retry = has_session_id && version is V1 or unset` (`:309-312`). The comment at `:296-308` says requests park only while a deactivated handler awaits removal, and "Any other unhandled request is answered with `-32601`".
- **Park or reject** (`incoming_actor.rs:586-617`):
  - a notification with role retry is parked (bounded 1024, `:650-652, 700-713`);
  - a request is parked only if `dynamic_handlers.any_inactive()` (`:604-605`, `:670-677`);
  - otherwise `reject_unhandled` answers `Error::method_not_found().data(method)` (`:621-646`).
  - The comment at `:592-599`: "a peer never waits for a handler nobody will register". Public doc: `src/agent-client-protocol/src/jsonrpc.rs:433-455`.
- **Tests:**
  - `tests/v1_feature_equivalence.rs:264-287` (`v1_client_answers_unhandled_session_request`): `session/request_permission` for an untracked id → `-32601`, `data: "session/request_permission"`. The doc comment says the old behavior parked it forever.
  - The behavior came from commit `2596425` (2026-11-02, "fix(acp): answer unhandled session requests and bound parked messages (#485)").
  - Parking during handler replacement: `incoming_actor.rs:855-900`.
- **`session/new`:** routing is installed in the ordered result transform, after the response and before the next incoming message (`session.rs:1013-1060`, `block_task_with_ordered_result` → `attach_session` → `prepare_session_routing`, `:265-297`; contract at `jsonrpc.rs:7167-7174`). So a request that arrives *before* the response finds no route and gets `-32601` at once. One that arrives after it is routed into the session's stream (`ActiveSessionHandler`, `session.rs:1396-1446`).
- **Builder-level handlers take precedence:** a client that registers `on_receive_request` for `RequestPermissionRequest`, as `examples/yolo_one_shot_client.rs:56-85` does, answers every permission request itself, with no session routing involved, including early ones. A client that relies on the session stream (`yopo/src/lib.rs:119-146`) gets the `-32601` behavior.
- **`session/load` / `session/resume`:** `prepare_session_routing` runs, and the request is sent only after a dynamic-handler barrier acknowledges it (`session.rs:450-513`, `send_ordered_request_to_after(…, routing_ready)`; test `tests/session_restore.rs:291` `restore_waits_for_routing_acknowledgment_before_publication`). An early request is pushed into the session's unbounded channel right away, as `SessionMessage::Message`, in wire order with the updates. Rust doesn't wait. The app reads it from the stream once `start_session` returns / `on_session_start` runs, so after the response.
  - **Failed restore:** the routing is dropped before later messages (`tests/session_restore.rs:351`). Dropping the receiver drains the queued messages (`futures-channel-0.3.34/src/mpsc/mod.rs:1352-1377`), which drops the request's `Responder`, whose guard answers `-32603` "request handler dropped its responder for `session/request_permission` without responding" (`jsonrpc.rs:4680-4691, 4770-4800`), or `-32800` if the peer had cancelled. **Inferred from code, no test.**
- **`session/fork` (v1):** no v1 helper; it is sent raw (`session.rs:1116`), so no routing is ever installed, and early (and later) fork-session requests get `-32601` unless a builder handler claims them.
- **Subagents:** Rust v1 has no subagent logic (earlier report), so a child-id request with no builder handler gets `-32601`.

### Side-by-side

| Situation | Kotlin v1 | Rust v1 (no builder handler) |
|---|---|---|
| unknown id, no opening in flight | `-32602` "Session <id> not found", immediate | `-32601` `data=<method>`, immediate |
| early request during `session/new`/`fork` for the id it will return | waits, then the app answers after the opening | `-32601`, immediate |
| early request during `session/new` for an id it won't return | waits until the last opening ends, then `-32602` | `-32601`, immediate |
| new/fork fails after the answer | `-32603` "Failed to create session <id>" | n/a (already answered) |
| request during `session/load`/`resume` of that id | waits; app answers after the opening completes | queued in session stream; app answers when it reads it (after the response) |
| load/resume fails | `-32603` "Failed to create session <id>" | `-32603` dropped-responder error (inferred) |
| any wait bounded? | no timeout; reap at end of last opening | no wait for routing ever (except the transient handler-replacement park) |

## SDK implications

Everything here is in `:acp`, `client` package, v1 runtime (`Client.kt`, `ClientSessionRegistry.kt`). I didn't check the v2 client counterpart (`client/v2`). v2 has its own upstream rule (row 5).

**Verdict: differs.** Rust answers an early session request with `-32601` and never waits for routing. Kotlin parks it on a speculative holder for new/fork, with no bound, and uses `-32602` where Rust uses `-32601`. For load/resume the observable result is close to Rust's: the request goes to the session, the app answers after the response, and a failure gives `-32603`.

Minimal options to match Rust (not deciding):

1. **Requests don't create speculative holders.** The request handlers (`Client.kt:122`, `:57`, `:65`) use a non-creating lookup:
   - a registered holder that is completed, or a `RootSessionHolder` already claimed (phase ≥ `CLAIMED`, i.e. load/resume in flight or new/fork answered): wait as now;
   - no holder, or an unclaimed speculative holder (created by an early `session/update`): answer at once with `-32601` (`jsonRpcMethodNotFound`, `Exceptions.kt:24-25`).

   Consequences:
   - (b) and (d1) become immediate errors;
   - speculative holders become notification-only, so the reap and the opening boundary are untouched;
   - the subagent-id-blocking side effect goes away;
   - the subagent path is unaffected, since a registered child holder still waits, and the AGENTS.md cancel-race rule still holds;
   - `SimpleAgentTest.kt:943` must change: its message/code assertion, and it would then really be "fast";
   - a new test should cover "request for the id before the `session/new` answer gets `-32601` and the opening still succeeds".
2. **Also change (a)'s code from `-32602` to `-32601`** (same `acpFail` → `jsonRpcMethodNotFound` swap in `getOrCreateSessionHolder` for request callers only). `claimSessionHolder` also uses `getOrCreateSessionHolder` (`ClientSessionRegistry.kt:352`), so the swap must not change the openings' own errors. Tests that assert `Session … not found` for requests need updating. A behavior change visible to agents.
3. **Keep the wait, align only the codes.** Not a Rust match on timing. Listed only because it is the smallest change.
4. **Use `-32002` with `data.sessionId` instead of `-32601`.** Spec-analogous (row 1 binds agents, not clients) rather than Rust-matching. Mentioned because the spec has a dedicated unknown-session code.

Whichever option: load/resume already route before sending, like Rust, and need no change. Keeping the wait there is equivalent to Rust's queued stream for the agent's view.

## Discrepancies

1. **Kotlin vs Rust, early request during new/fork:** Kotlin waits (unbounded) and the app answers later, or `-32602` at the reap. Rust answers `-32601` immediately. Rust deliberately removed its own earlier "park forever" behavior (`2596425`).
2. **Kotlin vs Rust, unknown-id code:** Kotlin `-32602` "Session <id> not found" (`Exceptions.kt:77`). Rust `-32601` with `data` = method. The spec's unknown-session SHOULD (`-32002`, `error.mdx:53`) binds only Agents. Neither SDK uses `-32002` here.
3. **Rust builder handlers bypass routing:** a Rust app with a connection-level `RequestPermissionRequest` handler sees early requests at once, with an id it doesn't know yet. The Kotlin v1 client has no connection-level permission handler, only per-session operations, so it has no equivalent.
4. **Kotlin test name vs behavior:** `SimpleAgentTest.kt:943` says "fail fast", but the request fails only when the concurrent opening ends.
5. Spec: none. v1 is silent on pre-answer requests. v2 says Agents SHOULD NOT send them (`v2/session-setup.mdx:105`).

## Open questions

- Should the v2 client (`client/v2`) be checked for the same pattern? Out of scope here.
- If option 1 is chosen: should a request for an id that has only an unclaimed speculative holder, because an early `session/update` created one, also get `-32601`? Rust's answer would be yes, since routing for new exists only after the answer. That is the reading I used above.
- Choice of code (`-32601` Rust parity vs `-32002` spec analogy vs keeping `-32602`) is a behavior decision for the orchestrator or user.
