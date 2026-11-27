# Review: KDoc pass (uncommitted working tree against `HEAD` 6c8f7e2)

Scope: `git diff HEAD`, 48 product files plus `.agents/state.md`, which belongs to the orchestrator and was not
reviewed.

## Mechanical checks (all pass)

- Only comment lines changed. Every added or removed line that isn't blank starts with `*`, `/*` or `//`. The one
  changed code sample is inside the `AgentSession.replayHistory` KDoc. Its new call
  `root.subagents!!.announce(sender, factory, parent)` matches the signature `announce(sessionId, factory, parent)`
  (`AgentSubagents.kt:206-210`).
- No v2 file is in the diff.
- No added line is over 120 columns. No added line references `.agents/`. No single-line KDoc was added. No
  consecutive `//` lines were added: the two new `//` comments are single lines (`Client.kt:76`, `Client.kt:643`).
- `@property`/`@param` placement is fixed where it was wrong: the body properties of `DelegatedTask` and
  `NativeRelayedSubagentSession` moved out of the class tags. Tag text is a lowercase continuation without a trailing
  period. `RequestOutcome` tags now follow the constructor order.
- Every new `[link]` I checked resolves: `modesSupported`, `ClientOperationsFactory`, `clientSubagentSession`,
  `SubagentSessionHolder.markOpened`, `SubagentSessionHolder.deliverLive`, `RpcMethodsOperations.setRequestOutcomeHandlerRaw`,
  `JsonRpcException` and `AgentSubagentContext.client`.
- I ran no Gradle build, as the brief asked.

## Accuracy: claims checked against the code and found correct

- **Model.**
  - `StateUpdate` decoding: a known state always decodes as that state (`SubagentState.kt:169-184`).
  - `stopReason`: a non-string value decodes as `null` (`:216`). The old "known-but-invalid" wording was wrong, and the
    new text is right.
  - `ToolCallContent.Session._meta` is lenient (`LenientMetaSerializer`, `ToolCall.kt:118`).
  - `SubagentUpdate.title` checks the element itself (`lenientMaybeString`, `SubagentSessionUpdates.kt:28`).
  - "`{}` permits nothing, like `null`" matches the spec and `canCancel`.
- **Agent.**
  - `announce` failure cases match `SubagentSessionWrapper.announce` (`:168-188`):
    - before the announcement, an app `CancellationException` while the caller is active becomes
      `IllegalStateException`, and the caller's own cancellation is rethrown;
    - after it, the same rule holds and `reportFactoryFailure` runs.
  - `@throws IllegalStateException` for "root no longer registered" matches `registerSubagent`'s identity check
    (`Agent.kt:509`).
  - `AgentSubagentSession.cancel` exceptions are logged (`callCancel`, `SubagentSessionWrapper.kt:352-360`).
  - "every subagent announced below the root" during the window is replayed: `isReplayed = root.isReplaying` at
    creation (`:114`). The new text is more accurate than the old "from replayHistory or a factory called from there".
  - `AgentSession.close` throw/keep behaviour matches (`Agent.kt:263-273`).
  - A pending child dropped by subtree cleanup matches `remove()`.
  - The `subtreeScope` "keeps no job attached" claim holds: `cancelSubtreeScope` forces creation and cancels at once.
- **Client.**
  - `close` `@throws JsonRpcException` matches `ClientSessionImpl.kt:140-146`.
  - `getSession` throws match `getRootSession` (`ClientSessionRegistry.kt:212-216`).
  - `withInitializingSession` reaping matches `:476-477`.
  - "notify throws ... or while it is" matches: `notify(Opened)` throwing is caught and fails the holder
    (`SubagentSessionHolder.kt:184-227`).
  - The `GlobalSessionUpdateHandler` additions, the `registerChild` `@return`, the `QueuedItem.Opened` producers, the
    `deliverDuringOpening` live-failure sites and the `ResponseSlot`/`pendingOutgoingRequests` comments all match the
    code.
- **Samples.** `FinishedSubagentSession` exists, `continueReviewer` runs only when `outcome != null`, the probe drops
  after the rerun tool call, and `showOpened` is overridden in both subclasses.
- **Long-form restructures.** I compared the old and new text of `AgentSubagents` (class and `announce`),
  `ClientSubagentSession`, `SubagentUpdate`, `ClientSession.close`, `ClientOperationsFactory` and `SessionEvent.Opened`.
  Each fact that moved can still be found:
  - the replay recipe is in `replayHistory ### Subagents`;
  - the routing rule is in `AgentSubagentContext`;
  - the nested-level caveat is in `BaseClientSession.subagents`;
  - the `Opened` delivery cases are in `SessionEvent.Opened`.
  I found no contradiction between `AgentSubagents`, `replayHistory` and `AgentSubagentSession.events`. All three say
  the collection starts once the transport accepted the load response, before `postInitialize`.

## Findings

### 1. should-fix: `acp/src/commonMain/kotlin/com/agentclientprotocol/client/Client.kt:643`, the reason for wrapping the failure is gone

- **Problem.** The new comment "The holder fails with a wrapped cause; the caller gets the original failure." only
  repeats the code. The old comment said why the cause is wrapped in `IllegalStateException`, and that reason still
  holds. Audit S7, which this edit follows, says "no other side sees it", and that is wrong.
- **Evidence.**
  - An agent request already waiting for this root in `awaitSession` gets the holder's failure from
    `holder.session.await()` (`ClientSessionRegistry.kt:248`).
  - The handler then throws it, and `toJsonRpcError` maps a `CancellationException` to `-32800` and anything else to
    `-32603` (`protocol/Exceptions.kt:82-83`).
  - So when the caller is cancelled (the `throwable` is a `CancellationException`), the wrap is what makes the agent's
    waiting `fs/*`, `terminal/*` or permission request get an internal error instead of a "cancelled" error.
  - A later "simplification" that passes `throwable` straight through would change that without any comment against
    it.
- **Fix.** Replace the comment with something like
  `// Wrapped, so an agent request waiting for this session fails with -32603, not -32800 when the caller was
  cancelled; the caller gets the original failure.`
  Confidence: confirmed by reading.

### 2. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientSubagentSession.kt:128-129,140-142`, "parent's opening call" is wrong for a nested subagent

- **Problem.**
  - `state` says a held state takes effect "if the parent's opening call replayed no history".
  - `canCancel` says "A load or resume of the parent" and "if the opening replayed no history".
  - For a grandchild, the parent is a subagent, which has no opening call and is never loaded or resumed.
- **Evidence.** `promote` comes from the root's opening: `root.phase.opening?.replayRequested`
  (`ClientSessionHolder.kt:317`, `SubagentSessionHolder.kt:79`, `RootSessionHolder.kt:180`).
- **Fix.** Say "if the root's opening call replayed no history" and "A load or resume of the root". "Before the parent's
  answer" can stay as "before the root's answer".

### 3. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/AgentSubagents.kt:92`, "while the parent replays" should say root

- **Problem.** `AgentSubagentSession.cancel` says "A grant sent while the parent replays". The same pass changed the
  factory KDoc to "unless the root replays" (`:121`), and the class KDoc defines the window as the root's
  `session/load`. A grandchild's parent never replays.
- **Fix.** Write "while the root replays".

### 4. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/AgentSubagents.kt:203`, "was never announced" reads wrong for a child whose factory is still running

- **Problem.** The `@throws IllegalStateException` case "the bound session is a subagent that was never announced"
  covers two states:
  - a child whose factory is still running, which may announce later. `checkAnnounced` says "is not announced yet: send
    its SubagentUpdate first" (`SubagentSessionWrapper.kt:142`);
  - a dropped child.
  The old text said "has not been announced yet".
- **Fix.** Write "a subagent that isn't announced yet, was dropped, or was removed with its root".

### 5. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/RpcMethodsOperations.kt:28` and `:154-158`, two qualifiers were dropped

- **Problem.**
  - `setRequestOutcomeHandlerRaw` dropped "with its response hook". `AGENTS.md` keeps "hook included" for this rule,
    because the client's permission race depends on it.
  - `RequestOutcome.onResponseQueued` dropped "as does a message another thread sends in the few instructions between
    the hand-off and the hook". That caveat told readers the hook's message doesn't necessarily come right after the
    response.
- **Fix.** Restore "may be handled, with its response hook, before ...". Restore the caveat as a short clause, for
  example "…follows the response on the wire, though not necessarily right after it".

### 6. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientSession.kt:121-122`, "open the id again to close it" assumes load or resume

- **Problem.** The old text said "The agent's session then stays open until the connection ends". The new text
  replaces that with advice that works only if the agent supports `session/load` or `session/resume`.
- **Fix.** "The client then doesn't close the agent's session. It stays open until the connection ends, unless the id
  is loaded or resumed again and then closed."

### 7. nit: `acp/src/commonMain/kotlin/com/agentclientprotocol/common/SessionEvent.kt:33-34`, "every subagent that came before it" is vague

- **Problem.** It doesn't say "announced", it leaves out failed setups, and it drops the old pointer that nested lists
  complete only at their own `Opened`. `BaseClientSession.subagents` has all of this, and the text says "see there",
  so nothing is lost. The sentence alone just reads as a stronger guarantee than the real one.
- **Fix.** "At this point the session's [...subagents] lists every subagent announced before it whose setup succeeded;
  the subagents' own lists complete at their own [Opened], see there."

## Follow-ups (outside the changed text)

- `client/ClientOperationsFactory.kt:45-52`: the `createClientOperations` KDoc is unchanged, but it has lines over
  120 columns, a `[sessionId]` pseudo-tag in prose, and no `@param`. It is in a file this pass edited.
