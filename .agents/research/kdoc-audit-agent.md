# KDoc audit: agent and protocol (PR `master...42afb77`)

**Sources checked:** this repository @ `42afb77` (branch `eugenethedev/subagents`), `git diff master...HEAD` for
`acp/src/commonMain/.../agent/**` and `.../protocol/**`. No upstream checkout was needed: this is a consistency audit
of KDoc against the code.
**Confidence:** high for the stale and inaccurate findings, which were each checked against the code lines cited.
The long-form verdicts are editorial judgement.

All line numbers are at `42afb77`. Paths are relative to `acp/src/commonMain/kotlin/com/agentclientprotocol/`.

## Summary

| File | Long-form | Stale / inaccurate | Rule violations | Missing |
|---|---|---|---|---|
| `agent/AgentSubagents.kt` | 5 (keep 2, trim 1, restructure 2) | 4 | 1 | 1 |
| `agent/AgentSession.kt` | 1 (keep, fix example) | 2 | 0 | 1 |
| `agent/AgentSupport.kt` | 1 (keep, wording) | 0 | 0 | 1 (optional) |
| `agent/Agent.kt` | 0 | 1 | 0 | 1 (optional) |
| `agent/internal/SubagentSessionWrapper.kt` | 0 (class KDoc 15 lines, keep) | 2 | 1 | 1 |
| `agent/internal/RootSessionWrapper.kt` | 1 (FIXME, trim) | 1 | 1 | 1 (optional) |
| `agent/internal/RemoteClientSessionOperations.kt` | 0 | 2 | 1 | 0 |
| `agent/internal/BaseSessionWrapper.kt` | 0 | 0 | 0 | 1 |
| `agent/v2/Agent.kt` (PR hunk) | 1 (FIXME, trim) | 1 | 0 | 0 |
| `protocol/RpcMethodsOperations.kt` | 1 (`RequestOutcome`, trim one clause) | 1 | 2 | 0 |
| `protocol/RpcMethodsOperations.extensions.kt` | 0 | 0 | 0 | 1 |
| `protocol/Protocol.kt` | 0 | 1 | 1 | 1 |
| `protocol/Protocol.extensions.kt` | 0 | 0 | 0 | 0 |

I found no references to `.agents/` or `docs/superpowers`, and no single-line KDoc added by the PR. Every `[link]` I
checked resolves. The one stale symbol reference is S10.

---

## 1. Long-form assessments

### L1. `AgentSubagents` class KDoc, `agent/AgentSubagents.kt:127-173` (47 lines): restructure

The content is correct and the reader needs almost all of it. Two structural problems:

- The second paragraph of `### Removal` (lines 146-150) chains four separate facts: the id is freed and cancel is
  ignored; the operations fail; the connection-end case; the in-flight-request caveat. That is the "enumeration in
  chained clauses" the rules ask to turn into a list.
- `### Replay` takes four paragraphs. Paragraphs 2 and 3 are one instruction ("how to replay"). The "events are
  live, collected after the load response" point is repeated almost word for word in
  `AgentSubagents.announce` (202-204) and in `AgentSubagentSession.events` (67-68).

One wording issue against the code: lines 154-156 say announcements "made from its [AgentSession.replayHistory], or
from a factory called from there" are replays. The code treats every announcement made while the window is open as
replayed, whoever makes it: `SubagentSessionWrapper.isReplayed = root.isReplaying`
(`agent/internal/SubagentSessionWrapper.kt:109`).

Proposed replacement (about 50 lines, +3 here, but -5 in `announce`, see L2):

```kotlin
/**
 * **UNSTABLE**
 *
 * The subagent protocol is stable in v1, but this Kotlin API is unstable and may be removed or changed at any point.
 *
 * The subagents of one session, a root session or a subagent, for announcing its children to the client. An instance
 * is bound to its session and stays valid for that session's lifetime, not only inside the call that obtained it.
 * There is no update method: patches to a child are ordinary events of the child.
 *
 * ### Removal
 *
 * Every subagent belongs to a root session. The SDK removes every subagent below a root, at every depth, and calls no
 * app code for them:
 * - on `session/close`, after the root's [AgentSession.close] returns and before the response. Stopping their work
 *   and freeing their resources belongs to that `close`. If it throws, the root and its subagents stay registered;
 * - when `session/load` or `session/resume` re-opens a root that is still open, once [AgentSupport.loadSession] or
 *   [AgentSupport.resumeSession] returns, before the replay and the response. The old [AgentSession] isn't closed
 *   and its turn isn't cancelled, so stopping the old subagents' work is up to the app.
 *
 * After the removal:
 * - the subagent's id is free again, and `session/cancel` for it is ignored;
 * - its event collection stops, and its client operations and [announce] fail, as each of them documents;
 *   [announce] of the closed root fails too;
 * - none of its updates follows the close response, but a request it already had in flight, or the
 *   `$/cancel_request` the SDK sends when the removal cancels that call, may still reach the client after it.
 *
 * When the connection ends, the collections stop the same way.
 *
 * ### Replay
 *
 * During the root's `session/load`, until the transport has accepted its response, every subagent announced below
 * the root replays a recorded one, and every update sent for them is history, as the subagents spec defines it: a
 * cancel grant is never in effect, and a state doesn't count as active. If the load fails, including when its
 * response can't be built or sent, every subagent announced during it is removed with the root. Subagents announced
 * at any other time are live.
 *
 * The SDK doesn't record history and doesn't tell a factory that it replays. To replay, [AgentSession.replayHistory]
 * sends each recorded update in its recorded order through the client of the session that sent it live: the root's
 * own client, or the child's [AgentSubagentContext.client], which routes as it did live. A recorded announcement
 * becomes a call to [announce] whose factory sends it and returns. The factory's context is the only way to reach
 * the child afterwards, so the app keeps it, for example in the returned session. So no update needs special
 * handling, a patch can't precede its announcement, and only the interleaving of different streams may differ from
 * the live one.
 *
 * The returned session's [AgentSubagentSession.events] are live: their collection starts once the transport has
 * accepted the load response, before [AgentSession.postInitialize], and their updates are fresh patches to the
 * replayed child. A child that only has history returns empty events. See [AgentSession.replayHistory] for an
 * example.
 */
```

What changes:
- **Restructured:** the removal effects (old 146-150) become a bullet list. The "connection ends" sentence gets its
  own line.
- **Merged:** Replay paragraphs 2 and 3 become one paragraph. The old paragraph 4 sentence "Only the interleaving..."
  joins the "So no update needs special handling" sentence.
- **Dropped:** the parenthetical "(a child's own `subagent_update` goes on its parent's stream, everything else on
  the child's)" (old 163-164). It duplicates the routing list in the `AgentSubagentContext` KDoc (26-29), which
  `[AgentSubagentContext.client]` links to.
- **Moved in:** from `announce` (202-204): "before its [AgentSession.postInitialize]" and "their updates are fresh
  patches to the replayed child".
- **Reworded:** "announcements made from its replayHistory, or from a factory called from there" becomes "every
  subagent announced below the root", to match `SubagentSessionWrapper.kt:109`.

### L2. `AgentSubagents.announce`, `agent/AgentSubagents.kt:176-211` (36 lines): restructure

The failure matrix is what the reader needs. It has one real defect:

- Line 195 says that after the announcement, a throwing factory's exception is rethrown "unchanged".
- Lines 199-200 then say: "In both cases, a [CancellationException] from the app's code while the caller is active
  surfaces as [IllegalStateException]".
- "Both cases" can't mean the two bullets above it. In the second bullet the caller is cancelled, so the
  `CancellationException` passes as is (`SubagentSessionWrapper.kt:163,165,170`). It also isn't needed for the
  "before" list: the first bullet there already says the call throws `IllegalStateException`
  (`SubagentSessionWrapper.kt:166`). So the sentence really only qualifies "rethrows unchanged" at line 195, and it
  contradicts that line as written.

Lines 202-204 (replay timing) are the third copy of that fact (see L1).

Proposed replacement (35 lines):

```kotlin
    /**
     * Registers [sessionId] under [parent], calls [factory] with the child's context, then collects the returned
     * session's events. Returns the session once [factory] has returned, after the child's announcement. Returning is
     * the caller's ordering point: an update the parent sends afterwards, such as a `session_message` naming the
     * child, follows the announcement.
     *
     * [parent] defaults to the session this object is bound to. It may name an announced descendant of that session
     * that is still registered, for relays that learn about nested children from a flat event stream.
     *
     * The announcement is a [SessionUpdate.SubagentUpdate] whose session id is [sessionId]. [factory] must send it
     * through [AgentSubagentContext.client] before it returns, and it must be the first thing sent for the child.
     * While the root replays, the events are collected later, see [AgentSubagents].
     *
     * ### Failure
     *
     * Before the announcement is sent, the child is unregistered, so its id is free again, and nothing more is sent:
     * - if [factory] throws, or returns without having sent the announcement, this call throws
     *   [IllegalStateException];
     * - if the caller is cancelled, this call rethrows the [CancellationException].
     *
     * After it is sent, the child stays registered and its events are never collected:
     * - if [factory] throws, the SDK revokes the child's cancel grant and, if the factory left the work state active,
     *   reports [StateUpdate.Unknown], except while the root is replaying. This call rethrows the exception unchanged,
     *   except that a [CancellationException] while the caller is active surfaces as [IllegalStateException], so it
     *   doesn't tear down the caller's scope;
     * - if the caller is cancelled, the SDK does the same, and this call rethrows the [CancellationException].
     *
     * @throws IllegalArgumentException if [parent] is neither the bound session nor a registered, announced
     * descendant of it, or if [sessionId] is already registered on the connection, as a root session or a subagent
     * @throws IllegalStateException if the bound session's root is no longer registered (closed, replaced by a
     * re-opening, or its load failed), if the bound session is a subagent that was never announced or was removed
     * with its root, if the child's announcement could not be sent, or when [factory] fails as described above
     */
```

What changes:
- **Fixed:** the "In both cases" paragraph is folded into the one bullet it qualifies.
- **Restructured:** the failure cases move under `### Failure`, because the KDoc has several distinct topics.
- **Moved out:** the replay paragraph (202-204) moves to the class KDoc (L1). A one-line pointer stays here.
- **Reworded:** "It is handled as a [factory] that throws the [CancellationException], so the events are never
  collected" becomes "the SDK does the same", and "never collected" moves to the list's lead-in, since it holds for
  both bullets.
- **Fixed in `@throws`:** see S3 and S4.

### L3. `AgentSubagentContext` class KDoc, `agent/AgentSubagents.kt:16-38` (23 lines): keep

The reader needs everything here, and it isn't duplicated elsewhere. Only S1 applies.

### L4. `AgentSubagentSession.events`, `agent/AgentSubagents.kt:59-79` (21 lines): keep

The state-reporting paragraph (70-75) is the main contract for app authors. The rest is short and not duplicated
beyond the one sentence L1 centralizes. Only S2 applies.

### L5. `AgentSubagentFactory.createSubagent`, `agent/AgentSubagents.kt:106-123` (18 lines): trim lightly

The third paragraph (117-120) restates `AgentSubagentSession.cancel` (86-92) and links to it twice. Replace 117-120
with:

```kotlin
     * A cancel grant sent from here takes effect at once unless the root replays: a `session/cancel` for the child that
     * arrives before this returns is held for the returned session, see [AgentSubagentSession.cancel].
```

**Dropped:** "A grant sent during the parent's replay is history and never in effect, see [AgentSubagents] and
[AgentSubagentSession.cancel]". It is covered by "unless the root replays", by `AgentSubagentSession.cancel` (91-92)
and by `AgentSubagents` `### Replay`. 18 lines become 16.

### L6. `AgentSession.replayHistory`, `agent/AgentSession.kt:24-68` (45 lines, including a 19-line example): keep, fix the example

The `### Subagents` prose is a two-sentence summary plus a link, which is the right weight. The example has a bug
(S5). Fix it as shown there.

### L7. `AgentSupport.forkSession`, `agent/AgentSupport.kt:208-224` (17 lines): keep, wording only

Two optional wording changes in the new paragraph (215-219):
- "message participant fields" becomes "the `senderSessionId` and `recipientSessionId` of messages". These are the
  model field names (`acp-model/.../SessionUpdate.kt:336-337`).
- "including references to the forked root session" becomes "including references to the source session, which
  become the fork's id". As written it reads as "references to the fork".

### L8. FIXME in `RootSessionWrapper.prompt`, `agent/internal/RootSessionWrapper.kt:136-168` (33 lines): trim

This is maintainer-facing, and it is accurate:
- `cancel()` cancels `promptJob` (224).
- The handler's own cancellation propagates to it.
- Subagent feeds use the `isActive` rule (`SubagentSessionWrapper.kt:272`).
- `runTurn` exists in v2 (`agent/v2/Agent.kt:156`).

But a third of it is history and a test plan, which belong in an issue or the follow-up PR. If the user wants the
FIXME to stay the full hand-off spec, keep it as is: nothing in it is wrong. Otherwise, proposed replacement
(13 lines):

```kotlin
                    /*
                     * FIXME: a CancellationException that the app's own flow throws, such as an uncaught
                     *  `withTimeout`, is treated as a client cancel: rethrowing it cancels `promptJob`, so the turn
                     *  answers `stopReason: cancelled`, which v1 defines as a cancel "by the client via
                     *  `session/cancel`", and the real failure is lost.
                     *
                     *  Fix: if `currentCoroutineContext().isActive` is still true here, the CE came from the app,
                     *  because `cancel()`, protocol close and `$/cancel_request` all cancel `promptJob`; the subagent
                     *  feed in `SubagentSessionWrapper.startCollection` uses the same rule. Record it as `failure`,
                     *  wrapped in a non-cancellation exception, because `Protocol` maps a CancellationException from a
                     *  handler to `-32800` (`toJsonRpcError`). The code after `join()` then answers `cancelled` after
                     *  a client cancel and an error otherwise. The v2 counterpart is the FIXME in `runTurn` in
                     *  `agent/v2/Agent.kt`.
                     */
```

**Dropped:**
- the `#131` history paragraph (145-147);
- the `schema/v1/schema.json` path and the second schema quote ("even if the cancellation causes exceptions...");
- "This changes stable v1 behaviour";
- the explicit three-case test list (163-165);
- the `-32603` detail.

Move the test list to the follow-up issue if it is wanted.

### L9. FIXME in v2 `runTurn`, `agent/v2/Agent.kt:230-263` (34 lines): trim, and fix S10

This is the same case as L8. It also sits as a second block comment right after the branch's own comment (223-229).
Proposed replacement (16 lines, in the file's no-leading-`*` style):

```kotlin
                    /*
                    FIXME: this branch also catches a CancellationException the app's own flow throws, such as an
                     uncaught `withTimeout`, so the client gets `idle(cancelled)` for a turn nobody cancelled. Before
                     insertion the same CE reaches `insertedMessageId.await()`, and `Protocol` answers `-32800`.

                     Fix: classify a CE by whether the turn's job is still active and whether a `session/cancel`
                     arrived during this turn.
                     - Job cancelled (`close()`, `cancelPendingIncomingRequest(s)`, `turn.cancel()`): as now.
                     - Job active after a client cancel: still `idle(cancelled)`, since aborted calls often throw.
                     - Job active without a cancel: like the `else` branch, an error log and `idle(stopReason = null)`.
                     The job state alone isn't enough, because `cancel()` only calls `session.cancel()`: the turn needs
                     a cancel-requested flag like v1's `PromptSession.isCancelRequested` in
                     `agent/internal/RootSessionWrapper.kt`. Before insertion, answer `-32800` only after a cancel, and
                     keep the SDK's own "Prompt cancelled before user message insertion" CE out of the app case.
                     The v1 counterpart is the FIXME in `RootSessionWrapper.prompt`.
                     */
```

**Dropped:**
- the `#131` history sentence (238-239);
- "fail the receipt with a non-cancellation exception so it becomes `-32603`" (condensed);
- "it needs its own exception type or an explicit branch" (condensed);
- the test sentence (261).

### L10. `RequestOutcome` class KDoc, `protocol/RpcMethodsOperations.kt:140-161` (22 lines): trim one clause

The rest is the contract and should stay. In `@property onResponseQueued` (151-152), drop ", as does a message
another thread sends in the few instructions between the hand-off and the hook". It describes a race the reader
can't act on, and it is trivially true of anything sent after the hand-off. Replacement for 149-154:

```kotlin
 * @property onResponseQueued optional hook called synchronously in the coroutine that queues the frame, right after
 * the transport accepted the frame carrying the success response: before [afterResponse] starts and before any
 * other work waiting for that frame resumes, so a message sent from it follows the response on the wire. It is not
 * called for an error reply, or when the frame never reaches the transport; [onCompletion] is where to detect that.
 * It must not suspend, block, or throw; a hook that throws is logged
```

Also see R6 (tag order).

---

## 2. Stale or inaccurate

**S1. `agent/AgentSubagents.kt:31-33`, `AgentSubagentContext`.**
- The KDoc says operations fail until the announcement and fail again "once the child is removed".
- It misses the dropped child. If the factory throws or returns before announcing, the context "stays valid"
  (23-24), but every operation fails for good: `remove`/`dropUnlessAnnounced` set `DROPPED`
  (`SubagentSessionWrapper.kt:214-227`), and `checkRegistered` throws (`:138`).
- Fix: "Once the child is dropped, because its factory failed or returned before announcing it, or removed (see
  [AgentSubagents]), every operation fails that way again."

**S2. `agent/AgentSubagents.kt:67-68`, `AgentSubagentSession.events`.**
- The KDoc says a replayed child's collection starts "after the parent's `session/load` response".
- For a grandchild the parent is a subagent, which has no load. The window is the root's: `root.startAfterReplay`
  (`SubagentSessionWrapper.kt:179`), closed by `endReplay` from the root's `onResponseQueued` (`Agent.kt:463-464`).
- Fix: "after the root's `session/load` response has been accepted by the transport".

**S3. `agent/AgentSubagents.kt:195` vs `:199-200`, `AgentSubagents.announce`.** "rethrows the exception unchanged" is
contradicted by the following "In both cases" paragraph. Evidence and fix are in L2.

**S4. `agent/AgentSubagents.kt:208-210`, `announce` `@throws IllegalStateException`.**
- "a subagent that has not been announced yet" also needs to cover a bound child that was dropped (factory failed):
  `resolveParent` calls `checkAnnounced` (`AgentSubagents.kt:228`), which throws for `DROPPED` through
  `checkRegistered` (`SubagentSessionWrapper.kt:130-138`). Write "was never announced".
- "the bound session's root is closed" is also the case after a re-opening replaced the root or its load failed.
  `registerSubagent` checks only identity in the table (`Agent.kt:509`).
- The fix is in L2.

**S5. `agent/AgentSession.kt:59`, `replayHistory` example, a bug.**
- The record's `parent` is "the id of its parent" (45-46). For a top-level child that is the root's id, and
  `children.getValue(it)` then throws `NoSuchElementException`, because the root isn't in `children`.
- `announce` already takes the parent id and accepts the bound session's own id
  (`AgentSubagents.kt:229`). Replace 57-64 with:

```kotlin
 *         } else {
 *             // A child's first recorded update is its announcement.
 *             val factory = AgentSubagentFactory { context ->
 *                 context.client.notify(update)
 *                 ReplayedSubagent(context)
 *             }
 *             children[sender] = root.subagents!!.announce(sender, factory, parent).context
 *         }
```

**S6. `agent/AgentSession.kt:34`, `replayHistory`, low priority.**
- "If it throws, `session/load` fails with that error and the session is not registered" leaves out a fact about
  a session that was still open under that id.
- That session was already replaced (`Agent.kt:431-438`) and isn't restored (`Agent.kt:427`, `445`), so the id then
  has no session at all. This behaviour isn't new in the PR, but the PR now also removes the old subagents there.
- Optional addition: "A session that was open under the same id is not restored."

**S7. `agent/Agent.kt:538-542`, `cleanUpSubtree`.**
- The KDoc says "stopping their work belongs to the root's [AgentSession.close]". That is false on the replacement
  path (`Agent.kt:439`), where `close` is never called and the public KDoc says stopping the work is up to the app
  (`AgentSubagents.kt:143-144`).
- "have left the table with it or with its replacement" is hard to parse.
- Fix: "Subtree cleanup for [root], once [removed], its subagents at every depth, have left the table, together
  with [root] or when a re-opening replaced it: cancels their collections and makes their operations fail. No app
  code is called for them."

**S8. `agent/internal/SubagentSessionWrapper.kt:38-41`, class KDoc, minor.**
- "Once announced, it stays registered until subtree cleanup removes it" is right, but subtree cleanup also drops a
  still-pending child (`remove()`, `:237`).
- Append: "Subtree cleanup drops a child that is still pending too."

**S9. `agent/internal/SubagentSessionWrapper.kt:152-153`, `announce`, minor.** "A replayed child's collection waits
for the load response" becomes "...waits until the transport has accepted the root's load response". `endReplay`
runs from `onResponseQueued` (`Agent.kt:463-464`).

**S10. `agent/v2/Agent.kt:252`, stale path.** "v1's `PromptSession.isCancelRequested` in `agent/Agent.kt`":
`PromptSession` now lives in `agent/internal/RootSessionWrapper.kt` (`:232-244`). Line 262 of the same comment
already uses the new path. The fix is included in L9.

**S11. `agent/internal/RemoteClientSessionOperations.kt:49`, `notify` `@throws`.** Only `Opened` is listed. The
method also throws `IllegalArgumentException` for a `SubagentUpdate` (`:54-58`) and `IllegalStateException` for an
update that needs the missing `subagents` capability (`:60-62`). The fix is in R3.

**S12. `agent/internal/RemoteClientSessionOperations.kt:192-195`, `SubagentClientOperations`.** "gated until the
announcement" is incomplete: `checkCanSend` (`:203`) is `checkAnnounced`, which also fails after the child is dropped
or removed. Fix: "requests for the child's session, allowed only while the child is announced and registered".

**S13. `agent/internal/RootSessionWrapper.kt:54-55`, `subtreeScope`, minor.**
- "Created on first use, so a session without subagents doesn't attach a job to the connection" is not quite true.
  `cancelSubtreeScope()` (`:104-106`) is called on every close, failed load and replacement, and it creates the
  scope just to cancel it.
- Nothing lingers, so the intent holds. Fix: "so a session that never has subagents keeps no job attached to the
  connection".

**S14. `protocol/RpcMethodsOperations.kt:26-31`, `setRequestOutcomeHandlerRaw` (public).**
- "with its response hook" refers to the internal `onResponse` overload of `sendRequestRaw`. A public reader can't
  see or use it.
- "and so before any message that arrives after a later notification is handled" is hard to parse.
- The behaviour itself matches the code (FIFO single-thread `handlerDispatcher`, `Protocol.kt:70,486-489,535,563`).
- Replacement:

```kotlin
     * Ordering: request handlers start in arrival order, on the same single thread as the notification handlers, see
     * [setNotificationHandlerRaw]. A request handler runs until it first suspends, or returns, before the handler of
     * any later request or notification starts, and so before any message that follows such a notification. The read
     * loop doesn't wait for the handler otherwise: a response that arrives right after the request may be handled
     * before the request's handler starts. As for notifications, this holds only if [additionalContext] contains no
     * dispatcher.
```

**S15. `protocol/Protocol.kt:748-749`, `ResponseSlot.successQueuedHook`, minor.** "Returns [onSuccessQueued] if
[response] is a success, once the frame carrying it was accepted by the transport" reads as if the function waits.
It also has an unstated precondition: `getCompleted()` throws if `response` isn't complete. Fix: "Called once the
transport has accepted the frame, when every slot's [response] is complete: returns [onSuccessQueued] if
[response] is a success, `null` otherwise."

---

## 3. Rule violations

**R1. `agent/AgentSubagents.kt:102`, `AgentSubagentFactory` (vague wording).** "Follows `ClientOperationsFactory` on
the client side." becomes "Mirrors the client-side [com.agentclientprotocol.client.ClientOperationsFactory]." This
also turns the backticked name into a link.

**R2. `agent/internal/SubagentSessionWrapper.kt:80-85` (one KDoc for two properties).** The KDoc above
`factoryRunning` describes `factoryState` too, but it attaches only to `factoryRunning`. Split it:

```kotlin
    /**
     * Whether the factory is running. Guarded by [lock].
     */
    private var factoryRunning = false

    /**
     * The last `state` the child's operations sent while the factory ran, used only when the factory throws after the
     * announcement. Guarded by [lock].
     */
    private var factoryState: StateUpdate? = null
```

**R3. `agent/internal/RemoteClientSessionOperations.kt:38-50`, `notify` (wall of text, incomplete `@throws`).**
Replacement:

```kotlin
    /**
     * Sends the update of [event] for this root session.
     *
     * Every root path sends through here (`client.notify`, the prompt flow and replay), so the subagents rules for a
     * root are checked once, here:
     * - a [SessionUpdate.SubagentUpdate] is always rejected, because the SDK must see every announcement and grant;
     * - without the `subagents` client capability, `session_message`, `session_message_chunk` and `session` tool call
     *   content are rejected. An agent MUST NOT send the messages without it (`docs/protocol/v1/subagents.mdx`,
     *   "Checking support"; `docs/protocol/v1/initialization.mdx`, "Subagents"), and sends `session` content only to
     *   a client that advertised it (`docs/protocol/v1/tool-calls.mdx`, "Sessions").
     *
     * @throws IllegalArgumentException for a [SessionUpdate.SubagentUpdate]
     * @throws IllegalStateException for an update that needs the missing `subagents` capability, or for
     * [SessionEvent.Opened], which only the client runtime delivers
     */
```

Optional: these citations are upstream ACP repo paths with no repo named. `Agent.kt:256` cites the public docs URL
instead. Pick one style.

**R4. `agent/internal/RootSessionWrapper.kt:212` and `:217-223`, `cancel()` (code the PR moved into a new file).**
- Line 212 restates the call below it.
- Lines 217-223 are seven consecutive `//` lines, including an ASCII sketch.
- Replacement:

```kotlin
            agentSession.cancel()

            if (promptToCancel != null) {
                logger.trace { "Cancelling prompt" }
                /*
                 * Cancelling the turn's job cancels every outgoing request it has in flight, such as a pending
                 * `fs/read_text_file` or `session/request_permission`, and each of them sends `$/cancel_request`.
                 */
                promptToCancel.promptJob.cancel()
            }
```

The `$/cancel_request` claim matches `Protocol.kt:224-240`. The intent of line 212 ("so implementations can
gracefully stop ongoing requests") is already in the `AgentSession.cancel` KDoc. This is pre-existing text, so it is
optional for this pass.

**R5. `protocol/RpcMethodsOperations.kt:20`, `setRequestOutcomeHandlerRaw` (empty first line).** The KDoc starts
with an empty ` *` line. The PR edits this KDoc, so remove the line.

**R6. `protocol/RpcMethodsOperations.kt:148-160`, `RequestOutcome` (tag order).** The `@property` tags are in the
order `response, onResponseQueued, afterResponse, onCompletion`, while the constructor order is `response,
afterResponse, onCompletion, onResponseQueued` (163-167). Reorder the tags to match the constructor, or leave them
as they are on purpose, because they follow the run order the prose describes (143-146). Either way, decide
explicitly.

**R7. `protocol/Protocol.kt:44-47`, class KDoc (wording).** "Each handler runs until it first suspends before the
handler of a later message starts" lacks "or returns" and a comma. Write "Each handler runs until it first suspends,
or returns, before the handler of a later message starts".

---

## 4. Missing

**M1. `agent/AgentSubagents.kt:82-94`, `AgentSubagentSession.cancel`.** The KDoc doesn't say what happens when the
method throws. `callCancel` logs and swallows any exception except the caller's own cancellation
(`SubagentSessionWrapper.kt:344-352`). Add: "An exception it throws is logged and ignored: `session/cancel` is a
notification."

**M2. `agent/AgentSession.kt:104-113`, `close`.** The PR changes what happens around `close`, and the KDoc doesn't
say so:
- the SDK removes every subagent of the session right after `close` returns, and calls no app code for them;
- if `close` throws, the session and its subagents stay registered.

So `close` is where the app must stop subagent work (`Agent.kt:263-272`). Add: "Stop the work of the session's
subagents here too: once this returns, the SDK removes them without calling app code. If it throws, the session and
its subagents stay registered. See [AgentSubagents]."

**M3 (optional). `agent/AgentSupport.kt:197-206` and `230-240`, `loadSession` and `resumeSession`.** The PR adds a
new behaviour: returning a session whose id is still open replaces it and removes its subagents, without closing the
old session (`Agent.kt:423-439`). Only `AgentSubagents` `### Removal` documents it. One line in each KDoc would let
the reader find it: "If a session with this id is still open, the returned session replaces it, see
[AgentSubagents]."

**M4 (optional). `agent/Agent.kt:517`, `unregisterSubagent`.** The identity check (`!== child`, `:519`) is the
non-obvious part. Add a one-line KDoc: "Removes [child] if it is still the subagent registered under its id."

**M5. `agent/internal/SubagentSessionWrapper.kt:130`, `checkAnnounced`.** The name hides that it also throws for a
dropped or removed child (`:132`, `:135-141`), and two callers rely on that (`AgentSubagents.kt:228`,
`RemoteClientSessionOperations.kt:203`). Add: "Throws [IllegalStateException] unless the announcement has been sent
and the child is still registered."

**M6 (optional). `agent/internal/RootSessionWrapper.kt:31-36`, class KDoc.** "A root session." is thin for the class
that now owns the one-turn rule, the replay window and the subtree scope. Possible text: "A root session: runs its
turns one at a time (see [AgentSession.prompt]) and owns the replay window and the subtree scope of its subagents."

**M7. `agent/internal/BaseSessionWrapper.kt:26-31`, `CoroutineContext.sessionWrapper`.** Both public accessors that
use it (`client`, `subagents`) document an `IllegalStateException`, but this function doesn't. Add
`@throws IllegalStateException outside a session context or in a NES session context`.

**M8. `protocol/RpcMethodsOperations.extensions.kt:86-112`, the typed `setRequestHandler` and
`setRequestOutcomeHandler`.**
- The PR gives the typed `setNotificationHandler` an ordering note (`:138-140`), but not the typed request
  overloads.
- `setRequestOutcomeHandler` has no KDoc at all. It is unstable, but it is now the way to return
  `onResponseQueued`.
- Add the same one-liner the raw overload got: "The handler starts in arrival order as described on
  [RpcMethodsOperations.setRequestOutcomeHandlerRaw]." Add a one-sentence purpose line for
  `setRequestOutcomeHandler`.

**M9. `protocol/Protocol.kt:272`, the `finally` block of `sendRequestRaw`.** The removal now matches by `response`
instead of by entry identity. That is needed because the cancel path replaces the entry with a `copy(onResponse =
null)` (`:231-234`), but nothing says so. Add a one-line block comment: "Match by the deferred: the cancel path above
may have replaced the entry with a copy that drops the hook."

---

## Out of scope, noted only

- `protocol/Protocol.kt:733-734`: the `ResponseSlot` class KDoc has `@property response`, but `response` is a body
  property. This is pre-existing and not touched by the PR.
- `protocol/Protocol.kt:487-488` and `:595-596`: consecutive `//` lines. Pre-existing.
- `RequestOutcome` became `@UnstableApi` and stopped being a `data class` in this PR (`8b8257d`). That is a public
  API change for API review, not a KDoc issue. Other `@UnstableApi` declarations in `protocol` also carry no
  `**UNSTABLE**` header, so its KDoc is consistent with the package.
