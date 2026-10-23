# `e2e/rust-agent`: Kotlin clients against the Rust SDK's test agent

## Intent

The `e2e/tck` suite checks our *agents* against an external conformance kit. Nothing yet checks our *clients*
against an independent agent implementation. This suite fills that gap from the other direction: the v1 and v2
Kotlin `Client` runtimes drive the Rust SDK's deterministic test agent, `testy`, through broad, generic ACP flows.

It is an interoperability check, not a spec-conformance suite. Success means: most of the protocol functionality a
real client uses works end to end between the Kotlin SDK and the Rust reference SDK, for both protocol versions,
and a regression on either side shows up as a failing, individually runnable JUnit test.

## Goals

- Two Gradle subprojects, `:e2e:rust-agent:client-v1` and `:e2e:rust-agent:client-v2`, each holding JUnit tests that
  run the corresponding Kotlin client against `testy` over STDIO.
- A shared Gradle module, `:e2e:rust-agent:support`, next to the two clients, holding the utilities and the setup
  extension both clients use, since they are identical for v1 and v2.
- Both clients exercise a similar set of generic flows, limited only by what the agent supports for that version.
- Tests run the normal Gradle way, with all Gradle/JUnit options available, e.g.
  `./gradlew :e2e:rust-agent:client-v1:e2eTest --tests '*Cancel*'`.
- A fresh agent process per test, like acp-tck does, so tests are independent.
- An informational CI workflow that runs both suites on PRs.

## Non-goals

- Spec conformance verdicts or a requirement-by-requirement scorecard (that is acp-tck's job).
- MCP coverage (`call_tool`, `list_tools`) in this first pass; it needs an MCP server wired into the session.
- WebSocket transport; this suite is STDIO only.
- Maintaining our own Rust agent.

## Anti-goals

- The tests must not run, or appear as SKIPPED, during root `./gradlew check`. No "skip if condition" workarounds.
- No per-test build overhead: setup work, even an up-to-date check, must not run before every test.
- No workarounds in the clients for SDK defects; a failure should point at real SDK behavior on one side.

## Scope of this pass

The work happens in several passes. This pass only adds the e2e suite and documents the SDK bugs it discovers. It
does not fix SDK defects on either side; fixes come in later passes.

- A test that fails because of a real SDK defect stays enabled and red; CI is informational, so it does not block.
  Do not weaken its assertions to make it pass.
- Each discovered defect is recorded in a "Known failures" section of `e2e/rust-agent/README.md`: the failing
  test(s), the observed versus expected behavior, and which side (Kotlin SDK, Rust SDK/`testy`, or unclear) it points
  at. *Our call:* the README is the record for this pass; filing issues is left to the user.
- Before recording a failure as an SDK bug, rule out a defect in the test itself.

## Decisions

### Agent: upstream `testy`, v2 coverage deliberately thinner

We use the Rust SDK's own `testy` fixture (`src/agent-client-protocol-test`, binary `testy`), the same agent acp-tck
uses in its cross-check. We do not write our own Rust agent.

`testy` is driven by JSON prompt commands (`TestyCommand`): `greet`, `echo { message }`, and
`run_scenario { scenario }`, which gives tests precise control over what the agent does next.

- **v1** (`testy` built with `--no-default-features`): advertises `loadSession`, image/audio/embedded-context prompt
  capabilities, session `list`/`delete`/`resume`/`close`/`additionalDirectories`, an agent auth method
  (`testy-agent-auth`) and logout; supports modes and config options. Scenarios: `session_updates`, `content`,
  `tool_calls`, `callbacks` (permission, fs write/read, terminal create/output/wait/kill/release),
  `elicitations`, `cancel_status`, `wait_for_cancel`, `full`.
- **v2** (`testy` built with `--no-default-features --features unstable_protocol_v2`, which routes v2 connections to
  its native v2 agent): handles initialize, `session/new`, `session/list`, `session/resume`, `session/close`,
  `session/prompt`, and `session/cancel`. Prompts support only `greet`, `echo`, `wait_for_cancel`, and
  `cancel_status`; other scenarios answer with a `Refusal` stop reason. It sends no client callbacks and advertises
  only the basic session capability.

**Accepted limitation, documented in the suite README:** v2 coverage is intentionally thinner. We assume upstream
expands `testy`'s v2 agent as protocol v2 matures, and bumping the pin picks that up. This decision can be revisited:
if we need broader v2 coverage sooner, we can implement a custom Rust v2 agent.

### Rust SDK pin

The Rust SDK commit lives in a small checked-in file next to the scripts (the builder picks the name, e.g.
`rust-sdk.rev`). Initial value: `652d1003425319c170f1e677a1ca5af6a92269bb` from
`https://github.com/agentclientprotocol/rust-sdk`. Bumping it is a one-line change, documented in the README.

### `setup_sut.sh <1|2>`: the single source of truth for building the agent

One script in `e2e/rust-agent/`:

- fetches the Rust SDK at the pinned commit into a gitignored directory inside `e2e/rust-agent/`;
- builds only the requested version's `testy`, with all cargo flags in this one script; v2 uses its own
  `--target-dir` so the v1 and v2 builds never overwrite each other (mirrors acp-tck's cross-check);
- writes all fetch/build output to stderr;
- prints the absolute path of the built binary as the **last line of stdout**;
- exits non-zero on any failure.

Requires a Rust toolchain (`cargo`) and `git` on `PATH`. It must be runnable by hand too, which is handy for
debugging.

There is no `run_sut.sh`: running the agent takes no flags, only the binary path, so the tests start the binary
directly.

### Gradle: a dedicated `e2eTest` suite, outside `check`

Each client project uses Gradle's JVM Test Suite plugin and registers an `e2eTest` suite (sources in
`src/e2eTest/kotlin`), instead of the default `test` source set. Gradle only wires the default `test` task into
`check`, so:

- root `./gradlew check` never runs these tests and reports none as SKIPPED; the project's `test` task is
  `NO-SOURCE`;
- `./gradlew :e2e:rust-agent:client-v1:e2eTest` (or `client-v2`) runs them explicitly;
- an unqualified `./gradlew e2eTest` from the root runs both, which is acceptable.

Gradle does not call `setup_sut.sh`; the tests do.

Constraints from the build configuration (`gradle.properties`):

- `org.gradle.parallel=true` means an unqualified `./gradlew e2eTest` runs both suites at once, so concurrent
  `setup_sut.sh 1` and `setup_sut.sh 2` runs must not corrupt each other: use a lock or separate checkouts per
  version (builder's choice).
- `org.gradle.caching=true` must not serve a stale `e2eTest` result after a pin bump or a script change: declare the
  pin file and `setup_sut.sh` as task inputs, or disable caching for `e2eTest` (builder's choice).

*Our call:* the unit-level tests of the setup-output validation live in the `support` module's ordinary `test`
source set and run in `check`. They need no Rust toolchain and never start an agent, so the "no e2e tests in
`check`" anti-goal still holds.

*Our call:* this suite has no `run.sh`; the Gradle `e2eTest` task is its entry point. The "each suite has a `run.sh`
entry point" wording in `e2e/README.md` and `CLAUDE.md` gets relaxed to "a documented entry point (a `run.sh` or a
Gradle task)".

### Test lifecycle

The lifecycle below is identical for v1 and v2, so it lives in `:e2e:rust-agent:support` as a JUnit extension plus
shared utilities (running `setup_sut.sh`, validating its output, launching the agent process, wiring the STDIO
transport, teardown). The version (1 or 2) is the only thing a client project supplies. `support` depends on `:acp`
and JUnit only and contains no version-specific `Client` code; each client project depends on it from its
`e2eTest` suite.

- **Once per test class** (via the shared extension): run `setup_sut.sh <1|2>`, take the last
  stdout line, and validate it. If the script fails, or the last line is missing, empty, not an absolute path, not an
  existing file, or not executable, fail with a descriptive message stating that a valid path to the built agent
  binary was expected, and include what was actually received (and the exit code / stderr tail where available).
  This validation gets its own unit-level coverage.
- **Per test**: start the binary with `ProcessBuilder`, connect a `StdioTransport` to its stdin/stdout, run the flow
  through the public `Client` API, then shut down the client and make sure the process is gone (destroy it if it
  does not exit). The agent's stderr must be drained (e.g. redirected to a per-test log file) so it can neither
  block the agent nor pollute stdout.

### Flows to cover

Use only public APIs. Assert on observable results: response fields, the received `session/update` sequence, stop
reasons, and the client callbacks actually invoked.

**v1 (`client-v1`):**

- initialize: negotiated version, agent capabilities, auth methods;
- authenticate with `testy-agent-auth`, logout;
- session new, load, list, resume, close, delete; additional directories;
- set mode and set config option, including the resulting updates;
- prompts: `greet`, `echo`, and the scenarios `session_updates`, `content`, `tool_calls`, `callbacks`,
  `elicitations`; the client implements the permission, fs, terminal, and elicitation callbacks so the agent's
  requests succeed;
- cancellation: `wait_for_cancel` ends with `Cancelled` after the client cancels; `cancel_status` reports state.

**v2 (`client-v2`):** initialize; session new, list, resume, close; `greet`/`echo` prompts with their streamed
updates (user message, running state, agent message chunk, completion); cancellation via `wait_for_cancel` and
`cancel_status`. Add more as upstream `testy` v2 grows.

The two suites should mirror each other's structure and test names where a flow exists on both sides.

### CI

A new workflow (alongside `acpTck.yml`) on `pull_request` and `workflow_dispatch`, with a matrix over `client-v1` and
`client-v2`. It sets up JDK 21, Gradle, and a Rust toolchain, then runs the `e2eTest` task. The run step is
**informational** (`continue-on-error: true`), and it uploads the JUnit reports as an artifact. Cache the Rust build
where practical. Pin action revisions by SHA, like the existing workflows.

### Docs

- `e2e/rust-agent/README.md`: what the suite checks, requirements (JDK 21, Rust toolchain, git), how to run all or
  one test, what `setup_sut.sh` does and its output contract, how to bump the pin, and the accepted v2 limitation.
- Add a row to the table in `e2e/README.md`.
- Update `CLAUDE.md`'s repository map with the new subprojects, and relax its e2e `run.sh` convention as above.
- `settings.gradle.kts`: include the three projects.

## Left to the builder

- Package names, test class layout, and what else beyond the lifecycle goes into `support` (e.g. shared assertion
  helpers).
- The pin file name, the gitignored checkout/build directory layout, and how the fetch is done (shallow fetch of the
  commit, reuse of an existing checkout, etc.).
- Exact per-test timeouts and process teardown details.
- Exact assertions per scenario, derived from `testy`'s source at the pinned commit.
- How Rust build output is cached in CI.
