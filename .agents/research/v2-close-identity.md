# Does the v2 Kotlin client need the v1 `session/close` fix, and does ACP v2 define close the same way?

**Sources checked:**
- spec: `agent-client-protocol` @ `bc03f00f` (2026-11-19, `main`, already up to date; checkout `/Users/eugene/Documents/JetBrains/projects/agent-client-protocol-2`)
- this repo: `acp-kotlin-sdk` @ `55567a0` (branch `eugenethedev/subagents`, clean)
- Rust SDK: not re-checked. The Rust v2 `ActiveSession::close` facts are taken from `.agents/research/client-close-identity.md:119`
  (rust-sdk @ `8d91a29e`) and are second-hand here.

**Confidence:** high on the spec text and the Kotlin trace (each cited). The recommendation is a design judgment,
because v2 allows one situation v1 forbids (re-resuming an id that is still active).

## Verdict

**Yes, the same bug is present in the v2 client, and the same kind of fix applies, but not the same guard.** v2 close
is defined like v1 close (id only, idempotent, `-32002` counts as success). The one difference that matters is that
v2 lets a client re-`session/resume` an id that is still active. The v1 guard "send only if my object is the
registered one" would then wrongly refuse a legitimate close. So the v2 guard must ask "has this object already been
removed from the client?", not "is this object the one in the map?".

Concretely (details below):
- The v2 `ClientSession.close()` has no local guard. It always sends `session/close` for its id and then
  `removeSession(sessionId)` by id. A close on an object that was already closed, after the id was resumed again,
  closes the newer session on the agent and drops the newer session on the client (its `updates` flow completes).
- A `-32002` answer fails `close()` and removes nothing. The spec says the client SHOULD treat it as success.
- The Kotlin v2 agent does not decide what happens for an unknown id. It forwards to the app's
  `AgentSupport.closeSession`, whose default throws "not implemented" (`-32602`), so a conforming reply (empty result)
  depends on the app.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|---|---|---|---|---|
| 1 | "To close an active session, Clients MUST call `session/close` with the session ID". The request has `sessionId` and `_meta` only; no instance or generation token | MUST (for an active session) | client | v2 (in `schema/v2/schema.json` and `schema.unstable.json`; docs group `v2`, tag "Draft") | spec `docs/protocol/v2/session-setup.mdx:445`; schema `schema/v2/schema.json:7527-7549` |
| 2 | `session/close` needs no capability marker in v2. It is required once `capabilities.session` is advertised (v1 had `sessionCapabilities.close`) | MUST (agent implements), no client gate | both | v2 | `migration.mdx:47`, `:225`, `:798`; `initialization.mdx:206`; schema `schema.json:2922` |
| 3 | Agent MUST cancel the session's ongoing work as if `session/cancel`, free resources, cancel pending permission and elicitation requests with `$/cancel_request` before responding; send final updates and the idle `cancelled` state before a successful response; after the response send nothing for the session on that connection | MUST | agent | v2 | `session-setup.mdx:462-464` |
| 4 | Closing is idempotent. If the session does not exist or is not active, "for example because it was already closed", the Agent SHOULD respond with the same empty result | SHOULD | agent | v2 | `session-setup.mdx:478-481`; `error.mdx:58`; `overview.mdx:246` ("Idempotent / Succeeds"); schema `schema.json:7528` |
| 5 | Clients SHOULD treat a `-32002` response to `session/close` as a successful close | SHOULD | client | v2 | `session-setup.mdx:480-481`; `error.mdx:58`; schema `schema.json:7528` |
| 6 | "Active" means: from a successful `session/new` or `session/resume` on the connection until `session/close` or the end of the connection. A closed session is not discarded; it may be resumed later if the Agent still has it | definition | both | v2 | `session-setup.mdx:50`, `:54` |
| 7 | Resuming a session that is already active on the connection synchronizes it again; work continues and the new MCP servers and roots replace the old ones. The id stays one active session | allowed | both | v2 | `session-setup.mdx:55`; `overview.mdx:242` (`session/resume` is "Idempotent") |
| 8 | A session-scoped request (prompt, set_config_option, ...) for an unknown or inactive id MUST get `-32002` with `data.sessionId`. `session/close` and `session/delete` are the exceptions | MUST (agent) | agent | v2 | `error.mdx:56-58` |
| 9 | Clients MUST NOT send `session/close` for a child (subagent) session | MUST NOT | client | v2 | `subagents.mdx:425`, `:376`, `:380` |
| 10 | After a successful close the client SHOULD treat unfinished listed tool calls as `cancelled` and listed terminals as exited with unknown status; SHOULD dismiss permission and elicitation prompts for the closed session | SHOULD | client | v2 | `session-setup.mdx:462` |

## Details

### 1. How v2 defines close, versus v1

- Same as v1: close is keyed by the session id only (req. 1), idempotent (req. 4), and the client treats `-32002` as
  success (req. 5). In v1 these are `session-setup.mdx:326-329` of `docs/protocol/v1/` (see
  `.agents/research/client-close-identity.md` reqs. 4 and 5).
- May an id be active again after resume? Yes. A closed session "doesn't get discarded" (req. 6), so
  `session/resume` can make the same id active again on the same connection. v2 has no `session/load`; `session/resume`
  with `replayFrom` replaces it.
- Differences that matter:
  1. v2 allows `session/resume` of an id that is **still active** and treats it as a re-synchronization of the one
     session (req. 7). v1 is silent on that, and the Kotlin v1 client refuses it locally
     (`.agents/research/client-close-identity.md:106-107`). The Kotlin v2 client allows it and returns a new
     `ClientSession` that replaces the old one (`client/v2/Client.kt:268-290`, KDoc at `:261-268`).
  2. v2 needs no `close` capability (req. 2). The v2 client has no capability check to keep.
  3. v2 adds obligations around permission and elicitation cancellation and the idle `cancelled` state (reqs. 3, 10).
     They concern what the agent sends before answering, not how the client picks the session to close.
  4. v2's client-side subagent rules exist in the spec (req. 9), but the v2 Kotlin runtime has no subagent code
     (`rg -ci subagent` over `client/v2` and `agent/v2` finds nothing). The v1 holder-tree concerns do not exist in v2.
- The spec has no notion of two `ClientSession` objects for one id. With req. 7, two handles for one id are two views
  of the same active session.

### 2. The v2 Kotlin client, end to end

`ClientSession.close` (`acp/src/commonMain/kotlin/com/agentclientprotocol/client/v2/ClientSession.kt:127-136`):
1. `pendingPermissions.close()`, the object's own signal. Harmless on any object.
2. `SessionClose` is sent with `CloseSessionRequest(sessionId, _meta)`. No guard before it: no closed flag, no check
   of the registry.
3. `onClosed()` runs only after a successful response. It is `{ removeSession(sessionId) }`, set up in `register`
   (`Client.kt:474`).

`removeSession(sessionId)` (`Client.kt:632-639`) removes both `inboxes[sessionId]` and `sessions[sessionId]` **by id**,
whatever object they hold, and closes that inbox so the holder's `updates` flow completes. `deleteSession` does the
same (`Client.kt:451-454`).

`register` (`Client.kt:462-477`) builds the object after the opening response, claims the inbox
(`claimInbox`, `:517-547`, which closes a replaced inbox when the id is registered a second time), and puts the
object into `sessions` by id, overwriting an older one. There is no object visible before its opening answered, so
v1's S2 (close on an object whose opening failed) has no v2 counterpart.

Stale-close scenarios, all reproduced from the code:
- **S1, second close after a successful close, id resumed again.** O1.close() succeeds and removes the id. The app
  resumes the id (O2). O1.close() is called again: it sends `session/close` for the id; the agent closes O2's
  session; `removeSession(id)` removes O2 and completes O2's `updates` flow. O2's `pendingPermissions` are not
  answered. Later `session/request_permission` for the id gets "unknown session" (`Client.kt:209-210`).
- **S1', second close, id not open again.** The request goes out for an id the agent no longer has. With the Kotlin
  agent the outcome depends on the app (section 3); a conforming agent answers success, a `-32002` answer fails
  `close()` (below).
- **Superseded object (v2 only).** O1 open, then `resumeSession(sameId)` returns O2, which replaces O1 in `sessions`
  and closes O1's inbox (`Client.kt:546`). O1.close() sends and removes the id by id, which closes O2's session on
  both sides. That is **correct by the spec**: O1 and O2 are two handles to one active session (req. 7).
- **Delete.** `deleteSession(id)` removes whatever object holds the id, but the object itself is not marked. A later
  `close()` on it sends `session/close`.

`-32002`: not handled. `Protocol` converts error responses with `JsonRpcException.toProtocolException`
(`protocol/Protocol.kt:217`), which turns only `-32700`, `-32602` and `-32800` into other types and leaves other codes
as `JsonRpcException` (`protocol/Exceptions.kt:90-97`). So an agent answer `-32002` makes `close()` throw
`JsonRpcException(code = -32002)` before `onClosed()`, and nothing is removed. This violates req. 5. `RESOURCE_NOT_FOUND`
exists in the model (`acp-model/.../rpc/JsonRpc.kt:147`) but no client code uses it.

Tests: `V2ClientTest.kt:1043-1054` ("closing a session ends it on both sides") covers the single close. No test covers
a second close, a close after a re-resume, a close after delete, or `-32002`.

### 3. The v2 Kotlin agent

`SessionClose` handler (`agent/v2/Agent.kt:496-503`):
```
_sessions.value[params.sessionId]?.cancelAndJoinTurn()      // skipped for an unknown id
val response = agentSupport.closeSession(params.sessionId, params._meta)
_sessions.update { it.remove(params.sessionId) }
```
- The SDK never answers an unknown id itself. It always calls `AgentSupport.closeSession`
  (`agent/v2/AgentSupport.kt:174-175`), whose default is `notAdvertised(...)`, which is `acpFail`
  (`AgentSupport.kt:192-194`), mapped to `-32602` (`protocol/Exceptions.kt:77`).
- An app that implements `closeSession` decides. The test and sample agents return `CloseSessionResponse()` for any id
  (`V2ClientTest.kt:997-999`, `V2ConversationAgent.kt:138`, `samples/.../V2SampleSupport.kt:53`), which conforms to req. 4.
- The agent wrapper is removed only after the app returned, and `cancelAndJoinTurn` runs before the app call. If the app
  throws, the session stays tracked.
- Related discrepancy: `getSessionOrThrow` uses `acpFail("Session $sessionId not found")`, which is `-32602`
  (`Agent.kt:555-556`), for `session/prompt` and `session/set_config_option`. The spec requires `-32002` with
  `data.sessionId` (req. 8). The v2 agent never sends `-32002`. A real conforming agent does, so the client
  `-32002` handling matters even if this SDK's agent never produces it.
- `register` overwrites an existing wrapper for an id (`Agent.kt:551-552`), so a re-resume of an active id leaves the
  old `AgentSession` unclosed. Not part of this question; noted for routing.

### 4. Why the v1 approach does not apply as is

The v1 fix: send only if `sessions[id] === holder && holder blocks the id`. In v1 this works because
`claimForOpening` refuses to open an id whose holder is live, so "my holder is not the registered one" can mean only
"I was already closed or I failed". In v2:
- "My object is not the registered one" also means "I was superseded by a re-resume". By req. 7 that session is
  still active, and O1.close() is a legitimate request to close it. Refusing locally would leave the session open
  on the agent while the app believes it closed it.
- v2 has no failed-opening object, so the third v1 rule (throw `IllegalStateException`) has no counterpart.
- v2 has no holders, subagent trees or `closeDescendantPermissions`. The cleanup is one by-id map update plus
  closing one inbox.

## SDK implications

Where it lands: `:acp`, `com.agentclientprotocol.client.v2` (`ClientSession.kt`, `Client.kt`). No model change
(`CloseSessionRequest`/`CloseSessionResponse` already exist, `acp-model/.../model/v2/Session.kt:158-163`). Public API:
no new public member needed; `ClientSession` has an `internal` constructor, so adding an internal callback or flag is
not an API change. A KDoc update on `ClientSession.close` is needed.

Minimal changes that mirror the v1 fix, adapted to v2:
1. **Mark an object as ended when it leaves the client.** A `ClientSession` gets an internal atomic "ended" flag.
   `removeSession` marks whatever object it removes from `sessions` (so an object closed, deleted, or replaced-and-then-
   removed is ended). A superseded object is **not** marked by the replacement, because its id is still active.
2. **Guard `close()`:** if this object is ended, return `CloseSessionResponse()` locally without sending
   and without touching the registry. Matches the idempotent empty result of req. 4, as the v1 stale-close rule does.
   Do this check before `pendingPermissions.close()` or keep it harmless; the object's own signal is already closed.
3. **Clean up the object that is registered for the id, and only if the id is still registered.**
   Otherwise (the id has no registered session) there is nothing to remove. This keeps by-id removal for the
   superseded case, which is right (req. 7), and the guard in step 2 is what stops the S1 bug. Identity cleanup alone
   (option (c) in the v1 report) would leave the registered newer object live after the agent closed its session.
4. **Treat `-32002` as success.** In `close()`, catch `JsonRpcException` with
   `code == JsonRpcErrorCode.RESOURCE_NOT_FOUND.code`, run the cleanup, and return `CloseSessionResponse()`. Rethrow
   anything else; never catch `CancellationException` as a failure. Same for `deleteSession` (spec
   `session-delete.mdx:92`), a separate small slice.
5. **Optional:** a close issued concurrently with a resume of the same id races on the agent too; the spec leaves that
   to the app. Do not add a lock.

What a test would observe:
- Agent that counts `session/close` and `-32002` for unknown ids. Conforming case: `close()` on O1, `resumeSession` of
  the same id (O2), `close()` on O1 again. The agent sees exactly one close; O2 stays registered, its `updates` flow
  stays open, and the second `close()` returns an empty response. Today the agent sees two closes and O2 is removed.
- Control: `close()` on O2 sends and removes O2.
- Superseded case: O1 open, `resumeSession` returns O2, `close()` on O1 sends one close and ends O2 as well
  (`client.getSession(id)` is `null`). Not-guarded today and after the fix.
- Delete: `deleteSession(id)` then `close()` on the object sends nothing after the fix.
- `-32002`: an agent answering `-32002` to `session/close` makes `close()` return normally and remove the session
  (today it throws and keeps the session).
- A non-conforming agent that answers `-32602` for an unknown id still makes `close()` throw, which is accurate for
  that peer.

## Discrepancies

- **Kotlin v2 client vs spec:** `-32002` on `session/close` fails `close()` and removes nothing
  (`ClientSession.kt:133-134`); spec SHOULD treat it as success (`session-setup.mdx:480-481`, `error.mdx:58`).
- **Kotlin v2 agent vs spec:** unknown-id close is delegated to the app, and the default answers `-32602`
  (`Agent.kt:496-503`, `AgentSupport.kt:174`, `:192`); spec says SHOULD succeed (`session-setup.mdx:478-481`). The
  agent answers `-32602`, not `-32002` with `data.sessionId`, for prompt and set_config_option on an unknown id
  (`Agent.kt:555-556`; spec `error.mdx:56`).
- **v1 report vs this finding:** `client-close-identity.md:182-183` says v2 "routes to the latest object, so its
  trade-off differs". Confirmed: by-id removal is partly right in v2 (superseded case) and only the already-ended case
  is a bug.
- **Rust v2 `ActiveSession` (second-hand):** by-id handle, always sends, maps `-32002` to success
  (`client-close-identity.md:119`). Consistent with the spec; it keeps no registry, so it has no analog of the Kotlin
  bug.

## Open questions

- Should a `ClientSession` superseded by a re-resume (`Client.kt:546` closes its inbox, so its `updates` flow ends)
  be documented as a stale handle for everything except `close()`? A separate slice.
- Should `deleteSession` also treat `-32002` as success (spec `session-delete.mdx:92`)? Separate slice.
- Should the v2 agent answer `-32002` (with `data.sessionId`) for unknown sessions, and default an unknown-id close to
  success? Agent-side slices.
- Should the v2 agent close the old `AgentSession` when `session/resume` re-registers an active id (`Agent.kt:551-552`)?
  Not asked; spec says resume re-synchronizes, so closing the old object may be wrong. Needs its own question.
