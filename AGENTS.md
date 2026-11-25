# Repository Guide for Coding Agents

This file is the repository-wide source of truth for agents changing the ACP Kotlin SDK. Keep it practical and current. When a core change alters the architecture, module boundaries, lifecycle rules, public API policy, or a shared implementation pattern, update this file in the same change.

## Project Overview

This repository is a Kotlin Multiplatform implementation of the Agent Client Protocol (ACP). It provides:

- serializable ACP and JSON-RPC models;
- agent and client runtimes;
- protocol negotiation, request correlation, batching, and cancellation;
- STDIO and Ktor WebSocket transports;
- Ktor client/server integrations;
- shared integration tests and runnable samples.

Use the Gradle wrapper and JDK 21 for all builds. The shared convention plugin configures a JDK 21 toolchain, strict explicit API mode, and the supported Kotlin Multiplatform targets.

## Repository Map

### `:acp-model`

Pure protocol data and serialization:

- ACP request, response, notification, capability, and session models;
- JSON-RPC messages, request IDs, errors, and transport frames;
- stable models under `com.agentclientprotocol.model`;
- unstable protocol v2 models under `com.agentclientprotocol.model.v2`;
- stable-to-v2 conversions under `model.v2.conversion`;
- checked-in public API dump at `acp-model/api/acp-model.api`.

This module must not depend on runtime or transport implementations.

### `:acp`

The core runtime:

- `agent`: agent lifecycle, sessions, and client callbacks;
- `client`: client lifecycle, sessions, and update delivery;
- `common`: shared operations and session abstractions;
- `protocol`: JSON-RPC dispatch, correlation, batching, negotiation, and cancellation;
- `transport`: the transport contract and STDIO implementation;
- `util`: coroutine and pagination helpers.

`agent.internal` and `client.internal` hold the runtimes' non-public machinery (session implementations and wrappers, holders, the registry, pending permissions). Public types, and direct subtypes of a public sealed type (Kotlin requires those in the sealed type's package), stay in the parent package.

The public API dump is `acp/api/acp.api`.

### `:acp-ktor`

Shared Ktor transport infrastructure, including `WebSocketTransport`. It builds on `:acp` and owns transport behavior that is common to Ktor clients and servers. Its public API dump is `acp-ktor/api/acp-ktor.api`.

### `:acp-ktor-client` and `:acp-ktor-server`

Client-side and server-side Ktor adapters. Keep shared WebSocket behavior in `:acp-ktor`; keep endpoint-specific setup in the corresponding adapter module. Their API dumps are in each module's `api` directory.

### `:acp-integration-test`

Cross-transport integration and conformance-style tests. Use this module for behavior that should hold across STDIO/WebSocket or client/server wiring. It is a test module, not a published API module.

### `:samples:kotlin-acp-client-sample`

Runnable examples for stable ACP, direct v2 use, negotiation, authentication, subagents, and external-agent integration. Samples should demonstrate supported public APIs, not internal shortcuts.

Each sample has its own package under `com.agentclientprotocol.samples`, with shared helpers in `support`, and its own Gradle run task registered with `registerSampleTask` in the module's `build.gradle.kts`. `samples/README.md` is the table of contents. A new sample adds a package, a task, its own `README.md` in its package, and a row in `samples/README.md`. The module's JVM tests (`src/test`) run scripted samples through their entry points, so `./gradlew check` catches a sample that an SDK change breaks.

### `e2e`

End-to-end suites that check the SDK against external counterparts, such as the ACP TCK and the Rust SDK. They need tools outside the Gradle build, and `./gradlew check` does not run their tests. Before changing anything under `e2e/`, read `e2e/README.md` and the README of the suite you touch.

### `build-logic`

Shared Gradle convention and publishing plugins, an included build declared in `pluginManagement` of `settings.gradle.kts`. Changes here affect most modules and are core architectural changes.

Its tests (`build-logic/src/test`) run from the root `./gradlew check`, which depends on the included build's `check`.

## Architecture and Dependency Direction

The main runtime stack is:

```text
AgentSupport / AgentSession        Client support / ClientSessionOperations
             |                                      |
           Agent                                  Client
             \                                      /
                         Protocol
                            |
                         Transport
                    (STDIO or WebSocket)
```

Keep these boundaries intact:

- Models and JSON-RPC wire types belong in `:acp-model`.
- Request routing, correlation, negotiation, and cancellation belong in `Protocol`.
- Transport implementations move complete `TransportFrame` values and own transport state, I/O, and shutdown.
- Agent and client layers expose typed ACP behavior and session lifecycles. Do not leak raw transport concerns into their public APIs.
- Ktor-neutral behavior belongs in `:acp` or `:acp-model`; Ktor-specific behavior belongs in the `acp-ktor*` modules.
- Prefer `commonMain` for portable behavior. Put platform-specific code or tests in the narrowest applicable source set.

Avoid dependency cycles and upward dependencies. Lower layers must not depend on agent/client runtime types merely to simplify a call site.

Pass dependencies explicitly. In common code (`protocol`, `transport`, `util`, `:acp-model`, the Ktor modules) and in the v2 runtimes, don't pass dependencies, hooks, or per-request state implicitly through the coroutine context, as a context element or a `currentCoroutineContext()` lookup. Use parameters, constructor arguments, or return values such as `RequestOutcome`. The v1 runtimes keep their existing context accessors, such as `clientSession`, for API compatibility; don't extend that pattern. Using the coroutine context for its own purposes, such as the job, the dispatcher, cancellation, or names, is fine.

## Protocol Versions and Unstable APIs

Stable ACP and protocol v2 coexist in this repository. Preserve their boundaries.

- Treat v2 APIs as unstable unless the protocol and repository explicitly promote them.
- Mark unstable declarations with `@UnstableApi`.
- Opt in explicitly at the narrowest useful scope with `@OptIn(UnstableApi::class)` or a file-level opt-in when most of a file is v2-specific.
- Keep version negotiation at the connection/runtime boundary. A connection speaks one negotiated protocol version.
- Put conversion logic in `model.v2.conversion`; do not scatter ad hoc stable/v2 conversions across runtimes and transports.
- When changing behavior shared by stable and v2 paths, check both paths and add tests for both when they can diverge.
- Do not silently promote, rename, or remove unstable APIs without checking samples, conversions, tests, and API dumps.

## JSON-RPC and Framing

`TransportFrame` is the low-level wire boundary:

- `Single` carries one JSON-RPC message;
- `Batch` is non-empty and retains source order and per-entry outcomes;
- `Malformed` is receive-only and must not be serialized as an outbound frame.

Preserve complete frame boundaries between transports and `Protocol`. This is required to return batch responses as one JSON array and to keep valid batch members usable when siblings are malformed.

Use the shared serialization entry points rather than creating module-local `Json` configurations. Parse wire text with `parseTransportFrame` and encode frames through `JsonRpcJson` and the `TransportFrame` serializer.

The parser deliberately delegates JSON syntax acceptance to kotlinx.serialization, then validates the JSON-RPC envelope. Preserve the distinction between:

- parser rejection: JSON-RPC Parse Error (`-32700`);
- parsed but invalid JSON-RPC shape: Invalid Request (`-32600`);
- valid envelope whose `params` do not decode into the method's type: Invalid params (`-32602`), mapped where typed handlers decode them.

Do not add a second custom JSON parser or strict preflight serialization without a protocol-level reason and tests. Document any intentional compatibility limitation.

## Runtime and Lifecycle Rules

Lifecycle correctness is part of the public behavior. Changes to startup, cancellation, request maps, response delivery, or shutdown require focused lifecycle tests.

- `Protocol` owns outgoing request IDs, pending request correlation, incoming handler jobs, and negotiated version state.
- A transport owns its state transitions: `CREATED`, `STARTING`, `STARTED`, `CLOSING`, and `CLOSED`.
- Reject new sends after shutdown begins, while allowing already accepted work to follow the transport's documented drain behavior.
- Keep frame-local serialization or send failures local. Propagate the affected operation's error without closing the whole protocol unless the transport or parent lifecycle has actually ended.
- Treat successful notification/transport send as queue acceptance unless the transport contract says otherwise.
- Preserve coroutine cancellation. Catch `CancellationException` only to add required cleanup or protocol behavior, then rethrow it.
- Put caller-local cleanup in `finally` when it must survive success, failure, and cancellation.
- Register lifecycle callbacks only after all state they may access is initialized.
- Isolate user/listener callback failures so one callback does not prevent later callbacks or lifecycle cleanup.
- Do not parent per-request `CompletableDeferred` values casually. Any change to their ownership must account for pending-map cleanup and peer cancellation.
- Return follow-up work, cleanup, and a response-queued hook through `RequestOutcome` rather than running them inside the handler.
- Both client runtimes answer a session's pending `session/request_permission` requests with `cancelled` when the client cancels or closes the session. Each handler races the app's operations against a per-session cancel signal (`PendingPermissions`: `RootPendingPermissions` for a root, `SubagentPendingPermissions` for a subagent). Keep that race the only answering path, so each request gets exactly one response.
- Keep normal close, timeout/abort, parent cancellation, and I/O failure paths distinct and testable.
- `Protocol` has an internal response hook: the `onResponse` overload of `sendRequestRaw` and the typed `Protocol.sendRequest` in `Protocol.extensions.kt`. It runs synchronously in the read loop for a success response, before the caller resumes and before the next incoming message, so it sees where the response falls among the notifications. It must not suspend, block or throw: every message of the connection waits for it. It doesn't run for an error, a response that doesn't decode, or a cancelled call, but it may already have run for a call that then ends with `CancellationException`, so the caller's failure path must undo whatever the hook did.
- The v1 client keeps one `ClientSessionHolder` per session id, a `RootSessionHolder` or a `SubagentSessionHolder`, and one opening call at most claims a root's (`claimForOpening`): opening an id that is open or being opened fails with "Session … is already open or being opened", for every overload. With a `ClientOperationsFactory`, the holder buffers the session's updates until the opening call answers. The `loadSession` and `resumeSession` overloads that take `ClientSessionOperations` claim the holder before the request goes out, and one consumer hands its queue to the operations until the answer. The opening call's response hook puts the `Opened` marker into the same queue (for `session/new` and `session/fork` it claims the holder of the answered id in the same step), so the marker keeps its wire position among the updates. The consumer stops at the marker; it is delivered as `SessionEvent.Opened` only after the request has returned, so a request that fails or is cancelled delivers none and failing the holder drops it. The opening call then delivers the marker and the rest of the queue in its own coroutine, cancellably: a cancellation of the caller, or a `notify` failure, there still fails the holder like any failed opening (the rest goes to `GlobalSessionUpdateHandler`, and the id can be opened again), even though the operations may already have the marker. That is app code, so do not run it in `NonCancellable`. Until the switch to the session, every update and the marker pass that one queue with its one consumer at a time, so the arrival order holds by construction: do not add a second delivery path, a lock, or a counter there. After the switch, an update goes synchronously to the session. An update that misses the closed queue waits in the `lateDeliveries` chain for the drain and for the update that missed the queue before it, so late updates keep their order too.
- The v1 agent runs one turn at a time per session, an SDK guarantee the protocol does not define. Only the turn frees its slot, when it answers, so a `session/prompt` sent earlier, even while a cancelled turn winds down, is rejected. `session/cancel` targets the turn registered when it arrives, never one registered while `AgentSession.cancel()` runs, and a cancelled turn answers only after every such call returns, so updates sent from it precede the response. A turn's flow failure stays inside the turn's job so it still passes that wait: after a cancel it answers `cancelled` (aborted calls often throw), otherwise it answers with the error.
- A v1 agent's session request (`session/request_permission`, `fs/*`, `terminal/*`, session-scoped `elicitation/create`) finds its session through `ClientSessionRegistry.awaitSession`, which reads the session map once and never changes it. It waits only for a subagent's holder or a root's holder that an opening call has claimed (`session/load` and `session/resume` before the request, `session/new` and `session/fork` in the response hook); anything else, no holder or an unclaimed holder that an early update created, fails at once with `-32002`. Only updates create speculative holders. A request must not: its wait would have no bound, since an unclaimed holder is reaped only when the last opening ends, and its holder would block a later announcement of a subagent with that id.
- `Protocol` runs each incoming notification's handler until its first suspension, or its return, before it handles the next incoming message. So those first parts run one at a time in arrival order, and a response that arrives after a notification is handled after that part; the rest of the handler may interleave with later ones. Code that needs a notification's effect in place before a later response relies on this, so keep that part non-suspending, and don't give such a handler a dispatcher in its additional context, because `withContext` would dispatch before it runs.
- `Protocol` starts incoming request handlers in arrival order too, on the same single thread as the notification handlers: a request's handler runs until its first suspension, or its return, before the handler of any later request or notification, and so before any message that arrives after a later notification. The read loop doesn't wait for it otherwise, so a response that arrives right after a request may be handled, hook included, before that request's handler starts. The client's permission handler relies on this to join a subagent's cancel race before a later `session/update` is sorted, so keep that part non-suspending, and don't give such a handler a dispatcher in its additional context.
- `RequestOutcome.onResponseQueued` is the request's response-queued hook. It runs right after the transport accepted that request's success response, before its `afterResponse` and before work waiting for the frame resumes, so whatever it sends follows the response on the wire. It doesn't run for an error or when the frame never reaches the transport (`onCompletion` is where to detect that). It must not suspend, block or throw. `Protocol` stores it in the request's `ResponseSlot` before it completes the slot's response, so the frame's sender sees it; `mapResponse` keeps it.
- `Protocol.newConnectionScope` is the home of work that belongs to the connection but outlives a request, such as the collections of a root's subagents. It is a supervisor scope under the protocol's job, so it ends with the connection, and it uses the dispatcher of the scope the protocol was given, not the handler's, so slow app code in it doesn't delay handlers.
- The v1 agent exposes subagents only through `AgentSubagents.announce`. The first thing sent for a child is its own `SubagentUpdate`, from the factory through the child's operations, before the factory returns; the `events` are collected only afterwards. Until it is sent, every other operation of the child fails, and a child whose factory throws or returns without it, or whose announcement can't be sent, is unregistered. Routing has one rule: a child's own `SubagentUpdate` goes on its parent's stream, everything else on the child's. A root's `notify` rejects every `SubagentUpdate`, so the SDK sees each announcement and grant, and it rejects `session_message`, `session_message_chunk` and `ToolCallContent.Session` unless the client advertised the `subagents` capability. Keep these checks in the one place every root path sends through.
- A child's cancel grant is recorded before the `SubagentUpdate` that carries it is sent, and restored if the send fails, so a `session/cancel` the client sends right after seeing the grant is routed. `session/cancel` on a child id reaches `AgentSubagentSession.cancel` only while the grant is in effect. While the factory still runs with a grant in effect, it is latched and delivered once, outside `announce`, if the grant still holds when the factory returns. In every other case it is ignored, since a notification can't get an error. Every other session method on a child id is rejected.
- The SDK reports a child's work state only as `unknown`, only when its `events` feed ends (completes or throws) while the last state sent through `events` is active, and never invents an outcome: the app reports `cancelled` or `error` through `events`. State sent through the child's own operations isn't seen there. The one exception is a factory that throws after the announcement: the SDK then revokes the grant and reports `unknown` if the factory left the state active. SDK cancellation of a collection (root close, connection end) sends nothing. A `CancellationException` from the app's factory, with the caller still active, surfaces as `IllegalStateException`, so it can't tear down the caller's scope.
- On `session/close`, after the root's `AgentSession.close` returns and before the response, the agent removes the root and every descendant at every depth, cancels the root's subtree scope, and makes their operations and `announce` fail. It removes the root by identity, so a root that replaced it while `close` ran stays. If `close` throws, the root and its children stay registered. A `session/close` for an id that is neither a root nor a subagent, unknown or already closed, gets an empty result without any app code, as ACP's idempotent close asks; a subagent's id is still rejected. No app code is called for children: stopping their work is the root's `close`. Only a child's updates are guaranteed not to follow the response; a request a child already had in flight, and its `$/cancel_request`, may. When opening a session replaces a root still open under that id, as `session/load` and `session/resume` may, the same cleanup runs for the old root's subtree once the app's factory returns, before the replay and the response; the old root's turn and `AgentSession` are left alone, and a later failure of the load doesn't restore the old root. Opening a root under a subagent's id fails.
- The v1 agent keeps its roots and subagents in one atomic snapshot (`SessionTable`). Every rule that spans both, registering a subagent only while its root is registered, opening, replacing or removing a root together with its subagents, and keeping root and subagent ids apart, is checked and applied in one update of it. Keep it that way: a check in one update and a change in another lets a registration commit under a root that is already gone. Cancelling the subtree scope and marking the removed children run after the update.
- During `session/load` the root is replaying, from the start until the transport has accepted the response (the window closes from the load's `RequestOutcome.onResponseQueued`, not from `afterResponse`). Everything sent for the subtree in that window is history: a grant sent then is never in effect, and a state sent then doesn't count as active. The `events` collections of replayed children are deferred and start when the window closes, before `postInitialize()`, so a slow `postInitialize()` doesn't keep the children unconfirmed. A failed load, including a response that never reached the transport, drops the root with every registration made during it.
- A child's lock makes each of its own `SubagentUpdate` sends atomic with the phase change and grant tracking, and orders every send for the child against dropping or removing it. Hold it only around non-suspending sends, never nest it inside another lock, and never call app code, or complete a deferred, under it.
- The v1 client gives each subagent its own `SubagentSessionHolder`, created only by the `session/update` handler under its parent's holder, never through the speculative path. The holders form a tree: each keeps its children, failed ones too, and seals them (`sealChildren`) when it fails, so a child registered at that moment is either failed by the cascade or not registered. A holder keeps its children by id and its public `subagents` list in one atomic `Children` state, so one update seals both, and adding a child (`addChild`) and listing a set-up subagent (`listSubagent`) check the same flag. `markOpened`, the failure cascade and close walk the tree, and only the handler adds children. A subagent holder is exempt from the reaping at the end of the last opening, and a failed one keeps its id taken while its parent lives. A failed root's descendants leave the session map when the root is reaped or replaced.
- `ClientSessionRegistry.deliverSessionUpdate` sorts each update when it arrives, before anything suspends: a `subagent_update` of a registered child is a `Patch`, one that announces a new id is an `Announcement` and registers the child's holder (`registerChild`), and every other update, or a rejected announcement, is a plain `Update`. The lookup and the sorting are not `suspend` and must stay so, because the handler's first part runs before the next message is handled (see the notification rule above): a child registered later would have its next update treated as unconnected and its next request fail with "session not found". The consumer acts on the tag and never reads the session map again. `registerChild` adds the child to its parent first and to the session map second, in one compare-and-set that checks the id.
- The parent's consumer sets a child up when it reaches the announcement, in queue order, before it delivers the announcement: `createSubagentOperations`, then the `ClientSubagentSession`, then the parent's `subagents`, then the child's opening (`SubagentSessionHolder.startOpening`). So inside the parent's `notify` for the announcement the child exists, and the parent's stream waits while the setup runs. If the setup fails, only that child's holder fails. So a session's `subagents` lists every child its history announced at that session's own `Opened`; deeper levels are complete at each child's own `Opened`, which nothing orders against its parent's.
- A child's opening runs in the connection scope (`Protocol.newConnectionScope`), not in the handler's coroutine. It delivers the child's queue up to the marker and `SessionEvent.Opened(null)`, then waits for its parent's holder to complete, and only then closes the queue, delivers the rest and completes its own holder. The root's opening can still fail after its answer, and every holder below fails with it, so a child that had completed would stay live under a failed root. The cost is that a child's live updates and requests wait for the root's opening call. An opening calls `ensureActive()` before it takes each item, because the failure cascade cancels openings without joining them.
- `markOpened`, in the opening call's response hook, puts `Opened(null)` into the queue of every subagent below the root, and a subagent registered after the answer gets it as its first item, when its holder is created, before it is reachable. The hook and the handler both run in read-loop order, so each child gets its marker exactly once, as long as a child's queue stays open until it has delivered it.
- Once a holder is complete, an announcement always takes the `lateDeliveries` chain, even when nothing waits for it: its setup may suspend in `createSubagentOperations`, and the later updates of that holder must wait for the setup, as they do in the queue.
- The members of `ClientSubagentSession` and its `subagents` are plain getters, not `StateFlow`s. The parent's consumer applies each patch before it delivers the event that carries it, and holds back `capabilities` and `state` patches that arrive before the parent's `Opened` until it: they take effect after a resume and are dropped after a load. A flow beside the stream would be a second channel that can show a value before the event that caused it, so don't add one. During a turn a root's updates come from the buffered `prompt` flow, not from `notify`, so the getters may be ahead of that flow; inside `notify` they match the stream.
- `SubagentPendingPermissions` is fed in wire order, from the registry's sorting at arrival, not from the parent's consumer, which can lag behind by as long as the app's `notify` takes. A live `onState` records whether the state confirms foreground work before it calls `clearCancellation()`. A child's `session/request_permission` joins the cancel race when it arrives: the handler takes the child's holder (`subagentHolderOrNull`) and its permissions before anything suspends, and the wait for that same holder runs inside the race's `answer` block, without a second lookup of the id. So a cancel, an `idle(cancelled)` or a close answers a request that still waits for the holder, and the app sees a request only after the holder completed. A state that arrives before the root's answer is held back, its effect included: an `idle(cancelled)` sets aside the requests pending at its arrival (`setPendingAside`), and `onOpened` answers them `cancelled` after a resume, as the live `idle` would have, and leaves them pending like the others after a load. Keep that race the only answering path.
- The root's holder keeps its lifecycle in one atomic `RootPhase`: the stage, `UNCLAIMED`, `CLAIMED`, `ANSWERED` or `CLOSING`, read only through `isAtLeast` because each stage includes the earlier ones, and the claim's `opening`; read `phase` once for a decision that needs both. `RootSessionHolder.startClosing` first moves it to `CLOSING` and then closes the descendants' permissions, and `addChild` reads it after adding the child and closes the new child's permissions, so a child that the walk misses is closed by its own registration. It stays `CLOSING` after a failed close, as the closed permissions do, and it needs no lock. `startClosing` throws for a root that isn't answered yet: only the close of the root's own session calls it, and only while that holder is the registered, live one. Inside its own update of the session map, `registerChild` checks that the root's id still maps to the root's holder: `removeSessionTree`, the reaping at the end of the last opening and `replacing` take the root's entry out no later than its descendants, so a registration after the removal stays out of the map, and one before it is removed with the tree. Don't use the `CLOSING` stage for that: only a successful response starts the removal, while a failed close must leave the root `CLOSING`.
- A v1 `ClientSession.close()` acts on its own holder, never on whatever its id maps to now: the request carries only the id, so a close of a session that is gone would close a later session of that id. It sends `session/close` only while its holder is the registered holder of the id and blocks it (`ClientSessionRegistry.isOpen`), read once at the start. Otherwise it sends nothing and touches no session: a holder that has failed means the opening failed, and the call throws `IllegalStateException`; any other means the session was closed, because a root's holder that blocks its id leaves the map only through `removeSessionTree`, and the call returns an empty `CloseSessionResponse`. While the guard holds, no other opening of the id can start, so `startClosing` and `removeSessionTree` take the holder itself, and `startClosing` accepts only an answered or closing root. Two closes in flight on the open session both send, and the second removal removes nothing.
- On `session/close` the client first calls the root holder's `startClosing`, because the agent's close may wait for child work that waits for a permission answer. After a successful response `removeSessionTree` seals each holder's children and `subagents` top-down, stops and joins every subagent's opening, and then, under `NonCancellable`, removes the whole tree from the session map in one update, each holder only if its id still maps to it, and finally, bottom-up, closes each subagent's permissions, marks it closed and fails its holder if that hasn't completed. An answer with `-32002` counts as a successful close, as ACP asks; any other failed close removes nothing.
- A subagent's operations may call `close()` from their `notify`, which runs in its opening. `removeSessionTree` doesn't join that opening, because it would wait for itself, and cancels it at the end. It counts an opening as its own if the close runs below the opening's job or its context carries the opening's `SubagentOpening` element. Keep both signs: the element survives `withContext(NonCancellable)` and other replaced jobs, where the opening waits for the close without reacting to cancellation, and the ancestry covers a coroutine below the opening whose context dropped the element. A false match only skips a join, for example for an app coroutine that copied the context into another scope, while a missed one deadlocks. A close in a coroutine of another scope with a context of its own that `notify` waits for without reacting to cancellation still deadlocks; the `ClientSession.close` KDoc says so.
- A child is marked closed when its holder fails, because its own `notify` throws before its `Opened` or an ancestor's holder fails, and when its root's tree is removed after a successful close, even if its holder has completed: `canCancel` is false, `cancel()` throws and later patches don't apply. It stays in its parent's `subagents`, which only grows until the parent's holder is sealed, because it fails or its root is closed, after which a setup that finishes drops its child instead of adding it.

For WebSocket shutdown, preserve the established sequence: reject new work, enter closing state, drain accepted frames within the configured timeout, then perform a normal WebSocket close. Reserve cancellation/abort for timeout, cancellation, or writer failure.

For STDIO, preserve newline-delimited frame behavior. Protocol output belongs on stdout; diagnostics and logs belong on stderr.

## Public API and Compatibility

The published modules use strict explicit API mode and Kotlin's binary compatibility validator.

- Write explicit visibility and return types for public declarations.
- Design public APIs deliberately; avoid exposing an internal implementation type for convenience.
- Preserve high-level Agent, Client, and session-facing ergonomics when refactoring lower layers.
- Treat constructors, default values, interfaces, sealed hierarchies, annotations, and public constants as API changes.
- Add KDoc for public behavior whose lifecycle, failure, ownership, or compatibility contract is not obvious.
- `internal` packages hold only non-public declarations: `check` enforces it through the API dumps with `checkInternalPackagesNotPublic`, which fails if a dump lists a class from a package with an `internal` segment.
- If a public API change is intentional, update the affected checked-in `.api` dump with that module's `apiDump` task.
- Review API dump diffs as source changes. Do not accept generated API changes blindly.
- Run API generation and verification as separate Gradle invocations; combining `apiDump` with `apiCheck`/`check` can cause implicit task-dependency validation failures.
- Do not edit files under `build/generated-sources` or other `build` directories. The `LIB_VERSION` constants are generated by Gradle.

Example:

```bash
./gradlew :acp:apiDump
./gradlew :acp:apiCheck
```

Replace `:acp` with the affected published module. After generating the dump, run the appropriate tests and `./gradlew check` for core or public API changes.

## Model and Schema Changes

ACP model changes must be traceable to the upstream schema or an intentional SDK behavior.

- Compare model changes directly with the relevant upstream ACP schema and record the source revision in the change description.
- Keep stable and unstable schema changes separate where practical.
- Mark all unstable-schema types with `@UnstableApi`.
- Update serializers and serialization tests together with model changes.
- Preserve wire names, optionality, nullability, tagged-union behavior, and unknown-value behavior required by ACP.
- Verify related stable/v2 conversions when a field exists on both sides.
- Regenerate and review `acp-model/api/acp-model.api` for public model changes.

Do not infer wire compatibility from similar Kotlin types. Confirm serialized JSON shapes with tests.

## Kotlin Style

Follow the official Kotlin style configured in `gradle.properties` and the existing package layout.

- Prefer clear, direct control flow over clever indirection or double negatives.
- Keep functions focused, but do not split a simple sequence into tiny helpers without a meaningful abstraction.
- Reuse shared serializers, operations, and lifecycle utilities instead of duplicating behavior.
- Avoid double serialization, discarded preflight work, and conversions that exist only to convert back immediately.
- Choose names that describe protocol meaning and lifecycle state, not incidental implementation details.
- Keep logging structured and at the correct level. Never write diagnostics to a protocol data stream.

### Logical spacing

Use blank lines to separate distinct logical steps inside a function or code block. For example, construction, registration, execution, and cleanup may form separate groups.

Do not add a blank line after every statement. Short, closely related steps should stay together when separating them would inflate the code without improving readability.

### File layout

- One concern per file, named after its main declaration. Helpers live with what they serve: private declarations, parameter and result types, and extensions. Coroutine-context elements and their accessors live with the family's base.
- A file with several top-level declarations stays under about 400 lines. A single component may be as long as it needs.
- For a class with subclasses, either its whole subtree is in its file, or each direct subclass gets its own file, and its own subtree follows the same rule. Keep the subtree together while it fits the length limit. Nested subclasses are in the base's file by definition. Siblings that share one concern may share a file named after that concern.
- Internal runtime machinery goes in the `internal` subpackage, as the `:acp` map above describes.

## Comments and KDoc

Comments should explain intent, constraints, protocol requirements, lifecycle subtleties, or non-obvious tradeoffs.

- Keep comments concise, concrete, and in plain language.
- Do not write verbose or vague comments.
- Do not comment every minor operation or restate the code.
- Add comments only where the code is non-trivial or needs context to be understood correctly.
- Use a block comment for a multiline non-KDoc comment. Do not write several consecutive `//` lines.

Use:

```kotlin
/*
 * Preserve the frame boundary so batch responses remain a single wire value.
 */
```

Avoid:

```kotlin
// Preserve the frame boundary so batch responses
// remain a single wire value.
```

Every KDoc must begin on a new line after `/**`, including a one-sentence KDoc. Never write a single-line KDoc.

Use:

```kotlin
/**
 * Sends one complete transport frame.
 */
```

Never use:

```kotlin
/** Sends one complete transport frame. */
```

Keep KDoc focused on the public contract. Document parameters, results, exceptions, cancellation, ownership, or lifecycle semantics when they are not evident from the signature.

### Where property and parameter documentation goes

The location of a declaration decides where its documentation lives:

- A public property declared in the primary constructor is documented in the class KDoc with a `@property` tag. Do not put a KDoc comment above a primary constructor property.
- A property declared in the class body is documented with a KDoc comment directly above it.
- A primary constructor argument that is not a public property is documented in the class KDoc with a `@param` tag.

This keeps a type's whole contract readable in one place and keeps the constructor's parameter list scannable.

Example:

```kotlin
/**
 * A chunk of bytes appended to an agent-owned terminal's output.
 *
 * @property data independently base64-encoded terminal output bytes
 * @property _meta chunk-scoped metadata; omitted and `null` are equivalent
 */
public data class TerminalOutputChunk(
    val terminalId: TerminalId,
    val data: String,
    override val _meta: JsonElement? = null,
) : AcpWithMeta
```

Phrase `@property` and `@param` text as a lowercase continuation with no trailing period, matching the existing `@param` and `@throws` style. Not every property needs a tag: document the ones whose contract is not evident from the name and type, and leave the rest to the class prose.

### Structuring long comments

A long comment must be easy to scan. Never write a wall of text.

- Keep paragraphs short, with one idea each.
- Put enumerations, alternatives, and case matrices, such as "before X / after X" or a list of failure cases, in bullet lists instead of chained clauses.
- Split a long KDoc, meaning several paragraphs or several distinct topics, into sections with Markdown headings. Use `###` for a section and `####` for a subsection. Level 3 keeps them below the page headings that Dokka generates.
- Put a heading on its own line, followed by a blank KDoc line.
- Keep a short comment, one or two paragraphs, as plain prose without headings.
- A long non-KDoc block comment does not render Markdown. Give it the same structure with separate paragraphs and lists; headings are optional.

The `AgentSubagents` class KDoc is the example for sections (`### Removal`, `### Replay`), and `AgentSubagents.announce` is the example for bulleted failure cases.

Use:

```kotlin
/**
 * Closes the session and frees its id.
 *
 * ### Pending requests
 *
 * Requests in flight when the call starts are answered before it returns. Requests that arrive later fail with
 * [IllegalStateException].
 *
 * ### Failure
 *
 * The call throws when:
 * - the session is already closed;
 * - the transport rejects the request.
 *
 * Nothing is removed then, and the session can be closed again.
 *
 * ### Cancellation
 *
 * If the caller is cancelled, the request is still sent, and the session counts as closed once the agent answers.
 */
```

Avoid:

```kotlin
/**
 * Closes the session and frees its id. Requests in flight when the call starts are answered before it returns, and requests that arrive later fail with [IllegalStateException]. The call throws when the session is already closed or when the transport rejects the request, in which case nothing is removed and the session can be closed again, and if the caller is cancelled, the request is still sent, and the session counts as closed once the agent answers.
 */
```

## Testing Strategy

Use proportional verification while keeping core changes well covered.

### Narrow changes

- Run the closest module or test task first.
- Prefer a focused test class while iterating when the test task supports `--tests`.
- Run serialization tests for model/serializer changes.
- Run protocol lifecycle and batch tests for correlation, cancellation, framing, or dispatch changes.
- Run `:acp-integration-test` coverage for behavior crossing client/server or STDIO/WebSocket boundaries.

Example focused commands:

```bash
./gradlew :acp:jvmTest --tests com.agentclientprotocol.protocol.BatchProtocolTest
./gradlew :acp-integration-test:jvmTest --tests com.agentclientprotocol.WebSocketTransportTest
```

### Core and public changes

For changes to shared architecture, protocol behavior, transports, build conventions, public APIs, or schema models:

1. Run focused tests while iterating.
2. Run `apiDump` separately if the public API intentionally changed.
3. Review the API diff.
4. Run `apiCheck` or `./gradlew check` in a separate invocation.
5. Run `./gradlew check` before completion.

CI runs a clean Gradle build on JDK 21. Use `./gradlew clean build` when reproducing CI behavior or when stale build outputs may matter; do not use `clean` for every local iteration.

If a target cannot run in the current environment, report exactly what ran and what remains unverified. Do not claim success from compilation alone when behavior changed.

## Test Placement and Design

- Put portable unit tests in `commonTest`.
- Put JVM-only process, filesystem, or transport integration tests in `jvmTest`.
- Keep tests near their owning module; use `:acp-integration-test` for cross-module transport scenarios.
- Test externally observable behavior and lifecycle outcomes, not private implementation order unless the order is itself contractual.
- Include failure, cancellation, and cleanup paths for lifecycle code.
- For configuration or timing behavior, include a control case that differs only in the setting under test.
- Use explicit JSON assertions for serializer changes, including absent versus explicit `null` when meaningful.
- Add regression tests for bugs, and make the test fail for the original reason.
- Avoid sleeps when a channel, deferred, state flow, or test scheduler can express the condition deterministically.

## Documentation and Samples

Update documentation in the same change when behavior, supported targets, setup, examples, or public workflows change.

- Keep `README.md` aligned with actual modules, versions, requirements, and sample commands.
- Keep samples compiling against public APIs and representative of supported patterns.
- Update this `AGENTS.md` whenever a core change affects global architecture or shared patterns.
- Keep detailed publication and release instructions in `PUBLISHING.md`; do not duplicate them here.

## Publishing

See [PUBLISHING.md](PUBLISHING.md) for versioning, credentials, workflows, validation, and release procedures. Publishing changes require explicit user authorization; building or validating the repository does not imply permission to publish an artifact or create a release.

## Change Workflow

Before editing:

1. Read the relevant module build file and nearby implementation/tests.
2. Trace behavior through the owning layer instead of patching the first visible symptom.
3. Check for stable/v2, STDIO/WebSocket, or common/platform-specific counterparts.
4. Check the working tree and preserve unrelated user changes.

While editing:

1. Keep the change narrowly scoped.
2. Maintain module and lifecycle boundaries.
3. Add or update focused tests with behavior changes.
4. Update KDoc, docs, samples, API dumps, and this guide when their contracts change.

Before completion:

1. Inspect the diff for accidental generated files, formatting noise, secret material, and unrelated edits.
2. Run proportional focused checks.
3. Run full `./gradlew check` for core or public changes.
4. Report the exact verification performed and any remaining limitations.

## Working Files

### Scratch files

Put throwaway files, such as experiments, captured output, and ad hoc scripts, in `scratch/` at the repository root. It is gitignored. Do not create scratch files anywhere else in the repository.

### Working files in `.agents/`

`.agents/prompt.md`, `.agents/state.md`, and `.agents/research/` are working files for the current goal of a multi-agent session run with the `orchestrator` skill. They are deleted before the PR merges.

Never reference them in the final code: not in comments, KDoc, tests, samples, docs, or build scripts, because such a reference would dangle. A temporary reference is fine while the work that needs it is in progress. By the time that work is done, remove it or replace it with a permanent upstream citation or a plain explanation.

## Agent Hygiene

- Do not overwrite or revert unrelated working-tree changes.
- Do not edit generated build output.
- Do not commit credentials, `.env`, signing keys, tokens, local IDE state, or Gradle caches.
- Do not publish, tag, create releases, or push changes unless explicitly requested.
- Prefer Gradle wrapper tasks over locally installed Gradle.
- Keep dependency and tool versions centralized in `gradle/libs.versions.toml` when possible.
- Preserve pinned GitHub Action revisions unless intentionally updating and reviewing them.
- Treat warnings, skipped tests, and unavailable targets as evidence to report, not results to hide.
