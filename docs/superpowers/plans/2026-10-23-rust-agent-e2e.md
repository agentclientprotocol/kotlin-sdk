# `e2e/rust-agent` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An informational e2e suite in which the v1 and v2 Kotlin `Client` runtimes drive the Rust SDK's `testy` agent over STDIO through broad, generic ACP flows.

**Architecture:** `setup_sut.sh <1|2>` fetches the pinned Rust SDK and builds one `testy` variant, printing the binary path last on stdout. A shared JVM module `:e2e:rust-agent:support` holds a JUnit 5 extension that runs the script once per test class, validates its output, and starts a fresh agent process per test, wired to a `Protocol` over `StdioTransport`. `:e2e:rust-agent:client-v1` and `:client-v2` hold the flows in a dedicated `e2eTest` JVM test suite, which `check` never runs.

**Tech Stack:** Kotlin/JVM 21, Gradle JVM Test Suite plugin, JUnit Jupiter, kotlinx.coroutines, bash, git, cargo.

**Spec:** `docs/superpowers/specs/2026-10-23-rust-agent-e2e-design.md`

## Global Constraints

- Rust SDK: `https://github.com/agentclientprotocol/rust-sdk`, pinned at `652d1003425319c170f1e677a1ca5af6a92269bb`.
- v1 agent: `cargo build --locked -p agent-client-protocol-test --bin testy --no-default-features`.
- v2 agent: the same command plus `--features unstable_protocol_v2`, built into its own target directory.
- Root `./gradlew check` must not run, or report as SKIPPED, any test that starts an agent. No skip-if-condition logic.
- A fresh agent process per test. Setup runs once per test class, never per test.
- Only public SDK APIs. No workarounds for SDK defects.
- This pass fixes no SDK bugs. A test that fails because of a real SDK defect stays enabled, with its assertions unchanged, and gets an entry under "Known failures" in `e2e/rust-agent/README.md`. First rule out a defect in the test itself.
- MCP (`call_tool`, `list_tools`) is out of scope.
- CI is informational (`continue-on-error: true`), and its actions are pinned by SHA.
- Repo style (`CLAUDE.md`): KDoc starts on a new line, multi-line comments are block comments, and blank lines separate logical steps.
- Dependency versions live in `gradle/libs.versions.toml`.

## Review Focus

- **Malformed setup output:** the script prints nothing, only build noise, a relative path, a missing file, or a non-executable file, or it exits non-zero. Each case fails with a message saying a valid agent binary path was expected, showing what was received. *Task 1 tests every case.*
- **Lost setup script location:** `e2eTest` is run from a different working directory, or the system property is missing. The test should fail clearly, not with an NPE. *Task 1: `missingSetupScriptPropertyFailsDescriptively`.*
- **Agent that never exits:** the agent ignores stdin close during teardown. It is force-killed after a grace period, so the next test still gets a fresh process. *Task 1: `teardownKillsAgentThatIgnoresStdinClose`, which uses `sleep` as a fake agent.*
- **Parallel setup runs:** `./gradlew e2eTest` runs v1 and v2 setup at the same moment. Separate per-version checkouts prevent interference. *Task 2: run both at once by hand.*
- **Stale results after a pin bump:** a second `e2eTest` invocation must run the tests again, not show `UP-TO-DATE` or `FROM-CACHE`. *Task 3: check the task outcome on a second run.*

---

### Task 1: `:e2e:rust-agent:support` (setup output validation, agent launcher, JUnit extension)

**Files:**
- Modify: `gradle/libs.versions.toml`. Add `junit = "5.11.4"` and the libraries `junit-jupiter-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }` and `junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }`.
- Modify: `settings.gradle.kts`. Add `include(":e2e:rust-agent:support")` under a comment introducing the rust-agent suite.
- Create: `e2e/rust-agent/support/build.gradle.kts`. Use `kotlin("jvm")` and `jvmToolchain(21)`. Declare `api(project(":acp"))`, `api(libs.junit.jupiter.api)`, and `api(libs.kotlinx.coroutines.core)`. Use the default `test` suite with `useJUnitJupiter(libs.versions.junit.get())` and `kotlin("test-junit5")`, so these unit tests run in `check`.
- Create: `e2e/rust-agent/support/src/main/kotlin/com/agentclientprotocol/e2e/rustagent/SetupOutput.kt`
- Create: `e2e/rust-agent/support/src/main/kotlin/com/agentclientprotocol/e2e/rustagent/AgentProcess.kt`
- Create: `e2e/rust-agent/support/src/main/kotlin/com/agentclientprotocol/e2e/rustagent/TestyAgent.kt`
- Test: `e2e/rust-agent/support/src/test/kotlin/com/agentclientprotocol/e2e/rustagent/SetupOutputTest.kt`
- Test: `e2e/rust-agent/support/src/test/kotlin/com/agentclientprotocol/e2e/rustagent/AgentProcessTest.kt`

**Interfaces:**
- Produces:
  - `class SetupFailedException(message: String) : IllegalStateException(message)`
  - `fun agentBinaryFromSetupOutput(exitCode: Int, stdout: String, stderr: String): Path`. It returns the absolute, normalized path from the last non-blank stdout line, or throws `SetupFailedException`.
  - `const val SETUP_SCRIPT_PROPERTY = "acp.e2e.rustAgent.setupScript"` and `const val AGENT_LOG_DIR_PROPERTY = "acp.e2e.rustAgent.logDir"`
  - `fun runSetup(setupScript: Path, protocolVersion: Int): Path`. It runs `bash <script> <version>`, reads stdout and stderr concurrently with stderr forwarded to `System.err`, and passes the results to `agentBinaryFromSetupOutput`.
  - `suspend fun <T> withAgentProcess(binary: Path, stderrLog: Path, block: suspend CoroutineScope.(Protocol) -> T): T`. It starts the binary with stderr redirected to `stderrLog`, starts a `Protocol` over a `StdioTransport`, and runs `block`. In `finally`, it closes the protocol, waits up to 5 s for exit, and then calls `destroyForcibly()`.
  - `class TestyAgent(protocolVersion: Int) : BeforeAllCallback, BeforeEachCallback`, used as `@JvmField @RegisterExtension val agent = TestyAgent(1)` in a companion object.
    - `beforeAll` reads `SETUP_SCRIPT_PROPERTY` and calls `runSetup` once.
    - If the property is missing or blank, it throws `SetupFailedException`, and the message names the property.
    - `beforeEach` records `<TestClass>/<testMethod>.log` under `AGENT_LOG_DIR_PROPERTY` (default `build/e2e-logs`) as the current test's stderr log.
    - `suspend fun <T> run(block: suspend CoroutineScope.(Protocol) -> T): T` delegates to `withAgentProcess`.

- [ ] **Step 1: Write the failing `SetupOutputTest`.** Each case asserts that `assertFailsWith<SetupFailedException>` holds, that the message contains `"expected a valid path to the built testy binary"`, and that it contains the offending value:
  - `nonZeroExitFails`: exit 101 with stderr `"error: could not compile"`. The message contains `"exit code 101"` and the stderr tail, meaning the last 20 lines.
  - `emptyStdoutFails`: the message contains `"stdout was empty"`.
  - `blankLastLinesAreIgnored`: stdout is `"/abs/bin\n\n  \n"`, an executable file created in `@TempDir`, so the path resolves.
  - `relativePathFails`: stdout `"target/debug/testy"` gives a message containing `"is not absolute"`.
  - `missingFileFails`: an absolute path that doesn't exist gives a message containing `"does not exist"`.
  - `nonExecutableFileFails`: a temp file without the executable bit gives a message containing `"is not executable"`.
  - `buildNoiseThenPathReturnsPath`: stdout `"Compiling foo\nFinished\n<abs exe>\n"` returns `<abs exe>`.

- [ ] **Step 2: Run `./gradlew :e2e:rust-agent:support:test --tests '*SetupOutputTest'`.** Expect a compile failure, since nothing is defined yet.

- [ ] **Step 3: Implement `SetupFailedException` and `agentBinaryFromSetupOutput` in `SetupOutput.kt`.** Every message starts with `"setup_sut.sh: expected a valid path to the built testy binary as the last line of stdout, but "` followed by the reason. Non-zero exits also include the stderr tail.

- [ ] **Step 4: Run Step 2's command.** Expect PASS.

- [ ] **Step 5: Write the failing `AgentProcessTest`.**
  - `runSetupReturnsPathFromScript`: a temp bash script prints `"noise"` to stderr and the path of `/bin/cat` last on stdout. `runSetup(script, 1)` returns that path, and the script receives `1` as `$1`.
  - `missingSetupScriptPropertyFailsDescriptively`: calling `TestyAgent(1).beforeAll(...)` with the property cleared throws `SetupFailedException`, and the message contains `SETUP_SCRIPT_PROPERTY`. Use a minimal `ExtensionContext` stub, or test an extracted `fun setupScriptFromProperty(): Path`. The implementer chooses.
  - `teardownKillsAgentThatIgnoresStdinClose`: run `withAgentProcess` on a wrapper script that execs `sleep 600`, with a block that returns at once. The call finishes within 10 s, and the process is no longer alive afterwards. Expose the `Process`, or check through `ProcessHandle`; the implementer chooses.

- [ ] **Step 6: Run `./gradlew :e2e:rust-agent:support:test`.** Expect the new tests to fail.

- [ ] **Step 7: Implement `runSetup`, `withAgentProcess`, and `TestyAgent` in `AgentProcess.kt` and `TestyAgent.kt`.** Follow `samples/.../ProcessTransportUtils.kt` for the `StdioTransport` wiring. Create the log file's parent directories. Give `runSetup` a 30-minute timeout, since a cold cargo build is slow.

- [ ] **Step 8: Run Step 6's command.** Expect PASS. Also run `./gradlew check --dry-run | grep rust-agent`. Expect `:e2e:rust-agent:support:test` and no `e2eTest`.

- [ ] **Step 9: Commit** with the message `test(e2e): add shared support for rust-agent suite`.

### Task 2: `setup_sut.sh` and the Rust SDK pin

**Files:**
- Create: `e2e/rust-agent/rust-sdk.rev`, holding one line: the pinned SHA.
- Create: `e2e/rust-agent/setup_sut.sh`, executable.
- Create: `e2e/rust-agent/.gitignore`, with `/.sut`.

**Interfaces:**
- Produces: `setup_sut.sh <1|2>`. On success it exits 0 and the last stdout line is the absolute binary path. On a usage error it exits 2. Everything else goes to stderr.

- [ ] **Step 1: Write `setup_sut.sh`.**
  - Start with `set -euo pipefail`, and resolve `SCRIPT_DIR` the same way `e2e/tck/run.sh` does.
  - Read the SHA from `rust-sdk.rev`. The checkout is `"$SCRIPT_DIR/.sut/rust-sdk-v$1"`, a separate directory per version, so parallel runs don't collide.
  - If `git -C <checkout> rev-parse HEAD` already equals the SHA, skip the fetch. Otherwise run `git init`, `git fetch --depth 1 <url> <sha>`, and `git checkout --detach FETCH_HEAD`.
  - Run cargo with the exact flags from Global Constraints, sending its output to stderr with `>&2`. v2 also passes `--target-dir "<checkout>/target-v2"`.
  - Check that the binary is executable, then `echo` its absolute path.
  - Give each git and cargo step a comment explaining why when the reason isn't obvious.

- [ ] **Step 2: Run `e2e/rust-agent/setup_sut.sh 1 2>/dev/null | tail -1`.** Expect an absolute path to an executable ending in `/target/debug/testy`. Then run `echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}' | "$(...)"`. Expect a JSON response with `"protocolVersion":1`.

- [ ] **Step 3: Run `e2e/rust-agent/setup_sut.sh 1 & e2e/rust-agent/setup_sut.sh 2 & wait`.** Expect both to succeed and to print different paths. Then rerun `setup_sut.sh 1`. Expect no fetch and a fast cargo no-op.

- [ ] **Step 4: Run `e2e/rust-agent/setup_sut.sh 3; echo $?`.** Expect usage text on stderr and exit code `2`.

- [ ] **Step 5: Commit** with the message `test(e2e): add setup_sut.sh building pinned Rust testy agent`.

### Task 3: `:e2e:rust-agent:client-v1`

**Files:**
- Modify: `settings.gradle.kts`, adding `include(":e2e:rust-agent:client-v1")`.
- Create: `e2e/rust-agent/client-v1/build.gradle.kts`:
  - `kotlin("jvm")` with `jvmToolchain(21)`.
  - `testing.suites.register<JvmTestSuite>("e2eTest")` with `useJUnitJupiter(libs.versions.junit.get())`.
  - Dependencies: `project(":e2e:rust-agent:support")`, `kotlin("test-junit5")`, and `runtimeOnly(libs.logback.classic)`.
  - The `e2eTest` task sets `systemProperty(SETUP_SCRIPT_PROPERTY, rootProject.file("e2e/rust-agent/setup_sut.sh").absolutePath)`, and sets `AGENT_LOG_DIR_PROPERTY` to `layout.buildDirectory.dir("e2e-logs")`.
  - It also sets `outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`. Results depend on an external build, so explicit runs always execute. This is our call between the two options the spec allows.
  - Do not wire it into `check`.
- Create: `e2e/rust-agent/client-v1/src/e2eTest/resources/logback-test.xml`, logging WARN to stderr.
- Create these under `e2e/rust-agent/client-v1/src/e2eTest/kotlin/com/agentclientprotocol/e2e/rustagent/v1/`:
  - `RecordingClientOperations.kt`: implements `ClientSessionOperations`, `FileSystemOperations`, `TerminalOperations`, and `ElicitationOperations`. It records every call in order. Permission picks the `allow_once` option. The fs operations really read and write files. Terminals run the real process, as `samples/.../TerminalClientSupport.kt` does. Elicitations accept with a fixed, schema-valid answer.
  - `V1Fixtures.kt`: provides `suspend fun TestyAgent.client(block: suspend (Client) -> Unit)`, which runs it, constructs `Client(protocol)`, and calls `initialize` with all fs, terminal, and form plus url elicitation capabilities. It also provides `fun prompt(command: String)`, building JSON commands such as `{"command":"run_scenario","scenario":"callbacks"}`.
- Test: `InitializationTest.kt`, `SessionLifecycleTest.kt`, `ModesAndConfigTest.kt`, `PromptTest.kt`, `ClientCallbacksTest.kt`, and `CancellationTest.kt`, all in the same package.

**Interfaces:**
- Consumes: `TestyAgent`, `SETUP_SCRIPT_PROPERTY`, and `AGENT_LOG_DIR_PROPERTY` from Task 1, and `setup_sut.sh 1` from Task 2.

Each test class has `companion object { @JvmField @RegisterExtension val agent = TestyAgent(1) }`, uses a `@TempDir` as the session cwd, and wraps its body in `runBlocking { withTimeout(30.seconds) { ... } }`. Take exact strings from `src/agent-client-protocol-test/src/testy.rs` at the pin. The following are pinned already:

- [ ] **Step 1: Write the tests.**
  - `InitializationTest`
    - `initializeNegotiatesV1AndReportsCapabilities`: protocol version 1, and `loadSession` is true. The prompt capabilities image, audio, and embeddedContext are true. The session capabilities list, delete, resume, close, and additionalDirectories are present. Logout capability is present. `authMethods` contains id `testy-agent-auth`.
    - `authenticateAndLogout`: `authenticate(AuthMethodId("testy-agent-auth"))` and then `logout()` both succeed.
  - `SessionLifecycleTest`
    - `newSessionIsListed`
    - `loadSessionReopensSession`
    - `resumeSessionReopensSession`
    - `closeSessionSucceeds`
    - `deletedSessionIsNotListed`
    - `additionalDirectoriesAreAccepted`: a second `@TempDir`.
  - `ModesAndConfigTest`
    - `modesAreChatAndPlan`: chat and plan are available, and the current mode is `chat`.
    - `setModeUpdatesCurrentMode`: `plan`.
    - `configOptionVerbosityHasThreeValues`: brief, normal, and verbose.
    - `setConfigOptionUpdatesValue`: `verbose`, reflected in `configOptions`.
  - `PromptTest`
    - `greetRepliesHelloWorld`: the agent message text is `Hello, world!`, with `StopReason.END_TURN`.
    - `echoRepliesMessage`
    - `sessionUpdatesScenarioEmitsEveryUpdateKind`: assert that the set of received `SessionUpdate` subclasses covers the variants `emit_session_updates` sends, and that the reply contains `session_updates: sent`.
    - `contentScenarioCountsBlocks`: send text, image, audio, and resource blocks, and expect `content_blocks: 4`.
    - `toolCallsScenarioEmitsToolCallAndUpdates`: a `ToolCall`, then `ToolCallUpdate` updates with diff and location content, and `tool_calls: sent`.
  - `ClientCallbacksTest`
    - `callbacksScenarioExercisesEveryClientMethod`:
      - The recorded calls, in order, are requestPermissions, fsWriteTextFile to `<cwd>/testy-write.txt`, fsReadTextFile, terminalCreate with `printf` and `testy terminal\n`, output, waitForExit, kill, and release.
      - The file exists afterwards.
      - The reply contains `terminal/create: ok`.
      - The reply contains `elicitations: completed`.
    - `elicitationsScenarioCompletes`: `elicitations: completed`.
  - `CancellationTest`
    - `cancelEndsWaitForCancelTurn`: start `wait_for_cancel`, call `session.cancel()`, and expect `StopReason.CANCELLED`.
    - `cancelStatusReportsNotCancelled`: a reply containing `cancel_status: not_cancelled`.

- [ ] **Step 2: Run `./gradlew :e2e:rust-agent:client-v1:e2eTest`.** Expect tests to run against testy. If one fails, first check it against `testy.rs` for a test defect, and fix the test. A remaining failure is an SDK defect: leave it red and note it for Task 5.

- [ ] **Step 3: Run `./gradlew :e2e:rust-agent:client-v1:e2eTest --tests '*PromptTest.greetRepliesHelloWorld'` twice.** Expect exactly one executed test each time, and the task never `UP-TO-DATE` or `FROM-CACHE`. Run `./gradlew check --dry-run | grep client-v1`. Expect only `:test`, `:compileKotlin`, and similar tasks, with no `e2eTest`.

- [ ] **Step 4: Commit** with the message `test(e2e): add v1 client suite against Rust testy agent`.

### Task 4: `:e2e:rust-agent:client-v2`

**Files:**
- Modify: `settings.gradle.kts`, adding `include(":e2e:rust-agent:client-v2")`.
- Create: `e2e/rust-agent/client-v2/build.gradle.kts`, the same as Task 3's.
- Create: `e2e/rust-agent/client-v2/src/e2eTest/resources/logback-test.xml`
- Create these under `e2e/rust-agent/client-v2/src/e2eTest/kotlin/com/agentclientprotocol/e2e/rustagent/v2/`. The package uses a file-level `@file:OptIn(UnstableApi::class)`.
  - `V2Fixtures.kt`: provides `suspend fun TestyAgent.client(block: suspend (Client) -> Unit)` using `com.agentclientprotocol.client.v2.Client(protocol)` and `initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("acp-kotlin-e2e", "1")))`. It also provides `suspend fun ClientSession.promptAndCollect(text: String): List<SessionUpdate>`, which collects `updates` until `StateUpdate.Idle`.
- Test: `InitializationTest.kt`, `SessionLifecycleTest.kt`, `PromptTest.kt`, and `CancellationTest.kt`.

**Interfaces:**
- Consumes: Task 1's `TestyAgent` and Task 2's `setup_sut.sh 2`. Uses `TestyAgent(2)`.

Mirror Task 3's class and test names wherever a flow exists in v2 (spec: "mirror each other's structure and test names").

- [ ] **Step 1: Write the tests.**
  - `InitializationTest.initializeNegotiatesV2`: protocol version 2 and implementation name `test-agent`.
  - `SessionLifecycleTest`
    - `newSessionIsListed`: the id starts with `testy-v2-session-`.
    - `resumeSessionReopensSession`
    - `closeSessionSucceeds`
  - `PromptTest`
    - `greetRepliesHelloWorld`: the updates include a user message, `StateUpdate.Running`, an `AgentMessageChunk` with `Hello, world!`, and `StateUpdate.Idle`.
    - `echoRepliesMessage`
  - `CancellationTest`
    - `cancelEndsWaitForCancelTurn`: the turn ends with a cancelled stop reason.
    - `cancelStatusReportsNotCancelled`

- [ ] **Step 2: Run `./gradlew :e2e:rust-agent:client-v2:e2eTest`.** Triage exactly as in Task 3, Step 2.

- [ ] **Step 3: Run `./gradlew e2eTest`.** Both suites run in parallel, and both builds succeed (Review Focus: parallel setup).

- [ ] **Step 4: Commit** with the message `test(e2e): add v2 client suite against Rust testy agent`.

### Task 5: CI workflow and documentation

**Files:**
- Create: `.github/workflows/rustAgentE2e.yml`
- Create: `e2e/rust-agent/README.md`
- Modify: `e2e/README.md`, adding a table row and relaxing the `run.sh` wording.
- Modify: `CLAUDE.md`. Add a repository-map entry for `:e2e:rust-agent:*` and relax "with a `run.sh` entry point" to "with a documented entry point (a `run.sh` or a Gradle task)".

- [ ] **Step 1: Write the workflow.**
  - Name it `Rust Agent E2E`, triggered by `pull_request` and `workflow_dispatch`, with the same `concurrency` block as `acpTck.yml`.
  - Run the matrix `client: [client-v1, client-v2]` on `ubuntu-latest` with `fail-fast: false`. Set `timeout-minutes: 40`.
  - Reuse the checkout, JDK 21, and Gradle steps from `acpTck.yml` with the same SHAs.
  - Run `rustup toolchain install stable --profile minimal` (the runner image ships rustup).
  - Cache `e2e/rust-agent/.sut` with `actions/cache`. The key is `rust-agent-${{ matrix.client }}-${{ hashFiles('e2e/rust-agent/rust-sdk.rev', 'e2e/rust-agent/setup_sut.sh') }}`. Pin the action to the SHA of its latest v4 tag, resolved with `gh api repos/actions/cache/git/ref/tags/v4`.
  - Run `./gradlew :e2e:rust-agent:${{ matrix.client }}:e2eTest` with `continue-on-error: true`.
  - Upload `e2e/rust-agent/${{ matrix.client }}/build/reports/tests/e2eTest`, `build/test-results/e2eTest`, and `build/e2e-logs` under `if: always()`, using the upload SHA from `acpTck.yml`.

- [ ] **Step 2: Write `e2e/rust-agent/README.md`.** Cover:
  - what the suite checks: interoperability, not conformance;
  - requirements: JDK 21, git, and a Rust toolchain of at least 1.88;
  - running everything, one client, or one test;
  - the `setup_sut.sh` output contract and its `.sut` directory;
  - bumping `rust-sdk.rev`;
  - the accepted thinner v2 coverage, assumed to grow upstream and open to revisiting with a custom Rust v2 agent;
  - MCP left out;
  - a "Known failures" section listing every SDK-defect failure from Tasks 3 and 4: the test, observed versus expected behavior, and the suspected side (Kotlin SDK, Rust SDK/testy, or unclear). If there are none, say so.

- [ ] **Step 3: Update `e2e/README.md` and `CLAUDE.md`.**

- [ ] **Step 4: Run `./gradlew check`.** Expect it to pass, with no `e2eTest` task executed and no SKIPPED tests from the rust-agent projects. Run `actionlint .github/workflows/rustAgentE2e.yml` if it is installed; otherwise report that it was not run.

- [ ] **Step 5: Commit** with the message `ci: run rust-agent e2e suite informationally`.
