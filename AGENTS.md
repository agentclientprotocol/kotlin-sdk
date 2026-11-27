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
- Keep normal close, timeout/abort, parent cancellation, and I/O failure paths distinct and testable.
- Both client runtimes answer a session's pending `session/request_permission` requests only through the `PendingPermissions` race against the session's cancel signal. Don't add a second answering path: each request gets exactly one response.

For WebSocket shutdown, preserve the established sequence: reject new work, enter closing state, drain accepted frames within the configured timeout, then perform a normal WebSocket close. Reserve cancellation/abort for timeout, cancellation, or writer failure.

For STDIO, preserve newline-delimited frame behavior. Protocol output belongs on stdout; diagnostics and logs belong on stderr.

### Shared state

- Keep state that several coroutines or threads share in immutable values behind atomics, with one atomic for all the state that one rule connects, as `SessionTable` does. Don't split a rule's state over several atomics and keep the rule true by the order of writes and reads. A plain `var` holds only state that one coroutine owns at a time.
- Decide from one `value` load. If a decision needs two atomics, a rule connects them: fold them. The value may be stale, so a decision that changes state is made in the write itself.
- Write with a pure CAS (`update`, `getAndUpdate`, `compareAndSet`). The lambda can run more than once, so it only computes the new value from the old one: it assigns nothing outside, reads no other atomic, and calls nothing with an effect. It may throw to reject the update. Read results from the snapshot that `getAndUpdate` returns.
- Take a lock (`SynchronizedObject`) only when no one may see the update without an effect that can't lag it, such as a send whose place on the wire matters. Every writer of that atomic then takes the lock and publishes with `value =`, and readers still don't lock. If a failed effect makes the section restore the old value, say so at the site: a reader may have seen the new one.
- Under a lock, call only code that doesn't suspend, block, take another lock, or call back into code that takes this lock. App code, and completing or cancelling a deferred or a job, can do any of that, so do them after the lock.
- Use a `Mutex` only for a section that must suspend.
- An operation may change several components in steps. State that can't be a value, such as a job, a deferred or a channel, follows one deciding CAS: its winner runs the other steps once, after it. Each step must be harmless to observe before the others, and removals compare identity, so a late step changes nothing.

### Read-loop ordering and hooks

- `Protocol` starts incoming notification and request handlers in arrival order, on one thread, and runs each until its first suspension, or its return, before any later handler starts. A notification's first part also runs before the next message, but a response that arrives right after a request may be handled before that request's handler starts. Code that relies on this, such as the client's `session/update` sorting and its permission handler, must keep that first part non-suspending and must not give the handler a dispatcher in its additional context.
- The response hook of `sendRequestRaw` (and the typed `Protocol.sendRequest`) runs in the read loop for a success response, before the caller resumes and before the next message. It must not suspend, block or throw. It may have run for a call that then ends with `CancellationException`, so the caller's failure path must undo what the hook did.
- `RequestOutcome.onResponseQueued` runs once the transport has accepted the success response, before `afterResponse`, so what it sends follows the response. It must not suspend, block or throw, and `mapResponse` must keep it. Use `onCompletion` to detect a response that never reached the transport.
- Run work that belongs to the connection but outlives a request, such as subagent collections and openings, in `Protocol.newConnectionScope`, not in a handler's coroutine.

### v1 agent

- One turn runs at a time per session, an SDK guarantee that ACP doesn't define. Only the turn frees its slot, when it answers. A `session/cancel` targets the turn registered when it arrives, and a cancelled turn answers only after every such `AgentSession.cancel()` has returned, so updates sent from it precede the response. Keep a turn's flow failure inside the turn's job so the turn still passes that wait.
- `SessionTable` holds roots and subagents in one atomic snapshot. Check and apply each rule that spans both in one update: a subagent registers only while its root is registered, a root is opened, replaced or removed together with its subagents, and root and subagent ids stay apart. A check in one update and a change in another lets a child commit under a root that is already gone.
- A `SubagentSessionWrapper` is pending until its own `SubagentUpdate` is sent from the factory, and every other operation fails until then. It is dropped (unregistered) if the factory throws or returns without the announcement, and removed by subtree cleanup. Its `events` are collected only after the factory returns, and a replayed child's only when the replay window closes.
- A child's own `SubagentUpdate` goes on its parent's stream, everything else on the child's. A root's `notify` rejects every `SubagentUpdate`, so the SDK sees each announcement and grant, and rejects `session_message`, `session_message_chunk` and `ToolCallContent.Session` without the client's `subagents` capability. Keep these checks in the one place every root path sends through.
- Record a child's cancel grant before sending the `SubagentUpdate` that carries it, and restore it if the send fails, so a `session/cancel` sent right after the grant is routed.
- The SDK never invents a child's outcome. It reports only `unknown`, when the `events` feed ends while the last state it sent is active, or when a factory that throws after the announcement left the state active. A collection that the SDK cancels sends nothing.
- A root's subtree is removed, its scope cancelled and its children's operations made to fail, before the response: on `session/close` after `AgentSession.close` returns, and, when a load or resume replaces an open root, once the app's factory returns and before the replay. No app code runs for the children. The root is removed by identity, so a root that replaced it stays. A later failure of the replacing load doesn't restore the old root.
- During `session/load`, everything sent for the subtree is history until the transport has accepted the response: a grant is never in effect and a state doesn't count as active. The window closes from `onResponseQueued`, not `afterResponse`, because the client treats whatever follows the response as live, and so the replayed children's collections start before a slow `postInitialize()`. A failed load drops the root with every registration made during it.
- A child's lock orders its sends against dropping or removing it.

### v1 client

- The client keeps one `ClientSessionHolder` per session id, and at most one opening call claims a root's holder (`claimForOpening`), so a second opening of an open or opening id fails, for every overload.
- Until the switch to the session, a root's updates and its `Opened` marker pass one queue with one consumer at a time; the opening call's response hook queues the marker, so it keeps its wire position. In the early-delivery path that consumer hands the queue to the operations until the answer. Keep this the only delivery path, with no second path, lock or counter. Late updates keep their order through the `lateDeliveries` chain.
- `Opened` is delivered only after the opening request has returned, so a failed or cancelled request delivers none. The rest of the opening runs app code in the caller's coroutine, cancellably, not in `NonCancellable`. A cancellation or `notify` failure there still fails the holder, even though the operations may already have the marker.
- Only `session/update` creates speculative root holders. `awaitSession` never creates a holder or waits for an unclaimed one; it fails at once with `-32002`, because that wait would have no bound and the holder would block a later subagent with that id.
- The registry sorts each `session/update` into an update, an announcement or a patch, and registers an announced child, when it arrives, before anything suspends. Keep that non-suspending, and let the consumer act on the tag without reading the session map again.
- Only that sorting creates a subagent's holder, under its parent's, never through the speculative path. The tree lives only in the registry's one snapshot, beside the session map, the failed holders that take no children and the closing roots, so each rule that spans them is checked and applied in one update. A holder's `subagents` list has its own seal, set on failure or close after that update.
- Each subagent gets its `Opened` marker exactly once. `markOpened`, in the root's response hook, queues it for every subagent below the root, and a subagent registered after the answer gets it when its holder is created, before it is reachable. Both run in read-loop order, so keep a child's queue open until it has delivered the marker.
- A subagent's opening completes its holder only after its parent's holder has completed, because the root's opening can still fail after its answer.
- Each holder owns its session's `PendingPermissions`, a root's as well as a subagent's. `SubagentPendingPermissions` is fed in wire order from the sorting at arrival, not from the parent's consumer, which can lag. A permission request, a root's or a child's, joins the cancel race when it arrives: the handler takes the holder before it suspends and waits for that same holder inside the race, with no second lookup of the id.
- A state that arrives before the root's answer is held back. Requests that an early `idle(cancelled)` set aside are answered `cancelled` after a resume and stay pending after a load.
- Don't add flows beside the stream for `ClientSubagentSession`'s members: the consumer applies each patch before it delivers the event that carries it, and a flow could show a value before its event.
- A close marks its root closing in the snapshot and takes the root's descendants from the snapshot it replaced, so a child registered later sees the mark in its own update and has its permissions closed. A failed close leaves the root closing until its tree leaves the map.
- A holder in the session map owns its id. A failed root's tree leaves the map when its failure cascade ends (`failRootTree`), not before its drains, so an update that arrives meanwhile follows the queued ones. A failed subagent keeps its id until its root's tree goes.
- `ClientSession.close()` acts on its own holder, never on whatever its id maps to now, since the request carries only the id. It sends only while its holder still owns the id (`ClientSessionRegistry.isOpen`). Otherwise it sends nothing: a failed holder means the opening failed and the call throws, and any other holder that is gone was closed, so it returns an empty response.
- The close removes the tree only after a successful response (`-32002` counts as one), and it cancels subagent openings without joining them, so a subagent's `notify` may still run after `close()` returns.
- The first stop of a subagent's opening (`markOpeningStopped`) settles its holder: the stopper fails it, or an opening that was already completing completes it. Decide from the state that the stop replaced, never from a later read, and fail a subagent's holder from nowhere else, so two failures never drain one queue.
- A listed subagent is marked closed when its holder fails, even after it was listed, and when its root's tree is removed, even if it had opened. It stays in its parent's `subagents`. A setup that finishes after its parent was sealed drops its child.

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

A serializer defines its type's wire shape, so it stays next to the model; there is no separate `serialization` package:

- A type's custom serializer lives in the model's file.
- A serializer base or helper used by several files gets its own file named after it, in the same package, such as `JsonObjectSerializer.kt` or v2 `OpenTaggedUnionSerializer.kt`.
- A file over the length limit splits by model concern, and the serializers move with their types, as in `SubagentSessionUpdateSerializers.kt`.

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

### Public versus internal documentation

How much a KDoc says depends on who reads it:

- A public declaration may have a detailed KDoc when it documents the contract and explains non-obvious behavior.
- An internal or private declaration, including everything in an `internal` package, gets a few short sentences: why it exists and what it does. Leave the rest to the code: its names and structure.
- Explain a non-obvious implementation detail inline, at the line it concerns, in as few words as possible. Do not collect such details in the KDoc above.

In every comment, drop what a reader would not get wrong without it: restatements of the code, "see [X]" chains that only point at other documentation, and invariants already stated in another KDoc or in "Runtime and Lifecycle Rules". If a shorter comment leaves the code unclear, prefer a clearer name, condition, or extraction over a longer comment.

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
