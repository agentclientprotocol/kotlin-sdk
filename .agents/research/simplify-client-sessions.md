# Can the v1 client's sessions, update delivery and permissions get simpler?

Scope: `client/Client.kt`, `ClientSession.kt`, `ClientSubagentSession.kt`, `BaseClientSession.kt`,
`SubagentOperations.kt`, `ClientOperationsFactory.kt`, `ClientNegotiator.kt` (v1 path), and `client/internal/`
`ClientSessionImpl.kt`, `BaseClientSessionRuntime.kt`, `PendingPermissions.kt`, `ElicitationSessionStore.kt`,
`ClientNesSessionImpl.kt`. Holders and the registry were read as context only.

Checked at `e609e49` (working tree clean). Spec: `agent-client-protocol` @ `927e3186` (pulled, already up to
date). Rust SDK: not checked again; where it matters I rely on `.agents/research/updates-before-opening-answer.md`
(Rust `34aea54f`).

Nothing was run. Every claim below is **by reading**; the three defects in "Defects found on the way" are traced
line by line but have no test behind them.

Paths are relative to `acp/src/commonMain/kotlin/com/agentclientprotocol/client/`.

## Summary

The area is not uniformly complicated. Three parts are already minimal (root permissions, `ClientSubagentSession`
values, close identity). Three parts carry most of the cost:

1. **`SubagentPendingPermissions`** (`internal/PendingPermissions.kt:99-185`, 87 lines, 23 unit tests). It
   implements two SDK inventions on top of the two spec rules: the "later requests after a client cancel" window and
   the hold-back of pre-answer states. Both are user decisions. Without them the class is about 15 lines.
2. **The three opening flows** in `Client.kt:618-754` (about 140 lines). They are one algorithm written three times,
   with four `failSessionTree` sites and the "app `CancellationException` becomes `IllegalStateException`" conversion
   repeated at six places across the client. One flow and one helper would do.
3. **Live delivery after the opening** (`ClientSessionImpl.handleNotification` plus `ClientSessionHolder.handleOrQueue`,
   the second owned by the holder reviewer). It has two real ordering holes and one lost-update race, and the fix for
   each is less code, not more.

Rough numbers for my area (about 900 lines that take part in concurrency):

| Step | Product LOC | Mechanisms removed | Needs a user decision |
|---|---|---|---|
| Prompt routing: `trySend`, identity-safe clear | -5 | `isClosedForSend` check-then-act | no (bug fix) |
| One opening flow + one `callApp` helper | -60 to -75 | 3 flows -> 1, 4 fail sites -> 1, 6 CE conversions -> 1 | no (refactor) |
| Root permissions move to the holder, one request path | -15 | root/subagent branch in the handler | no (refactor) |
| Drop the post-cancel window for subagents | -25 (and 9 tests) | `activeWork`, the two-atomic ordering protocol | yes, reverses a final decision |
| Drop the permission hold-back (D1) | -50 (and 8 tests) | `WorkState`, `setPendingAside`, `onOpened`, `promote` plumbing | yes, rejected on 2026-11-22 |
| `ElicitationSessionStore` on a lock | -20 | counter, `Entry`, sort, 4 CAS sites | no (refactor) |
| `lateDeliveries` chain -> fair `Mutex` (holder area) | -20 | fast path, hand-rolled chain | yes, live `notify` becomes strictly sequential |

Top recommendations, in order:

1. Fix the three delivery defects (items D1-D3). They are small and each fix deletes code.
2. Merge the opening flows. No behaviour change, biggest readability gain in `Client.kt`.
3. Ask the user again about the post-cancel window for subagents. It is the one invention here whose benefit is
   close to zero, because the spec's own `idle(cancelled)` rule already covers the case it exists for.
4. Leave the permission hold-back unless the user reopens D1. I still think it is not worth its cost, and say why.
5. On CAS versus locks: in my area CAS is the right tool almost everywhere, because nearly every site is a single
   reference swap with a pure lambda. The exceptions are `ElicitationSessionStore` (lock) and the two-atomic protocol
   in `SubagentPendingPermissions` (restructure away, or lock).

## Who runs what

This decides which races exist at all, so it comes first.

- All request and notification handlers run on `handlerDispatcher`, which is `Dispatchers.Default.limitedParallelism(1)`
  (`protocol/Protocol.kt:70`). Handlers never run in parallel with each other. They interleave only at suspension
  points.
- The read loop waits for the first part of each notification handler (`Protocol.kt:473-476`), and response hooks run
  in the read loop (`Protocol.kt:632`). So "arrival path" code (the sorting in `ClientSessionRegistry.queuedItemFor`,
  `registerChild`, `SubagentPendingPermissions.onState`, `markOpened`, `onOpened`) is strictly sequential.
- Everything else runs on application threads: `prompt()`, `cancel()`, `close()`, the opening calls, the failure
  cascade, and the subagents' openings in the connection scope.

So every real race in this area is "arrival path versus an application coroutine". There is no arrival-versus-arrival
race.

## Inventory

"Real?" asks whether a conforming agent and a reasonable application can hit it. LOC is product code only.

| # | Item | Location | Real? | Spec or invention | Verdict | LOC |
|---|---|---|---|---|---|---|
| 1 | One prompt at a time per session | `internal/ClientSessionImpl.kt:53,80` `activePrompt` | yes | invention (the `Flow<Event>` API needs it) | keep, minimal | 0 |
| 2 | Turn updates precede `PromptResponseEvent` (close channel, join drain) | `ClientSessionImpl.kt:94-107` | yes | invention (API contract) | keep, fix D1 and D3 | -5 |
| 3 | Update routed to the prompt or to `notify` | `ClientSessionImpl.kt:215-232` | yes | invention | simplify (`trySend`), fixes D1 | -3 |
| 4 | Cancel answers pending root requests, also if the send fails | `ClientSessionImpl.kt:110-116`, `PendingPermissions.kt:32-46,58-60` | yes | spec MUST (`prompt-turn.mdx:648`) | keep, minimal | 0 |
| 5 | Root requests after a cancel are answered until the next prompt | `PendingPermissions.kt:34,66-68`, `ClientSessionImpl.kt:81` | yes | invention | keep | 0 |
| 6 | App answer wins a tie; handler cancelled and awaited | `PendingPermissions.kt:36-45` | yes | spec ("MUST NOT get more than one response", `subagents.mdx:475`) | keep, minimal | 0 |
| 7 | Close answers pending requests before the request, permanently | `ClientSessionImpl.kt:128-133`, `PendingPermissions.kt:51-53,70-77` `CLOSED` | yes | implied by spec (`session-setup.mdx:314` plus item 4) | keep; permanence after a *failed* close is questionable | 0 |
| 8 | Close acts on its own holder | `ClientSessionImpl.kt:118-126`, `internal/ClientSessionRegistry.kt:94` | marginal (stale object after a reopen) | invention, user decision (R6) | keep, 5 lines | 0 |
| 9 | `-32002` is a successful close | `ClientSessionImpl.kt:135-144` | yes | spec SHOULD (`session-setup.mdx:326-329`) | keep | 0 |
| 10 | Cancelled close skips the cleanup; late update reaches a closed subagent | `ClientSessionImpl.kt:135-146` | yes | invention gap | deferred by the user; the response-hook proposal is right | n/a |
| 11 | Subagent: live `idle(cancelled)` answers pending requests | `PendingPermissions.kt:122,132` | yes | spec (`subagents.mdx:472`) | keep | 0 |
| 12 | Subagent: client cancel answers pending requests | `ClientSubagentSession.kt:162-171`, `PendingPermissions.kt:161-162` | yes | spec MUST (`subagents.mdx:471`) | keep | 0 |
| 13 | Subagent: later requests after a client cancel are answered while the last live state is active | `PendingPermissions.kt:104-109,133-138,163-165` | marginal | invention, user decision (final) | drop (argue to revisit) | -25 |
| 14 | Subagent: pre-answer states held back, `idle(cancelled)` sets requests aside | `PendingPermissions.kt:100-106,123-130,148-152,174-184`; `internal/SubagentSessionHolder.kt:77-81,99-101`; `internal/RootSessionHolder.kt:152-153` | marginal | load: spec; resume: invention, user decision (D1 rejected) | simplify (argue to revisit) | -50 |
| 15 | Subagent request joins the cancel race at arrival | `Client.kt:116-124`, `internal/BaseClientSessionRuntime.kt:46-55`, `ClientSessionRegistry.kt:100-101` | yes (during an opening) | invention, user decision (M4 answer 1) | keep; unify with the root path | -15 |
| 16 | States fed to permissions at arrival, not at delivery | `ClientSessionRegistry.kt:219-223` | yes | invention, user decision | keep, 5 lines | 0 |
| 17 | Subagent `capabilities`/`state` held until the parent's `Opened` | `ClientSubagentSession.kt:91-96,181-212` | yes | spec (`subagents.mdx:499-508`) | keep, minimal | 0 |
| 18 | Subagent getters as separate atomics, no flows | `ClientSubagentSession.kt:83-89` | yes (visibility) | invention, user decision (no flows) | keep | 0 |
| 19 | Three opening flows, four fail sites | `Client.kt:618-754` | n/a (duplication) | n/a | simplify: one flow | -50 to -60 |
| 20 | `Opened` marker queued from the response hook | `Client.kt:664,689-692,741`, `RootSessionHolder.kt:145-155` | yes | load boundary: spec (`subagents.mdx:508`); the event itself: invention | keep | 0 |
| 21 | Hook claimed a holder, then a cancellation won | `Client.kt:687-705` `claimed` | marginal (only with a second opening in flight; otherwise the reaper frees it) | invention | keep, folds into item 19 | 0 |
| 22 | App `CancellationException` while the caller is active fails the opening | `Client.kt:628-632,732-739`, `RootSessionHolder.kt:240-247`, `internal/ClientSessionHolder.kt:311-315`, `SubagentSessionHolder.kt:190-197,225-228` | yes (`withTimeout` in `notify`) | invention, user decision (S1 on the agent side) | keep, one helper | -15 |
| 23 | Cancellable `Opened` delivery; a late cancel still fails the opening | `RootSessionHolder.kt:217-232` | yes | invention, user decision (a) | keep | 0 |
| 24 | Early delivery as a concurrent `async` | `Client.kt:728-746` | yes | invention (overload contract) | keep, part of item 19 | 0 |
| 25 | Requests for an unknown or unclaimed id fail at once with `-32002` | `Client.kt:58-68`, `ClientSessionRegistry.kt:160-171` | yes | invention, user decision; matches Rust in spirit | keep, minimal | 0 |
| 26 | `elicitation/complete` after the session ended | `Client.kt:184-195` | marginal | RFD ("silently ignored") | simplify: one lookup, fixes D4 | -4 |
| 27 | `ElicitationSessionStore` bounded map | `internal/ElicitationSessionStore.kt` | marginal | invention (safety net) | restructure on a lock | -20 |
| 28 | Negotiation cached under a `Mutex` | `ClientNegotiator.kt:105-122` | yes | invention | keep; fix D5 | 0 |
| 29 | Live updates after the opening: fast path and `lateDeliveries` chain | `ClientSessionHolder.kt:350-376` (holder area) | yes | spec says per-session order (`subagents.mdx:210`, `prompt-turn.mdx:483`) | simplify: fair `Mutex`, fixes D2 | -20 |

## Defects found on the way

These are not the goal of the review, but each one is a reason to simplify, and each fix removes code.

### D1. An update that races the end of a turn is lost (should-fix, by reading)

`ClientSessionImpl.kt:221-227` checks `isClosedForSend` and then calls `send`. The check runs on the handler thread.
The channel is closed on the application thread at `:98` or `:105`.

1. The agent answers `session/prompt` and sends one more update for the session (legal: `session_info_update`,
   `available_commands_update`, or a subagent's `subagent_update` on the root's stream).
2. The read loop completes the prompt's deferred. The collector's coroutine resumes on its own thread.
3. The update's handler reads `activePrompt.value`, sees the prompt, sees `isClosedForSend == false`.
4. The collector runs `activePrompt.getAndSet(null)?.updateChannel?.close()`.
5. The handler calls `send`, which throws `ClosedSendChannelException`. `Protocol.handleNotification` logs it
   (`Protocol.kt:610-618`). The update reaches neither the flow nor `notify`.

The `@OptIn(DelicateCoroutinesApi::class)` on that line is the warning sign. Fix, which also removes the opt-in and
the comment:

```kotlin
internal suspend fun handleNotification(notification: SessionUpdate, _meta: JsonElement?) {
    trackState(notification)
    // The channel is unlimited, so this fails only once the turn has closed it.
    val delivered = activePrompt.value?.updateChannel?.trySend(notification)?.isSuccess == true
    if (!delivered) operations.notify(notification, _meta)
}
```

### D2. A suspended `notify` is overtaken by the next live update (should-fix, by reading; holder area)

`ClientSessionHolder.kt:357-359`: once the session is open, a plain update takes the fast path and calls
`deliverToSession` directly. It does not register in `lateDeliveries`. So:

1. Update A arrives. Its handler calls `operations.notify(A)`, which suspends (for example `withContext(Dispatchers.IO)`).
2. Update B arrives. `lateDeliveries.value.isCompleted` is still `true`, so B takes the fast path too and
   `notify(B)` starts while `notify(A)` is suspended.

The SDK then guarantees only the order in which the calls start. A root's turn is safe because the prompt channel
never suspends. A subagent is not: all of its content goes through `notify` (`BaseClientSessionRuntime.kt:35`), so
message chunks of a subagent can reorder with a multi-threaded dispatcher in `notify`. The spec orders updates within
each session (`subagents.mdx:210`), and `Client.loadSession`'s KDoc promises "exactly once and in arrival order"
(`Client.kt:410`).

The chain exists, but only announcements and the opening window use it. See item 29 for the fix.

### D3. A slow `createSubagentOperations` lets the prompt response overtake the turn's last updates (should-fix, by reading)

1. During a turn the agent announces a subagent. The handler takes the chain (`ClientSessionHolder.kt:357`, an
   announcement never takes the fast path) and suspends inside the application's `createSubagentOperations`
   (`ClientSessionHolder.kt:310`).
2. The agent sends more updates for the root. Their handlers wait in the chain (`:368-371`).
3. The agent answers `session/prompt`. The read loop does not wait for suspended handlers.
4. `prompt()` closes the channel, joins the drain, emits `PromptResponseEvent` and completes
   (`ClientSessionImpl.kt:98-103`).
5. The factory returns. The announcement and the updates behind it now find no active prompt and go to
   `operations.notify`, after the turn's flow has ended.

Nothing is lost, but the comment at `:94-96` and every consumer of the flow assume the turn's updates come before the
response event. Minimal fix: before closing the channel, `prompt()` waits for the deliveries that were chained when
the response arrived. With today's code that is one `lateDeliveries.value.join()` exposed by the holder. With the
`Mutex` of item 29 it is `liveDelivery.withLock {}`.

Related and already recorded in `.agents/state.md`: an update that arrives *after* the response goes to `notify`
while the flow still drains. That one is inherent to having two sinks and is not fixed by any of this.

### D4. `elicitation/complete` swallows `CancellationException` (nit, by reading)

`Client.kt:189`: `runCatching { sessions.awaitSession(sessionId) }.getOrNull()`. `AGENTS.md` asks to rethrow
cancellation. It also looks the id up twice (`isSessionCompleted`, then `awaitSession`), and the second lookup can
find a different, incomplete holder and suspend. One non-suspending `completedSessionOrNull(sessionId)` in the
registry replaces both.

### D5. `ClientNegotiator` caches a `CancellationException` (nit, by reading)

`ClientNegotiator.kt:117-121` stores every `Throwable` as `State.Failed`. If the first `negotiate()` caller is
cancelled, later callers get that stale `CancellationException` thrown in an active coroutine, which structured
concurrency treats as a silent cancellation. The connection is already closed by then (`:143`), so only the error
report is wrong. Store a wrapping `IllegalStateException` when the cause is a cancellation.

### D6. A second `prompt()` can be unregistered by the first one's cleanup (nit, by reading)

`ClientSessionImpl.kt:98` clears `activePrompt`, then the coroutine suspends in `join` and `send`. A `prompt()` that
starts in that window registers itself, and the first prompt's `finally` at `:105` then does `getAndSet(null)` and
closes the *new* prompt's channel. It needs an application that starts a turn before it has seen the previous
`PromptResponseEvent`, so it is marginal. `activePrompt.compareAndSet(promptSession, null)` plus
`promptSession.updateChannel.close()` is both correct and simpler, and with D1's `trySend` the order of the two no
longer matters.

## Items worth discussing

### Items 13 and 14: `SubagentPendingPermissions`

What the spec requires (`subagents.mdx:469-475`, `499-508`):

- a client cancel answers that session's pending requests `cancelled` (MUST);
- a descendant's `idle` with `cancelled` is handled "as if it had cancelled the descendant directly";
- `unknown` resolves nothing (`:419`);
- a request gets at most one response;
- during a load, `capabilities` and `state` before the response are history and never take effect;
- during a resume everything is current, before or after the response; `session/cancel` for a child waits for
  success; a failed request discards what arrived during it.

What the class adds on top:

**Item 13, the post-cancel window (user decision, final).** After `onClientCancel`, later requests are also answered
`cancelled` while the last live state is `running` or `requires_action`, until the next live state.

- Interleaving it exists for: the client cancels a child; a `session/request_permission` of that child was already on
  the wire; it arrives after the cancel and would reach the application.
- What happens without it: the application sees that request. Then the agent confirms the cancellation with
  `idle(cancelled)`, as `subagents.mdx:454` requires, and item 11 answers the request and cancels the handler. So the
  only difference is a prompt that is visible between the cancel and the agent's confirmation.
- If the agent never confirms (it reports `unknown`, or nothing): the request stays answerable, which is what
  `:419` and `:472` ("Until then, the user can still answer them") describe.
- Cost: `Live.activeWork`, `Held.activeWork`, the write-then-clear order in `onState` (`:133-138`) against the
  cancel-read-clear order in `onClientCancel` (`:161-165`), a recorded decision about what "a cancel racing a state"
  means, nine unit tests, and a paragraph of public KDoc on `ClientSubagentSession.cancel`.
- It also has a failure mode of its own: if the child keeps working without a new state, every later request of it is
  denied silently.

The root's equivalent (item 5) is different: a root has no state stream, the turn ends with the prompt response, and
nothing else would ever answer a late request. There the stickiness earns its keep. For a subagent it duplicates
item 11. Recommendation: drop it.

**Item 14, the hold-back (user decision, D1 rejected 2026-11-22).** A state before the root's answer is not applied.
Its `activeWork` is kept for the window of item 13, and an `idle(cancelled)` sets the pending requests aside to be
answered at `onOpened(promote = true)`.

- For a load this is the spec: a replayed `idle(cancelled)` must not answer a live request. But "ignore it" is
  enough. Nothing has to be remembered.
- For a resume the states are current. The hold-back delays the `cancelled` answer until the root's answer, and turns
  it into an error if the resume fails.
- Interleaving it exists for: a resume in flight; the agent sends a live permission request for a child before it
  answers the resume; then cancels that child's work, still before the answer. The request has not reached the
  application (requests wait for the holder). With the hold-back it is answered `cancelled` at the root's answer.
  Without it, it is answered `cancelled` when the state arrives.
- Both outcomes resolve the request exactly once, and neither leaves client state behind after a failed resume. The
  user's reason for keeping it was "a failed attempt invalidates what arrived during it". An answer already sent
  cannot be invalidated either way; the only observable difference is the code of the answer on a failed resume.
- Cost: `WorkState.Held`, `setPendingAside` (a CAS loop with an `invokeOnCompletion` link, `:174-184`),
  `onOpened(promote)`, and the `promote` parameter threaded through `SubagentSessionHolder.markOpened` (`:99-101`),
  its `init` (`:77-81`) and `RootSessionHolder.markOpened` (`:152-153`). Eight unit tests.

If both go, the class is:

```kotlin
internal class SubagentPendingPermissions : PendingPermissions() {
    /**
     * Follows a `state` of this subagent when it arrives.
     *
     * @param current `false` for a state replayed by a load before its answer, which is history
     */
    fun onState(state: StateUpdate?, current: Boolean) {
        if (current && state is StateUpdate.Idle && state.stopReason == IdleStopReason.Cancelled) answerPending()
    }

    fun onClientCancel(): Unit = answerPending()

    // Answers the requests pending now; later ones reach the app.
    private fun answerPending() {
        signal.getAndUpdate { if (it === CLOSED) it else CompletableDeferred() }.complete(Unit)
    }
}
```

with `current = phase.isAtLeast(ANSWERED) || phase.opening?.replayRequested != true` in
`ClientSessionRegistry.followSubagentState`. One atomic, one pure lambda, no ordering protocol between two fields.

If only item 13 goes, `WorkState` shrinks to "held signal or live" and `onState` loses the write-then-clear order.
If only item 14 goes, `activeWork` stays but becomes a plain `atomic(false)`.

Tests that change: `SubagentPendingPermissionsTest` lines 112-150, 200-229 (window) and 229-385 (hold-back);
`ClientSubagentSession.cancel` KDoc; the `AGENTS.md` bullets "A state that arrives before the root's answer is held
back" and "Requests that an early `idle(cancelled)` set aside".

### Item 15: one request path instead of two

`Client.kt:116-131` has two branches. A subagent's request takes the holder before it suspends and awaits the session
inside the race. A root's request awaits the session first and then enters the race in `ClientSessionImpl`.

The split exists only because the root's `RootPendingPermissions` lives in `ClientSessionImpl` (`:54`) and the
subagent's lives in its holder. A root cannot be cancelled or closed before its session object exists, so entering the
race at arrival changes nothing for it. Moving the root's instance to `RootSessionHolder` gives one path:

```kotlin
protocol.setRequestHandler(AcpMethod.ClientMethods.V1.SessionRequestPermission) { params ->
    // Taken before the first suspension, so the request joins the cancel race when it arrives.
    val holder = sessions.holderForRequest(params.sessionId)   // claimed root or subagent, else -32002
    holder.permissions.answerUnlessCancelled(cancelledAnswer = { RequestPermissionResponse(Cancelled) }) {
        val session = holder.session.await()
        session.executeWithSession { session.operations.requestPermissions(params.toolCall, params.options, params._meta) }
    }
}
```

It removes `ClientSessionImpl.answerPermissionRequest`, the `as? ClientSessionImpl` cast, `subagentHolderOrNull`, and
the extension in `BaseClientSessionRuntime.kt:46-55`. `ClientSession.close()` then calls one
`holder.startClosing()` that closes the root's and the descendants' permissions together. No behaviour change, no
test change beyond construction.

### Item 19: one opening flow

`withFactoryInitializingSession`, `withAnsweredSessionId` and `withEarlyInitializingSession` differ in two points
only:

- when the holder is claimed: before the request (load, resume) or in the response hook (new, fork);
- when the operations exist: before the request (early overloads) or after the answer (factory).

`RootSessionHolder.drainHistory` (`:163-170`) and `deliverEarly` (`:180-197`) are the same loop: deliver until the
marker, return the marker. The factory path just starts it after the answer, when the marker is already queued. So:

```kotlin
private suspend fun <R : AcpCreatedSessionResponse> openSession(
    parameters: SessionCreationParameters,
    claimedBefore: RootSessionHolder?,                // load, resume
    earlyOperations: ClientSessionOperations?,        // the overloads that take operations
    factory: ClientOperationsFactory?,
    request: suspend (onResponse: (R) -> Unit) -> R,
    claimAnswered: (R) -> RootSessionHolder?,         // new, fork: runs in the hook
): ClientSession = sessions.withInitializingSession {
    val claimed = atomic(claimedBefore)
    try {
        coroutineScope {
            val early = earlyOperations?.let { ops ->
                async { callApp("ClientSessionOperations.notify") { claimed.value!!.deliverUntilOpened(ops, session = null) } }
            }
            val response = request { r ->
                val holder = claimed.value ?: claimAnswered(r).also { claimed.value = it }
                holder?.markOpened(r)
            }
            val holder = claimed.value ?: error("Session is already open or being opened")
            val operations = earlyOperations
                ?: callApp("createClientOperations") { factory!!.createClientOperations(holder.sessionId, response) }
            val session = createClientSession(holder, holder.sessionId, parameters, operations, response)
            val opened = early?.await() ?: holder.deliverUntilOpened(operations, session)
            holder.applyEarlyState(session)
            holder.completeOpening(session, checkNotNull(opened))
            session
        }
    } catch (t: Throwable) {
        // The only place an opening fails its holder. The hook may have claimed one before a cancellation won.
        claimed.value?.let { sessions.failSessionTree(it, IllegalStateException("Failed to create session ${it.sessionId}", t)) }
        throw t
    }
}

/** A cancellation that app code throws while the caller is active is a failure, not the caller's cancellation. */
internal suspend inline fun <T> callApp(what: String, block: () -> T): T =
    try { block() } catch (ce: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("$what was cancelled", ce)
    }
```

Points to check when doing it:

- `session` is passed to `deliverUntilOpened` only in the factory path, so the history's `notify` keeps the session in
  its context there and has none in the early path, as documented (`Client.kt:412`).
- Today the early path calls `completeOpening` outside its `coroutineScope` (`Client.kt:747`). Inside is equivalent:
  the `async` has completed by then.
- `markOpened` returning `false` for a failed holder stays as is.

The existing tests (`ClientSessionOpenedTest`, `ClientEarlySessionUpdatesTest`) run every case over `allOpenings`, so
they cover the merged flow unchanged. `callApp` also replaces `RootSessionHolder.deliverDuringOpening` and the inline
conversions in `ClientSessionHolder.setUpSubagent` and `SubagentSessionHolder`.

The deeper simplification is the one the `TODO` in `ClientOperationsFactory.kt:13-16` already names: once `newSession`
and `forkSession` take operations (issue 167) and the factory is deprecated, `factory`, the "create after the answer"
branch and the buffering-only mode go away.

### Item 29: live delivery (holder area, but it decides D2 and D3)

`lateDeliveries` (`ClientSessionHolder.kt:128,357-375`) is a hand-rolled FIFO lock: each late update swaps in its own
`CompletableDeferred` and joins the previous one. The fast path beside it skips the chain and causes D2.

`kotlinx.coroutines.sync.Mutex` is fair, and an uncontended `lock()` does not suspend. Handlers reach it in arrival
order because they start in arrival order on one thread. So:

```kotlin
private val liveDelivery = Mutex()

suspend fun handleOrQueue(item: QueuedItem): Boolean {
    if (queue.trySend(item).isSuccess) return true
    // Fair, so the updates that missed the closed queue are delivered one at a time in arrival order.
    return liveDelivery.withLock {
        _session.join()
        deliverToSession(item)
    }
}
```

- Removes the fast path, the chain, and the special case for announcements.
- Fixes D2. D3 becomes `liveDelivery.withLock {}` in `prompt()` before it closes the channel.
- Behaviour change, hence a user decision: a `notify` that hangs now stalls its session's later updates. That already
  happens behind an announcement and during the opening, so the change makes the rule uniform rather than new.
- `ClientSessionOpenedTest` "updates that miss the closed queue wait for the updates queued after the marker" keeps
  passing. A new test should pin D2.

The larger alternative is one long-lived consumer coroutine per holder, with the queue never closed. It would remove
the "switch to the session" step entirely, but it moves `notify` off the caller's coroutine during the opening, which
contradicts user decision (a) ("runs app code in the caller's coroutine, cancellably"), and it needs an end-of-turn
marker for `prompt()`. I do not recommend it now. The `Mutex` gets most of the benefit for a tenth of the change.

### Item 7: permanence of `close()` on permissions

`CLOSED` is permanent so that a `prompt()` or a live state during a close in flight cannot let requests through
again. After a *successful* close it is redundant: the tree has left the map, so a later request fails with `-32002`
before it reaches any `PendingPermissions`. After a *failed* close (for example the agent lacks the capability and
answers `-32601`), the session is still open at the agent but can never show a permission request again. The KDoc
documents that ("even if the close then fails", `ClientSession.kt:67-69`), so it is deliberate. It is six lines; I
would keep it and only note that "a failed `session/close` leaves the id taken" in the state file's follow-ups has
this second half.

### Items that are already minimal

- **Root permissions (4-6).** One atomic reference, one `select`. The sticky signal costs one `if` and one `update`.
- **Close identity (8).** Two reads. The alternative, a stale object closing a reopened session, is worse than five
  lines.
- **`ClientSubagentSession` (17, 18).** Seven atomics are volatile fields with one writer at a time (the parent's
  consumer) and readers on any thread. `closed` is check-then-act in `applyPatch` and `cancel`, and both outcomes are
  harmless: one more patch applied, or one `session/cancel` for a child the agent has dropped. A single snapshot
  object would make reads consistent, but the KDoc already says they need not be (`:69`).
- **`-32002` at once (25).** One `when`.

## CAS versus locks

Primitives the repository already has: atomicfu `0.25.0` (`gradle/libs.versions.toml:13`) with
`kotlinx.atomicfu.locks.SynchronizedObject` / `synchronized`, used in `agent/internal/SubagentSessionWrapper.kt:55`
and `RootSessionWrapper.kt:62`; and `kotlinx.coroutines.sync.Mutex` (coroutines `1.9.0`), used in
`ClientNegotiator.kt:105`. Both work in `commonMain`.

| Site | Guards | Lambda pure and cheap? | Contention | Recommendation |
|---|---|---|---|---|
| `ClientSessionImpl.kt:53,80` `activePrompt.compareAndSet(null, p)` | one turn at a time | no lambda | app thread vs handler thread reads | keep CAS |
| `ClientSessionImpl.kt:98,105` `getAndSet(null)` | clearing the turn | no lambda | see D6 | keep CAS, make it `compareAndSet(promptSession, null)` |
| `ClientSessionImpl.kt:224` `isClosedForSend` then `send` | routing | n/a (check-then-act, not atomic) | real, see D1 | restructure: `trySend` |
| `ClientSessionImpl.kt:57-73` `by lazy` state flows | deprecated state | n/a | none | keep until the deprecated API goes |
| `PendingPermissions.kt:33,59` `signal.value` | reading the current signal | n/a | none | keep |
| `PendingPermissions.kt:52` `getAndSet(CLOSED).complete` | permanent close | no lambda; completes outside any lock | none | keep CAS. A lock would need "read under the lock, complete after it", which is more code |
| `PendingPermissions.kt:67` `signal.update { ... }` | clearing a completed signal | pure, one allocation | app thread vs app thread | keep CAS |
| `PendingPermissions.kt:109,128,137,150` `workState` | last work state | plain volatile writes, single writer (arrival path) | read by `onClientCancel` | not CAS. See next row |
| `PendingPermissions.kt:132-138` with `:161-165` | "cancel racing a state" over two atomics | n/a: a lock-free protocol, write-then-clear against cancel-read-clear | arrival path vs app thread | correct for all eight interleavings I traced, but only by ordering. Restructure by dropping item 13. If it stays, a `SynchronizedObject` around both functions (collect the deferred, complete it after the lock) is clearer than the comment at `:133-136` |
| `PendingPermissions.kt:174-184` `setPendingAside` CAS loop | swapping the signal and linking old to new | body pure; the `invokeOnCompletion` side effect runs after a successful CAS, so a retry cannot repeat it | arrival path vs cancel/close | correct. Goes with item 14; otherwise keep |
| `ClientSubagentSession.kt:83-89` | visibility of getters | no CAS, plain writes | readers on any thread | keep |
| `ClientSubagentSession.kt:95-96` plain `var`s | held patches | n/a | single writer, no other reader | keep; the comment is accurate |
| `Client.kt:51,108,594` `_nesSessions.update` | NES session map | pure (`put`/`remove`) | negligible | keep CAS |
| `Client.kt:687,699,708` `claimed` `CompletableDeferred` | handing the claimed holder from the hook to the caller | n/a | read loop vs caller, once | keep, or an `atomic` in the merged flow |
| `ElicitationSessionStore.kt:29` `entries.update { it.put(..., counter.getAndIncrement()) }` | bounded map | **not pure**: the counter moves on every retry. Harmless (ordinals only need to grow) but it is the pattern to avoid | handler thread vs a closing caller: practically none | replace with a lock |
| `ElicitationSessionStore.kt:36-37,42-46,49-56` | remove, remove by session, evict | read outside, write inside: the keys to remove are computed before the `update`, so an entry added in between survives `removeBySession`, and two evictions can both run | as above | replace with a lock |
| `ClientNegotiator.kt:105-122` `Mutex` | negotiate once, across a suspending request | n/a | concurrent `negotiate()` calls | keep `Mutex`: it is the only primitive that may be held across a suspension. Fix D5 |
| `ClientSessionHolder.kt:128,368` `lateDeliveries.getAndSet` (holder area) | FIFO of late updates | no lambda | handler coroutines | replace with a fair `Mutex` (item 29) |

Sketch for `ElicitationSessionStore`, which also drops `Entry`, the counter and the sort:

```kotlin
internal class ElicitationSessionStore(private val maxCapacity: Int = MAX_ELICITATION_ENTRIES) {
    private val lock = SynchronizedObject()
    // Insertion-ordered, so the oldest entries come first.
    private val entries = LinkedHashMap<ElicitationId, SessionId>()

    operator fun get(elicitationId: ElicitationId): SessionId? = synchronized(lock) { entries[elicitationId] }

    fun put(elicitationId: ElicitationId, sessionId: SessionId): Unit = synchronized(lock) {
        entries.remove(elicitationId) // an overwrite counts as the newest entry
        entries[elicitationId] = sessionId
        if (entries.size > maxCapacity) {
            val oldest = entries.keys.take(entries.size / 2)
            oldest.forEach(entries::remove)
        }
    }

    fun remove(elicitationId: ElicitationId): SessionId? = synchronized(lock) { entries.remove(elicitationId) }

    fun removeBySession(sessionId: SessionId): Unit = synchronized(lock) {
        entries.values.removeAll { it == sessionId }
    }
}
```

`ElicitationSessionMapTest` should pass unchanged; check "overwriting existing key does not trigger eviction" and
"eviction order is based on insertion timestamp" against the remove-then-put.

### Overall

- In this area the worry about CAS lambdas running twice applies to exactly one site (`ElicitationSessionStore.put`),
  and it is harmless there. Every other lambda is a pure function of one reference.
- CAS fits where the state is one reference and the side effect (completing a deferred) must happen outside any
  critical section. That is `PendingPermissions`, and it should stay lock-free.
- A lock fits where several values must change together or where a read and a write must be one step. In my area that
  is `ElicitationSessionStore`. In the holder and registry files it is probably also the lambdas that assign captured
  variables (`ClientSessionRegistry.kt:61-85,235-246,363-387`), but those belong to the other reviewer.
- The hardest code here is neither CAS nor lock. It is ordering between two separate atomics
  (`workState` and `signal`) and between two sinks (the prompt channel and `notify`). Removing the second value is
  the simplification; changing the primitive is not.
- A `Mutex` is right where the critical section suspends and FIFO order is wanted: negotiation today, live delivery
  if item 29 is accepted. It must not be used on the arrival path where it could be contended before the first
  suspension matters; the uncontended fast path of `withLock` keeps that property.

## Follow-ups outside this report

- `ClientNegotiator.initializeV1` builds `Client` without a `globalSessionUpdateHandler`
  (`ClientNegotiator.kt:173`), and `V1ClientConfig` has no field for one. A negotiated v1 client therefore drops
  unconnected updates. Not a concurrency matter; not checked further.
- The Rust SDK's behaviour for a suspended update handler was not compared. It would be the natural reference for
  whether item 29's strict order is expected.
