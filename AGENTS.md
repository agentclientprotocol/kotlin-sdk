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

The public API dump is `acp/api/acp.api`.

### `:acp-ktor`

Shared Ktor transport infrastructure, including `WebSocketTransport`. It builds on `:acp` and owns transport behavior that is common to Ktor clients and servers. Its public API dump is `acp-ktor/api/acp-ktor.api`.

### `:acp-ktor-client` and `:acp-ktor-server`

Client-side and server-side Ktor adapters. Keep shared WebSocket behavior in `:acp-ktor`; keep endpoint-specific setup in the corresponding adapter module. Their API dumps are in each module's `api` directory.

### `:acp-ktor-test`

Cross-transport integration and conformance-style tests. Use this module for behavior that should hold across STDIO/WebSocket or client/server wiring. It is a test module, not a published API module.

### `:samples:kotlin-acp-client-sample`

Runnable examples for stable ACP, direct v2 use, negotiation, and external-agent integration. Samples should demonstrate supported public APIs, not internal shortcuts.

### `buildSrc`

Shared Gradle convention and publishing plugins. `acp.multiplatform.gradle.kts` defines targets, explicit API mode, the JDK toolchain, and generated library-version constants. Changes here affect most modules and are core architectural changes.

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
- parsed but invalid JSON-RPC shape: Invalid Request (`-32600`).

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
- Keep normal close, timeout/abort, parent cancellation, and I/O failure paths distinct and testable.

For WebSocket shutdown, preserve the established sequence: reject new work, enter closing state, drain accepted frames within the configured timeout, then perform a normal WebSocket close. Reserve cancellation/abort for timeout, cancellation, or writer failure.

For STDIO, preserve newline-delimited frame behavior. Protocol output belongs on stdout; diagnostics and logs belong on stderr.

## Public API and Compatibility

The published modules use strict explicit API mode and Kotlin's binary compatibility validator.

- Write explicit visibility and return types for public declarations.
- Design public APIs deliberately; avoid exposing an internal implementation type for convenience.
- Preserve high-level Agent, Client, and session-facing ergonomics when refactoring lower layers.
- Treat constructors, default values, interfaces, sealed hierarchies, annotations, and public constants as API changes.
- Add KDoc for public behavior whose lifecycle, failure, ownership, or compatibility contract is not obvious.
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

## Testing Strategy

Use proportional verification while keeping core changes well covered.

### Narrow changes

- Run the closest module or test task first.
- Prefer a focused test class while iterating when the test task supports `--tests`.
- Run serialization tests for model/serializer changes.
- Run protocol lifecycle and batch tests for correlation, cancellation, framing, or dispatch changes.
- Run `:acp-ktor-test` coverage for behavior crossing client/server or STDIO/WebSocket boundaries.

Example focused commands:

```bash
./gradlew :acp:jvmTest --tests com.agentclientprotocol.protocol.BatchProtocolTest
./gradlew :acp-ktor-test:jvmTest --tests com.agentclientprotocol.WebSocketTransportTest
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
- Keep tests near their owning module; use `:acp-ktor-test` for cross-module transport scenarios.
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

## Agent Hygiene

- Do not overwrite or revert unrelated working-tree changes.
- Do not edit generated build output.
- Do not commit credentials, `.env`, signing keys, tokens, local IDE state, or Gradle caches.
- Do not publish, tag, create releases, or push changes unless explicitly requested.
- Prefer Gradle wrapper tasks over locally installed Gradle.
- Keep dependency and tool versions centralized in `gradle/libs.versions.toml` when possible.
- Preserve pinned GitHub Action revisions unless intentionally updating and reviewing them.
- Treat warnings, skipped tests, and unavailable targets as evidence to report, not results to hide.
