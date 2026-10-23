# Rust Agent Interoperability Suite

This suite drives the Kotlin SDK's v1 and v2 `Client` runtimes against `testy`, the deterministic test agent of the
[Rust SDK](https://github.com/agentclientprotocol/rust-sdk) (`src/agent-client-protocol-test`), over STDIO.

It is an interoperability check, not a conformance suite. It answers whether the protocol functionality a real client
uses works end to end between the Kotlin SDK and the Rust reference SDK, for both protocol versions. A regression on
either side shows up as an individually runnable, failing JUnit test. For spec conformance of the Kotlin agents, see
[`../tck`](../tck/).

## Contents

- **`support/`** (`:e2e:rust-agent:support`): the shared JUnit extension `TestyAgent`, validation of the `setup_sut.sh`
  output, and the agent launcher. Its own unit tests need no Rust toolchain and run in `./gradlew check`.
- **`client-v1/`** (`:e2e:rust-agent:client-v1`): stable-protocol client tests, in an `e2eTest` JVM test suite.
- **`client-v2/`** (`:e2e:rust-agent:client-v2`): protocol v2 client tests, in an `e2eTest` JVM test suite.
- **`setup_sut.sh`**: builds `testy` at the pinned Rust SDK commit.
- **`rust-sdk.rev`**: the pinned Rust SDK commit.

The suite has no `run.sh`; the Gradle `e2eTest` task is its entry point. These tests are not part of `./gradlew check`
and never appear as skipped there.

## Requirements

- JDK 21
- `git`
- A Rust toolchain (`cargo` on `PATH`) of at least 1.88.0, the Rust SDK's `rust-version`

## Running

```bash
./gradlew :e2e:rust-agent:client-v1:e2eTest        # one client
./gradlew :e2e:rust-agent:client-v2:e2eTest
./gradlew e2eTest                                  # both clients
./gradlew :e2e:rust-agent:client-v1:e2eTest --tests '*Cancel*'   # one test or class
```

The task always runs; Gradle never reports it as up to date or restores it from the build cache, because the result
depends on an externally built agent. The first run builds `testy` and takes several minutes; later runs reuse it.

Each test starts a fresh agent process. The agent's stderr goes to `build/e2e-logs/<TestClass>/<test>.log` in the
client project, which is the first place to look when a test fails.

## What is covered

Both suites mirror each other's structure and test names where a flow exists for both versions.

**v1** (21 tests):

- initialize: negotiated version, agent capabilities, and auth methods;
- authenticate with the agent auth method and log out;
- sessions: new, load, list, resume, close, delete, and additional directories;
- modes and config options, including the resulting updates;
- prompts: `greet`, `echo`, and the `content`, `tool_calls`, `callbacks`, and `elicitations` scenarios, with the client
  implementing the permission, file system, terminal, and elicitation callbacks;
- cancellation: `wait_for_cancel` ends with `Cancelled`, and `cancel_status` reports the state.

**v2** (8 tests): initialize; sessions new, list, resume, and close; `greet` and `echo` prompts with their streamed
updates; cancellation through `wait_for_cancel` and `cancel_status`.

### v2 coverage is thinner

The v2 suite is deliberately smaller than the v1 suite. With the `unstable_protocol_v2` feature, `testy` routes v2
connections to a native v2 agent that handles only initialize, `session/new`, `session/list`, `session/resume`,
`session/close`, `session/prompt`, and `session/cancel`. Its prompts support only `greet`, `echo`, `wait_for_cancel`,
and `cancel_status`; other scenarios answer with a `Refusal` stop reason. It sends no client callbacks and advertises
only the basic session capability.

This is an accepted limitation. We assume upstream expands `testy`'s v2 agent as protocol v2 matures, and bumping
`rust-sdk.rev` picks that up. The decision can be revisited: if broader v2 coverage is needed sooner, we can implement
a custom Rust v2 agent.

### Out of scope

- MCP (`call_tool`, `list_tools`): it needs an MCP server wired into the session, so it is left out of this pass.
- WebSocket: the suite is STDIO only.

## `setup_sut.sh`

```bash
./setup_sut.sh <1|2>
```

The script is the single source of truth for building the agent, and it can be run by hand when debugging. It:

- fetches the Rust SDK at the commit in `rust-sdk.rev` into `.sut/rust-sdk-v<N>/`, a gitignored directory next to the
  script, reusing an existing checkout that is already at that commit;
- builds only the requested version's `testy` with `cargo build --locked`: v1 with `--no-default-features`, and v2 with
  `--no-default-features --features unstable_protocol_v2` into its own target directory;
- writes all fetch and build output to stderr;
- prints the absolute path of the built binary as the last line of stdout;
- exits non-zero on any failure.

Each protocol version has its own checkout and target directory, so concurrent v1 and v2 runs, such as the one
`./gradlew e2eTest` starts, never share a working tree.

The tests call the script once per test class through the `TestyAgent` extension, and take the last stdout line as the
binary path. If the script fails, or that line is missing, empty, not an absolute path, not an existing file, or not
executable, the test class fails with a message that says what was expected and what was received, including the exit
code and the stderr tail. Gradle itself never calls the script.

If a setup run is killed in the middle of a checkout, it can leave a stale git lock behind. Delete `e2e/rust-agent/.sut`
(or just that version's `rust-sdk-v<N>` directory) and run again.

## Bumping the Rust SDK pin

1. Replace the commit in `rust-sdk.rev` with the new Rust SDK commit SHA.
2. Run `./gradlew e2eTest` and review any new failures. Check `testy`'s changes upstream first, since a changed
   scenario can need a test update rather than an SDK fix.
3. If the v2 agent gained support for more flows, add the matching tests to `client-v2`.

CI keys its `.sut` cache on `rust-sdk.rev` and `setup_sut.sh`, so a bump rebuilds `testy` there.

## CI

The `Rust Agent E2E` workflow (`.github/workflows/rustAgentE2e.yml`) runs on pull requests and manual dispatch, with
one job per client. It is informational: the test step uses `continue-on-error`, so a red run does not block a PR.
Each job uploads the HTML report, the JUnit XML results, and the agent logs as the `rust-agent-e2e-<client>` artifact.

## Known failures

None. At the pinned commit both suites pass fully (v1 21/21, v2 8/8), and this pass found no Kotlin SDK defects.

A test that fails because of a real SDK defect stays enabled and red; do not weaken its assertions to make it pass.
Before recording a failure here, rule out a defect in the test itself. Add one entry per failure in this format:

- **`TestClass.testName`** (client-v1 or client-v2)
  - Observed: what happens.
  - Expected: what should happen.
  - Suspected side: Kotlin SDK, Rust SDK/`testy`, or unclear.
