# subagents

An agent that delegates work to subagents, and a client that shows, cancels and closes them.

- Protocol: ACP v1 (the `subagents` capability).
- Needs: nothing.
- Package: `com.agentclientprotocol.samples.subagents`.

The subagent protocol is stable in v1, but the SDK's Kotlin API for it (`@UnstableApi`) may still change, so the sample
opts in explicitly.

## What it shows

A scripted run with no input. The client opens a session and sends two prompts, then closes the session.

- The agent delegates the first prompt to three subagents. The reviewer completes, the user cancels the cleaner while
  its permission request is pending, and the tester fails after it started a grandchild whose feed is lost.
- The agent relays the second prompt to a native agent, whose workers become nested subagents.

Then the client loads the closed session again.

- The agent tells the session's history again from its own record of each turn, subagents included. Each stream gets
  its history, then `Opened`, then live updates.
- The replayed subagents start with no confirmed state and no cancel grant. The agent grants cancel again to the
  reviewer, which works on a new task, and the user cancels it.
- The client then closes the session again.

The app prints what each side sees.

## How to run

From the repository root:

```bash
./gradlew :samples:kotlin-acp-client-sample:runSubagents
```

The streams of different sessions are delivered concurrently, so lines of different sessions may interleave
differently from run to run. The task logs only warnings, to stderr, through `logback-subagents.xml`.

One warning may appear, depending on timing. When the user cancels the cleaner, the client answers its pending
permission request `cancelled`, and the agent stops the cleaner's work. If the agent's stop cancels the request before
the answer arrives, the agent sends `$/cancel_request` for it, and the client logs that it got a cancel for a request
it has already answered.

## Where to start

Read [`agent/DelegatingAgentSession.kt`](agent/DelegatingAgentSession.kt) first for the agent side and
[`client/SubagentsClient.kt`](client/SubagentsClient.kt) for the client side.
[`SubagentsApp.kt`](SubagentsApp.kt) wires them together in one process, and [`Transcript.kt`](Transcript.kt) holds the
lines both sides write.

## Files

Each file is named after the SDK type it implements or mainly uses.

The agent side, in package `agent`:

- [`DelegatingAgentSupport.kt`](agent/DelegatingAgentSupport.kt): the `AgentSupport`; it opens a
  `DelegatingAgentSession`.
- [`DelegatingAgentSession.kt`](agent/DelegatingAgentSession.kt): the root `AgentSession`, which delegates or relays
  each prompt and replays the history on `session/load`.
- [`DelegatedSubagentFactory.kt`](agent/DelegatedSubagentFactory.kt): the `AgentSubagentFactory` that sends a delegated
  child's announcement.
- [`DelegatedSubagentSession.kt`](agent/DelegatedSubagentSession.kt): the `AgentSubagentSession` of a delegated child;
  it runs a `DelegatedTask`.
- [`DelegatedTask.kt`](agent/DelegatedTask.kt): a task, and what its work can do through the child's context.
- [`SampleTasks.kt`](agent/SampleTasks.kt): the reviewer, cleaner and tester tasks, and the reviewer's task after the
  load.
- [`SessionRecord.kt`](agent/SessionRecord.kt): what the root, each of its turns and each subagent did, which the root
  tells again on `session/load`.
- [`RemoteProbeSubagentSession.kt`](agent/RemoteProbeSubagentSession.kt): the tester's grandchild.
- [`NativeRelayedSubagentSession.kt`](agent/NativeRelayedSubagentSession.kt): the `AgentSubagentSession` of one
  relayed worker of a native agent, and the relay loop that builds the tree from the flat event stream.
- [`FakeNativeAgent.kt`](agent/FakeNativeAgent.kt): the simulated native agent and its flat event stream; it uses no SDK
  type.
- [`SessionUpdates.kt`](agent/SessionUpdates.kt): small builders for the updates the agent sends, and its
  transcript lines.

The client side, in package `client`:

- [`SubagentsClient.kt`](client/SubagentsClient.kt): runs the `Client`: initialize, the two prompts, close, load, close.
- [`TreeClientSessionOperations.kt`](client/TreeClientSessionOperations.kt): the `ClientSessionOperations` and
  `SubagentOperations` shared by every session.
- [`RootClientSessionOperations.kt`](client/RootClientSessionOperations.kt) and
  [`SubagentClientSessionOperations.kt`](client/SubagentClientSessionOperations.kt): the operations of the root and of a
  subagent.
- [`ScriptedUser.kt`](client/ScriptedUser.kt): answers permission requests, or cancels the subagent that asks.
- [`ClientTranscript.kt`](client/ClientTranscript.kt): writes the client's lines and renders sessions and updates.

## Where each scenario lives

The numbers are those of the section comments in each side's files.

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
| `session/load`: the root tells each turn again from its record | `DelegatingAgentSession` (11) | `SubagentsClient` (9), `TreeClientSessionOperations` (8) |
| A replayed child's own history, its children included | `DelegatingAgentSession` (12) | |
| The replayed tree, at each parent's `Opened` | | `TreeClientSessionOperations` (11) |
| A fresh cancel grant after the load | `DelegatingAgentSession` (13) | `SubagentsClient` (10) |
| The current state of a finished child after the load | `DelegatingAgentSession` (14) | |

## Tests

`SubagentsSampleTest` runs the same script, on one thread and on `Dispatchers.Default`, and checks its key events, so
`./gradlew check` fails if the sample breaks. `agent/DelegatedWorkTest` checks how a task's end maps to its `idle` stop
reason. Both are under `src/test`.
