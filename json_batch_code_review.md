# Code review — JSON-RPC frame/batch migration

Scope: staged working-tree changes implementing `json_batch_migration.md`
(`git diff HEAD`, 31 files). Findings are ordered by severity.

---

## 1. HIGH — `WebSocketTransport.close()` discards queued outgoing frames and aborts the socket

`acp-ktor/src/commonMain/kotlin/com/agentclientprotocol/transport/WebSocketTransport.kt:51`

```kotlin
override fun close() {
    if (!sendChannel.close()) return
    _state.value = Transport.State.CLOSED
    scope.cancel()
    wss.cancel()
    fireClose()
}
```

`sendChannel.close()` alone would let the writer drain buffered frames and then exit;
`scope.cancel()` on the very next line kills the writer immediately, so every frame still
sitting in the UNLIMITED channel is silently dropped. `wss.cancel()` then aborts the socket
without the WebSocket Close handshake that the previous implementation performed
(`wss.close(CloseReason(NORMAL, ...))` / `wss.flush()`).

Concrete scenario: a handler queues its final response or a `session/update` notification
(`send` is `trySend` — it returns before anything is written), the app then calls
`Protocol.close()`. `Protocol.close()` calls `transport.close()` first, so the response is
dropped and the peer sees an abnormal closure instead of a normal one. The same path is now
reached automatically: `Protocol.start()`'s reader loop has `finally { close() }`, so remote
EOF also tears down the writer mid-drain.

Fix: let the writer drain (`for (frame in sendChannel)` until exhausted, then
`wss.close(CloseReason(NORMAL, ...))`), and only cancel the scope as a bounded fallback.

---

## 2. MEDIUM — Blank / non-JSON stdout lines now produce outgoing error responses

`acp/src/commonMain/kotlin/com/agentclientprotocol/transport/StdioTransport.kt:104`,
`acp-model/src/commonMain/kotlin/com/agentclientprotocol/rpc/TransportFrame.kt:36`,
`acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.kt:477`

Before, the read loop did `try { decodeJsonRpcMessage(line) } catch { return@collect }` — an
undecodable line was logged at trace and skipped — and `decodeJsonRpcMessage` even recovered
from a garbage prefix. Now every line becomes `TransportFrame.parse(line)`; a parse failure
yields `Malformed(raw, PARSE_ERROR, isResponse = false)`, and `handleIncomingFrame` answers
every non-response `Malformed` with `JsonRpcResponse(RequestId.Null, error = ...)`.

`sourceAsLineFlow` emits `""` for a blank line (`StdioTransport.kt:191`), and
`TransportFrame.parse("")` is `-32700`. JSON syntax acceptance follows kotlinx.serialization;
a bare token such as `garbage` is parsed and then rejected as an invalid envelope (`-32600`).
So a peer process that writes a trailing blank line, a banner, or any log line to stdout now
gets one unsolicited JSON-RPC error response with `id: null` per invalid line back
across the pipe. That is a live-interop regression against the very agents the old
garbage-prefix recovery existed for.

At minimum, skip blank/whitespace-only lines in the stdio read path, and consider making
"reply to unparseable input" opt-in for the line-oriented transport.

---

## 3. MEDIUM — `Transport.send` now throws out of the non-suspend public `sendNotificationRaw`

`acp/src/commonMain/kotlin/com/agentclientprotocol/transport/Transport.kt:33`,
`StdioTransport.kt:166`, `WebSocketTransport.kt:47`, `Protocol.kt:345`

`send` changed from best-effort (`sendChannel.trySend(message)`, result ignored) to
`trySend(frame).getOrThrow()`. `Protocol.sendNotificationRaw` is a **public, non-suspend**
method and is what every `sendNotification` / `AcpMethod...SessionUpdate(protocol, ...)` call
funnels into. Previously, emitting a session update after the peer disconnected was a silent
no-op; now it throws `ClosedSendChannelException` synchronously into arbitrary user code
(e.g. an `AgentSession` update emitter, or an `onCompletion`/cleanup path).

This also contradicts the migration guide, which scopes the "closed queue must reject sends"
change to *custom transports* and states that "Agent, Client, session and support interfaces …
are unchanged". Either document the new throwing contract for `sendNotificationRaw` and audit
its callers, or keep the throw at the `Transport` boundary and have `Protocol` swallow/log it.

---

## 4. MEDIUM — `sendBatchRequestRaw` hangs forever when the peer does not reply to every request

`acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.kt:322`

```kotlin
return outgoing.map { (_, request) ->
    try { Result.success(request.deferred.await()) } catch (e: JsonRpcException) { ... }
}
```

There is no per-request completion guarantee. The realistic failure mode is a peer that does
not implement batching: it replies with a *single* `{"id":null,"error":{"code":-32600}}`
object. `handleResponse` cannot correlate `RequestId.Null`, logs
`"Received response for unknown request ID"`, and drops it — so `sendBatchRequestRaw` blocks
indefinitely with no timeout and no failure. The guide says "the caller must know the peer
accepts batches", but a mistake here is an unrecoverable hang rather than an error. Consider
completing all outstanding deferreds when an uncorrelated `id:null` error response arrives
while a batch is in flight, or documenting the mandatory caller-side `withTimeout`.

---

## 5. MEDIUM — Duplicate incoming request IDs orphan the first handler job

`acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.kt:518-531`

```kotlin
pendingIncomingRequests.update { it.put(requestId, job) }
job.invokeOnCompletion { ...
    pendingIncomingRequests.update { if (it[requestId] === job) it.remove(requestId) else it }
}
```

If two in-flight requests share an ID, the second `put` evicts the first job from the map. The
first job is then unreachable: `$/cancelRequest` for that ID cancels only the second, and
`cancelPendingIncomingRequests()` (invoked by `Protocol.close()`) will not cancel it either, so
it keeps running after the protocol is closed — and its `slot.ready` will eventually resolve
into a send on a closed transport.

Batches make this concrete rather than theoretical: `BatchProtocolTest.mixedBatchKeepsInvalidSiblingsAndDuplicateIds`
deliberately sends `request(1)` twice in one batch. Consider keying pending incoming requests
by `(id, job)` or storing a list, or rejecting a duplicate in-flight ID with `-32600`.

---

## 6. LOW — `Protocol.close()` closes the transport before cancelling incoming requests, producing ERROR noise on every shutdown

`acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.kt:416-422`

```kotlin
transport.close()
cancelPendingIncomingRequests(CancellationException("Protocol closed"))
```

The cancellation causes each in-flight request's slot to resolve to a `CANCELLED` error
response (`requestFailure` maps a plain `CancellationException` to
`JsonRpcErrorCode.CANCELLED`). The collector job then calls `transport.send(...)` on the
already-closed transport, which now throws (finding 3), is logged at ERROR
(`"Unable to queue response frame"`, `Protocol.kt:511`) and recursively calls `close()`.

Every normal shutdown or EOF with an in-flight incoming request emits a spurious ERROR. Cancel
pending requests before closing the transport, or treat a send failure during shutdown as
trace-level.

---

## 7. LOW — `Malformed.raw` for batch entries is re-serialized, not the original text

`acp-model/src/commonMain/kotlin/com/agentclientprotocol/rpc/TransportFrame.kt:37`

```kotlin
Batch(value.map { parseEntry(it, it.toString()) })
```

For batch entries `raw` is `JsonElement.toString()`, i.e. a canonicalised re-serialization
(whitespace, key ordering and number formatting are lost); only the single-frame path keeps the
true input. The KDoc and the migration guide both claim "`Malformed` retains the raw input …
for relays", which is only true for non-batch frames. Either slice the original substring or
soften the claim.

---

## 8. LOW — Scratch planning document staged for commit

`json_batch_plan.md` (new file, 281 lines) is a working plan, not user-facing documentation,
and is staged alongside the code. `json_batch_migration.md` is referenced from `README.md` and
should stay; `json_batch_plan.md` should probably be dropped or moved out of the repo root.

---

## Verified as correct (no action)

- `queued` is created as a child of the protocol scope's `SupervisorJob`, so
  `completeExceptionally` cannot fail the scope and a pre-cancelled scope still releases
  `slot.queued.await()`.
- `RequestOutcome.onCompletion` cannot be double-invoked: the wrapper in
  `setRequestOutcomeHandlerRaw` only sees an `outcome` when `handler` returned, and
  `handleRequest`'s local `outcome` is only assigned when that wrapper returned normally.
  `mapResponse`'s failure path is likewise disjoint from both.
- Malformed *response-only* envelopes (`isResponse = true`) are never answered, so the
  `-32700`/`-32600` replies cannot ping-pong between two SDK peers.
- Ordering of v1 in-request session updates versus the final response is preserved: updates are
  enqueued on the transport channel before `slot.ready.complete(...)`, and `afterResponse` is
  gated on `slot.queued`.
- Lazy-start + register-before-`start()` in `dispatchRequest` correctly handles a
  `$/cancelRequest` that races the dispatch (the slot resolves to `null`, i.e. no reply).
