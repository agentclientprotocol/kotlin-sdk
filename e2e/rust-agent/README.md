# Rust Agent Interoperability Suite

Drives the Kotlin SDK's v1 and v2 `Client` runtimes over STDIO against `testy`, the deterministic test agent of the
[Rust SDK](https://github.com/agentclientprotocol/rust-sdk) (`src/agent-client-protocol-test`). It checks end-to-end
interoperability with the Rust reference SDK.

## Contents

- **`support/`**: the `TestyAgent` JUnit extension, agent launcher, and `setup_sut.sh`
  output validation. Its unit tests need no Rust toolchain and run in `./gradlew check`.
- **`client-v1/`**, **`client-v2/`**: stable and protocol v2 client tests, each in an `e2eTest` JVM test suite. 
- **`setup_sut.sh`**: builds `testy` at the pinned commit.
- **`rust-sdk.rev`**, **`rust-toolchain.toml`**: the pinned Rust SDK commit and Rust toolchain.

`./gradlew check` compiles the `e2eTest` sources, so SDK API breaks fail the build, but it never runs these tests.

## Requirements

JDK 21, `git`, and [rustup](https://rustup.rs). `setup_sut.sh` installs the pinned toolchain alongside any existing ones.

## Running

```bash
./gradlew :e2e:rust-agent:client-v1:e2eTest        # one client
./gradlew :e2e:rust-agent:client-v2:e2eTest
./gradlew e2eTest                                  # both clients
./gradlew :e2e:rust-agent:client-v1:e2eTest --tests '*Cancel*'   # one test or class
```

The task is never "up to date" or "cached". The first run builds `testy` (several minutes); later runs reuse it. Each test
starts a fresh agent, whose stderr goes to `build/e2e-logs/<TestClass>/<test>.log` in the client project; check it
first when a test fails.

## Coverage

The two suites share structure and test names where a flow exists in both versions.

**v1**:

- initialize: negotiated version, agent capabilities, and auth methods;
- authenticate and log out;
- sessions: new, load, list, resume, close, delete, and additional directories;
- modes and config options, including the resulting updates;
- prompts: `greet`, `echo`, and the `content`, `tool_calls`, `session_updates`, `callbacks`, and `elicitations`
  scenarios, with the client implementing permission, file system, terminal, and elicitation callbacks;
- cancellation: `wait_for_cancel` ends with `Cancelled`, and `cancel_status` reports the state.

**v2**: initialize; sessions new, list, resume, and close; `greet` and `echo` with their streamed updates; cancellation
through `wait_for_cancel` and `cancel_status`.

v2 coverage is limited by `testy`: its native v2 agent (feature `unstable_protocol_v2`) handles only the methods above
plus `session/prompt` and `session/cancel`, answers other prompt scenarios with `Refusal`, sends no client callbacks,
and advertises only the basic session capability. We accept this and expect upstream to grow it, picked up by bumping
`rust-sdk.rev`. If broader coverage is needed sooner, we can write a custom Rust v2 agent.

Out of scope: MCP (`call_tool`, `list_tools`), which needs an MCP server wired into the session, and WebSocket.

## `setup_sut.sh`

```bash
./setup_sut.sh <1|2>
```

The single source of truth for building the agent.
Each version has its own checkout and target directory, so concurrent v1 and v2 runs don't collide.

Gradle never calls the script; `TestyAgent` calls it once per test class. If it fails or the last line is not an
existing executable at an absolute path, the class fails with the expected and received values, the exit code, and the
stderr tail.

A setup run killed mid-checkout can leave a stale git lock; delete `e2e/rust-agent/.sut` (or that version's directory)
and rerun.

## Bumping the Rust SDK pin

1. Update the SHA in `rust-sdk.rev`. If the new commit's `rust-version` in `Cargo.toml` requires it, bump `channel` in
   `rust-toolchain.toml`.
2. Run `./gradlew e2eTest`. For new failures, check upstream `testy` changes first: a changed scenario may need a test
   update, not an SDK fix.
3. Add `client-v2` tests for any flows the v2 agent newly supports.

The CI `.sut` cache is keyed on `rust-sdk.rev`, `rust-toolchain.toml`, and `setup_sut.sh`.

## CI

The `Rust Agent E2E` workflow (`.github/workflows/rustAgentE2e.yml`) runs on pull requests and manual dispatch, one job
per client. It is informational (`continue-on-error`), so failures don't block PRs. Each job uploads the HTML report,
JUnit XML, and agent logs as the `rust-agent-e2e-<client>` artifact.

## Known failures

None: both suites pass at the pinned commit.

A test failing on a real SDK defect stays enabled and red; don't weaken its assertions. Rule out a test defect first,
then add an entry:

- **`TestClass.testName`** (client-v1 or client-v2)
  - Observed: what happens.
  - Expected: what should happen.
  - Suspected side: Kotlin SDK, Rust SDK/`testy`, or unclear.
