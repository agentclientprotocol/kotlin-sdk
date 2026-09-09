# JSON-RPC frame and batch migration

Built-in stdio and WebSocket transports now carry both single messages and JSON-RPC batches. The shared `Protocol` handles batches for ACP v1 and v2. Agent, Client, session and support interfaces, and transport constructors are unchanged. Existing calls still send individual objects; there is no automatic batching.

## Custom transports

The low-level transport boundary is intentionally source/binary incompatible:

| Before | After |
| --- | --- |
| `send(JsonRpcMessage)` | `send(TransportFrame)` |
| `onMessage` / `MessageListener` | `onFrame` / `FrameListener` |
| `fireMessage` | `fireFrame` |
| `asMessageChannel` | `asFrameChannel` |
| `decodeJsonRpcMessage(text)` / `TransportFrame.parse(text)` | `parseTransportFrame(text)` |
| Polymorphic message serialization / `frame.toJson()` | `JsonRpcJson.encodeToString(TransportFrame.serializer(), frame)` |

`TransportFrame` is in `com.agentclientprotocol.rpc` (`acp-model`). `Single` wraps a message; `Batch` contains non-empty `Entry` values (`Single` or `Malformed`), never nested batches. Constructor input is copied. `Malformed` retains the protocol error and response-shape classification, including malformed siblings of otherwise valid batch entries. It no longer retains raw input and cannot be serialized, either standalone or inside a batch.

For a custom `BaseTransport`, the central changes look like:

```kotlin
private val outgoing = Channel<TransportFrame>(Channel.UNLIMITED)

override fun send(frame: TransportFrame) {
    outgoing.trySend(frame).getOrThrow()
}

// Reader: once per NDJSON line or WebSocket text frame.
fireFrame(parseTransportFrame(text))

// One ordered writer; never flatten batches into separate writes.
for (frame in outgoing) {
    write(JsonRpcJson.encodeToString(TransportFrame.serializer(), frame))
}
```

The custom transport remains responsible for start/close/state management, input framing, and output newline/flushing. A successful `send` means queue acceptance, not network flush or peer acknowledgement. A closed queue must reject sends. EOF and writer failure must close the transport and notify listeners so pending calls and continuations are released. Use `onError` for transport failures, not malformed JSON-RPC input.

Use the shared `parseTransportFrame` wrapper for wire text: it also catches syntax failures raised after the serializer returns, such as trailing input. Calling `decodeFromString` directly can throw on invalid JSON. JSON-RPC envelope failures are converted to `Malformed` by the frame serializer itself. Built-in transports keep reading after malformed input. Attempting to send a malformed frame is instead a writer error: it emits no partial JSON, reports `onError`, and closes the transport asynchronously.

## JSON-RPC serializers and responses

`JsonRpcMessage` and `JsonRpcResponse` now have custom JSON serializers. They validate envelopes and delegate field encoding/decoding to the concrete generated serializers; interface serialization no longer emits a Kotlin type discriminator. `JsonRpcJson` is the wire configuration; the existing lenient `ACPJson` configuration for method payloads is unchanged.

`JsonRpcResponse` is now a sealed interface with `id` and `jsonrpc`. Replace constructor calls with `JsonRpcSuccessResponse(id, result)` or `JsonRpcErrorResponse(id, error)`, and inspect the subtype instead of nullable `result`/`error` properties. Success results are required, non-nullable `JsonElement` values: use `JsonNull` for a JSON null result. Null IDs remain `RequestId.Null`.

Envelope serialization rejects unsupported JSON-RPC versions and scalar request/notification params consistently for singles and batches. Raw send callers that previously supplied scalar params must supply an object, array, or null instead. This validation belongs to the interface/frame serializers; generated concrete serializers remain field codecs. Agent/Client/session-facing APIs are unchanged.

## Explicit outgoing batches

Use `Protocol.sendBatchRaw` with `JsonRpcCall.Request` and `JsonRpcCall.Notification` from `com.agentclientprotocol.protocol`. These describe outgoing calls without wire IDs; responses cannot be passed to this operation. The wire models (`JsonRpcRequest`, `JsonRpcNotification`, and `JsonRpcResponse`) remain separate.

```kotlin
val results = protocol.sendBatchRaw(listOf(
    JsonRpcCall.Request(MethodName("example/read"), buildJsonObject { put("path", "a.txt") }),
    JsonRpcCall.Notification(MethodName("example/notify"), buildJsonObject { put("event", "read") }),
    JsonRpcCall.Request(MethodName("example/read"), buildJsonObject { put("path", "b.txt") }),
))
```

The protocol assigns request IDs using the same atomic counter as ordinary single requests. Callers do not allocate IDs; reusing a call description still generates a new ID for every request occurrence. Notifications do not consume IDs.

The optional `sessionId` argument to `sendBatchRaw` associates every request in that batch with one session for local tracking (`getOutgoingRequestSessionId`). It does not alter wire params. Omit it for batches spanning multiple sessions; each call still carries its own wire-level session ID in its params.

Results correspond to requests in input order, with notifications omitted. Remote errors, including remote cancellation, are individual failures. Local caller cancellation throws and cancels outstanding requests. Validation/enqueue/connection failure prevents normal completion of the operation. Notification-only batches return an empty result list immediately after enqueue. Empty batches are rejected. Timeouts remain caller-controlled.

Only use this API when the peer supports batches; the SDK does not invent a capability flag for older peers. ACP advises against batching lifecycle-sensitive operations such as `initialize`, `auth/login`, `session/new`, `session/resume` and `session/prompt`. Batches are not transactions and do not establish dependencies between entries. Ordinary Agent/Client operations remain standalone. The older `sendBatchedRequest` helper still means pagination, not JSON-RPC batching.

## Wire behavior and handler lifecycle

Parsing uses kotlinx.serialization's `Json.parseToJsonElement` without garbage-prefix recovery. Input rejected by that parser produces `-32700`; empty arrays and invalid call envelopes produce `-32600`. Invalid members do not discard valid siblings. Malformed response-only objects (no `method`, but `result` or `error`) are not answered, preventing response loops. Notifications never receive replies. Single requests receive objects; requests in a batch receive one aggregate response array after all reply slots terminate. Response IDs, not array positions, correlate outgoing requests.

Known limitation: kotlinx.serialization accepts some non-standard JSON, including arbitrary unquoted primitive tokens, even with `isLenient = false`. For simplicity, we accept its JSON syntax rules and do not reimplement custom JSON validation. Successfully parsed values still undergo JSON-RPC envelope validation.

Uncorrelated errors explicitly encode `id: null` using `RequestId.Null`. Null successes encode `result: null`. A present null request ID is not a notification. Unknown, duplicate and null-ID responses never resolve an unrelated outgoing call.

Per-method handlers retain their existing signatures; there is no batch handler registry. Internally, SDK handlers can return `RequestOutcome(response, afterResponse, onCompletion)`. The dispatcher starts `afterResponse` only after the complete response frame is queued, and runs cleanup once even when prepared work is discarded. Continuations remain independently cancellable and do not delay their batch's response or sibling continuations. V1 updates sent during request execution still precede the final response when appropriate.

`executeAfterCurrentRequest` and its coroutine-local callback list are removed. The public `CoroutineContext.jsonRpcRequest` metadata accessor and v1 session context accessors remain available; they no longer schedule response-dependent work.
