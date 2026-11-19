# Should the v1 client's close callbacks act on the session's own holder (identity) instead of the id?

**Sources checked:**
- spec: `agent-client-protocol` @ `bc03f00f` (2026-11-19, `main`, fast-forwarded from `6ace4f6a` during this research)
- Rust SDK: `rust-sdk` @ `8d91a29e` (2026-11-19, `main`, fast-forwarded from `6a4a95c` during this research)
- this repo: `acp-kotlin-sdk` @ `0c334d8` (branch `eugenethedev/subagents`, clean)

**Confidence:** high on the trace and the spec text, each one cited. The recommendation is a design judgment. The
spec allows (a) and (b) and does not decide between them.

## Verdict

**Yes, if the identity change comes with a local guard: option (b).** Do not do identity cleanup alone, which is
option (c).

Today a stale `ClientSession.close()` always sends `session/close` for its id. Nothing in the client checks first.
The wire carries only the id, so the agent closes whatever session is active under that id. The client then cleans up
whatever holder the id maps to. That is internally consistent, but it closes a newer session the app never asked to
close. The newer `ClientSession` object is not told, and its own pending permissions are not answered.

ACP v1 defines close per session id, says close is idempotent, and has no notion of a session instance beyond the id.
A stale object whose session was already closed therefore owes the agent nothing. The empty result the client can
return locally is the same result a conforming agent would send for an already-closed session.

The guard is: send only while this object's holder is the registered holder of its id and blocks the id. Two things
follow from that rule:
- Identity cleanup and by-id cleanup pick the same holder in every close that is actually sent. `claimForOpening`
  refuses to open an id whose holder blocks it, so no newer holder can appear while the close is in flight.
- `markClosing` can become strict.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|---|---|---|---|---|
| 1 | Close only if the agent advertises `sessionCapabilities.close`, otherwise MUST NOT call `session/close` | capability:`sessionCapabilities.close`, MUST NOT | client | stable v1 | spec `docs/protocol/v1/session-setup.mdx:276`, `:293` |
| 2 | "To close an **active** session, Clients MUST call `session/close` with the session ID". The id is the only key; there is no other instance field | MUST (for an active session) | client | stable v1 | spec `session-setup.mdx:297-311`; schema `schema/v1/schema.json:5465-5473` |
| 3 | Agent MUST cancel ongoing work for "that session" as if `session/cancel`, then free its resources | MUST | agent | stable v1 | spec `session-setup.mdx:313` |
| 4 | Close is idempotent. For an unknown or inactive session ("for example because it was already closed"), the agent SHOULD answer the same empty result | SHOULD | agent | stable v1 | spec `session-setup.mdx:326-328`; `docs/protocol/v1/error.mdx:55` |
| 5 | Clients SHOULD treat a `-32002` response to `session/close` as a successful close | SHOULD | client | stable v1 | spec `session-setup.mdx:328-329` |
| 6 | "Active" means from create, load or resume on the connection until closed. An id can become active again by a later load or resume | definition | both | stable v1 | spec `error.mdx:53` |
| 7 | Session id is "a unique identifier for the conversation context", used for prompt, cancel, load, resume and close. No generation or instance id exists | definition | both | stable v1 | spec `session-setup.mdx:424-434`; `schema/v1/schema.json:297` (per the earlier report `reopen-open-session.md:75`) |
| 8 | Clients MUST NOT send `session/close` for a child (subagent) session | MUST NOT | client | unstable (v1 draft page; RFD Preview) | spec `docs/protocol/v1/draft/subagents.mdx:475`; agent SHOULD reject it with `-32602` (`:428`) |
| 9 | A child `sessionId` MUST NOT be reused for an unrelated session | MUST | agent | unstable (RFD Preview, `docs/docs.json:242-243`) | spec `docs/rfds/subagents.mdx:122-124` |

## Details

### 1. What a stale `close()` does today, end to end

`ClientSessionImpl.close` (`acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientSessionImpl.kt:109-120`)
does four things, with **no guard of any kind**. There is no closed flag, no check of its `holder` (`:37`), and no
check of the registry:

1. `pendingPermissions.close()` (`:114`). This is the object's own signal, so it is harmless on a stale object.
2. `onClosing()` (`:115`), which is `sessions.closeDescendantPermissions(sessionId)` (`Client.kt:83`). It looks the
   root up **by id** with `rootHolderOrNull` (`ClientSessionRegistry.kt:102-103`, `:113-117`) and then:
   - calls `markClosing()` on whatever holder the id maps to now (`ClientSessionHolder.kt:410-416`). An `ANSWERED`
     newer holder becomes `CLOSING`; only an unanswered one is left alone (`:360-364`);
   - closes the permissions of every descendant of that holder. Their pending and later permission requests are
     answered `cancelled`, permanently. This happens before anything is sent.
3. `session/close` goes out for `sessionId` (`ClientSessionImpl.kt:117`), whatever happened locally.
4. On success, `onClosed()` (`:118`) runs `removeClosedSession(sessionId)` (`Client.kt:84`, `:91-97`), which runs
   `removeSessionTree(sessionId)`. That function also looks the root up by id (`ClientSessionRegistry.kt:146-147`),
   walks that holder's subtree, removes it from the map (the `===` checks at `:178` compare against that same
   subtree), marks the subagents closed, and drops the elicitation entries. On an error response nothing is removed
   (`ClientSession.kt:78`).

How a stale object comes to exist:

- **S1, a second close after a successful close.** `removeSessionTree` took the holder out of the map. The design
  says "To load it again, close it first" (design doc `:1101-1103`, release note `:1946`), so re-opening the id on
  the same connection is a designed workflow. If the app then calls `close()` on the old object:
  - **Id open again (holder H2):** H2 is marked closing and its subagents' permissions are cancelled. The wire close
    makes the agent close H2's session, which conforms to req. 3. After success, H2's whole tree is removed. The new
    object O2 still exists, but:
    - its `pendingPermissions` were not answered;
    - later root requests for the id fail with "session not found";
    - nothing tells the app.
  - **H2 claimed but not answered yet (a load in flight):** the descendants' permissions are cancelled, and the
    close races the load on the agent. After success, H2's tree is removed while its opening is still running.
  - **Id not open again:** the agent receives a close for an unknown id. A conforming agent answers with an empty
    result (req. 4), and the client finds no holder and does nothing. The Kotlin agent answers `-32602` instead
    (`agent/Agent.kt:249-250`, `:463-467`; `AcpExpectedError` maps to `INVALID_PARAMS` at
    `protocol/Exceptions.kt:77`), so the second `close()` throws.
- **S2, close after a failed opening.** The object exists before its holder completes:
  - `createSession` builds it and then delivers the history and the marker under `executeWithSession`
    (`Client.kt:628-631`, `ClientSessionHolder.kt:470-479`, `:534-553`). App code in `notify` can read it through
    `CoroutineContext.clientSession` (`ClientSessionImpl.kt:252-253`, `BaseClientSessionRuntime.kt:66-71`).
  - If the opening then fails, the holder fails (`Client.kt:632-636`). A failed root holder doesn't block its id
    (`ClientSessionHolder.kt:396`). It is reaped at the end of the last opening (`ClientSessionRegistry.kt:456-464`)
    or replaced (`:499-505`), so the id can be opened again.
  - A close on that object behaves like S1. Note that in S2 the agent answered the opening successfully, so on the
    agent's side the session is active (req. 6).
- **S3, retry after a failed close.** The holder stays registered at `CLOSING` (`markClosing` KDoc, `:405-409`). By
  id and by identity find the same holder, so this case is not stale.

So: **yes, the request is sent for the id regardless.** Today's by-id cleanup is the client-side mirror of what the
agent does with that request.

### 2. Protocol

- `session/close` is defined per session id on a connection (reqs. 2, 3, 7). There is no instance or generation
  token: the request has `sessionId` and `_meta` only (`schema/v1/schema.json:5465-5473`).
- Re-opening an id after closing it on the same connection is allowed:
  - load and resume take an existing id (`session-setup.mdx:110-114`, `:207`, `:432-433`);
  - an id is active from load or resume until it is closed (`error.mdx:53`), so it can be active again.
  - v1 is silent only on re-opening an id that is **still** active (see `reopen-open-session.md`). That case doesn't
    arise here, because the Kotlin client refuses it locally (`ClientSessionRegistry.kt:364`).
- Closing an unknown or already-closed id: the agent SHOULD answer an empty result, and the client SHOULD treat
  `-32002` as success (reqs. 4, 5). Closing is "idempotent" (`session-setup.mdx:326`).
- Only an *active* session carries a client MUST to send `session/close` (`:297`). An already-closed session carries
  no obligation to send anything.
- `sessionCapabilities.close` and `session/close` are in the **stable** v1 schema
  (`schema/v1/schema.json:4649-4650`, `:5465`).

### 3. Which client behaviours are consistent

| Option | Spec | Repo design | Rust SDK |
|---|---|---|---|
| (a) by-id cleanup, always send | Consistent with the wire: the agent closes the current holder of the id, and so does the client. Surprising for the app: closing O1 tears down O2 behind O2's back, and O2's own permissions are not answered | Matches the snippet in the design (`:1372-1376`). The design gives no reason to prefer by-id; the reason it gives against passing the holder (`:1384-1385`, "removal still needs the registry, so it would be the holder plus a callback") no longer applies, because `ClientSessionImpl` already takes `holder` (`ClientSessionImpl.kt:37`, `Client.kt:85`) | v2 `ActiveSession` is a by-id command handle `(session_id, connection)` (`src/agent-client-protocol/src/session/v2.rs:814-827`). Its `close()` always sends, without a local guard, and maps `-32002` to success (`:984-1018`; test `tests/session_v2.rs:1214-1230` closes the same handle three times). Old clones "are not invalidated" (`:996-998`). It keeps no client-side session registry tied to close, so nothing local is removed by id |
| (b) identity cleanup + local guard before sending | Consistent: a stale object's session is no longer active, so no MUST requires sending. Returning an empty result locally equals a conforming agent's answer for an already-closed id (req. 4) | Consistent with "one opening per holder" (design `:1101-1103`) and with the agent-side decision R3 ("`removeRoot` by identity", `.agents/state.md:265-268`). It lets `markClosing` be strict | v1 `ActiveSession` has no close helper; the app sends `session/close` by id itself (`session.rs:1068-1073`). Its local routing is owned by the handle and removed by the handle's own registration uuid on drop (`session.rs:1091-1100`, `jsonrpc.rs:4503-4512`). So Rust v1 does local cleanup by identity and leaves the wire to the app |
| (c) identity cleanup, always send | **Inconsistent**: the agent closes the newer session, but the client keeps its holder live. The newer root would then receive "session not found" for later prompts while the client still routes to it | Contradicts the close contract in `ClientSession.kt:67-70` (the client drops the tree after the agent's success) for the session the agent actually closed | Not applicable |

The research files add nothing that decides this.
- `client-register-child-race.md` is about `registerChild` against removal. It relies on the `===` identity CAS at
  removal, which (b) keeps.
- `reopen-open-session.md` is about re-opening a still-open id, which the client forbids.

### Why the guard makes identity and by-id coincide

Guard: send only if `sessions[sessionId] === holder && holder.blocksId`.
- While a root holder is registered and not failed, `getOrCreateSessionHolderOrNull` returns it
  (`ClientSessionRegistry.kt:64`, `:73-77`), and `claimForOpening` refuses it, because it is claimed or completed
  (`ClientSessionHolder.kt:426-431`). So no opening of the id can start on this client while the close is in flight.
- A failed close leaves the holder registered and `CLOSING`, and a successful one removes it only after the
  response. So the holder found by id at `onClosed` is still `holder`.
- The `ClientSession` object is built only after the opening's answer, and the response hook ran `markOpened`
  (`Client.kt:628`, `:747`). Every close that passes the guard therefore finds its holder at `ANSWERED` or
  `CLOSING`, and `markClosing` can `check(...)` instead of tolerating `null` (`ClientSessionHolder.kt:415`).

A completed, non-failed root holder leaves the map only through `removeSessionTree`:
- reaping takes only incomplete or failed holders (`ClientSessionRegistry.kt:456-459`);
- `replacing` takes only holders that don't block (`:73`, `:499-505`).

So "completed, not failed, not in the map" means "closed". An explicit `CLOSED` stage would make this readable.

## Recommendation and public behaviour

Recommend **(b)**:
- `onClosing`/`onClosed` capture `sessionHolder` (`closeDescendantPermissions(holder)`, `removeSessionTree(holder)`),
  with the root no longer looked up by id;
- `close()` checks its holder before `onClosing` and before sending;
- `markClosing` becomes strict.

Public behaviour changes to document in the `ClientSession.close` KDoc (`ClientSession.kt:52-93`):

1. **Second close after a successful close:** returns an empty `CloseSessionResponse` without sending anything and
   without touching any other session, as the spec's idempotent close would. Today it sends `session/close`. That
   closes a re-opened session of the same id, and against the Kotlin agent it throws if the id is not open.
2. **Retry after a failed close:** unchanged. It sends again for its own holder, which is still registered and
   `CLOSING`.
3. **Close of an object whose opening failed** (reachable only through `coroutineContext.clientSession` inside
   `notify` during the opening). A choice for the user:
   - **b1:** throw `IllegalStateException` ("session … failed to open") and send nothing. The agent's session, which
     the agent did answer, stays active until the connection ends. This keeps the guard race-free.
   - **b2:** send only if no other holder now owns the id. That frees the agent's session, but the check and the
     send are not atomic against a concurrent opening of the same id on another coroutine, so a load that slips in
     between could be closed.

   Recommend b1. The case is narrow, the object was never returned to the caller, and b2 brings back the race that
   (b) removes.
4. **Two concurrent `close()` calls on the current object:** both pass the guard and both send. The agent answers
   the second as idempotent, and identity cleanup makes the second removal a no-op. That is safe. Deduplicating the
   in-flight close is optional.

A test would observe the following:
- With an agent that counts `session/close`: `close()` on O1, a load of the same id (O2), then `close()` on O1
  again. The agent sees exactly one close, O2 stays usable (prompt works, updates reach O2, child permissions are
  not cancelled), and the second `close()` returns an empty response.
- Control: `close()` on O2 sends and removes O2's tree.
- Today the same test sees two closes, and O2's tree is removed.

v2 counterpart: `v2/Client.kt:474`, `:632-639` also removes by id (`removeSession(sessionId)`). v2 allows re-resuming
an open id and routes to the latest object, so its trade-off differs. Treat it separately.

## Discrepancies

- **Kotlin agent vs spec:** a close of an unknown id answers `-32602` (`agent/Agent.kt:249-250`, `:463-467`,
  `protocol/Exceptions.kt:77`). The spec says the agent SHOULD answer an empty result (`session-setup.mdx:326-328`,
  `error.mdx:55`).
- **Kotlin client vs spec:** any error response, `-32002` included, fails `close()` and removes nothing
  (`ClientSessionImpl.kt:117-118`, `ClientSession.kt:78`). The spec says the client SHOULD treat `-32002` as success
  (`session-setup.mdx:328-329`). The Rust v2 client does (`session/v2.rs:1007-1018`). This is related to the known
  follow-up "a failed `session/close` leaves the id taken".
- **KDoc vs spec:** `ClientSession.close` KDoc says "This capability is not part of the spec yet"
  (`ClientSession.kt:53-55`). `session/close` and `sessionCapabilities.close` are in the stable v1 schema
  (`schema/v1/schema.json:4649-4650`, `:5465`) and docs (`session-setup.mdx:270-329`).
- **Design doc vs code:** the doc rejected "passing the session its holder" (`:1384-1385`), but the code now passes
  it (`Client.kt:85`) for `subagents`. The doc's close snippet (`:1372-1376`) shows the by-id callbacks.
- **Edge case of today's by-id path:** if a closed root's id were later reused for a child (forbidden by the RFD,
  req. 9), a stale close would send `session/close` for a child id, which violates the client MUST NOT (req. 8).
  Option (b) never sends in that case.

## Open questions

- Should the client treat a `-32002` close response as success and run the cleanup (spec SHOULD)? That would also
  settle part of "a failed `session/close` leaves the id taken". It is a separate slice.
- Should the Kotlin agent answer a close of an unknown id with an empty result? This is agent side, a separate slice.
- Should a client-side failure after a successful opening answer (S2) close the agent's session itself? Today
  nothing does.
- Should the stale "not part of the spec yet" text be removed from the close KDoc?
- Should the v2 client close by identity? It needs a separate question.
