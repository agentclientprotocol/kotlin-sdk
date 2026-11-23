# ACP Kotlin SDK samples

Runnable examples for the ACP Kotlin SDK. They live in one Gradle module, `:samples:kotlin-acp-client-sample`,
and each sample has its own package and its own run task. Samples use only the SDK's public APIs, so
they can serve as templates for real applications. Their code comments explain the protocol lifecycle.

Samples are not published artifacts.

## How to run

You need JDK 21. Run the samples from the repository root with the Gradle wrapper:

```bash
./gradlew :samples:kotlin-acp-client-sample:<task>
```

For example, `./gradlew :samples:kotlin-acp-client-sample:runV2SimpleAgent`. List the tasks with
`./gradlew :samples:kotlin-acp-client-sample:tasks --group samples`.

The interactive samples (`runSimpleAgent`, `runGeminiClient`) read your messages from the console. Type `exit`,
`quit` or `bye` to leave the chat. `runSimpleAgent` does not return to the shell after that, so stop it with Ctrl+C.

The plain `run` task starts the simple agent. Its main class can be replaced, which is useful for a sample that
has no task of its own yet:

```bash
./gradlew :samples:kotlin-acp-client-sample:run \
    -PmainClass=com.agentclientprotocol.samples.auth.AuthStatusAppKt
```

## Samples

| Sample | What it shows | Protocol | Task | Needs |
| --- | --- | --- | --- | --- |
| [simple](#simple) | The terminal client and the simple agent, joined in one process over STDIO pipes | v1 | `runSimpleAgent` | nothing |
| [gemini](#gemini) | The same terminal client against an external agent process | v1 | `runGeminiClient` | the Gemini CLI on `PATH` |
| [v2](#v2) | A v2 client and a v2 agent talking directly | v2 | `runV2SimpleAgent` | nothing |
| [v2](#v2) | `ClientNegotiator` choosing between v1 and v2, then the v2 conversation | negotiated | `runV2Negotiation` | nothing |
| [auth](#auth) | The unstable `auth/status` query, without a session | v1 and v2 | `runAuthStatus` | nothing |

The v2 and `auth/status` APIs are unstable (`@UnstableApi`) and need an explicit opt-in.

## simple

Package `com.agentclientprotocol.samples.simple`. Run with `runSimpleAgent`.

- `SimpleAgentApp.kt`: the entry point. It connects an agent and the terminal client with two in-process pipes.
  Read this first.
- `SimpleAgentSupport.kt`: the agent. It implements `AgentSupport` and `AgentSession`: it echoes the prompt, sends a plan and
  a tool call, and uses the client's file system and terminal when the client offers them. It also exposes session
  config options.

The client side is `support/TerminalClientSupport.kt`, shared with the `gemini` sample.

## gemini

Package `com.agentclientprotocol.samples.gemini`. Run with `runGeminiClient`.

- `GeminiClientApp.kt`: starts `gemini --experimental-acp` as a child process and runs the terminal client against its
  STDIO. It fails to start if the `gemini` command is not on `PATH`.

## v2

Package `com.agentclientprotocol.samples.v2`. Run with `runV2SimpleAgent` or `runV2Negotiation`.

- `V2SimpleAgentApp.kt`: a v2 client and a v2 agent connected directly, with no negotiation. Read this first.
- `V2NegotiationApp.kt`: the same conversation, but the client first uses `ClientNegotiator` to pick the version.
- `V2ClientExamples.kt`: the two client flows and the conversation they share: a session, a config option change,
  a prompt and the update stream.
- `V2SampleSupport.kt`: the v2 echo agent. It offers a response mode config option; selecting "Uppercase once" makes
  the next reply upper case, after which the agent resets to Echo and reports the change.

## auth

Package `com.agentclientprotocol.samples.auth`. Run with `runAuthStatus`.

- `AuthStatusApp.kt`: extends the v1 and the v2 echo agents to advertise and answer `auth/status`, then queries both from a client.
  Set `SAMPLE_AGENT_API_KEY` before the run to see the agents report configured credentials.

It builds on the agents of the `simple` and `v2` packages.

## support

Package `com.agentclientprotocol.samples.support`. It has no entry point and no task.

- `TerminalClientSupport.kt`: the interactive v1 terminal client, with file system and terminal operations.
- `InMemoryTransportPair.kt`: two connected STDIO transports without an external process.
- `ProcessTransportUtils.kt`: a transport to an external agent process.
- `util.kt`: console rendering of session updates.

## Adding a sample

1. Add a package under `com.agentclientprotocol.samples` with a `main` function.
2. Register a task for it with `registerSampleTask` in `build.gradle.kts`.
3. Add a row to the table above and a section about its files.
