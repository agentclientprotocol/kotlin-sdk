# ACP Kotlin SDK samples

Runnable examples for the ACP Kotlin SDK. They live in one Gradle module, `:samples:kotlin-acp-client-sample`,
and each sample has its own package and its own run task. Samples use only the SDK's public APIs, so
they can serve as templates for real applications. Their code comments explain the protocol lifecycle.

Samples are not published artifacts.

## Samples

Each sample has its own README in its package, linked from the first column.

| Sample | What it shows | Protocol | Task | Needs |
| --- | --- | --- | --- | --- |
| [simple][simple] | The terminal client and the simple agent, joined in one process over STDIO pipes | v1 | `runSimpleAgent` | nothing |
| [gemini][gemini] | The same terminal client against an external agent process | v1 | `runGeminiClient` | the Gemini CLI on `PATH` |
| [v2][v2] | A v2 client and a v2 agent talking directly | v2 | `runV2SimpleAgent` | nothing |
| [v2][v2] | `ClientNegotiator` choosing between v1 and v2, then the v2 conversation | negotiated | `runV2Negotiation` | nothing |
| [auth][auth] | The unstable `auth/status` query, without a session | v1 and v2 | `runAuthStatus` | nothing |
| [subagents][subagents] | An agent that delegates to subagents, and a client that shows, cancels and closes them | v1 | `runSubagents` | nothing |

The v2, `auth/status` and subagent APIs are unstable (`@UnstableApi`) and need an explicit opt-in.

The package [`support`][support] is not a sample. It has the helpers that several samples share: the interactive
terminal client, in-memory transports, the transport to an external process, and console rendering of updates.

## How to run

You need JDK 21. Run the samples from the repository root with the Gradle wrapper:

```bash
./gradlew :samples:kotlin-acp-client-sample:<task>
```

For example, `./gradlew :samples:kotlin-acp-client-sample:runV2SimpleAgent`. List the tasks with
`./gradlew :samples:kotlin-acp-client-sample:tasks --group samples`.

The plain `run` task starts the simple agent. Its main class can be replaced, which is useful for a sample that
has no task of its own yet:

```bash
./gradlew :samples:kotlin-acp-client-sample:run \
    -PmainClass=com.agentclientprotocol.samples.auth.AuthStatusAppKt
```

## Adding a sample

1. Add a package under `com.agentclientprotocol.samples` with a `main` function.
2. Register a task for it with `registerSampleTask` in `build.gradle.kts`.
3. Add a `README.md` to the package: what the sample shows, how to run it, and its files.
4. Add a row to the table above, linking to that README.
5. If it runs without input or external tools, add a test under `src/test` that runs its entry point, as
   `SubagentsSampleTest` does. `./gradlew check` runs it.

[simple]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/simple/README.md
[gemini]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/gemini/README.md
[v2]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/v2/README.md
[auth]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/auth/README.md
[subagents]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/subagents/README.md
[support]: kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/support
