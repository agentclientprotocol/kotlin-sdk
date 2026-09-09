# JSON-RPC batch support plan

Status: design only. This document replaces the earlier compatibility-extension proposal; it does not authorize implementation.

## Recommendation and feasibility

Introduce `TransportFrame` directly into the existing `Transport` and shared `Protocol`. Accept the resulting low-level source and binary incompatibilities. Keep the existing Agent/Client/session APIs, handler contracts, and built-in transport construction unchanged.

This is realistic and cleaner than adding an optional `BatchTransport` capability or a separate `v2/Transport`. Both ACP versions already use the same `Protocol`, and application code normally constructs a stdio or WebSocket transport and passes it to that protocol. Batch framing does not require a different agent, client, session, or connection-negotiation abstraction.

The work is a bounded cross-module refactor, not just a parser enhancement. The significant complexity is correct response collection, cancellation, and response-before-stream ordering. Replace the implicit `executeAfterCurrentRequest` callback registry with explicit handler outcomes as part of that refactor. Changing the transport interface itself is straightforward. There is no reason to retain two parsing or dispatch implementations to preserve low-level compatibility.

“First-class batches” means receiving, sending, and preserving complete batch frames, collecting replies to incoming batches, and explicitly originating correlated batches through `Protocol`. It does not mean automatically grouping existing application calls.

## Compatibility boundary

| Area | Intended outcome |
| --- | --- |
| v1/v2 `Agent`, `Client`, session and support interfaces | Existing public signatures and interaction patterns remain unchanged. |
| Request/notification handlers and typed method invocation | Existing usage remains unchanged; the protocol supplies framing internally. |
| `ClientNegotiator` | Continues choosing the endpoint implementation on the same `Protocol`; no transport replacement. |
| `StdioTransport` and `WebSocketTransport` constructors | Existing setup remains valid; frame support is built in. |
| `Transport`, `BaseTransport`, listeners and channel helpers | Change directly from individual messages to frames. Custom implementations must migrate. |
| `Protocol` | Preserve construction and existing single-call operations; refactor internals and add an explicit batch operation. |
| After-response work | Return it explicitly from SDK handlers; remove `executeAfterCurrentRequest` and `RequestHolder`. |
| Public request/session context accessors | Preserve their behavior in this PR. Request metadata becomes independent of response scheduling. |
| JSON-RPC models, codecs and API snapshots | Low-level changes are allowed, including representing null IDs and replacing the object-only decoder. |

This preserves the application API, not every historical wire-level quirk. In particular, malformed input should produce the specified protocol behavior instead of stdio silently dropping it or recovering an object from a garbage prefix. Document that deliberate behavior change.

## 1. One frame model and one wire codec

Place `TransportFrame` alongside the JSON-RPC types in `acp-model`, under `com.agentclientprotocol.rpc`, so every transport can use it without depending on a concrete I/O implementation.

The conceptual model is:

```text
TransportFrame
  Single(message)
  Batch(non-empty entries)
  Malformed(raw input, protocol error)

Batch entry
  Valid message
  Malformed JSON value
```

Use a small sealed hierarchy, preferably reusing the single/malformed entry representations. Prevent nested batch frames through the type model; a nested array received inside a batch is an invalid entry. Copy constructor input so an externally mutable list cannot empty or alter a batch after construction.

Keep `JsonRpcMessage` as the model for one request, notification, or response. A batch is an envelope around messages, not another method-bearing message. A malformed entry must remain representable alongside valid entries; directly decoding an entire array as `List<JsonRpcMessage>` cannot provide the required partial-error behavior.

Expose one canonical parsing and encoding path, for example `TransportFrame.parse(text)` and `frame.toJson()`. Exact names can be finalized during implementation. Replace the existing `decodeJsonRpcMessage` entrypoint rather than maintaining a legacy decoder alongside it.

The codec must:

- Parse the entire JSON value using kotlinx.serialization's `Json.parseToJsonElement`; remove garbage-prefix recovery.
- Distinguish parser rejection from an invalid JSON-RPC envelope.
- Validate the JSON-RPC version, method/ID fields, and request/notification/response shapes before dispatching.
- Decode batch entries independently, retaining their order and enough raw information for classification and forwarding.
- Preserve malformed standalone text and malformed batch values without turning them into connection failures. Batch whitespace need not survive reserialization.
- Emit JSON-RPC objects and arrays without Kotlin serialization discriminator fields.
- Include `id: null` on uncorrelated errors and `result: null` on null success results. Emit exactly one of `result` and `error`.
- Keep wire-envelope strictness separate from existing ACP payload serialization settings and method-specific decoding.

Known limitation: for simplicity, accept the JSON syntax supported by kotlinx.serialization, including its acceptance of some non-standard JSON, without implementing custom syntax validation.

Envelope validation must distinguish missing fields from explicit nulls, require a string method name, and validate response error objects. Unknown extension fields must not make an otherwise valid envelope fail. Preserve the existing method-handler exception conversion separately; changing payload error semantics is not a prerequisite for replacing the wire codec.

Prefer adding an explicit `RequestId.Null`, following Rust, so the existing request/response model signatures can remain uniform. Adjust its serializer and low-level value accessor as needed. A present null ID must not be confused with an absent ID: only an absent ID makes a request a notification. Locally generated request IDs remain non-null; uncorrelated null-ID responses do not complete arbitrary pending requests.

## 2. Replace the transport boundary directly

The intended low-level API becomes:

```kotlin
fun send(frame: TransportFrame)
fun onFrame(handler: (TransportFrame) -> Unit)
```

Rename `MessageListener`, `fireMessage`, and `asMessageChannel` to their frame equivalents. Keep transport start/close/state responsibilities and I/O error reporting. Do not add an optional capability interface, a v2 transport hierarchy, an automatic flattening adapter, or compatibility aliases for the old message boundary.

Both built-in transports should use the same codec and queue complete frames:

- Stdio reads/writes one frame per NDJSON line. Preserve the Flow constructor's existing responsibility split: the supplied output callback owns newline/flushing behavior, while the Source/Sink adapter supplies it itself.
- WebSocket reads/writes one frame per text message.
- The writer queue preserves frame acceptance order and prevents individual batch entries from interleaving with other writes.
- Malformed wire input is delivered through `onFrame`; `onError` remains for transport failures.
- Sending to a closed transport must fail observably, so newly registered pending requests can be cleaned up.
- EOF, write failure, and explicit close must release protocol waiters and batch collectors.

A successful `send(frame)` means the complete frame has been accepted by the ordered writer queue. It does not acknowledge a physical flush or peer receipt. Later serialization/I/O failures must end the connection and release its waiters; they must not silently discard a response while leaving `Protocol` running.

Existing application code continues constructing these transports and `Protocol` exactly as before. Custom transports, fake transports, and frame-inspecting tools migrate to the new boundary.

## 3. Shared protocol dispatch and response collection

Keep one `Protocol` implementation for v1 and v2. Incoming batch support is available in both, matching Rust. Framing does not depend on a v2 capability flag or require renegotiating the connection.

Separate request execution from response emission. An internal handler produces an explicit outcome containing the response and optional follow-up work; a small internal response destination determines whether the response completes an individual frame or a slot in a batch. Reuse the same handler invocation, exception conversion, request-ID registry, and cancellation machinery in both cases.

Existing request and notification handlers remain per-method and per-message. There is no second registry of batch handlers. A batch is dispatched to the existing handlers entry by entry; complete-frame observation belongs to `Transport`.

### Explicit handler outcomes

Replace the coroutine-local callback registry with a small internal result type. The following sketch defines the intended ownership contract; concrete naming can be finalized during implementation:

```kotlin
internal data class RequestOutcome<T>(
    val response: T,
    val afterResponse: (suspend () -> Unit)? = null,
    val onCompletion: (() -> Unit)? = null,
)
```

`afterResponse` is work to run after this response is queued. `onCompletion` is optional, non-suspending local cleanup that runs exactly once when the outcome's work finishes or is discarded. Keeping these roles separate is important: a stream that never starts still needs its prepared state released.

Keep the current `RpcMethodsOperations` and ordinary `setRequestHandler`/`setRequestHandlerRaw` calling conventions. Normalize their returned values to outcomes inside the same handler-registration path. Add a distinctly named internal registration helper, such as `Protocol.setRequestHandlerWithOutcome`, for the SDK's special handlers. Both registrations must feed one canonical handler map and executor. Do not overload solely on a lambda return type, which makes Kotlin call resolution problematic.

Migrate the two production use sites:

- **V1 session setup:** return the session response with follow-up work invoking `sessionWrapper.executeWithSession { session.postInitialize() }`. The shared creation helper serves new/load/resume/fork handlers. Preserve the public `AgentSession.postInitialize()` contract and the client operations available inside it.
- **V2 prompt acceptance:** return `PromptResponse()` together with a continuation that collects the update flow and sends `session/update` notifications. Move `_activePrompt` reset into outcome completion so it also happens if the continuation never starts. If `session.prompt()` fails before an outcome is returned, its producer still owns cleanup.

Ordinary application handlers keep returning ordinary responses. The outcome type and continuation registration are SDK implementation details; they do not need to appear in Agent/Client/session interfaces.

Delete `RequestHolder`, its mutable `handlers` list, the `CoroutineContext.requestHolder` accessor, and both `executeAfterCurrentRequest` helpers. Request identity is a separate concern: retain a minimal immutable `JsonRpcRequestContextElement` containing the request directly so the existing public `CoroutineContext.jsonRpcRequest` accessor still works. Remove the unused v1 `PromptSession.currentRequestId` field and its now-unnecessary production read of that accessor.

This boundary follows the IDEA usage analysis: the callback hook has two production callers, but an application-style `AgentSession.prompt()` in `FeaturesTest` uses the public request accessor for request-scoped elicitation. Passing metadata only to a raw protocol handler would not preserve that use case. Replacing that public access, and the separate v1 `CoroutineContext.client`/`agent`/`clientInfo` session context, requires a later explicit per-call/session API design. This PR removes implicit response scheduling, while retaining coroutine-based asynchronous execution and those existing metadata APIs.

### Batch response collection

For an incoming batch:

1. Identify response-bearing entries: requests and invalid call entries. Notifications and incoming responses have no response slot.
2. Allocate independent slots by entry position, not by request ID. Duplicate IDs must not overwrite collected results.
3. Dispatch entries in source order using the existing handler scheduling policy. Do not await long-running handlers on the frame-reading loop.
4. Mark dispatch complete after every entry has been handed to its appropriate handler or response/error path.
5. Emit one response array after dispatch is complete and every response-bearing slot has reached a terminal outcome. Emit at most once, and never emit an empty array.
6. After enqueueing that array, release the corresponding request outcomes to run their follow-up work. Each continuation keeps its own lifecycle; the collector does not execute or join the continuations.

A one-request batch still receives a response array. A standalone request receives a standalone response object. Ordinary calls must not become singleton arrays merely because the implementation shares response-collection code.

Prefer Kotlin coroutine primitives and a small private collector over copying Rust's actor/ownership machinery. Per-slot deferred completion and a dispatch-completion signal are a reasonable starting point. The response-ready signal and the response-queued signal must be separate: publish a ready response before waiting for its enclosing frame to be queued. Keep collection separate from request-handler lifetime; do not implement it by joining complete handler jobs. Likewise, share request execution without building a generic scheduling framework.

### Required wire outcomes

| Input | Protocol behavior |
| --- | --- |
| Malformed JSON | One parse-error object, code `-32700`, `id: null`. |
| Empty array | One invalid-request object, code `-32600`, `id: null`. |
| Mixed requests and notifications | Collect request responses into one array; never answer valid notifications. |
| Invalid member in a non-empty batch | Add its own invalid-request response; valid siblings continue. |
| Valid notification-only batch | No response, including when a notification handler fails or the method is unknown. |
| Incoming response array | Match responses to pending calls by ID, regardless of array order; do not answer responses. |
| Malformed response-only envelope | Suppress a reply, following the Rust reference, to avoid response loops. Ambiguous call-shaped invalid values still receive invalid-request errors. |

The Rust malformed-response classification is an interoperability policy, distinct from the draft's explicit invalid-call batch examples. Classify an invalid JSON object with no `method` but a `result` or `error` field as response-only. A value containing `method` is call-shaped even if it also contains response fields, and must receive an invalid-request error when malformed. Unparseable text remains a parse error. Test these boundaries rather than treating every malformed value as a request.

For incoming valid responses, use the same pending-ID lookup for individual and array entries. Ignore/report unknown or already-completed IDs without replying or completing another call. A null-ID uncorrelated error must not fail arbitrary pending requests or be guessed to belong to the latest outgoing batch. The explicit outgoing API creates only request/notification batches; raw incoming frames can still be processed entry by entry if they mix message kinds, as Rust does.

### Cancellation, ordering, and streaming

These invariants are necessary to preserve the existing Agent/Client behavior:

- Register incoming request jobs before they can be cancelled or finish. Completion of an earlier job must not remove a later request's registry entry when IDs are reused.
- Keep reading frames while a batch is pending. Nested requests, independent batches, notifications, and cancellation must continue to progress.
- Preserve the current ordering guarantee for non-suspending notification handlers without waiting for suspended notifications to finish.
- Every response slot must terminate, including when a handler fails or is cancelled before it starts. Retain existing request-cancellation semantics; if those semantics intentionally suppress a reply, record a terminal omitted outcome instead of leaving an unresolved slot. Only emit a response array if actual responses remain.
- Do not add Rust's dropped-`Responder` abstraction: Kotlin handlers return or throw. Explicit job-completion handling is sufficient to avoid stranded slots.
- Start an outcome's follow-up work only after its successful individual response or aggregate response has been accepted by the transport writer. The collector must not wait for that work to finish.
- Handle cancellation while waiting for that send boundary, including cleanup needed by v2 prompt/session state. A response-ready signal and an after-response continuation must not wait on each other.
- On connection shutdown, cancel/fail collectors and pending requests rather than introducing unbounded `NonCancellable` waits or trying to flush responses forever.

The outcome lifecycle must cover these cases explicitly:

| Event | Required handling |
| --- | --- |
| Successful response queued | Run `afterResponse`, then run `onCompletion` in `finally`. |
| Handler fails before returning an outcome | Convert the handler failure using the existing error rules. The handler owns any resources it prepared before returning. |
| Before enqueue: outcome returned, but typed response serialization/mapping fails | Do not run follow-up work. Release the outcome exactly once and produce a normal error response if the connection can still send one. |
| Request cancelled while the batch is collecting replies | Do not start its follow-up work. Finish its slot according to the existing cancellation rule and release prepared state. A slot already finalized must not be completed a second time. |
| Enqueue fails, or connection closes before follow-up starts | Do not start follow-up work; release prepared state and fail/cancel the affected send-boundary waiters. |
| After enqueue: writer serialization or I/O fails | Close/fail the connection, cancel any running follow-up work, and finalize its outcome. Do not attempt a replacement response after queue acceptance. |
| Follow-up work fails or is cancelled | Run local cleanup exactly once. Do not send a second response or revise an already-queued batch. Isolate/log its failure as appropriate. |

Outcome ownership must transfer explicitly from producer to dispatcher, including through any typed-to-JSON adapter. Adaptation must preserve one cleanup owner rather than independently finalizing copies of the same prepared work. Cleanup cannot depend only on entering the continuation: v2 may already have marked a prompt active when the outcome is prepared. Keep local cleanup non-suspending and independent of transport availability; log a cleanup failure without replacing the primary error. This intentionally replaces the old unconditional callback loop, which could also run callbacks after handled request failures.

Keep continuations owned by the corresponding request/protocol lifecycle, with existing cancellation reachability; do not detach them into an unrelated global scope or serialize all continuations inside the batch collector. Awaiting their completion would delay a v2 acceptance response until the output stream ended. Queue acceptance is the ordering boundary, not peer acknowledgement.

Only work explicitly returned as `afterResponse` is deferred. Notifications sent during ordinary request processing remain allowed; v1 prompt output and session replay must not accidentally move behind the final response.

## 4. Explicit outgoing batches are part of the target

Rust currently supports incoming batches and their aggregate replies while ordinary SDK calls originate individual messages. This proposal goes one step further: include a concise protocol-level operation for explicitly originating batches, in addition to `Transport.send(Batch(...))`.

Recommended initial API shape, illustrative rather than final:

```kotlin
suspend fun Protocol.sendBatchRaw(
    calls: List<JsonRpcCall>,
    sessionId: SessionId? = null,
): List<Result<JsonElement>>
```

`JsonRpcCall` is a protocol-level sealed interface with `Request` and `Notification` call descriptions containing only method and params. It is separate from the wire models (`JsonRpcRequest`, `JsonRpcNotification`, and `JsonRpcResponse`). The protocol assigns request IDs using the same atomic counter as single requests; callers do not supply IDs. The optional `sessionId` associates every request in a batch with one session for local tracking without modifying params; omit it for batches spanning multiple sessions. Results correspond to request entries in input order, with notifications omitted. A notification-only batch returns an empty result list after enqueueing and does not wait for a response.

The operation must:

- Reject empty input before changing request state.
- Allocate an ID for every request occurrence from the shared atomic counter, then atomically register every pending response before enqueueing the frame. Notifications do not consume IDs.
- Reusing a call description must still allocate distinct request IDs. No caller-ID collision validation or separate reservation loop is needed; single and batch requests share the same allocator.
- Reuse the existing per-request result/error and cancellation handling.
- Send the calls as one batch, then correlate both array responses and individually arriving response objects by ID.
- Preserve independent outcomes: one remote method error must not erase successful sibling results or cancel the other calls.
- Propagate cancellation of the batch operation using the existing request-cancellation mechanism and clean up all of its pending entries. Transport failure also cleans up all affected entries.

For this aggregate API, remote JSON-RPC errors (including remote request cancellation) become failures in their individual `Result` entries. Cancellation of the local calling coroutine still throws and cancels/cleans up its outstanding requests. Validation or transport failure prevents normal completion of the whole operation. Use the existing caller-controlled timeout model; do not add a hidden batch timer. A missing or uncorrelated reply remains pending until caller cancellation/timeout or connection failure.

Validate and serialize all outgoing calls before enqueueing any part of the batch. Registration is all-before-send; an enqueue failure rolls back every registered entry. Never await one request while still gathering later calls, and do not attach responses to batches using arrival order.

Existing `sendRequestRaw`, typed invocations, and notifications keep their public usage and single-frame behavior. Their implementation can share private registration/awaiting helpers with the new operation. No changes are needed to existing `RpcMethodsOperations` consumers just to support batch reception.

Do not add implicit time-window batching, coroutine-context interception, or a DSL that executes existing suspending methods before the batch has been sent. Add typed convenience wrappers only if they meaningfully simplify actual usage; they are not required for the initial design.

Keep the existing pagination helper `sendBatchedRequest` unchanged. Its name refers to paginated fetching, not JSON-RPC batching; clarify that distinction in its documentation.

Lifecycle-sensitive methods (`initialize`, `auth/login`, `session/new`, `session/resume`, `session/prompt`) continue to be sent individually by existing Agent/Client APIs. Document the draft's SHOULD NOT recommendation on the raw batch operation. Do not reject otherwise valid incoming batches solely for containing these methods. Batch framing is not transactional and provides no promise that one entry establishes the preconditions for another.

Receiving support can be shared across versions without assuming every external v1 peer accepts outbound arrays. Explicit batch callers are responsible for peer support; do not invent a capability field or automatically batch existing calls.

## 5. Implementation sequence and affected files

1. **Model and codec:** update `acp-model/.../rpc/JsonRpc.kt`, introduce `TransportFrame`, and replace object-only codec tests. Establish error classification, null-ID/null-result encoding, and malformed-entry behavior first.
2. **Transport migration:** change `Transport.kt`, `BaseTransport.kt`, `StdioTransport.kt`, and `acp-ktor/.../WebSocketTransport.kt` together. Migrate transport tests and custom fake transports to frames. Preserve constructors.
3. **Explicit outcomes and incoming protocol support:** normalize handler results, add the internal outcome-aware registration helper, and migrate both Agent implementations' follow-up work. Remove the callback registry; retain request metadata separately. Implement response slots, batch completion, and outcome cleanup in the shared executor. Keep higher-level endpoint interfaces intact.
4. **Outgoing protocol support:** add the explicit raw batch operation using the same pending-request infrastructure. Complete this step before describing batch support as fully first-class in this project.
5. **Integration and migration documentation:** exercise both transports and both ACP versions, refresh API snapshots, and document the intentional low-level break with a small custom-transport migration example.

The main migration sites outside production transport/protocol code are `acp/src/jvmTest/.../agent/TestTransport.kt`, fake transports in client delivery tests, and stdio transport tests. Their high-level assertions should remain intact; helpers can wrap/unwrap single frames internally.

Reuse the existing shared test arrangement in `acp-ktor-test`: `ProtocolTest` is exercised by both `StdioProtocolTest` and `WebSocketProtocolTest`. The v2 conversation and client-negotiation suites provide checks that application-facing behavior has not changed.

## 6. Verification and acceptance criteria

Test externally observable behavior, particularly cases where a naive array decoder or collector fails:

- Single messages, one-element batches, mixed batches, notification-only batches, and response arrays in reversed order.
- Malformed JSON, empty arrays, scalars/nested arrays inside batches, invalid envelope fields, and malformed response-only entries.
- Exact wire encoding of null IDs, null success results, and result/error exclusivity; no accidental discriminator fields or empty response arrays.
- A batch with immediate and delayed responses, later notifications, and overlapping independent batches. Assert no premature flush or duplicate emission.
- Duplicate/reused IDs do not overwrite response slots or remove unrelated pending jobs.
- Cancellation before handler startup, cancellation during execution, and close/write failure while a batch is pending. Assert no stranded waiters.
- Suspended notifications and nested outbound requests do not block the frame-reading loop.
- After-response streaming begins after the enclosing response frame is queued, and a continuing stream does not delay that response.
- Outcome cleanup runs exactly once after normal completion, response serialization failure, cancellation before continuation startup, enqueue failure, and follow-up failure. V2 prompt state is not left active when prepared work is discarded.
- A continuation failure cannot produce a second response; one batch member's long-running continuation cannot prevent another continuation or later frame from progressing.
- The public `jsonRpcRequest` accessor remains available inside application session code, including the existing request-scoped elicitation test. V1 `postInitialize` still has its session/client context.
- Existing v1 streamed updates and replay notifications can still precede their request response; only explicitly declared follow-up work waits for response enqueueing.
- Explicit outgoing mixed batches register IDs before sending, preserve independent success/error results, and clean up on cancellation/failure.
- Remote cancellation is an individual batch result, while local coroutine cancellation propagates. Unknown, duplicate, and null-ID replies cannot resolve the wrong pending request.
- Existing v1/v2 Agent/Client/session, negotiation, replay/update delivery, and single-request tests still pass with the same high-level call sites.
- Stdio and WebSocket carry the same frames and protocol outcomes; custom frame transports behave consistently.

During implementation, run the focused JVM suites first (`:acp-model:jvmTest`, `:acp:jvmTest`, and `:acp-ktor-test:jvmTest`), then the repository's configured multiplatform checks. Regenerate API dumps for the changed public modules and review the diff: low-level transport/RPC changes are expected, while Agent/Client/session signatures should remain unchanged. Complete the relevant API checks and documented `./gradlew check` before handoff, reporting any environment-specific limitations.

Success means one shared frame representation, one codec, and shared request execution for standalone calls and batches; built-in transports support both out of the box; ordinary application code keeps its shape; and no legacy decoder, optional batch capability, duplicate v2 transport, or coroutine-local callback registry remains.

## 7. Implementation readiness and scope limits

The plan now fixes the important behavioral decisions: the frame boundary, partial batch errors, handler registration shape, explicit continuation ownership, cleanup and cancellation, the metadata compatibility boundary, and outgoing batch result semantics. It is sufficient to begin implementation without another architecture decision from the user. Exact internal names and the choice of coroutine primitives are implementation details to resolve against the tests above.

The prior gaps were the mechanism replacing `executeAfterCurrentRequest`, cleanup of an outcome whose stream never starts, preservation of request-scoped elicitation, and the distinction between local cancellation and a remote error in an outgoing batch. Those are addressed explicitly here. Implementation may expose further edge cases; passing the listed regression suites, rather than the plan alone, establishes behavioral compatibility.

Keep the following outside this PR: replacing public v1 session-context APIs, adding a new per-call context parameter to `AgentSession`, redesigning session cancellation, adding automatic outbound batching, introducing a public responder/actor framework, or changing the transport framing of HTTP mechanisms beyond the existing WebSocket implementation.

## Sources

- [ACP v2 draft transport rules](../agent-client-protocol/docs/protocol/v2/draft/transports.mdx).
- [Rust transport architecture](../acp-rust-sdk/md/transport-architecture.md) and [SDK 2.0 frame migration](../acp-rust-sdk/md/migration_v2.0.md).
- [Rust frame types and response accumulator](../acp-rust-sdk/src/agent-client-protocol/src/jsonrpc.rs), particularly `TransportFrame`, `ResponseDestination`, and `take_completed_batch`.
- [Rust frame parser](../acp-rust-sdk/src/agent-client-protocol/src/jsonrpc/transport_actor.rs) and [incoming dispatch](../acp-rust-sdk/src/agent-client-protocol/src/jsonrpc/incoming_actor.rs).
- [Rust batch regression tests](../acp-rust-sdk/src/agent-client-protocol/tests/jsonrpc_batch.rs).
- [Current Kotlin transport](acp/src/commonMain/kotlin/com/agentclientprotocol/transport/Transport.kt), [protocol](acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.kt), and [JSON-RPC models/decoder](acp-model/src/commonMain/kotlin/com/agentclientprotocol/rpc/JsonRpc.kt).
- [Kotlin v2 prompt continuation](acp/src/commonMain/kotlin/com/agentclientprotocol/agent/v2/Agent.kt), [request context](acp/src/commonMain/kotlin/com/agentclientprotocol/protocol/Protocol.extensions.kt), and [shared protocol tests](acp-ktor-test/src/commonTest/kotlin/com/agentclientprotocol/ProtocolTest.kt).
- [V1 session setup and session context](acp/src/commonMain/kotlin/com/agentclientprotocol/agent/Agent.kt) and [request-scoped elicitation coverage](acp-ktor-test/src/commonTest/kotlin/com/agentclientprotocol/FeaturesTest.kt).

Research is based on the local checkouts, including Rust revision `7c5119e`. No implementation or test execution is part of this planning task.
