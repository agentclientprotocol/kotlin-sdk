# KDoc audit: client runtime and common session API (PR `master...eugenethedev/subagents`)

**Sources checked:** this repository @ `42afb77` (branch `eugenethedev/subagents`), `git diff master...HEAD` for
`acp/src/commonMain/kotlin/com/agentclientprotocol/{client,common}`. No other `acp` main-source file outside
`agent/` and `protocol/` is touched by the PR. No upstream sources were needed. One claim, in
`BaseClientSessionRuntime.kt:22-28`, was checked by compiling a snippet with Kotlin 2.3.10: an interface can't have
`internal` members ("modifier 'internal' is not applicable inside 'interface'"), so that comment is correct.
**Confidence:** high for the stale and rule findings, which cite the code lines. The long-form verdicts are judgment
calls, and each lists what it drops.

All paths below are relative to `acp/src/commonMain/kotlin/com/agentclientprotocol/`.

## Summary

| File | Long-form | Stale/inaccurate | Rule violations | Missing |
|------|-----------|------------------|-----------------|---------|
| `client/BaseClientSession.kt` | 1 (light fix) | 1 | 1 | 0 |
| `client/ClientSubagentSession.kt` | 1 (restructure) | 3 | 5 | 0 |
| `client/ClientSession.kt` | 1 (light fix) | 2 | 1 | 2 |
| `client/Client.kt` | 1 (restructure a paragraph) | 2 | 3 | 1 |
| `client/ClientOperationsFactory.kt` | 2 (bullets; TODO) | 1 | 0 | 0 |
| `client/SubagentOperations.kt` | 1 (keep) | 0 | 0 | 0 |
| `client/GlobalSessionUpdateHandler.kt` (not in diff) | 0 | 0 | 0 | 1 |
| `common/SessionEvent.kt` | 1 (trim) | 0 | 0 | 0 |
| `common/ClientSessionOperations.kt` | 1 (trim) | 1 | 0 | 1 |
| `client/internal/ClientSessionRegistry.kt` | 3 (1 move, 2 keep) | 1 | 4 | 1 |
| `client/internal/ClientSessionHolder.kt` | 2 (keep with fixes) | 2 | 2 | 0 |
| `client/internal/RootSessionHolder.kt` | 2 (keep) | 1 | 2 | 0 |
| `client/internal/SubagentSessionHolder.kt` | 1 (keep) | 0 | 1 | 0 |
| `client/internal/PendingPermissions.kt` | 2 (1 trim, 1 keep) | 0 | 0 | 0 |
| `client/internal/BaseClientSessionRuntime.kt` | 0 | 1 | 0 | 0 |
| `client/internal/ClientSessionImpl.kt` | 0 | 0 | 1 (optional) | 1 |
| `client/v2/ClientSession.kt`, `client/v2/Client.kt` | 1 (keep) | 1 (code/KDoc gap) | 0 | 0 |

No references to `.agents/` or `docs/superpowers` were found. No references to the renamed symbols
(`closeChildren`, `getSessionOrThrow`, `markClosing`, `SubagentList`, `PendingPermissionCancellation`, `startTurn`,
`removeSessionHolder`, `failSessionHolder`) remain in scope. Every `[link]` I checked resolves.

---

## Long-form assessments

### L1. `client/BaseClientSession.kt:42-71` `BaseClientSession.subagents`: light fix (30 to 31 lines)

An app developer needs every paragraph: what the list holds, that it only grows, snapshot semantics, and what is
complete at `Opened`. Don't cut it. Two problems:
- line 48 is 145 characters;
- the second paragraph is inaccurate (see S3): the list also freezes when the session's holder fails, and it also
  misses announcements that arrive after the session was closed or failed.

Replace lines 46-50 with:

```
     * The list only grows, until this session is closed or its stream fails, and then it stays as it was. A subagent
     * whose operations couldn't be created is never in it. A listed subagent may be closed, because its own stream or
     * that of a session above it failed, or its root session was closed, see [ClientSubagentSession].
     *
     * A `subagent_update` whose subagent is not in the list is one that the client didn't accept: the setup of the
     * subagent failed, the update arrived after this session was closed or failed, or the agent announced an id that
     * another session owns.
```

Optional: rename `### After a load` to `### At this session's Opened`. The rule also holds for announcements that a
resume or `session/new` buffers before the answer. Nothing is dropped.

### L2. `client/ClientSubagentSession.kt:18-68` `ClientSubagentSession` class KDoc: restructure (51 to 58 lines)

Problems:
- Paragraphs 2 and 4 (lines 30-47) are walls of text. Paragraph 4 mixes four topics: what's missing (no prompt or
  close), the three close causes, what a closed subagent looks like, and the routing of `config_option_update`.
- `**Contract:**` (line 54) is a bold pseudo-heading.
- Lines 45 and 50 are over 120 characters.
- The "During a turn" paragraph repeats `ClientSession.prompt`'s KDoc (`ClientSession.kt:35-37`).
- Two inaccuracies (S4): `idle(cancelled)` also answers a waiting permission request, and a `notify` that throws
  *for* the `Opened` also closes the subagent (`SubagentSessionHolder.kt:169-186`, all inside the `try` at 167).

Everything here is something the app can act on except the flow rationale, which stays as one sentence because it
explains why there is no `StateFlow`.

Proposed replacement for lines 18-64 (the `@property` and `@param` tags stay as they are):

```
/**
 * **UNSTABLE**
 *
 * Part of the SDK's Kotlin API for client-side subagents. The subagent protocol is stable in v1, but this Kotlin API
 * is unstable. It may change or be removed at any point.
 *
 * A session that the agent started under another session, to do work for it. The client builds it when the agent
 * announces the subagent, from the operations that [SubagentOperations.createSubagentOperations] returned, and lists
 * it in the parent's [BaseClientSession.subagents]. The agent announces subagents only if the application declares
 * the `subagents` client capability, [com.agentclientprotocol.model.ClientCapabilities.subagents], in the
 * [ClientInfo] it passes to [Client.initialize]. The client doesn't declare it on its own.
 *
 * The client never prompts a subagent, so there is no prompt, mode, model, configuration or close here. Every update
 * of the subagent, `config_option_update` and `current_mode_update` included, reaches its [operations] through
 * [ClientSessionOperations.notify], and none of them changes a root session's mode or options.
 *
 * ### Stream
 *
 * Inside the parent's [ClientSessionOperations.notify] for the announcement, the subagent is already in the parent's
 * [BaseClientSession.subagents], with its operations and the announced values. The subagent's own stream then gets:
 * - its history;
 * - exactly one [com.agentclientprotocol.common.SessionEvent.Opened] without a response, at the place of the root's
 *   answer. Nothing orders it against the parent's `Opened`, and by then the subagent's own
 *   [BaseClientSession.subagents] lists what its history announced;
 * - its live updates.
 *
 * The live updates, and the agent's requests for the subagent, wait until the opening call of the root session has
 * completed. A permission request that waits there is still answered `cancelled`, without reaching [operations], if
 * the subagent's work is cancelled meanwhile, by [cancel] or by an `idle` state with `cancelled`, or if its root is
 * closed.
 *
 * ### Closing
 *
 * A subagent is closed:
 * - with its root session, see [ClientSession.close], whether or not it has finished opening;
 * - when its own [ClientSessionOperations.notify] throws before its `Opened` is delivered, or while it is;
 * - when the stream of a session above it fails, as when the root's opening fails.
 *
 * Its later updates then go to the [GlobalSessionUpdateHandler], and the agent's requests for it fail. A closed
 * subagent stays in its parent's [BaseClientSession.subagents] with the values it had: [canCancel] is `false`,
 * [cancel] throws, and later `subagent_update`s don't change it.
 *
 * ### Reading the properties
 *
 * The getters return the values the client has received from the agent so far. Every change reaches the application
 * as an event of a stream it already receives: a `subagent_update` in the parent's [ClientSessionOperations.notify],
 * or the parent's `Opened`. Inside a `notify` of the parent's operations the getters match the parent's stream: they
 * reflect every event delivered so far, including the current one.
 *
 * The properties are plain getters, not flows, because a flow beside the stream could show a value before the event
 * that caused it. An application that wants a flow keeps its own and updates it from `notify`. Each value is read
 * atomically, so any thread may read them, but values read one after the other may come from different moments.
 *
 * During a turn, a root's updates come from the buffered [ClientSession.prompt] flow, so the getters may be ahead of
 * it. To show what an update changed, read the update, which carries the patch. A subagent that an update from the
 * flow names is already in the parent's [BaseClientSession.subagents], unless its setup failed.
 *
```

What changes:
- **Dropped:** "a second channel with its own timing" and "a collector on another thread" (folded into "could show a
  value before the event that caused it"); "read the getters for the subagent's current values" (implied); "During a
  turn, a root session's updates don't go to `notify`" (already in `ClientSession.prompt`'s KDoc).
- **Moved:** the `config_option_update` and `current_mode_update` sentence moves from the end of paragraph 4 into the
  intro, next to "no prompt, mode, …".
- **Added:** `idle(cancelled)` and the "while it is" close case (S4).

### L3. `client/ClientSession.kt:56-123` `ClientSession.close`: keep the structure, fix three passages (68 to 70 lines)

The sections fit what an app developer asks: what happens to permissions, what the tree looks like afterwards,
errors, the deadlock warning, and repeated calls. The "close from a subagent's own operations" section is long, but
it warns about a real deadlock the app can cause, so keep it. Fix these passages:

1. Lines 84-86 are ambiguous. "`createSubagentOperations` call in progress for such a subagent" reads as the call
   that creates that subagent. A direct child of the root is set up in the root's consumer, which isn't joined
   (`ClientSessionRegistry.kt:159-172` joins only subagent openings). Replace with:
   ```
     * This function cancels the openings of the subagents that haven't finished opening and waits for them to end. So it
     * returns only once each [ClientSessionOperations.notify] call of such a subagent, and each
     * [SubagentOperations.createSubagentOperations] call that such a subagent's operations run for its own subagents,
     * reacts to the cancellation.
   ```
2. Lines 90-92 (`### Errors`) don't say that the other errors are thrown. Replace the last sentence with "Any other
   error is thrown, and nothing is removed." (`ClientSessionImpl.kt:131-139`).
3. Lines 96-97: "Such a call may close the root itself." is confusing, because this *is* the root's close. Replace
   with:
   ```
     * A subagent's operations may call this function, for example from their `notify`. It then doesn't wait for that
     * subagent's opening, which would wait for itself, and cancels it before returning. It recognizes the call when
     * this function runs:
   ```
4. Line 108: "Only this session object sends `session/close` for its id" is misleading, because another object of a
   later session of the id also sends. Replace with "This object sends `session/close` only while the session it
   was opened as is still open:".
5. Lines 115-116: "The agent's session then stays open until the connection ends." overclaims. The id can be opened
   again and then closed (`ClientSessionRegistry.kt:63-64`; the reaping removes failed roots,
   `ClientSessionRegistry.kt:469-471`). Replace with "The client then doesn't close the agent's session: open the
   id again to close it."

Nothing is dropped.

### L4. `client/ClientOperationsFactory.kt:42-63` `ClientOperationsFactory` KDoc: bullets for the failure list (22 to 27 lines)

This is the canonical home of the opening-failure contract. `newSession`, `loadSession`, `forkSession` and
`resumeSession` all point here ("as [ClientOperationsFactory] describes"), so keep the content here. But lines 51-57
chain a list of failure cases in one paragraph, which the "Structuring long comments" rule forbids. Replace lines
51-57 with:

```
 * ### Failure
 *
 * The opening call fails when:
 * - the agent answers with an error;
 * - the factory throws;
 * - the caller is cancelled, or `notify` throws, before the call returns.
 *
 * A call that fails before the answer arrives doesn't call the factory, so nothing gets
 * [com.agentclientprotocol.common.SessionEvent.Opened]. Once the answer has arrived, `Opened` may have been delivered
 * even though the call then fails, so treat a failed call as a failed opening. The session's updates that the
 * operations did not receive yet, and its later ones, then go to the client's [GlobalSessionUpdateHandler], and the
 * session id can be opened again. After the call has returned, a failure of `notify` is logged, as for any live update.
```

Nothing is dropped. L7 and L8 below then link here or to `SessionEvent.Opened` instead of repeating it.

### L5. `client/ClientOperationsFactory.kt:10-40` TODO block (31 lines): fix two stale facts, preferably move it out

It is a design plan for maintainers. Two facts are stale (S6):
- line 31 says "the KDoc above", but the KDoc is below, at lines 42-63;
- line 22 says "The only v1 sample ignores both arguments (`TerminalClientSupport.kt`)", but the subagents sample
  now opens a root with the factory and uses the id:
  `samples/kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/subagents/client/SubagentsClient.kt:75`
  (`client.newSession(parameters) { id, _ -> RootClientSessionOperations(id, view, user) }`).

Minimum fix: "the KDoc above" becomes "the KDoc below". Replace line 22 with "The v1 samples use at most the id
(`TerminalClientSupport.kt` ignores both arguments, `SubagentsClient.kt` reads the id), and v2 has no factory: …".

Recommended: move the plan to a GitHub issue and leave a 3-line `TODO` with the issue link. It is the only 30-line
plan in the main sources in scope. This is the user's call.

### L6. `client/SubagentOperations.kt:7-38` and `:41-66`: keep (32 and 26 lines)

The sections (`Implementing`, `Capability`, `Failure`, `Context`, `Ordering`) are all actionable for an implementer.
`Context`'s second paragraph tells the implementer that the context has no session during an early load, which they
need to know. `### Capability` repeats `ClientSubagentSession`'s intro sentence, but each type is read on its own in
the IDE, so keep both.

### L7. `common/SessionEvent.kt:21-41` `SessionEvent.Opened`: trim (21 to 19 lines)

`### Delivery` is the event's own contract, so keep it. Lines 33-35 repeat `BaseClientSession.subagents` `### After
a load` (`BaseClientSession.kt:55-68`) and omit its "except those whose setup failed". Replace lines 33-35 with:

```
     * At this point the session's [com.agentclientprotocol.client.BaseClientSession.subagents] lists every subagent
     * that came before it, see there.
```

**Dropped:** "The subagents' own lists are complete only at their own [Opened], which nothing orders against this
one." It stays in `BaseClientSession.subagents` (bullets at lines 62-63) and in `ClientSubagentSession` (L2).

### L8. `common/ClientSessionOperations.kt:36-52` `ClientSessionOperations.notify`: trim and add the subagent case (17 to 17 lines)

Problems:
- Paragraph 2 repeats `SessionEvent.Opened` `### Delivery` and `ClientOperationsFactory`'s failure text.
- It describes only roots ("the opening call"), although subagent operations get `notify` too and have no opening
  call.
- `[notify]` at line 51 resolves to this member, not to the extension it means.

Replacement:

```
    /**
     * Handles an event of the session's stream that is not bound to any prompt.
     *
     * On the client, this receives the session's updates as [SessionEvent.Update], and once the [SessionEvent.Opened]
     * marker, which separates the updates that came before the answer of the opening call, such as the history that
     * `session/load` replays, from the live ones. [SessionEvent.Opened] says when it is and isn't delivered.
     *
     * Until the opening call returns, the call delivers these events itself: cancelling the caller cancels `notify`,
     * and a cancellation of the caller or a failure of `notify` fails the opening. After the call has returned, a
     * failure of `notify` is logged, as for any live update. A subagent session has no opening call, see
     * [com.agentclientprotocol.client.ClientSubagentSession].
     *
     * On the agent, this sends a [SessionEvent.Update] to the client, and throws [IllegalStateException] for
     * [SessionEvent.Opened], which only the client runtime delivers. Agent code usually calls the `notify` extension
     * that takes a [SessionUpdate].
     */
```

**Dropped:** "If the call fails before the agent's answer arrives, the marker never arrives. Once the answer has
arrived, the marker may have been delivered even though the call then fails, so treat a failed opening call as a
failed opening." It is in `SessionEvent.Opened` (`SessionEvent.kt:27-31`) and `ClientOperationsFactory` (L4).

### L9. `client/Client.kt:408-433` `Client.loadSession(…, operations)`: restructure the failure paragraph (26 to 30 lines)

Lines 419-424 chain the failure cases, the same rule violation as L4. Replace with:

```
     * The load fails when:
     * - the agent answers with an error;
     * - the caller is cancelled, or `notify` throws, before this call returns.
     *
     * A load that fails before the answer arrives delivers no opened marker to [operations]. Once the answer has
     * arrived, the opened marker may have been delivered even though the load then fails, so treat a failed call as a
     * failed load. The updates that [operations] did not receive yet, and all later updates for [sessionId], then go to
     * [globalSessionUpdateHandler], and [sessionId] can be loaded again. After this call has returned, a failure of
     * `notify` is logged, as for any live update.
```

Nothing is dropped.

### L10. Internal long comments in `client/internal/ClientSessionRegistry.kt`

- `removeSessionTree` (`:120-148`, 29 lines): **keep**, but lines 144-145 ("A second removal of [root] …") sit under
  `### Cancellation` and have nothing to do with cancellation. Move them above `### Steps`, after line 121, as their
  own paragraph. Nothing is dropped.
- `registerChild` (`:309-334`, 26 lines): **keep**. The design note at lines 329-331 ("Entering the map first would
  need an undo …") is the reason for the step order, and a maintainer needs it.
- `awaitSession` (`:218-234`, 17 lines): **keep**.

### L11. Internal long comments in `client/internal/ClientSessionHolder.kt`

- Class KDoc (`:96-140`, 45 lines): **keep** with the S5 fix and the `@property openingScope` fix (R-list). In the
  `### Subagents` bullet at lines 130-131, the verb "walk" comes after a long subject list, which makes it hard to
  read. Suggested: "the holders by id, failed ones included, which [RootSessionHolder.markOpened], the failure cascade
  of [ClientSessionRegistry.failSessionTree] and the root's close walk;".
- `setUpSubagent` (`:357-381`, 25 lines): **keep**.

### L12. Internal long comments in `client/internal/RootSessionHolder.kt`, `SubagentSessionHolder.kt`, `PendingPermissions.kt`

- `RootSessionHolder.markOpened` (`:152-169`) and `completeOpening` (`:244-260`): **keep**.
- `SubagentSessionHolder.completeOpening` (`:151-165`): **keep**. Turn the inline label `Failures:` (line 159) into
  `### Failure` followed by a blank KDoc line (R-list).
- `SubagentSessionHolder.kt:199-208` block comment: **keep**.
- `SubagentPendingPermissions` class (`PendingPermissions.kt:95-130`, 36 lines): **trim** to 33 lines by deleting
  lines 127-129 ("As in [PendingPermissions], the race in [answerUnlessCancelled] is the only answering path, …").
  **Dropped:** that paragraph, which repeats the base class KDoc (`PendingPermissions.kt:19-20`), where it stays.
  Optional: the sentence "The spec requires …" (lines 100-101) states a protocol requirement without a citation.
  Add a spec link if one is at hand.
- `SubagentPendingPermissions.onOpened` (`:184-205`) and `onState` (`:146-161`): **keep**.

### L13. `client/v2/ClientSession.kt:127-152` v2 `ClientSession.close` (26 lines): keep, but see S10

The structure is fine. Lines 143-151 promise something the code doesn't do in one case (S10), so fix the code or add
a caveat before polishing the wording.

---

## Stale or inaccurate

**S1. `client/ClientSubagentSession.kt:113-118` `state`.** It says "`null` … before the first state that arrives
after the parent's answer". The code applies a state held back before the answer at the parent's `Opened` when the
opening replayed no history (`onParentOpened(promote = true)`, `:199-203`; `promote = replayRequested != true`,
`ClientSessionHolder.kt:316`). So after `session/new`, `session/fork` or `session/resume`, a state that arrived
before the answer is shown. Replacement:

```
    /**
     * The work state the agent last confirmed.
     *
     * `null` means no confirmed current state: before the first state takes effect, and after the agent reported the
     * state as unconfirmed with `null`, which does not mean idle. [StateUpdate.Unknown] is different: the agent
     * confirmed that it lost track of the subagent's state.
     *
     * A state that arrives before the parent's answer takes effect at the parent's
     * [com.agentclientprotocol.common.SessionEvent.Opened] if the parent's opening call replayed no history, and is
     * dropped after a load: a replayed state is never shown as current.
     */
```

**S2. `client/ClientSubagentSession.kt:124-127` `canCancel`.** Two problems:
- "The grant resets when the parent is loaded or resumed again": a load or resume builds new holders and new
  objects (`SubagentSessionHolder.kt:72-73`), so this object doesn't reset. It is closed, and the new one starts
  without a grant.
- "a grant that was reported while the history was replayed never takes effect": this sentence is correct, but it
  doesn't say that a grant held back before a resume's answer *does* take effect.

Replace with: "A load or resume of the parent builds new subagent objects, which start without a grant. A grant that
arrives before the parent's answer takes effect at the parent's `Opened` if the opening replayed no history, and never
after a load."

**S3. `client/BaseClientSession.kt:46-51` `subagents`.** "stays as it was after this session is closed" leaves out
the failure case: the failure cascade seals the holder too (`ClientSessionRegistry.kt:428`). The list of reasons a
`subagent_update`'s subagent is not listed leaves out the announcement that arrives after the parent was sealed
(`ClientSessionHolder.kt:418-421`, `ClientSessionRegistry.kt:298`). The fix is in L1.

**S4. `client/ClientSubagentSession.kt:35-43` class KDoc.**
- A waiting permission request is also answered `cancelled` by a live `idle(cancelled)`
  (`PendingPermissions.kt:174`), not only by `cancel` or the root's close.
- "when its own `notify` throws before its `Opened`": a throw from `notify(SessionEvent.Opened(null))` itself also
  fails the holder (`SubagentSessionHolder.kt:184`, caught at `:222-227`).

The fix is in L2.

**S5. `client/internal/ClientSessionHolder.kt:112`.** "for a root with ready operations (a load)" should be "(a load
or a resume)". `resumeSession(…, operations)` also uses `deliverEarly` (`Client.kt:564-566` → `:743`).

**S6. `client/ClientOperationsFactory.kt:22,31` TODO.** "the KDoc above" (it's below), and the claim that only
`TerminalClientSupport.kt` uses the factory (`SubagentsClient.kt:75` also does, and reads the id). See L5.

**S7. `client/Client.kt:641,643` `createSession`.** This comment predates the PR, but the PR changed the function.
"throw IllegalStateException to pass it as INTERNAL_ERROR to the other side (see in Protocol)" doesn't match the
code:
- the code rethrows the original `throwable` (line 644);
- the `IllegalStateException` is only the cause passed to `failSessionTree`;
- this is a client-side call, so no "other side" sees it.

Delete line 641, or replace it with `// The holder fails with a wrapped cause; the caller gets the original failure.`
Line 643 and line 762 say "finally of withInitializingSession", which should be
"`ClientSessionRegistry.withInitializingSession`'s `finally`". That `finally` removes the failed holder only at the
end of the last opening (`ClientSessionRegistry.kt:461-476`).

**S8. `client/Client.kt:394` `loadSession(…, operationsFactory)`.** `@param sessionParameters parameters for creating
a new session` predates the PR, in a KDoc the PR changed. Replace with "parameters for loading the session", as in
the overload at line 427.

**S9. `client/ClientSession.kt:137` `currentMode`.** "Check for [modelsSupported]" should be `[modesSupported]`. It
predates the PR, but the PR edited this KDoc.

**S10. `client/v2/ClientSession.kt:143-151` and `client/v2/Client.kt:450-451` (PR hunks): the KDoc promises more
than the code does.**
- The KDoc says that once this object "was removed … by another object's close of the same id", a later `close`
  sends nothing, "even if the id has been resumed again".
- Take an object A that a resume replaced with B. When B closes, or `deleteSession` runs, `removeSession` marks
  ended only the map's current object, B (`v2/Client.kt:647-656`). A stays un-ended, because a replacement never
  marks it (`register`, `:490`).
- So a later `A.close()` passes `if (ended.value)` (`v2/ClientSession.kt:154`) and sends `session/close`. It would
  close a session that resumed the id again.

Either fix the code, for example by marking the replaced object ended when the map's current one is removed, or add
a caveat to both KDocs. Route this to the orchestrator: it is a behaviour question, not wording.

**S11. `client/internal/RootSessionHolder.kt:283-284` `deliverDuringOpening`.** "Live deliveries log such failures
instead, see [SubagentSessionHolder.completeOpening]" is imprecise:
- a subagent's live deliveries log in `SubagentSessionHolder.deliverLive` (`:234-247`);
- a root's live deliveries let the failure propagate to the `session/update` handler, and `Protocol` logs it
  (`protocol/Protocol.kt:629`).

Suggested: "Live deliveries log such failures instead: a subagent's in [SubagentSessionHolder.deliverLive], a root's
in the protocol's notification handler."

**S12. `client/internal/ClientSessionRegistry.kt:333` `registerChild` `@return`.** "`null` if nothing was
registered" is not quite right. In the `rootEnded` case (`:349,355`) the child stays in the parent's `byId` and is
failed by the cascade. Suggested: "`@return` the child's holder, or `null` if it didn't enter the session map, so the
announcement is a plain update".

**S13. `client/internal/ClientSessionHolder.kt:87-89` `QueuedItem.Opened`.** "Only [RootSessionHolder.markOpened]
puts it into the queues, …, and the registration of a subagent after that answer" contradicts itself. Also,
`SubagentSessionHolder.markOpened` (`SubagentSessionHolder.kt:98-100`) is what puts it into a subagent's queue.
Suggested: "[RootSessionHolder.markOpened] puts it into the root's queue and, through [SubagentSessionHolder.markOpened],
into every subagent's, in the response hook of the opening call; a subagent registered after that answer gets it
when its holder is created."

**S14. `common/ClientSessionOperations.kt:50-51` `notify`.** `[notify]` links to the member itself, and the KDoc
leaves out subagent operations. The fix is in L8.

**S15. `client/internal/BaseClientSessionRuntime.kt:48-49,62-64` (low).** Two small gaps:
- `answerPermissionRequest`: "the subagent's session doesn't exist yet when the request arrives" should be "may not
  exist yet".
- `executeWithSession`: it lists `baseClientSession` and `clientSession` but not `clientSubagentSession`. Suggested:
  "so [baseClientSession] returns it, and [clientSession] or [clientSubagentSession] does by its kind".

---

## Rule violations

| # | Location | Rule | Fix |
|---|----------|------|-----|
| R1 | `client/ClientSubagentSession.kt:98` | KDoc closes with `**/` | `*/` |
| R2 | `client/ClientSubagentSession.kt:54` | `**Contract:**` bold label instead of a heading | `### Reading the properties` (L2) |
| R3 | `client/ClientSubagentSession.kt:30-47` | walls of text: close causes chained in one paragraph | bullets and sections (L2) |
| R4 | `client/ClientSubagentSession.kt:196-197` | `@param promote` has full sentences ending in a period | `@param promote `true` after a call that replayed no history, so the held values were current and are applied; `false` after a load, so they were history and are dropped` (the "A closed session drops them too" sentence moves to the KDoc body) |
| R5 | `client/ClientSubagentSession.kt:164` | vague: "applied at once: the history may stay visible" | "applied at once, so values replayed from history stay visible" |
| R6 | `client/internal/RootSessionHolder.kt:38-40` | `@property parameters` ends with a sentence and a period | `@property parameters the parameters of the call, which the effective working directories of the root's subagents come from; known from the claim on, so subagents can read them before the root's [ClientSession] exists` |
| R7 | `client/internal/ClientSessionHolder.kt:136-137` | `@property openingScope` ends with a sentence and a period | `@property openingScope the scope of the subagents' openings, which belongs to the connection, so they end with it and don't run on the protocol's handler thread` |
| R8 | `client/Client.kt:76-77` | consecutive `//` lines (moved by the PR) | one line: `// elicitation/complete only follows an accepted URL elicitation, so track only those.` |
| R9 | `client/internal/ClientSessionRegistry.kt:462-468` | the block comment the PR rewrapped still has the old broken prose ("during to ongoing"), 134- and 181-character lines, and no sentence case | `/*` / ` * No opening is in progress any more, so an unclaimed root holder, or a failed one, belongs to no session:` / ` * the agent sent updates for an id that no opening claimed. Remove those and fail them below.` / ` *` / ` * A subagent's holder is exempt: it may wait for its operations long after any opening, and it ends with its` / ` * own setup or with its parent.` / ` */` |
| R10 | `client/internal/ClientSessionRegistry.kt:263-273` | double space after "for." (line 265); paragraph 2's last sentence is hard to parse | paragraph 2: "The lookup and the sorting are not `suspend`, and must stay that way: `Protocol` runs a notification handler only until its first suspension before it handles the next message. So a subagent is registered before the agent's next message for it is handled, even while the parent's `notify` runs app code. Otherwise that message would count as unconnected, or, for a request, fail with "session not found"." |
| R11 | `client/internal/ClientSessionRegistry.kt:144-145` | paragraph under the wrong heading (`### Cancellation`) | move it (L10) |
| R12 | `client/internal/SubagentSessionHolder.kt:159` | inline label `Failures:` in a 15-line KDoc | `### Failure` plus a blank KDoc line |
| R13 | `client/ClientOperationsFactory.kt:51-57`, `client/Client.kt:419-424` | failure cases chained in prose | bullets (L4, L9) |
| R14 | `client/Client.kt:365-367, 395-397, 493-495, 531-533` (`@param operationsFactory`) and `:428-429, 558-559` (`@param operations`) | predate the PR, in KDocs the PR changed: several sentences, a trailing period, and lines over 120 characters | e.g. `@param operationsFactory creates the operations of the session after the answer, see [ClientOperationsFactory.createClientOperations]; they must also implement the interfaces of the extra capabilities the client declares, like file system or terminal support` and `@param operations the operations of the session, which must also implement the interfaces of the extra capabilities the client declares, like file system or terminal support` |
| R15 | lines over 120 characters that the PR added: `client/BaseClientSession.kt:48`, `client/ClientSubagentSession.kt:45,50,173`, `client/ClientSession.kt:154`, `client/Client.kt:720`, `client/internal/ClientSessionHolder.kt:333`, `client/internal/ClientSessionRegistry.kt:100,368,463,464` | formatting | rewrap |
| R16 (optional) | `client/internal/RootSessionHolder.kt:96,98,220,222,232,238,240` and `client/internal/ClientSessionImpl.kt` (7 more) | the same `// TODO remove together with the deprecated …` repeated on adjacent lines; `RootSessionHolder.kt:232` and `ClientSessionImpl.kt` `trackState` put a TODO between the previous member and the KDoc, then repeat it inside | keep one TODO per declaration. The per-site ones are fine if grep-ability is the goal; the user decides |

---

## Missing KDoc

**M1. `client/Client.kt:786` `Client.getSession` (public).** It has no KDoc, and the PR changed its behaviour: it now
throws for a subagent id (`ClientSessionRegistry.kt:211-216`). Suggested:

```
    /**
     * Returns the open root session [sessionId].
     *
     * @throws IllegalStateException if the id has no session, its opening hasn't finished or failed, or it is a
     *   subagent session
     */
```

**M2. `client/ClientSession.kt:12-13` `ClientSession` (public).** The interface has no KDoc. It now extends
`BaseClientSession`, and `clientSession` throws for subagents. Suggested: "A root session that the application opened
with [Client.newSession], [Client.loadSession], [Client.resumeSession] or [Client.forkSession]. For the sessions the
agent starts under it, see [subagents] and [ClientSubagentSession]."

**M3. `client/ClientSession.kt:122` `close` `@throws`.** It lists only `IllegalStateException`. Add
`@throws JsonRpcException if the agent answers with an error other than `-32002``, which matches the L3 point 2 text.

**M4. `client/GlobalSessionUpdateHandler.kt:8-15` (file not in the diff; its contract grew).** It says the handler is
for sessions "the client is not connected to", created elsewhere. It now also receives:
- the updates of sessions whose opening failed;
- everything for a closed root's tree;
- the updates of subagents whose setup failed, or that failed or were closed (`ClientSessionRegistry.kt:434-436`;
  `SubagentOperations.kt:35`; `ClientSession.kt:73`).

Several public KDocs point here for those cases. Suggested addition: "It also receives the updates of sessions the
client dropped: a session whose opening failed, the tree of a closed root, and a subagent whose setup or stream
failed."

**M5. `common/ClientSessionOperations.kt:20-29` `requestPermissions` (not in the diff; its contract grew).** For a
subagent's operations, the SDK also answers `cancelled` when the agent reports a live `idle(cancelled)`, when
`ClientSubagentSession.cancel` runs, and when the root closes (`PendingPermissions.kt:109-113`). Suggested: change
"when the client cancels or closes the session" to "when the client cancels or closes the session, and, for a
subagent, when the agent reports its work cancelled or its root is closed".

**M6. `client/internal/ClientSessionRegistry.kt:448` `withInitializingSession` (internal).** It has no KDoc, although
many KDocs refer to "the reaping at the end of the last opening" in it. Suggested: "Runs [block], an opening call,
while counting it as an initializing session, so that early updates for unknown ids get speculative root holders.
When the last such call ends, every root holder that is unclaimed, incomplete or failed is removed and failed, with
its subagents."

**M7. `client/internal/ClientSessionImpl.kt:30-42` (internal).** The new constructor parameters `isOpen`, `onClosed`
and `holder` are undocumented, and their contract isn't obvious. Suggested class KDoc:
`@param isOpen whether [holder] is still the registered holder of the id and blocks it, read once by [close]`,
`@param onClosed removes the closed root's tree after a successful close`,
`@param holder this root's holder, which keeps its subagents and its closing stage`.

---

## Open questions

1. S10 (v2 replaced-object close): fix the code or document the gap? This is a behaviour decision for the
   orchestrator or user, not a KDoc fix.
2. L5: keep the 31-line `TODO` plan in `ClientOperationsFactory.kt`, or move it to an issue? This is the user's call.
