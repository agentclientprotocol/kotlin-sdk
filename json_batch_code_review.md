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
| 5 | MEDIUM | Resolved: caller-controlled timeout documented and batch cleanup verified |
| 6 | LOW | Resolved: reject reuse of active incoming request IDs |
| 7 | LOW | Resolved: idempotent shutdown and cancellation of response queueing |
| 8 | LOW | Resolved: migration guide restored; review retained as the working checklist |
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

## 5. MEDIUM — Resolved: caller-controlled timeout and batch cleanup

`sendBatchRequestRaw` now documents that it waits for every request's response with no built-in
deadline. Callers requiring bounded completion should wrap the entire call in `withTimeout`.
This follows the existing `ProtocolOptions.requestTimeout` deprecation ("Use coroutine timeouts").
A normal return contains all request results; a notification-only batch returns an empty list
after queue acceptance.

An uncorrelated `id: null` error still cannot fail a particular batch: attributing it to concurrent
requests would be unsafe. A timeout or other local cancellation throws for the whole operation,
without returning partial results. The existing `finally` cancels the batch's deferreds and removes
its entries from `pendingOutgoingRequests`. Cancellation notifications for unfinished requests are
best effort, and failure to send them does not prevent local cleanup or cancel unrelated calls.

`BatchProtocolTest.externalBatchTimeoutCleansPendingRequestsEvenWhenCancellationSendFails` uses
virtual time to verify an external `withTimeout` after an uncorrelated error and a partial response.
It checks that all remaining batch entries are removed before the timeout reaches the caller,
only unfinished requests receive cancellation notifications, and an unrelated request remains
registered and completes normally. The same checks cover failure to send cancellation notifications.

Verified with `:acp:jvmTest --tests "*BatchProtocolTest*"` (18 tests passed) and `:acp:apiCheck`.
No runtime behavior or API signatures changed.

---

## 6. LOW — Resolved: reject reuse of active incoming request IDs

`pendingIncomingRequests` keeps one job per incoming ID. Registration atomically checks whether
that ID is already present. A duplicate receives an Invalid Request (`-32600`) response with the
same ID and message "Request ID is already in use"; its handler never starts. The original job
remains registered and can still be cancelled by ID, by bulk cancellation, or by the peer.

Duplicate rejection applies to IDs still registered in `pendingIncomingRequests`. Existing
cancellation behavior is preserved: cancellation by ID and by the peer removes the entry before
cancelling the job; bulk cancellation clears the map first. An ID can then be reused while the old
job finishes cleanup. Its completion callback checks job identity, so it cannot remove a newer
registration. Normal completion also releases the ID. Batches preserve valid siblings and include
an error response for each rejected duplicate.

[JSON-RPC 2.0](https://www.jsonrpc.org/specification#request_object) defines ID types and response
correlation but does not explicitly prescribe a duplicate-ID rejection rule. Rejecting concurrent
reuse with `-32600` is the SDK's policy, rather than a mandated duplicate-specific error code.
It avoids ambiguous dispatch and cancellation; the old request is never replaced.

Regression coverage checks duplicate rejection within one batch and across frames, all three
cancellation paths, immediate reuse after cancellation, protection against late completion removing
a newer registration, and reuse after normal completion.

---

## 7. LOW — Resolved: shutdown rejects response work without fatal error handling

`Protocol.close()` now starts once, cancels pending incoming/outgoing requests and the protocol
scope before closing the transport, and closes the transport in `finally`. Repeated or re-entrant
calls do not close it again. Response collectors check cancellation and protocol shutdown before
queueing a reply.

A response send that races a closing/closed transport cancels the queue acknowledgement and logs
at trace level. Follow-up work is skipped, outcome cleanup still runs, and this path does not call
`close()` again. A send failure while the protocol and transport are active still logs an error
and closes the protocol.

Regression tests verify cancellation before transport closure, re-entrant and repeated close,
and response-send failures at both CLOSING and CLOSED. Existing tests retain coverage of active
transport failures and prepared-outcome cleanup.

---

## 8. LOW — Resolved: migration guide restored

The README's `json_batch_migration.md` target now exists. The guide documents custom frame-aware
transports, receive-only malformed frames, response types, explicit outgoing batches, and
caller-controlled timeout/cancellation semantics.

The earlier suggestion to remove this review file is withdrawn as a correctness finding. It is
being used as the working checklist for this review; its presence is a repository housekeeping
choice. Public migration documentation now lives separately from these review notes.

---

## Verification of low-severity fixes

`:acp:jvmTest --tests "*BatchProtocolTest*" --tests "*StdioTransportFlowTest*"`
`--tests "*ClientSessionTest*"` passed all 44 tests. `:acp:apiCheck` passed.
The stdio/WebSocket integration run, `:acp-ktor-test:jvmTest --tests "*ProtocolTest*"`
`--tests "*V2ClientTest*" --tests "*WebSocketTransportTest*"`, passed 105 tests.

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
