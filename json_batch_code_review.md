# Code review — JSON-RPC frame/batch migration

Scope: `eugenethedev/json-rpc-batch-handling` vs `master` (`git diff master...HEAD`, 35 files).

Reassessed at `b9a5951` ("improve Protocol and RpcMethodOperations structure") after the
`Serialization.kt` / `RpcMethodsOperations.kt` split and the `Protocol` rework. Every finding below
was re-verified against the current sources; locations and descriptions are updated, two findings
are resolved, and two new ones were found during the reassessment.

Verification aid: `./gradlew :acp:jvmTest --tests "*BatchProtocolTest*" --tests "*StdioTransport*"`
passes; stderr from that run is cited as evidence where relevant.

| # | Severity | Status                                                                                   |
|---|----------|------------------------------------------------------------------------------------------|
| 1 | HIGH | Resolved: bounded graceful WebSocket shutdown                                            |
| 2 | MEDIUM | Withdrawn: parse-error responses are correct; noisy stdout violates ACP                  |
| 3 | MEDIUM | Resolved: synchronous send failures documented and callers audited                       |
| 4 | MEDIUM | Resolved: New (found during reassessment)                                                |
| 5 | MEDIUM | Still valid, unchanged                                                                   |
| 6 | LOW | Narrowed: the orphaned job is cancelled by `close()`, cancellation by ID still misses it |
| 7 | LOW | Narrowed to a shutdown race, but its blast radius grew (see 4)                           |
| 8 | LOW | Changed: the plan file is gone, the README link it left behind is broken                 |
| — | resolved | `Malformed.raw` (old finding 7) — the property no longer exists                          |

---

## 1. HIGH — Resolved: `WebSocketTransport.close()` drains queued outgoing frames

`close()` now rejects new sends, enters `CLOSING`, and gives the writer up to five seconds
to drain accepted frames and send/flush a normal WebSocket Close frame. The socket is aborted
if shutdown times out or is cancelled (and on a writer failure). Transport cleanup then sets
`CLOSED` and fires close listeners once. Closing before `start()` also drains accepted frames.

Regression coverage in `WebSocketTransportTest` verifies ordered delivery and a normal close
reason with a Ktor peer, draining after reader EOF, close-before-start behavior, send rejection,
idempotent close notifications, parent cancellation, and bounded cleanup when sending, flushing,
or sending the Close frame stalls.

Verified with `:acp-ktor-test:jvmTest --tests "*WebSocketTransportTest*"` and `:acp-ktor:apiCheck`.

---

## 2. MEDIUM — Withdrawn: responding to unparseable input is correct

The previous recommendation to skip unparseable stdio lines or make parse-error responses opt-in
is withdrawn. The compatibility difference from `master` is real, but it is not a correctness bug:

- [JSON-RPC 2.0, sections 5 and 7](https://www.jsonrpc.org/specification) define `-32700`
  (Parse error) for invalid JSON received by the server, require `id: null` when the request ID
  cannot be detected, and show a parse-error response to malformed input. Valid JSON that is not
  a valid request instead produces `-32600` (Invalid Request).
- [ACP stdio transport](https://agentclientprotocol.com/protocol/transports#stdio) delimits
  messages with newlines and explicitly says the agent MUST NOT write anything to stdout that
  is not a valid ACP message. Logs belong on stderr. The client has the corresponding restriction
  on what it writes to the agent's stdin.

Keep `StdioTransport` forwarding `parseTransportFrame(line)` and `Protocol` replying to malformed
input that is not identifiable as a response. Keep suppressing replies to recognized responses
and valid notifications; the notification exception does not make arbitrary invalid input a
notification. The existing transport test also verifies that valid input is still processed after
malformed lines.

On `master`, undecodable lines were logged and skipped, with an additional attempt to recover JSON
following a garbage prefix. That is tolerance for nonconforming peers, not a reason to suppress
protocol errors by default. Any future compatibility mode should be justified by a concrete peer
requirement and documented as such.

Blank-line handling is a separate framing-policy question: JSON-RPC itself is transport agnostic,
and ACP does not specify an empty-line exception. Ignoring empty/whitespace-only lines could be an
explicit tolerance policy, but is not a required fix. A normal newline terminating a valid message
is its delimiter; an additional blank line is a separate empty frame in this implementation.

---

## 3. MEDIUM — Resolved: notification send failures are part of the public contract

Synchronous failure remains intentional: no response is expected for a notification, but encoding
or accepting it into the writer queue can still fail. Silently swallowing that failure would hide
lost updates from the caller.

`RpcMethodsOperations.sendNotificationRaw`, typed `sendNotification`, and the notification method
`invoke` operator now document synchronous encoding and transport failures. The raw and typed send
KDocs clarify that successful return acknowledges queue acceptance, not a physical flush or peer
receipt. `Transport.send` also explicitly documents encoding failures and queue rejection while
closing or closed. The raw API advises cleanup callers to preserve required local cleanup when a
send fails.

Caller audit:

- v1 agent session updates propagate send failures through `RemoteClientSessionOperations.notify`;
  `Agent.SessionWrapper.prompt` clears active-prompt state in `finally`.
- v2 streamed updates propagate send failures from `RequestOutcome.afterResponse`;
  `RequestOutcome.onCompletion` still clears active-prompt state in the protocol's `finally`.
  The uncaught-exception behavior after response delivery remains finding 4.
- Single and batch outgoing-request cancellation already sends `$/cancelRequest` best effort
  using `runCatching`, with pending-request removal in `finally`.
- v2 `ClientSession.cancel` had a local cleanup gap: a failed notification send bypassed its
  permission-cancellation signal. That signal now completes in `finally`, releasing pending
  permission handlers while preserving the send exception for the caller. A regression test
  verifies both behaviors with a closed scripted transport.

Verified with `:acp:jvmTest --tests "*ClientSessionTest*"`
`--tests "*StdioTransportFlowTest*" --tests "*BatchProtocolTest*"` (37 tests passed) and `:acp:apiCheck`.
API signatures are unchanged.

---

## 4. MEDIUM — NEW Resolved: handler follow-up failures escape to the uncaught-exception handler

`Protocol.kt:480-493`, `Protocol.kt:55-56`

```kotlin
} catch (t: Throwable) {
    // The response is already finalized: a continuation must never send a second response.
    if (t !is CancellationException) {
        logger.error(t) { "After-response work failed for ${request.method}" }
    }
    throw t                      // Protocol.kt:485
} finally {
    try {
        outcome?.onCompletion?.invoke()
    } catch (t: Throwable) {
        logger.error(t) { "Outcome cleanup failed for ${request.method}" }
        throw t                  // Protocol.kt:492
    }
}
```

`handleRequest` runs in `handlerScope`, which carries a `SupervisorJob` and no
`CoroutineExceptionHandler` (`Protocol.kt:55-56`) — so any exception it rethrows goes to the
platform's uncaught-exception handler. On `master` the equivalent work was swallowed:
`for (handler in requestHolder.handlers) { runCatching { handler() }.onFailure { logger.error(...) } }`
(`master:Protocol.kt:462-471`).

Reproduced by the current test suite, which passes while dumping the exception to stderr:

```
[... @Protocol#31] ERROR ...Protocol - After-response work failed for MethodName(name=initialize)
java.lang.IllegalStateException: stream failed
Exception in thread "DefaultDispatcher-worker-2 @Protocol#31" java.lang.IllegalStateException: stream failed
```

(from `BatchProtocolTest.mappingFailureCleansOutcomeAndContinuationFailureCannotSendSecondResponse`;
`enqueueFailureCleansOutcomeAndClosesProtocol` produces the same pattern for a queue failure that
arrives through `slot.responseFrameQueued`.)

On the JVM this is stderr noise that consumers cannot intercept without installing a handler on
their own `parentScope`. On Android it reaches `Thread.UncaughtExceptionHandler` and crashes the
process; on Kotlin/Native it terminates the program; on JS it surfaces as an unhandled rejection. A
failing v2 `session/update` stream or a throwing user `onCompletion` should not be able to do that.

The rethrow also buys nothing: the reply is already finalized by then, and
`dispatchRequest`'s `invokeOnCompletion` only fills `slot.response` when it is not yet completed
(`Protocol.kt:446-453`). Log and swallow (as `master` did), or install a
`CoroutineExceptionHandler` on `handlerScope`.

---

## 5. MEDIUM — `sendBatchRequestRaw` hangs forever when the peer does not reply to every request

`Protocol.kt:229-236`, doc at `RpcMethodsOperations.kt:74-89`

```kotlin
return outgoing.map { (_, request) ->
    try { Result.success(request.deferred.await()) } catch (e: JsonRpcException) { ... }
}
```

Unchanged. There is still no per-request completion guarantee and no timeout. The realistic failure
mode is a peer that does not implement batching: it replies with a *single*
`{"id":null,"error":{"code":-32600}}` object. `handleResponse` cannot correlate `RequestId.Null`,
logs `"Received response for unknown request ID: null"` (`Protocol.kt:535`), and drops it — so
`sendBatchRequestRaw` blocks indefinitely with no failure.

`BatchProtocolTest.unknownDuplicateAndNullResponseIdsCannotResolveAnotherCall`
(`BatchProtocolTest.kt:373-399`) now pins this: after an uncorrelated `id: null` error it asserts
`assertFalse(operation.isCompleted)`. Not resolving another call from an `id: null` error is
correct; leaving the batch with no completion path is what remains unaddressed.

The KDoc still says only "The caller must know the peer accepts batches"
(`RpcMethodsOperations.kt:82`). Since the function is `suspend`, caller-side `withTimeout` works —
either document it as mandatory, or complete all outstanding deferreds when an uncorrelated
`id: null` error response arrives while a batch is in flight.

---

## 6. LOW — Duplicate incoming request IDs make the older handler uncancellable by ID

`Protocol.kt:440-456`, `Protocol.kt:328-334`

```kotlin
val job = handlerScope.launch(start = CoroutineStart.LAZY) { handleRequest(request, slot) }
pendingIncomingRequests.update { it.put(requestId, job) }

job.invokeOnCompletion { cause ->
    ...
    pendingIncomingRequests.update { if (it[requestId] === job) it.remove(requestId) else it }
}
```

Downgraded from MEDIUM. The `put` still evicts an older in-flight job with the same ID, so
`$/cancelRequest` and `cancelPendingIncomingRequests()` for that ID reach only the newest job and
the older one cannot be cancelled by ID. The identity guard in `invokeOnCompletion` is the part
that is now correct and tested
(`BatchProtocolTest.reusedIdCompletionDoesNotUnregisterTheNewerJob`, `BatchProtocolTest.kt:322-352`).

Two claims from the first review no longer hold and are withdrawn:

- "it keeps running after the protocol is closed" — `handlerScope`'s `SupervisorJob` is a child of
  `scope`'s job (`Protocol.kt:52-56`), and `close()` ends with `scope.cancel(message)`
  (`Protocol.kt:320`), so the orphaned job *is* cancelled on close.
- The batch test `mixedBatchKeepsInvalidSiblingsAndDuplicateIds` (`BatchProtocolTest.kt:64-81`) is
  not evidence of a leak: both duplicates run to completion and both replies are emitted.

What is left is a peer that reuses an ID while the first request is still in flight (a JSON-RPC
violation) plus a `cancelPendingIncomingRequests()` call that is expected to drain everything —
e.g. `V2ClientTest.kt:335`, `ProtocolTest.kt:440` — which would silently skip the older job. Keying
by `(id, job)` / storing a list, or rejecting a duplicate in-flight ID with `-32600`, would close it.

---

## 7. LOW — A send failure during shutdown is treated as fatal, producing ERROR noise and a re-entrant `close()`

`Protocol.kt:315-321`, `Protocol.kt:420-437`

```kotlin
public fun close() {
    transport.close()
    val message = "Protocol closed"
    cancelPendingIncomingRequests(CancellationException(message))
    cancelPendingOutgoingRequests(CancellationException(message))
    scope.cancel(message)
}
```

The ordering flagged in the first review is unchanged: the transport is closed before in-flight
incoming requests are cancelled, and a plain `CancellationException` maps to a `CANCELLED` error
response (`Exceptions.kt:62`), which the frame collector then tries to send on the closed
transport — logging ERROR at `Protocol.kt:433` and recursively calling `close()`.

Narrowed, though: the first review said this happens on "every normal shutdown or EOF with an
in-flight incoming request". It does not. `close()` reaches `scope.cancel(message)` a few
instructions after `cancelPendingIncomingRequests`, while resolving the slot needs a dispatch to
`handlerDispatcher` and the collector needs another to resume — so the collector is normally
cancelled first and `it.response.await()` throws `CancellationException`, which the
`catch` at `Protocol.kt:430-436` deliberately does not log.
`BatchProtocolTest.closeReleasesPreparedOutcomesAndOutgoingWaiters` (`BatchProtocolTest.kt:232-246`)
exercises exactly this path and produced no such ERROR in the verification run.

The race is still real, and it does not even need the cancellation path: a handler that finishes
normally at the same moment as EOF/`close()` will attempt `transport.send` on a closed transport
and get the same fatal treatment. With finding 4 in play the cost is higher than log noise —
`queued.completeExceptionally(ClosedSendChannelException)` propagates into
`slot.responseFrameQueued.await()` and is rethrown out of `handleRequest`, so a benign shutdown can
surface as an uncaught exception.

Cancel pending requests before closing the transport, and treat a send failure on an
already-closing transport as trace-level rather than "log ERROR and close".

---

## 8. LOW — README links to a deleted document, and the review file itself is now committed

`README.md:358`

```markdown
JSON-RPC batches are supported by the shared protocol and both built-in transports. See the
[frame/batch migration guide](json_batch_migration.md) for custom transport changes and explicit
outgoing batches.
```

The original finding (a 281-line `json_batch_plan.md` staged for commit) is resolved — both
`json_batch_plan.md` and `json_batch_migration.md` were added in `88f1b2c` ("wip") and deleted
later in the branch. But the README paragraph that pointed at the migration guide survived, so the
branch ships a user-facing broken link (`ls json_batch*` at HEAD: only this review file).

Two knock-on notes:

- Findings 3 and 5 previously leaned on that guide's wording ("closed queue must reject sends"
  applies only to custom transports; "the caller must know the peer accepts batches"). With the
  guide gone, the corresponding contracts exist *nowhere* — see the doc gaps called out in those
  findings.
- `json_batch_code_review.md` (this file) is itself part of `git diff master...HEAD` now. Same
  concern as the original finding: scratch review output in the repo root. Either drop it before
  merge, or replace it with the migration guide the README expects.

---

## Resolved since the first review (no action)

- **`Malformed.raw` was re-serialized, not original text** (old finding 7). `TransportFrame.Malformed`
  no longer carries `raw` at all (`TransportFrame.kt:60-63`), and no KDoc claims raw-input
  retention, so the inconsistency between the single-frame and batch-entry paths is gone. Frame
  relays no longer have access to the original text — a deliberate narrowing, not a bug.
- **`json_batch_plan.md` staged for commit** (old finding 8) — file deleted; see finding 8 for what
  the deletion left behind.

---

## Verified as correct (re-checked at `b9a5951`, no action)

- `queued` is created as a child of the protocol scope's `SupervisorJob`
  (`Protocol.kt:384`), so `completeExceptionally` cannot fail the scope and a pre-cancelled scope
  still releases `slot.responseFrameQueued.await()`.
- `RequestOutcome.onCompletion` cannot be double-invoked: the wrapper in
  `setRequestOutcomeHandlerRaw` (`Protocol.kt:274-285`) only sees an `outcome` when `handler`
  returned, and `handleRequest`'s local `outcome` (`Protocol.kt:466`) is only assigned when that
  wrapper returned normally. `mapResponse`'s failure path (`RpcMethodsOperations.kt:108-113`) is
  likewise disjoint from both.
- Malformed *response-only* envelopes (`isResponse = true`) are never answered
  (`Protocol.kt:389`, classification at `Serialization.kt:198`), so the `-32700`/`-32600` replies
  cannot ping-pong between two SDK peers.
- Ordering of v1 in-request session updates versus the final response is preserved: updates are
  enqueued on the transport channel before `slot.response.complete(...)`, and `afterResponse` is
  gated on `slot.responseFrameQueued` (`Protocol.kt:470-479`).
- Lazy-start + register-before-`start()` in `dispatchRequest` (`Protocol.kt:443-455`) correctly
  handles a `$/cancelRequest` that races the dispatch — the slot resolves through
  `invokeOnCompletion` and `JsonRpcIncomingRequestCanceledException` maps to no reply
  (`Exceptions.kt:56`). Covered by `BatchProtocolTest.cancellationBeforeHandlerEntryDoesNotStrandBatch`.
- Batch reply shape: a `Batch` frame is answered with a `Batch` (even for a single reply) and a
  single frame with a single object (`Protocol.kt:422-428`), matching
  `standaloneErrorsAndSingletonBatchKeepTheirShape`.
