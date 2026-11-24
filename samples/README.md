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
| [subagents](#subagents) | An agent that delegates to subagents, and a client that shows, cancels and closes them | v1 | `runSubagents` | nothing |

The v2, `auth/status` and subagent APIs are unstable (`@UnstableApi`) and need an explicit opt-in. The subagent protocol
itself is stable in v1; only the SDK's Kotlin API for it may still change.

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

## subagents

Package `com.agentclientprotocol.samples.subagents`. Run with `runSubagents`.

A scripted run, with no input: the client opens a session and sends two prompts, then closes the session. The agent
delegates the first prompt to three subagents. The reviewer completes, the user cancels the cleaner while its
permission request is pending, and the tester fails after it started a grandchild whose feed is lost. The agent relays
the second prompt to a native agent, whose workers become nested subagents.

Then the client loads the closed session again. The agent replays the session's history, subagents included, and each
stream gets its history, then `Opened`, then live updates. The replayed subagents start with no confirmed state and no
cancel grant. The agent grants cancel again to the reviewer, which works on a new task, and the user cancels it. The
client then closes the session again. The app prints what each side sees.

Read `agent/DelegatingAgentSession.kt` first for the agent side and `client/SubagentsClient.kt` for the client side.
`SubagentsApp.kt` wires them together in one process, and `Transcript.kt` holds the lines both sides write.

Each file is named after the SDK type it implements or mainly uses. The agent side, in package `agent`:

- `DelegatingAgentSupport.kt`: the `AgentSupport`; it opens a `DelegatingAgentSession`.
- `DelegatingAgentSession.kt`: the root `AgentSession`, which delegates or relays each prompt.
- `DelegatedSubagentFactory.kt`: the `AgentSubagentFactory` that sends a delegated child's announcement.
- `DelegatedSubagentSession.kt`: the `AgentSubagentSession` of a delegated child; it runs a `DelegatedTask`.
- `DelegatedTask.kt`: a task, and what its work can do through the child's context.
- `SampleTasks.kt`: the reviewer, cleaner and tester tasks, and the reviewer's task after the load.
- `SessionHistory.kt`: the recorded stream of each session, in its live order, and its replay for `session/load`.
- `RemoteProbeSubagentSession.kt`: the tester's grandchild.
- `NativeRelayedSubagentSession.kt`: a relayed worker of a native agent, and the relay loop.
- `FakeNativeAgent.kt`: the simulated native agent and its flat event stream; it uses no SDK type.

The client side, in package `client`:

- `SubagentsClient.kt`: runs the `Client`: initialize, the two prompts, close, load, close.
- `TreeClientSessionOperations.kt`: the `ClientSessionOperations` and `SubagentOperations` shared by every session.
- `RootClientSessionOperations.kt` and `SubagentClientSessionOperations.kt`: the operations of the root and of a
  subagent.
- `ScriptedUser.kt`: answers permission requests, or cancels the subagent that asks.
- `ClientTranscript.kt`: writes the client's lines and renders sessions and updates.

Where each scenario lives:

| Scenario | Agent | Client |
| --- | --- | --- |
| The `subagents` capability | `DelegatingAgentSession` (1: no capability, work inline) | `SubagentsClient` (1) |
| Announcing a child, and the operations that receive it | `DelegatedSubagentFactory` (2) | `TreeClientSessionOperations` (2) |
| A child's requests, such as a permission request | `DelegatedTask` (3) | `SubagentClientSessionOperations`, `ScriptedUser` (5) |
| Links from the parent: `session_message`, session tool call content | `DelegatingAgentSession` (4) | `TreeClientSessionOperations`, `SubagentsClient` (3) |
| Patches to a child | `DelegatedTask` (5) | `TreeClientSessionOperations` (3) |
| A child's own stream: history, `Opened`, live updates | | `SubagentClientSessionOperations` (4) |
| Cancel | `DelegatedSubagentSession` (6) | `ScriptedUser` (5, 6) |
| Failure, and the SDK's `unknown` | `DelegatedSubagentSession`, `RemoteProbeSubagentSession` (7) | |
| A grandchild | `RemoteProbeSubagentSession` (8) | `TreeClientSessionOperations` (3) |
| Closing the root | `DelegatingAgentSession` (9) | `SubagentsClient` (7) |
| Relay of a native agent, `announce(…, parent = …)` | `NativeRelayedSubagentSession` (10) | |
| Recording each stream in its live order, children included | `SessionHistory` (11) | |
| `session/load`: replaying each stream in that order | `SessionHistory` (12) | `SubagentsClient` (9), `TreeClientSessionOperations` (8) |
| The replayed tree, at each parent's `Opened` | | `TreeClientSessionOperations` (11) |
| A fresh cancel grant after the load | `DelegatingAgentSession` (13) | `SubagentsClient` (10) |
| The current state of a finished child after the load | `SessionHistory` (14) | |

The numbers are those of the section comments in each side's files.

The streams of different sessions are delivered concurrently, so lines of different sessions may interleave differently
from run to run. The run task logs only warnings, to stderr. One may appear, depending on timing: when the user
cancels the cleaner, the client answers its pending permission request `cancelled`, and the agent stops the cleaner's
work. If the agent's stop cancels the request before the answer arrives, the agent sends `$/cancel_request` for it, and
the client logs that it got a cancel for a request it has already answered.

`SubagentsSampleTest` runs the same script, on one thread and on `Dispatchers.Default`, and checks its key events, so
`./gradlew check` fails if the sample breaks. `agent/DelegatedWorkTest` checks how a task's end maps to its `idle`
stop reason.

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
4. If it runs without input or external tools, add a test under `src/test` that runs its entry point, as
   `SubagentsSampleTest` does. `./gradlew check` runs it.
