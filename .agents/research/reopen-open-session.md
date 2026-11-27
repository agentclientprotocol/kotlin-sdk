# May a v1 client `session/load` / `session/resume` a session already open on the connection, and what happens to its state and subagent subtree?

**Sources checked:**
- spec: `agent-client-protocol` @ `13b76582` (2026-11-14, `main`, `pull --ff-only` "Already up to date")
- Rust SDK: `rust-sdk` @ `05fd3893` (2026-11-14, `main`, fast-forwarded from `91d368a` during this research)
- this repo: `acp-kotlin-sdk` @ `6079896` (HEAD; `Agent.kt` identical to `7ab77d2`), and `63f54c5` for pre-subagents behaviour

**Confidence:** high on the facts (each one cited). "v1 is silent" is a finding from reading every v1 page and schema
description that touches load/resume/close/unknown sessions. It is not a quoted statement.

## Answer

The v1 protocol says nothing about `session/load` or `session/resume` naming a session that is already active on the
same connection. It neither allows nor forbids it, and it defines no error for it. v1 defines "active" ("once it is
created, loaded or resumed on the connection, until it is closed") and gives `-32002` only for load/resume of a session
that **does not exist**. The subagents RFD (stage **Preview**, unstable schema only) treats load/resume of a parent as
*reconnection*. It also says nothing about a parent that is still active on the same connection. It does say that
child registrations are rebuilt during the load/resume (ancestor chains are re-announced) and that the client need not
keep the old tree. Only the **unstable v2** docs settle the case: "Resuming a session that is already active on the
connection synchronizes it again… Ongoing work continues". That is allowed, not rejected, but it does not bind v1. The
Rust SDK keeps no agent-side session registry and hands every load/resume to the app. Its v1 test agent upserts the
existing session. Neither rejects, and the Rust SDK has no subagent runtime. The Kotlin v1 agent at `63f54c5` already
replaced the wrapper silently and cleaned up nothing, not even a running turn. The Kotlin design document handles the
re-open on the client side only.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|---|---|---|---|---|
| 1 | `session/new` returns a unique session id | MUST | agent | stable v1 | spec `docs/protocol/v1/session-setup.mdx:73`, `:426` |
| 2 | A session is active once created, loaded or resumed on the connection, until closed | definition | both | stable v1 | spec `docs/protocol/v1/error.mdx:53` |
| 3 | Session-scoped request for a session that doesn't exist / isn't active → `-32002` with `data.sessionId`; covers load/resume of a session **that does not exist** only | SHOULD | agent | stable v1 | spec `docs/protocol/v1/error.mdx:53-55` |
| 4 | Load/resume of a session that is **already active on the connection** | **not defined** (silent) | — | stable v1 | spec `docs/protocol/v1/session-setup.mdx:85-268`, `error.mdx:51-57`, `schema/v1/schema.json:5312`, `:5411` (none mention it) |
| 5 | Load replays the whole conversation, then responds | MUST | agent | stable v1, capability:`loadSession` | spec `session-setup.mdx:136`, `:191` |
| 6 | Resume must not replay; restores context, reconnects MCP servers | MUST NOT replay | agent | stable v1, capability:`sessionCapabilities.resume` | spec `session-setup.mdx:258` |
| 7 | Close cancels ongoing work as if `session/cancel`, then frees resources | MUST | agent | stable v1, capability:`sessionCapabilities.close` | spec `session-setup.mdx:314` |
| 8 | Child `sessionId` unique within the ACP connection; MUST NOT be reused for an unrelated session | MUST | agent | unstable (RFD Preview) | spec `docs/rfds/subagents.mdx:122-124` |
| 9 | An association cannot reparent an existing session | MUST (stated as "cannot") | agent | unstable | spec `subagents.mdx:168-169` |
| 10 | Clients MUST NOT load/resume a child to receive its events | MUST NOT | client | unstable | spec `subagents.mdx:226-227`, `:948-949`, `:1075-1079` |
| 11 | Parent reconnection: Agent decides whether to reattach, restore, or only replay history of children | MAY (Agent's choice) | agent | unstable | spec `subagents.mdx:950-952` |
| 12 | On v1 `session/load` the Agent SHOULD replay recorded associations and child history best-effort | SHOULD, capability:`subagents` (client) | agent | unstable | spec `subagents.mdx:956`, `:962-966`, `:1002-1005` |
| 13 | Replayed child updates preserve original ids, parentage, per-session order; announcements precede child traffic; all replayed history before the response | MUST | agent | unstable | spec `subagents.mdx:970-972`, `:990-993` |
| 14 | On load/resume of a parent, the Client MUST invalidate cached child work state and mutation authorization | MUST | client | unstable | spec `subagents.mdx:1008-1016` |
| 15 | Client need not retain an earlier session tree; Agent re-establishes each child's full ancestor chain during **this** load/resume before live traffic | MUST (announcement ordering) | agent | unstable | spec `subagents.mdx:1018-1024` |
| 16 | Closing the parent cancels all active descendant work and releases resources | MUST (under close contract) | agent | unstable | spec `subagents.mdx:1154-1158` |
| 17 | v2: resuming a session already active on the connection synchronizes it again; ongoing work continues; MCP servers/roots replaced | defined behaviour | agent | **unstable v2** | spec `docs/protocol/v2/session-setup.mdx:54` |
| 18 | v2: whether a session may be active on several connections is up to the Agent; it MAY reject resume for a session active on **another** connection or end it there | MAY | agent | unstable v2 | spec `docs/protocol/v2/session-setup.mdx:55` |

## Details

### 1. Protocol

**Stable v1 docs (`docs/protocol/v1/session-setup.mdx`).**
- Load is for resuming "previous conversations", including "sharing sessions between different Client instances"
  (`:87`). "To load an existing session, Clients **MUST** call the `session/load` method with" id, MCP servers, cwd
  (`:110-114`). "The Client can then continue sending prompts as if the session was never interrupted" (`:203`).
- Resume lets "Clients … reconnect to an existing session without replaying" (`:207`). Requirements at `:232-258`.
- Close is for "an active session" (`:272`, `:297`). It must cancel ongoing work (`:314`) and is idempotent
  (`:326-329`).
- The `additionalDirectories` and `systemPrompt` rules for load/resume talk about "any previous or reported list"
  (`:360`) and the prompt "used when the session was created" (`:400`). They assume an earlier session but never say
  whether it is still active.
- Session ID section (`:424-434`): unique per `session/new`; used for load/resume/close. Nothing on duplicates.
- No sentence covers loading or resuming a session that is active on this connection. The draft v1 copy
  (`docs/protocol/v1/draft/session-setup.mdx`) differs only in links, line wrapping, prompt suggestions, fork and
  config options. None of those changes touch this case (checked by `diff`).

**Stable v1 errors (`docs/protocol/v1/error.mdx:51-55`).** "A session is active once it is created, loaded or
resumed on the connection, until it is closed." `-32002` (SHOULD) applies to "`session/load` or `session/resume` of a
session that does not exist". Nothing about one that exists and is active.

**v1 schema.** `LoadSessionRequest` ("Request parameters for loading an existing session", required
`mcpServers, cwd, sessionId`) at `schema/v1/schema.json:5312`, Rust model
`agent-client-protocol-schema/src/v1/agent.rs:1453-1465`. `ResumeSessionRequest` (required `sessionId, cwd`) at
`schema.json:5411`, `agent.rs:1913-1926`. `SessionId` ("A unique identifier for a conversation session") at
`schema.json:297`, `src/v1/mod.rs:39-50`. Method docs: `docs/protocol/v1/schema.mdx:405-418` (load: "Restore the
session context and conversation history … Stream the entire conversation history"), `:665-673` (resume). None of
these mention an already-active session. `SubagentUpdate` is in `schema/v1/schema.unstable.json` only, not in
`schema.json`.

**Subagents RFD (`docs/rfds/subagents.mdx`, stage Preview: `docs/docs.json:239-240`, moved there on 2026-11-13 by
`12d4555a`).**
- It frames load and resume as reconnection: "The Client reconnects through the ordinary parent session"
  (`:948`). "The Agent decides whether parent reconnection reattaches surviving child runtimes, restores them, or only
  makes their recorded history available. The protocol does not prescribe a runtime-recovery algorithm"
  (`:950-952`).
- v1 entry points: load = replay, resume = no replay (`:954-957`).
- Re-registration on reload: "The Client need not retain an earlier session tree. Before using a child in live
  traffic, the Agent establishes the child's complete ancestor chain in parent-before-child order during this
  load/resume" (`:1018-1020`). Replayed associations "can provide that routing context" (`:1020-1022`). So on every
  load/resume the Agent sends the announcements for the same child ids again. Re-announcing the same child is
  allowed, because the uniqueness rule forbids reuse only "for an unrelated session" (`:122-124`).
- Client invalidation on every load/resume (`:1007-1016`). The freshness boundary when replay is requested
  (`:1026-1035`). Without replay, snapshots are current observations (`:1045-1050`).
- Close cascades to descendants (`:1154-1158`).
- **Silent on:** a parent load/resume while that parent and its children are still active on the same connection.
  The text never says whether surviving child runtimes on the same connection must be torn down, kept, or re-attached
  (`:950-952` leaves the choice to the Agent).

**Related Draft RFD `docs/rfds/session-status.mdx`** (stage Draft, `docs/docs.json:197`, `:229`). "`session/load` and
`session/resume` make the Agent start handling a session for the connection that sent them" (`:28`). `open` does not
mean the requesting connection is attached (`:98`, `:211`). It defers multi-client attach semantics to a "Multi-Client
Session Attach RFD" (PR #533), which is not in the repository (`:211`). It doesn't address re-open on the same
connection either.

**Unstable v2, for contrast only, not binding on v1.** `docs/protocol/v2/session-setup.mdx:50-56` (added by
`3493bd15`, "docs(protocol): define the v2 session lifecycle (#2579)", 2026-10-28):
- `:54`: "Resuming a session that is already active on the connection synchronizes it again, like any resume.
  Ongoing work continues, and the request's MCP servers and workspace roots replace the previous ones."
- `:55`: another connection is the Agent's choice (MAY reject, or end it there).
- The Draft v2 idempotency RFD repeats this: `docs/rfds/v2/idempotency-keys.mdx:53`, `:180` ("Synchronizes the session
  again… Idempotent").
- The draft v2 resume snapshot MUST include a `subagent_update` for each listed background child
  (`docs/protocol/v2/draft/session-setup.mdx:407`).
- So v2 models re-resume as "same session, re-synchronized", not as "replace" or "reject".

### 2. Rust reference SDK (`rust-sdk` @ `05fd3893`)

- **No agent-side session registry.** The agent's fallback for client messages that no handler claimed returns
  `Handled::No { retry: false }` (generic `-32601`). The only special case is v2 entity cancel
  (`src/agent-client-protocol/src/role/acp.rs:43-79`). `session/load` and `session/resume` reach the app's typed
  `on_receive_request` handler. The SDK neither rejects nor replaces anything for an already-open id.
- The `retry` path at `role/acp.rs:325-353` is on the **client** side (`ConnectionTo<Agent>`). It covers the race
  between a `session/new` response and the per-session dynamic handler. It does not detect duplicate agent sessions.
- **Test agent (`agent-client-protocol-test`, Testy v1).** Load and resume both call `upsert_session`
  (`src/agent-client-protocol-test/src/testy/v1.rs:1771-1802`). `upsert_session` (`:140-155`) uses
  `entry(id).or_insert_with(..)`, then overwrites `cwd`, `additional_directories`, `mcp_servers`. For an already-open
  session the existing `SessionData` is kept, including `active_prompts` and the cancel flags. Nothing is cancelled,
  and nothing is rejected. This is a test fixture, not a normative policy. It also creates unknown ids instead of
  answering `-32002`.
- **Subagents.** Schema support only, behind `unstable_subagents` (v1) or included in v2. `md/features.md:125-133`:
  "This is schema support, not a subagent scheduler or an implementation of application-level ownership and lifecycle
  policy." The only tests are serde tests (`src/agent-client-protocol/tests/schema_subagents.rs:1-48`).
- Client-side re-open in Rust is an app policy. The v2 cookbook prototype shares a pending resume and sequences
  "drain the published resume, then close" before a replacement (`md/session-operation-coordination.md:31-51`). It
  is a draft-v2 client-side example and doesn't cover agent behaviour.

### 3. This Kotlin SDK

**v1 agent at `63f54c5`, before subagents** (`acp/src/commonMain/kotlin/com/agentclientprotocol/agent/Agent.kt`
at that revision):
- The load handler (`:362-378`) and the resume handlers (`:380-389`, registered again at `:444-454`, so the later one
  wins) call `createSession`.
- `createSession` (`:552-584`) always calls the app's factory first (`agentSupport.loadSession`/`resumeSession`,
  `:557`). It then builds a new `SessionWrapper` and does `_sessions.update { it.put(session.sessionId, wrapper) }`
  (`:567-569`). It doesn't check for an existing entry, so an open id's wrapper is **replaced silently**. Replacing
  was therefore already the behaviour.
- Nothing of the old wrapper is cleaned up. By reading:
  - The old wrapper's running turn, if any, belongs to the old `session/prompt` handler coroutine. Its
    `_activePrompt` lives on the old wrapper (`:111`, `:179-182`). It keeps running and keeps sending updates through
    the old `RemoteClientSessionOperations`, which use the same session id on the wire.
  - A later `session/cancel` resolves to the **new** wrapper (`:412-417`, `getSessionOrThrow` `:586`). The new
    wrapper's `_activePrompt` is `null`, so the old turn's job is not cancelled. Only the new `AgentSession.cancel()`
    runs.
  - A new `session/prompt` on the new wrapper can run alongside the old turn. The "one turn per session" guarantee is
    per wrapper (`:180`).
  - `session/close` closes and removes only the new wrapper (`:420-427`).
  - Hypothesis, not checked: if the app returns the same `AgentSession` object from `loadSession`, its `cancel()` and
    `close()` still reach the app's state, but the old turn's SDK job still isn't cancelled.
- Failure path: if replay throws, it removes the new wrapper only if it is still the current one (`:573-577`). The
  old wrapper was already replaced at `:567`, so a failed re-load leaves the id unregistered. Finding S3 scenario A
  describes the same thing.

**v1 agent today (`7ab77d2` = HEAD).** Same replacement at `Agent.kt:697-699`. Subtree cleanup
(`closeSubtree`, `:777-786`) is keyed on `it.root === root`, and it runs only from the failed-replay path (`:709`) and
from `session/close` (`:546`). `registerSubagent` rejects an id already in `_subagents` or `_sessions`
(`:749-755`). On a reload of an open root, the replayed `announce` of an existing child therefore throws. See
review `.agents/research/review-m2-agent.md` S3 (`:74-95`).

**v1 client today (HEAD).**
- The factory overloads (`loadSession`/`resumeSession` with `ClientOperationsFactory`,
  `client/Client.kt:370-377`, `:501`) **allow** re-opening an open id. `claimFactorySessionHolder` → `claim(FACTORY)`
  succeeds when a factory call already owns the holder, completed or not
  (`client/ClientSessionHolder.kt:75-86`, `client/ClientSessionRegistry.kt:131-134`). The request goes out. By
  reading, `drainEventsAndCompleteSession` (`ClientSessionHolder.kt:88-98`) cannot complete the already-completed
  deferred. Updates, replay included, keep going to the first session object, and the returned object is never
  registered. The design document confirms this at `:1016-1019`.
- The operations overloads (`Client.kt:400-407`, `:529`) **refuse** before sending: "Session … is already open or
  being opened" (`ClientSessionRegistry.kt:141-144`, `ClientSessionHolder.kt:85`, KDoc `Client.kt:397-398`).
- The v2 client allows re-resume of an open session and routes updates to the latest object (tests at `63f54c5`
  `acp/src/jvmTest/kotlin/com/agentclientprotocol/client/v2/ClientSessionTest.kt:311-347`).

**Design document (`docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md`).**
- Client: "Holders", "One opening per holder" (`:1011-1021`). Every opening overload will refuse an id that is open or
  being opened, and the client must close the session first. Decision 19 (`:1950-1960`) rejected joining the open
  session (#148, JetBrains Air reopening a tab) and rejected replacing it ("a reload can be added later as an explicit
  API"). It is called out as a breaking release-note item at `:1661-1663`.
- Agent side: **nothing addresses an agent receiving load/resume of an already-open root**, whether from a non-Kotlin
  client or from today's Kotlin factory path.
  - "Replay (`session/load`)" (`:548-589`) assumes "child registrations don't survive a reconnect" (`:552`) and says
    "If the load fails, drop every registration made during the attempt" (`:575`). It never says what happens to an
    open root's existing registrations.
  - "Lifetimes" says subtree cleanup "runs in exactly two cases": `session/close` on the root and connection end
    (`:534-544`).
  - "`announce` semantics" step 3 rejects an id already registered, "root sessions included" (`:319-320`). The only
    duplicate-id scenario it discusses is a fork without remap loaded on one connection (`:584-586`).
  - The test plan has no agent-side reopen test. The "Reload and discovery" test (`:1525-1528`) is client-side and
    closes the root first.

## SDK implications

- Where the requirement lands: `:acp`, v1 `agent/Agent.kt` (`createSession`, `closeSubtree`, `registerSubagent`). The
  v2 counterpart `agent/v2/Agent.kt:552` also does a plain `put`, which is consistent with v2 `:54` "synchronizes it
  again" for the root. Whether v2 subagents need the same handling is out of scope here.
- Neither fix contradicts a v1 MUST. Each one decides something v1 leaves open.
- What a test would observe today on a conforming peer that re-opens, e.g. a client that sends `session/load s1`
  twice. v1 allows that because it is silent.
  - With subagents: the second replay's `announce(c1)` throws, the load answers with an error, `s1` drops out of
    `_sessions`, and `c1`'s collection keeps sending (S3).
  - Without subagents: the load succeeds, and a turn still running from before the reload can no longer be cancelled
    by `session/cancel`.

## Discrepancies

- **v1 vs v2.**
  - v1 is silent on re-open of an active session.
  - Unstable v2 defines it as valid re-synchronization with ongoing work continuing (`v2/session-setup.mdx:54`).
  - Neither the v1 spec nor the RFD says the v2 rule applies to v1.
- **v2 rule vs the "cleanup" fix.**
  - v2 says "Ongoing work continues" on re-resume.
  - Subtree cleanup cancels child collections, which "for an in-process child whose flow is the work, … also stops the
    work" (design `:519-521`).
  - If v1 followed v2's direction, cleanup on replace would stop work that v2 keeps running.
  - This is a tension with an unstable spec, not a v1 conflict.
- **v2 rule and RFD vs the "reject" fix.**
  - v2 explicitly accepts same-connection re-resume.
  - The RFD's reconnection model expects children to be re-announced during a load/resume (`subagents.mdx:1018-1024`).
  - Rejecting is not forbidden by v1, but v1 also defines no error code for it. `-32002` is only for a missing or
    inactive session (`error.mdx:53-55`), so an already-active session doesn't qualify.
- **Kotlin agent vs Kotlin client.**
  - Today the factory client path re-opens and the agent replaces.
  - The planned client refuses all re-opens. The agent side has no matching rule.
  - Other clients such as Rust-SDK apps, Testy-style agents and JetBrains Air (#148) can still send the request.
- **Rust test agent vs Kotlin agent.**
  - Rust Testy keeps the existing session state on re-load (upsert).
  - The Kotlin agent builds a fresh wrapper and discards the old one without cleanup.
- **Design doc vs RFD stage.** The design doc calls the RFD "an unstable draft" (`:9-10`). It is now in the Preview
  group (`docs/docs.json:239-240`, since `12d4555a`, 2026-11-13). The schema is still unstable.
- Side observation: the v1 `SessionResume` handler is registered twice (`Agent.kt` at `63f54c5` `:380`, `:444`; HEAD
  `:490`, `:566`). The design doc already records this (`:1731-1732`).

## Which fix each source supports or rules out

| Source | "Reject load/resume of an open id" | "Clean up the old subtree when a root is replaced" |
|---|---|---|
| v1 spec docs + schema (stable) | Not required, not forbidden. No defined error code, since `-32002` covers only non-existent or inactive sessions. | Not required, not forbidden. Close (`:314`) is the only defined teardown. |
| Subagents RFD (Preview, unstable) | Not addressed. Its reconnection model expects load/resume to rebuild child registrations, which a reject never reaches. | Consistent with it: the client drops the old tree (`:1008-1016`, `:1018`), the agent re-announces during this load/resume (`:1018-1024`), and runtime recovery is the agent's choice (`:950-952`). Not mandated. |
| Unstable v2 spec | Argues against it for same-connection resume (`v2/session-setup.mdx:54`). Not binding on v1. | Partly argues against it: "Ongoing work continues" suggests keeping or re-synchronizing children rather than stopping them. Not binding on v1. |
| Rust SDK | Doesn't do it. No SDK-level check; the app decides. | Not applicable. No subagent runtime; Testy keeps old state on reload. |
| Kotlin at `63f54c5` | Didn't do it. Replacing was already the behaviour. | Didn't do it. Nothing of the old wrapper was cleaned up, not even a running turn. |
| Kotlin design doc | Client side only: refuse. Nothing on the agent side. | Not addressed. Cleanup is defined only for close and connection end. |

Net: upstream does not decide this for v1. The closest normative direction is unstable v2, where re-resume is accepted
and work keeps running. That argues against SDK-level rejection. It also argues against a cleanup that stops work
silently without telling the app. It is not binding.

## Open questions

- Should the agent call the old root's `AgentSession.close()` (or `cancel()`) when replacing, or leave it to the app?
  Neither fix as stated handles the old root's running turn or the app's old session object. This is pre-existing
  behaviour from `63f54c5`. It is a design and user decision, not a protocol one.
- Multi-client attach semantics are deferred to an RFD that is not in the spec repo (PR #533,
  `session-status.mdx:211`). A future v1 rule may come from there.
- v2 agent (`agent/v2/Agent.kt:552`) re-resume with subagents. v2 requires a `subagent_update` per background child in
  the resume snapshot (`v2/draft/session-setup.mdx:407`). This needs a separate question once v2 subagents are
  scheduled.
