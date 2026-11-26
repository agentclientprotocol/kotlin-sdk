# Readability suggestions

Suggestions collected during the comment-trim pass. None of them is applied; they are kept for a later discussion.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionRegistry.kt`

1. **`ClientSessionHolder.blocksId`** (used throughout the registry)
   - Current: `takeIf { it.blocksId }`, `root.blocksId`.
   - Proposed: `ownsId` (or `holdsId`).
   - Why: "blocks" needs the KDoc in `ClientSessionHolder.kt` to decode. The trimmed comments in the registry already say "still owns its id"; a matching name would make `takeIf { it.ownsId }` read on its own.

2. **`getOrCreateSessionHolderOrNull`**
   - Current: `getOrCreateSessionHolderOrNull(sessionId)`.
   - Proposed: `holderOrSpeculativeRoot(sessionId)` (or `findOrBufferHolder`).
   - Why: "get or create ... or null" doesn't say when it creates (only while an opening runs) or what it creates (a speculative root holder). That's the one fact its KDoc still has to carry.

3. **`getOrCreateSessionHolder`**
   - Current: a private one-liner that only `claimSessionHolder` calls.
   - Proposed: inline it into `claimSessionHolder`: `getOrCreateSessionHolderOrNull(sessionId) ?: acpFail(...)`.
   - Why: one indirection less, and the "must run under `withInitializingSession`" precondition then sits next to the call that relies on it.

4. **`stopOpening` result and `settlesHolder` / `settles`**
   - Current: `val settlesHolder = ...associateWith(::stopOpening)`, `for ((holder, settles) in ...)`, `if (!stopOpening(child)) continue`.
   - Proposed: rename the function to `stopOpeningMustFail` (or return a small enum such as `StopResult.MUST_FAIL` / `SETTLED_ELSEWHERE`), and the locals to `mustFail`.
   - Why: "settles" is a concept the reader has to learn from `OpeningState`'s KDoc. `mustFail` states what the caller does with the result, which is what the trimmed KDoc says now ("whether the caller must now fail the holder").

5. **`settlesHolder.entries.reversed()` in `removeSessionTree`**
   - Current: `for ((holder, settles) in settlesHolder.entries.reversed())`.
   - Proposed: a named local, e.g. `val subagentsBottomUp = settlesHolder.entries.reversed()`.
   - Why: that reversing a preorder list means "children before parents" is easy to miss, and the trimmed KDoc no longer has the "bottom-up" step list.

6. **Removed-ids expression in `removeSessionTree`**
   - Current: last expression `subtree.filter { previousSessions.sessions[it.sessionId] === it }.mapTo(mutableSetOf()) { it.sessionId }`.
   - Proposed: `val removedIds = ...; removedIds`.
   - Why: names what the block returns without a reader matching it against `@return`.

7. **`registerChild` local `blocked`**
   - Current: `blocked = !rootEnded && storage.sessions[childId]?.blocksId == true`.
   - Proposed: `idTaken` (and, with suggestion 1, `storage.sessions[childId]?.ownsId == true`).
   - Why: the rejection branch is about the id belonging to another session (the log says so); `idTaken` says it at the condition.

8. **`followSubagentState`**
   - Current: `followSubagentState(parent, child, update)`.
   - Proposed: `feedSubagentPermissions(parent, child, update)`.
   - Why: the function only feeds the `state` to the subagent's pending permissions; "follow state" doesn't say whose state is followed or by what, so it needed a new KDoc.

9. **`withInitializingSession` / `initializingSessionsCount`**
   - Current: `withInitializingSession { ... }`, `initializingSessionsCount`.
   - Proposed: `withOpeningCall { ... }`, `openingCallsCount`.
   - Why: the count is of opening calls in flight, not of sessions; the comments have to translate "initializing session" into "opening call" each time.

10. **`hangingSessions` / `reaped` in `withInitializingSession`**
    - Current: `hangingSessions`, then `val reaped = hangingSessions`.
    - Proposed: `unclaimedRoots` for both.
    - Why: "hanging" needs the block comment to explain; the filter selects root holders that no opening completed.

11. **`awaitSession`'s holder selection**
    - Current: an inline `when` over the registered holder that yields `null` for an unclaimed root.
    - Proposed: extract `private fun waitableHolderOrNull(sessionId): ClientSessionHolder?`.
    - Why: the rule "only a subagent or a claimed root is waited for" would be a name, and the rationale comment could sit on that function.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/ClientSessionHolder.kt`

1. **`opened` / `parentOpened` parameters**
   - Current: `deliverItem(item, session, opened)`, `setUpSubagent(announcement, operations, parentOpened)`, `applyPatch(update, parentOpened)`; all carry the same value.
   - Proposed: one name everywhere, e.g. `streamOpened`.
   - Why: two names for one fact make the reader check they are the same, and each needed its own `@param`.

2. **`Children.byId` / `Children.listed`**
   - Current: `byId: PersistentMap<SessionId, SubagentSessionHolder>`, `listed: PersistentList<ClientSubagentSession>`.
   - Proposed: `holders` and `sessions` (or `registered` and `setUp`).
   - Why: `byId` names the key, not what is stored; the difference that matters is holders registered at arrival versus sessions set up by the consumer, which the `@property` tags now carry.

3. **`failSessionTree` constructor property**
   - Current: `failSessionTree: suspend (ClientSessionHolder, Throwable) -> Boolean`, called only with a subagent's holder (`setUpSubagent`, `failSetup`, `SubagentSessionHolder.failOpening`).
   - Proposed: `failSubagentTree: suspend (SubagentSessionHolder, Throwable) -> Boolean`.
   - Why: the name and type would say it only fails subagents, and that `false` means the opening was already stopped, without the `@property` text.

4. **`handleOrQueue` fast-path condition**
   - Current: `if (item !is QueuedItem.Announcement && _session.isCompleted && lateDeliveries.value.isCompleted)`.
   - Proposed: `val noLateUpdateWaits = _session.isCompleted && lateDeliveries.value.isCompleted` and `if (item !is QueuedItem.Announcement && noLateUpdateWaits)`.
   - Why: the block comment above it explains the two halves separately; a named local would carry the first half.

5. **`failSetup`**
   - Current: `failSetup(child, cause)`, which fails nothing when the caller is cancelled or the opening was stopped.
   - Proposed: `failSetupUnlessStopped` (and `failOpeningUnlessStopped` for `SubagentSessionHolder.failOpening`).
   - Why: the name promises a failure that often doesn't happen; the block comment inside exists to say so.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/RootSessionHolder.kt`

1. **`RootPhase.claimedBy` / `answered` / `closing`**
   - Current: return `RootPhase?`, `null` when the transition isn't allowed.
   - Proposed: `claimedByOrNull`, `answeredOrNull`, `closingOrNull`.
   - Why: Kotlin's `OrNull` convention would show at the call sites (`checkNotNull(current.closing())`) that `null` means a refused transition.

2. **`promote` and `opening?.replayRequested != true`**
   - Current: `promote = phase.opening?.replayRequested != true` is computed in `markOpened`, `resolveHeldSubagentState` and `SubagentSessionHolder`'s `init`, and passed on as `promote` to `markOpened`, `onOpened` and `onParentOpened`.
   - Proposed: a property `Opening.appliesHeldState` (or `RootPhase.appliesHeldState`), and the parameter renamed `applyHeld`.
   - Why: "promote" needs a `@param` at every function to say what it promotes; one named property would replace three copies of a double negative.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/SubagentSessionHolder.kt`

1. **`completeOpening`**
   - Current: `private suspend fun completeOpening()`, the body of the subagent's opening; `RootSessionHolder.completeOpening(session, opened)` is a different step with the same name.
   - Proposed: `runOpening()`.
   - Why: the subagent's function is the whole opening, not its last step, and the shared name suggests the two correspond.

2. **`OpeningState.Finished`**
   - Current: `Finished`, set before the holder is completed.
   - Proposed: `Completing` (or `CompletionClaimed`).
   - Why: the KDoc has to explain that "finished" means "will complete next"; the name could say it.

3. **Completion claim in `completeOpening`**
   - Current: `val running = opening.value as? OpeningState.Running ?: return` followed by `if (!opening.compareAndSet(running, OpeningState.Finished)) return`.
   - Proposed: `private fun claimCompletion(): Boolean` and `if (claimCompletion()) _session.complete(session)`.
   - Why: the inline comment "Claims the completion unless a stop came first" would become the function name.

4. **`_subagent`**
   - Current: `private val _subagent = atomic<ClientSubagentSession?>(null)` beside the inherited `_session` deferred.
   - Proposed: `setUpSession`.
   - Why: both hold "the session"; the difference is that this one exists from the setup, before `_session` completes, which the block comment has to explain.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/internal/PendingPermissions.kt`

1. **`WorkState.activeWork`**
   - Current: `Held(val activeWork: Boolean, ...)`, `Live(val activeWork: Boolean)`, set from the local `confirmsWork`.
   - Proposed: `confirmsWork` (or `foregroundWorkConfirmed`).
   - Why: the field is set from `confirmsWork` and the comments call it "confirmed foreground work"; one name would drop the translation.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/Client.kt`

1. **Failing an opening**
   - Current: `sessions.failSessionTree(holder, IllegalStateException("Failed to create session $sessionId", throwable))` in four `catch` blocks (`createSession`, `withFactoryInitializingSession`, `withAnsweredSessionId`, `withEarlyInitializingSession`); only the one in `createSession` explains the wrapping.
   - Proposed: `private suspend fun failOpening(holder: RootSessionHolder, sessionId: SessionId, cause: Throwable)`.
   - Why: the -32603-versus-cancelled rationale would sit once on the function, and the `catch` blocks would read as "fail the opening, rethrow".

2. **The three opening helpers**
   - Current: `withFactoryInitializingSession`, `withAnsweredSessionId`, `withEarlyInitializingSession`; two are named after how the operations arrive, one after where the id comes from.
   - Proposed: `openKnownIdWithFactory`, `openAnsweredIdWithFactory`, `openKnownIdWithOperations`.
   - Why: each KDoc's first sentence has to say which requests the helper serves; names on the same two axes would say it.

3. **The answered claim in `withAnsweredSessionId`**
   - Current: `CompletableDeferred<Pair<SessionId, RootSessionHolder>?>`, read with `isCompleted`/`getCompleted()` under `ExperimentalCoroutinesApi`; not completed means the hook didn't run, `null` means the id was refused.
   - Proposed: a private sealed result (`NotAnswered`, `Refused`, `Claimed(sessionId, holder)`) in an `atomic`.
   - Why: the three cases would be names instead of "a normal return means the hook has run" and `?: error(...)`, and the experimental opt-in would go.

## `acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientSubagentSession.kt`

1. **`applyPatch`**
   - Current: one function applies `title`, `description`, `cwd` and `additionalDirectories` at once, then branches on `parentOpened` for `capabilities` and `state`.
   - Proposed: split out `private fun applyDescriptiveFields(update)` and `private fun applyOrHoldWorkFields(update, parentOpened)`.
   - Why: the difference between the fields that apply at once and those held until the parent's `Opened` is the one fact the KDoc still carries; two names would carry it.
