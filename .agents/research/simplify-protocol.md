# Can `Protocol`'s concurrent code get simpler?

**Scope:** `acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/` at `e609e49` (branch
`eugenethedev/subagents`, clean tree). Analysis only, nothing changed, no tests run (every claim below is from
reading; none needed a run to settle).

**Sources checked:**
- this repo @ `e609e49`; `P:` below means `protocol/Protocol.kt`, `R:` means `protocol/RpcMethodsOperations.kt`.
- spec `agent-client-protocol` @ `927e3186` (2026-11-26, `pull --ff-only`: already up to date).
- Rust SDK @ `4656755` (`pull --ff-only` fast-forwarded), `src/agent-client-protocol/src/`.

## Summary

`Protocol` is close to minimal for what it promises. Of 738 lines, about **45-50 lines and two small mechanisms**
can go without any change in guarantees. One more mechanism (`onResponseQueued`, ~25 lines) is debatable. The only
large lever is not a concurrency trick at all: **JSON-RPC batch support** (on `master`, public API) is what makes
the response path need `ResponseSlot`, a shared `responseFrameQueued` deferred and a per-frame collector coroutine.
ACP v2 forbids batches, the Rust SDK rejects them for every version, and nothing in this repo sends one.

Top recommendations, most valuable first:

1. **Drop the "strip the hook on cancel" swap** (`P:215-221`, `P:258-259`). It narrows a window that every caller
   already has to handle and handles. −8 lines, one CAS site, one test.
2. **Replace `IncomingRequest`'s atomic flag with a read of the slot's reply** (`P:686-704`, `P:539`). The flag is
   only ever touched on the serialized handler dispatcher. −12 lines, one atomic. The behaviour is spec-required
   and stays.
3. **Merge `additionalContext` into the one `withContext` of `handleRequest`** (`P:345-356`, `P:562`). The wrapper's
   own cancellation branch duplicates what `handleRequest` already does. −10 lines.
4. **Collapse the graceful-wait catch ladder** (`P:231-252`): five catch arms that only log. −12 lines.
5. **CAS hygiene, no lock needed:** move `response.cancel()` out of the CAS lambda at `P:315-322`, and replace the
   captured-variable write at `P:424-429` with the `getAndUpdate{…}[id]` idiom of `P:626`. Drop the identity checks
   on outgoing ids (`P:218`, `P:259`, `P:319`): the counter never reuses an id.
6. **Raise with the user, outside this PR:** batches. Removing them would delete `ResponseSlot`, the collector job,
   `responseFrameQueued`, `sendBatchRequestRaw` and `JsonRpcCall` (~150 lines here, plus `TransportFrame.Batch` and
   most of a 928-line test file), and would make `onResponseQueued` a plain call after `sendFrame`. Independent of
   that, a v2 connection that accepts a batch is a conformance gap today.

What should stay as it is: the single-slot handler dispatcher and the notification barrier, the response hook, the
"register, then check the job" order in the send paths, `afterResponse`/`onCompletion`, `newConnectionScope`, the
close sequence, and CAS over persistent maps for the four tables. No site in `protocol/` would be simpler or safer
with a lock.

## Inventory

| # | Item | Location | Real? | Spec or invention | Who relies on it | Verdict | LOC |
|---|------|----------|-------|-------------------|------------------|---------|-----|
| 1 | Handlers serialized on one slot (`limitedParallelism(1)`), FIFO start | `P:70-73`, `P:522`, `P:550` | yes | mechanism is ours; per-session update order is spec (v2 `transports.mdx:60-62`, v1 `prompt-turn.mdx:483`, `subagents.mdx:210`) | client `session/update` sorting, permission handler (`Client.kt:110-118`), tests as a barrier | keep | 0 |
| 2 | Notification barrier: first part runs before the next message | `P:472-477` | yes | invention (Rust is stronger: the loop waits for the whole callback, `concepts/ordering.rs:13-15`) | client registry sorting vs the opening's answer | keep | 0 |
| 3 | No barrier after a request: a following response may be handled first | `P:466-470`, KDoc `R:25-30` | marginal | invention (an omission that became a documented caveat) | nobody needs the caveat; the permission handler only needs order against later notifications | optional: add the barrier, see §3 | +1 code, −6 doc |
| 4 | Response hook `onResponse` in the read loop | `P:189-198`, `P:630-638`, `Protocol.extensions.kt:15-31` | yes | mechanism ours, need is spec (`subagents.mdx:508`: the load response is the freshness boundary); Rust has the same (`on_receiving_result`, `ordering.rs:23-35`) | the three client opening paths (`Client.kt:663`, `:690`, `:741`) | keep | 0 |
| 5 | Hook stripped when the caller is cancelled | `P:215-221`, `P:258-259` | no (redundant) | invention | `ReadLoopOrderingTest:135` only | **drop** | −8 |
| 6 | Graceful wait for the peer's answer after `$/cancel_request` | `P:222-253` | marginal | invention (spec only says the peer MUST still answer, v2 `cancellation.mdx:30`) | on `master`; nobody reads the result, it is only logged | keep behaviour, collapse the ladder | −12 |
| 7 | Outgoing cleanup: register, then check the protocol job; remove in `finally` | `P:200-208`, `P:257-260`, `P:287-291`, `P:313-323` | yes | invention | every sender | keep; drop identity checks | −2 |
| 8 | Incoming id reservation and duplicate-id rejection | `P:525-535`, `P:547` | marginal (peer bug only) | invention (JSON-RPC is silent) | `BatchProtocolTest:107`, `:702-712`, `:749`, `:781` | keep | 0 |
| 9 | `$/cancel_request` ignored once a reply is collected (`settle`) | `P:539`, `P:686-704`, `P:141-154` | yes | **spec, MUST** (v2 `cancellation.mdx:34`) | v2 prompt turn (`agent/v2/Agent.kt:137-148`) | keep behaviour, drop the atomic | −12 |
| 10 | Every request answered, also when cancelled before its body runs | `P:541-548` | yes | spec, MUST (v2 `cancellation.mdx:30`) | — | keep | 0 |
| 11 | `ResponseSlot`, `responseFrameQueued`, collector job | `P:454-517`, `P:706-738` | yes, given batches | JSON-RPC batch rule; ACP v2 forbids batches (`transports.mdx:65-67`), v1 silent, Rust rejects (`transport_actor.rs:28-33`) | `afterResponse` of v1 `createSession` and v2 `acceptPrompt` | keep while batches exist; see §6 | (−150 with batches) |
| 12 | `afterResponse` waits for the frame to be queued | `P:575-592` | yes | need is spec (v2 `session-setup.mdx:100-105`: a non-idle state MUST follow the response); mechanism ours | v1 `Agent.kt:443`, v2 `Agent.kt:139` | keep | 0 |
| 13 | `onResponseQueued` | `P:498-502`, `P:568`, `P:729-737`, `R:154-158`, `R:165`, `R:172` | marginal | invention; **user decision** on its shape (state.md, "extend the existing `RequestOutcome`") | v1 `Agent.kt:453` only | keep; see §5 | (−25 if dropped) |
| 14 | `onCompletion` exactly once | `P:598-604`, `P:351-354`, `R:171-176` | yes | invention | v1 failed-load discard (`Agent.kt:444-447`), v2 turn cancel (`v2/Agent.kt:148`) | keep; one of its three call sites goes with §4 | −10 |
| 15 | `newConnectionScope` | `P:125-126` | yes | invention | `RootSessionWrapper.kt:56`, `Client.kt:50` | keep, already minimal | 0 |
| 16 | Shutdown: `close()` from three triggers, cancel-then-drain maps, then close the transport | `P:96-99`, `P:168-170`, `P:386-395` | yes | invention | `BatchProtocolTest:412-560` | keep, already minimal | 0 |
| 17 | Negotiated version, first write wins | `P:114-118` | yes | spec (one version per connection) | v2 client | keep | 0 |
| 18 | Typed hook overload's `decoded` variable | `Protocol.extensions.kt:21-30` | — | invention | client openings | keep; publication is ordered by the deferred | 0 |
| 19 | Public cancel APIs with no caller in the repo: `cancelPendingIncomingRequest(s)`, `cancelPendingOutgoingRequests`, `sendBatchRequestRaw` | `P:407-447`, `P:263-324` | — | invention, public API on `master` | tests only | keep (API), tidy `P:424-429` | −3 |

## Items worth discussing

### 1-2. One handler slot and the notification barrier: keep

**Interleaving without it.** Wire order `update(A, chunk 1)`, `update(A, chunk 2)`. With handlers launched on a
multi-threaded dispatcher, chunk 2's handler can enter the client registry first. The spec forbids the result:
"Receivers **MUST** apply a session's `session/update` notifications in the order they are received" (v2
`transports.mdx:60-62`); v1 says "Clients apply updates and chunks in receive order for each ID"
(`prompt-turn.mdx:483`). So ordered delivery is required; how we get it is ours.

**Why this mechanism and not Rust's.** Rust holds the dispatch loop until the callback completes
(`concepts/ordering.rs:13-15`) and tells users to `spawn` anything long. Our handlers are long by design (a v1
`session/prompt` handler is the whole turn), so "run to the first suspension" is the weakest guarantee that still
orders arrival, and it costs four lines (`P:473-476`). I see no simpler form.

**Cost to state honestly.** Everything a handler does without switching dispatcher runs on that one slot, so an
app handler that blocks a thread stalls every other handler and, at the next notification, the read loop itself
(the barrier waits behind it). That is the price of the guarantee, it predates this PR, and it is not documented
on the public handler KDoc.

**Comment nit.** `P:60-69` explains production code by what tests do with it. The first sentence is the contract;
the rest belongs in the test files it names.

### 3. Requests have no barrier (optional, not a simplification in lines)

Today `P:466-470` starts a request handler and moves on, while `P:472-477` waits for a notification's first part.
The asymmetry is documented in three places (`P:44-47`, `R:25-30`, AGENTS.md "Read-loop ordering and hooks").

**Interleaving.** Wire order `request_permission(child C)`, then the response to the client's `session/close`. The
read loop can complete the close before the permission handler starts; the handler then finds no holder and answers
`-32002` instead of `cancelled`. Both answers are acceptable after a close, so this is harmless. I found no caller
that needs the caveat, and none that is hurt by it.

**If the other reviewers find client code that exists only because a response hook can run in parallel with a
request handler's first part**, the fix is one line, and the caveat text goes:

```kotlin
is JsonRpcRequest -> {
    val slot = ResponseSlot(responseFrameQueued)
    slots += slot
    dispatchRequest(message, slot)
    withContext(handlerDispatcher) {}   // same barrier as for a notification
}
```

Cost: one thread hop per incoming request. A more thorough variant, running the read loop itself on
`handlerDispatcher` and using `yield()` as the barrier, would also serialize response hooks with handlers, but app
threads (`close()`, `cancel()`, the opening caller) touch the same client state anyway, so it would not let the
client drop its CAS. Not recommended.

### 4. `additionalContext` wrapper duplicates `handleRequest`'s cleanup: simplify

`P:345-356` wraps each handler in `withContext(additionalContext)` and, on `CancellationException`, calls
`onCompletion` for an outcome that was produced but lost at the dispatcher boundary. `handleRequest` then enters a
second `withContext` (`P:562`) and has the same recovery already: `outcome` is assigned inside the block, and the
`finally` at `P:598-604` calls `onCompletion`. No code in the repo passes an `additionalContext`.

Store the context with the handler and enter once:

```kotlin
private class RequestHandler(
    val context: CoroutineContext,
    val handle: suspend (JsonRpcRequest) -> RequestOutcome<JsonElement?>,
)

override fun setRequestOutcomeHandlerRaw(method, additionalContext, handler) {
    requestHandlers.update { it.put(method.methodName, RequestHandler(additionalContext, handler)) }
}

// in handleRequest
withContext(handler.context + JsonRpcRequestContextElement(request)) {
    outcome = handler.handle(request)
}
```

A cancellation at the boundary now lands in the `catch` at `P:570` (error reply) and the existing `finally`
(cleanup), exactly once. Tests: `BatchProtocolTest:193` and `:308` cover it; none should change.

### 5. Response hook: keep; strip-on-cancel: drop

**The hook is real.** Wire order `response(session/load)`, `update(live)`. Without the hook, `handleResponse`
completes the deferred and the read loop takes the update before the caller resumes on its own dispatcher. The
client can then no longer tell whether the update preceded the answer, which `subagents.mdx:508` requires it to
know. Rust solves the same problem the same way (`ordering.rs:23-35`). The implementation is nine lines
(`P:630-638`) and I would not touch it.

**The strip is redundant.** `P:215-221` swaps the map entry for a copy without the hook when the caller is
cancelled, so a response that arrives in the graceful wait skips the hook. That forces `P:258-259` to match by
deferred instead of by entry. But the hook's own contract says "it may have run for a call that then ends with
`CancellationException`" (`P:186`), and all three callers undo it in their failure path, after `sendRequestRaw`
has returned and therefore after the graceful wait:

- `Client.kt:664-667` and `:752-755`: `failSessionTree` drops the queued marker;
- `Client.kt:694-705`: reads `claimed` and fails the claimed holder.

So a hook that runs during the graceful wait is undone by the same code that undoes one that ran a microsecond
before the cancellation. The M3 review accepted the swap as correct (`review-m3-opening-boundary.md`, choice 1); it
is correct, it just buys nothing. Without it:

```kotlin
} catch (ce: CancellationException) {
    withContext(NonCancellable) { awaitCancelledAnswer(requestId, response) }
    throw ce
} finally {
    pendingOutgoingRequests.update { it.remove(requestId) }
}
```

`OutgoingRequest` no longer needs `copy`. Tests: `ReadLoopOrderingTest:135`
(`hookDoesNotRunForACancelledCallEvenIfItsResponseArrivesDuringTheGracefulWait`) inverts or goes; the KDoc sentence
"It doesn't run for … a cancelled call" (`P:185`) is reworded.

**Graceful wait.** The wait at `P:231-252` has five catch arms and one outcome: log, then `response.cancel()`.
Nothing reads which arm ran. Same behaviour in a third of the lines:

```kotlin
private suspend fun awaitCancelledAnswer(requestId: OutgoingRequestId, response: CompletableDeferred<JsonElement>) {
    if (!protocolJob.isActive) return
    runCatching { AcpMethod.MetaMethods.CancelRequest(this, CancelRequestNotification(requestId.id)) }
        .onFailure { return }
    if (response.isCancelled) return

    // Any outcome ends the wait: the answer, -32800, another error, or the timeout.
    val answer = runCatching { withTimeoutOrNull(options.gracefulRequestCancellationTimeout) { response.await() } }
    logger.trace { "Cancelled request $requestId ended with $answer" }
    response.cancel()
}
```

This loses the `warn` for an unexpected error code. Whether the wait should exist at all (it delays every local
cancellation by up to a second when the peer is slow) is a `master` behaviour and a separate question.

### 6. Batches are what complicates the response path (user decision, not this PR)

- JSON-RPC 2.0 allows batches and wants the replies in one array. That, and only that, is why a reply is a
  `ResponseSlot`, why slots share a `responseFrameQueued` deferred parented to `protocolJob` (`P:454`), why a
  collector coroutine per frame awaits every slot (`P:490-516`), and why `handleRequest` waits in `NonCancellable`
  for that deferred before `afterResponse` (`P:580-585`).
- ACP v2: "ACP does not use JSON-RPC 2.0 batches. Senders **MUST NOT** send a JSON array of messages, and receivers
  reject one with a single `-32600`" (`docs/protocol/v2/transports.mdx:65-67`). v1 only says it follows JSON-RPC 2.0
  (`v1/overview.mdx:12`).
- Rust rejects an array for every version (`jsonrpc/transport_actor.rs:28-33`).
- In this repo, `sendBatchRequestRaw` has no caller outside tests, and `README.md:349` advertises the feature.

Without batches a handler would send its own reply, and items 11-13 collapse:

```kotlin
val reply = try { … JsonRpcSuccessResponse(…) } catch (t: Throwable) { JsonRpcErrorResponse(…) }
sendFrame(TransportFrame.Single(reply))          // queue acceptance
if (reply is JsonRpcSuccessResponse) {
    outcome.onResponseQueued?.invoke()
    outcome.afterResponse?.invoke()
}
```

This is a public API removal on a `master` feature, so it is a follow-up for the user, not a recommendation for
this branch. **Separate finding, should-fix, confirmed by reading:** nothing in `Protocol` or the transports
consults `negotiatedProtocolVersion` for framing (its only reader is `client/v2/Client.kt:158`), so a v2 connection
accepts and answers a batch where the spec requires one `-32600`.

### 9. `settle()`: spec-required, but the atomic is not

v2 `cancellation.mdx:34`: "Receivers **MUST** ignore a `$/cancel_request` for a request ID that is unknown or
already answered". The entry stays in `pendingIncomingRequests` through `afterResponse`, so "already answered" needs
its own test; that part is necessary.

The `AtomicBoolean` with `compareAndSet` (`P:687-703`) is not. Its two writers are:

- `settle()`, from `slot.response.invokeOnCompletion` (`P:539`), which fires inside `slot.response.complete(…)` at
  `P:569`/`P:571`, in the handler coroutine, or at `P:545`, when the job is already complete;
- `cancelByCounterpart`, from the `$/cancel_request` notification handler's non-suspending body (`P:133-155`).

Both run on `handlerDispatcher`, one task at a time, and `ensureActive(); …complete(…)` at `P:566-569` has no
suspension between check and completion. So they cannot interleave, and the flag is exactly
`slot.response.isCompleted`:

```kotlin
internal class IncomingRequest(val handlerJob: Job, val reply: Deferred<JsonRpcResponse>)

// in the $/cancel_request handler
if (incoming.reply.isCompleted) {
    logger.debug { "Ignoring CancelRequest for request ${request.requestId}: it has already been answered" }
} else {
    incoming.handlerJob.cancel(IncomingRequestCancelledException("Cancelled by the counterpart", incomingRequestId))
}
```

`P:539` and both methods go. Tests `BatchProtocolTest:231`, `:251`, `:712` keep passing unchanged. The caveat: this
leans on item 1, which the class already calls load-bearing.

### 13. `onResponseQueued`: keep, but know what it buys

Its one user closes the v1 load replay window (`Agent.kt:453-457`). The alternative is `endReplay()` as the first
statement of `afterResponse`.

**Interleaving the hook prevents.** The transport accepts the load response in the collector coroutine
(`P:494`). `afterResponse` starts only after the handler coroutine is redispatched to the handler slot (`P:580`).
In between, app code on another thread sends a child's `SubagentUpdate` with a cancel grant. It follows the
response on the wire, so the client treats it as current, but the agent still files it as history and will not
route the client's `session/cancel` for that child. The window is one dispatch long, longer if the slot is busy.

So the race is real but narrow, and it needs app code that sends child updates from outside the replay callback
while the load is being answered. The hook costs ~25 lines here and a fourth field on a public `@UnstableApi`
class. The second reason given at `Agent.kt:449-451` (collections must not wait for a slow `postInitialize()`) does
not need the hook; calling `endReplay()` before `postInitialize()` inside `afterResponse` does that too.

Verdict: keep. Its shape is a user decision, it is correct, and the agent reviewer is better placed to say whether
the window matters. If batches go (§6), it becomes one line.

Open nit from the hand-over notes, still true: `successQueuedHook()` re-checks for a success reply (`P:736-737`)
although `onSuccessQueued` is set only on the success path (`P:568`). The field alone is enough.

### 7, 16. Send-path cleanup and shutdown: already minimal

The senders put the entry in the map (`P:200`, `P:287`) and only then check the protocol job (`P:335`). `close()`
cancels the job (`P:389`) and only then clears the maps (`P:390-391`). Whichever way the two interleave, either
the sender sees a cancelled job or `close()` sees the entry; nothing leaks. The `finally` blocks cover the
remaining paths. I would not restructure this.

Outgoing ids come from `requestIdCounter` (`P:196`, `P:273`) and are never reused, so the identity guards at
`P:218`, `P:259` and `P:319` can be plain removals. The guard at `P:547` must stay: the peer chooses incoming ids,
and `cancelPendingIncomingRequest` frees an id before its job has finished (`BatchProtocolTest:781`).

### `handleRequest` readability (no behaviour change, line-neutral)

`P:554-605` nests a `try` in a `try` with a `return` from the inner `catch`. Splitting at the reply reads better and
keeps the single `finally`:

```kotlin
private suspend fun handleRequest(request: JsonRpcRequest, slot: ResponseSlot) {
    var outcome: RequestOutcome<JsonElement?>? = null
    try {
        outcome = replyTo(request, slot) { outcome = it } ?: return
        if (awaitFrameQueued(slot)) outcome.afterResponse?.invoke()
    } catch (t: Throwable) {
        if (t !is CancellationException) logger.error(t) { "After-response work failed for ${request.method}" }
    } finally {
        runCatching { outcome?.onCompletion?.invoke() }.onFailure { … }
    }
}
```

Only worth doing together with §4.

## CAS versus locks

Primitives available in `commonMain`: atomicfu 0.25.0 (`gradle/libs.versions.toml:13`), so
`kotlinx.atomicfu.locks.SynchronizedObject`/`synchronized` (already used in `agent/internal/RootSessionWrapper.kt:62`
and `SubagentSessionWrapper.kt:55`), and `kotlinx.coroutines.sync.Mutex` (coroutines 1.9.0, used in
`client/ClientNegotiator.kt:105`). `Mutex` suspends, so it cannot guard anything the read loop or a hook touches.
`protocol/` has no lock today.

| Site | Guards | Lambda pure and cheap? | Contention realistic? | Recommendation |
|------|--------|------------------------|-----------------------|----------------|
| `P:196`, `P:273` `incrementAndGet` | request id counter | n/a | yes, harmless | keep |
| `P:200` `update { put }` | outgoing table, register | yes | low: callers vs the read loop | keep CAS |
| `P:217-220` `update { copy(onResponse = null) }` | hook strip | yes | low | **remove the site** (§5) |
| `P:259` `update { remove if same deferred }` | outgoing table, cleanup | yes | low | keep CAS, unconditional `remove(requestId)` |
| `P:287` `update { putAll }` | outgoing table, batch register | yes | low | keep CAS |
| `P:315-322` `update { mutate { response.cancel(); remove } }` | batch cleanup | **no**: cancels deferreds inside the lambda. Idempotent, so harmless on a retry, but it is the pattern to avoid | low | **restructure**: cancel first, then a pure removal |
| `P:358`, `P:371` `update { put }` | handler tables | yes | none (registration at init) | keep CAS |
| `P:115` `compareAndSet(null, v)` | negotiated version | n/a | none | keep |
| `P:408`, `P:442` `getAndUpdate { clear() }` | drain a table, then cancel outside | yes | only at shutdown | keep CAS: snapshot-then-act is exactly "never complete a deferred under a lock" |
| `P:424-429` `getAndUpdate { incoming = it[id]; remove }` | take one incoming entry | **no**: writes a captured local. Correct, since the last run is the one that wins, but needlessly subtle | low | **restructure** as `getAndUpdate { it.remove(id) }[id]`, like `P:626` |
| `P:525-527` `getAndUpdate { put if absent }` | incoming id reservation | yes | none (read loop only writes) | keep CAS |
| `P:547` `update { remove if same }` | incoming cleanup | yes | low | keep CAS |
| `P:626` `getAndUpdate { remove }` | take the outgoing entry; hot path, once per response | yes | low | keep CAS |
| `P:687-703` atomic flag, `compareAndSet` | "reply collected" | n/a | none: both writers are on the handler slot | **remove**, read `reply.isCompleted` (§9) |
| `P:729` plain `var onSuccessQueued` | hook hand-over to the collector | n/a | written before `response.complete`, read after `await` | keep; no synchronization needed |
| `Protocol.extensions.kt:21` plain `var decoded` | decoded response hand-over | n/a | written before the deferred completes, read after `await` | keep |

**Batch cleanup, restructured:**

```kotlin
} finally {
    outgoing.values.forEach { it.response.cancel() }
    pendingOutgoingRequests.update { pending -> pending.mutate { it.keys.removeAll(outgoing.keys) } }
}
```

**Overall.** Keep CAS everywhere in `protocol/`. Every table is a persistent map with an O(log n), allocation-light
lambda, retries are rare (a caller thread against the read loop), and after the two restructurings above every
lambda is pure. A lock would add a primitive without removing a line, and the drain sites (`P:408`, `P:442`) would
need the same copy-out step to cancel jobs outside it. The risky CAS pattern the question describes (side effects
or app code in a retried lambda) appears twice here, both benign, both fixable in two lines.

## Tests that would change

- §5: `ReadLoopOrderingTest:135` inverted or removed.
- §3, if taken: one new case next to `ReadLoopOrderingTest:305` (a response after a request waits for the handler's
  first part); `:331` unchanged.
- §4, §9, graceful-wait and CAS hygiene: none expected; `BatchProtocolTest` and `ReadLoopOrderingTest` are the
  safety net.
- §6: most of `BatchProtocolTest` (follow-up, user decision).

## Follow-ups and open questions

- v2 batch conformance gap (§6), should-fix, outside this goal.
- Whether batch support stays at all: user decision.
- For the client reviewer: does any client code exist only because a response hook can run in parallel with a
  request handler's first part (§3)?
- For the agent reviewer: does the one-dispatch window of §13 matter enough to keep `onResponseQueued`?
- The blocking-handler cost of item 1 is undocumented on the public handler KDoc.
